import Foundation
import CryptoKit
import Network
import Security
import Darwin
import MacBridgeProtocol

@available(macOS 15, *)
final class Engine {
    let queue = DispatchQueue(label: "MacBridge.native.engine", qos: .utility)
    private let identity: NativeIdentity
    private let directory: URL
    private let authentication: Authentication
    private let budget = WorkBudget()
    private let mirror = NotificationMirror()
    private let quota: ReceiveQuota
    private lazy var transport = Transport(queue: queue, budget: budget)
    private var active: Transport.Session?
    private var peerID: String?
    private var receivers: [String: FileReceiver] = [:]
    private var lastReceiver: FileReceiver?
    private var sender: FileSender?
    private var senderPeer: String?
    private var clipboard: Bool, files: Bool, notifications = false
    private let fixture: Bool, echo: Bool, host: String?
    private var address: String
    private var port: UInt16
    private var stopping = false
    private var renewing = false
    private var lastAction = "Ready to connect"
    private var storage: (Int64, Int)?
    private var nextReceiveReport = 0.0
    private var receiveStatus = ""
    private var pairingExpiry: DispatchWorkItem?
    private let emit: ([String: Any]) -> Void
    init(directory: URL, helper: String?, fixture: Bool, clipboard: Bool, files: Bool, echo: Bool,
         host: String?, port: UInt16, address: String?, emit: @escaping ([String: Any]) -> Void) throws {
        self.directory = directory; self.clipboard = clipboard; self.files = files; self.fixture = fixture
        self.echo = echo; self.host = host; self.port = port; self.emit = emit
        self.address = try address ?? Self.detectAddress()
        identity = try NativeIdentity(directory: directory, helper: helper, fixture: fixture)
        quota = ReceiveQuota(directory: directory.appendingPathComponent("ReceivedFiles"), settings: directory.appendingPathComponent("storage.json"))
        let peersURL = directory.appendingPathComponent("peers.json")
        var peers: [String: Peer] = [:]
        if PrivateStorage.exists(peersURL) {
            let data = try PrivateStorage.read(peersURL, limit: 128 * 1024)
            _ = try StrictJSON.object(data)
            peers = try JSONDecoder().decode([String: Peer].self, from: data)
            guard peers.allSatisfy({ !$0.key.isEmpty && $0.key.unicodeScalars.count <= 128 && $0.value.publicKey.utf8.count <= 4096 }) else { throw StartupFailure.identity(nil) }
        }
        authentication = try Authentication(deviceID: identity.deviceID, publicKey: identity.publicKey,
            name: ProcessInfo.processInfo.hostName.components(separatedBy: ".").first ?? "Mac", peers: peers, save: { values in
                let data = try JSONEncoder().encode(values)
                guard data.count <= 128 * 1024 else { throw ProtocolFailure.oversized }
                try PrivateStorage.write(data, to: peersURL)
            })
        storage = try? quota.usage()
    }
    func start() throws {
        if files {
            for id in authentication.peers.keys.sorted() {
                if receivers.count >= 4 { break }
                let checkpoint = receiverCheckpoint(id)
                if PrivateStorage.exists(checkpoint.url) { receivers[id] = FileReceiver(directory: quota.directory, state: checkpoint, quota: quota) }
            }
        }
        let senderURL = directory.appendingPathComponent("TransferState/sender.json")
        if let data = try? PrivateStorage.read(senderURL, limit: 128 * 1024), let saved = try? StrictJSON.object(data),
           let binding = saved["identity"] as? [String], binding.count == 4,
           binding[0] == identity.deviceID, binding[1] == authentication.pin,
           authentication.peers[binding[2]]?.publicKey == binding[3] {
            senderPeer = binding[2]
            sender = makeSender(binding[2], send: { _ in throw ProtocolFailure.invalidIdentity })
        }
        try cleanupOrphans()
        transport.onReady = { [weak self] port in self?.port = port; self?.state() }
        transport.onError = { [weak self] in self?.error("Could not listen on the local network. Close other copies of MacBridge and restart."); self?.shutdown(code: 1) }
        transport.onSession = { [weak self] session in
            guard let self else { return }; try self.transport.send(self.authentication.challenge(nonce: session.nonce), to: session)
        }
        transport.onFrame = { [weak self] session, frame in try self?.frame(session, frame) }
        transport.onClose = { [weak self] session in self?.disconnected(session) }
        try transport.listen(identity: identity.tls, port: port, host: host)
        armPairingExpiry()
    }
    private func binding(_ id: String) -> [String] { [identity.deviceID, authentication.pin, id, authentication.peers[id]!.publicKey] }
    private func receiverCheckpoint(_ id: String) -> TransferCheckpoint {
        let binding = binding(id)
        return TransferCheckpoint(url: directory.appendingPathComponent("TransferState/receiver-" + IdentityMaterial.hash(Self.pythonArray(binding)) + ".json"), identity: binding)
    }
    /// Match Python's historical checkpoint filenames, including Unicode escapes.
    static func pythonArray(_ values: [String]) -> Data {
        let encoded = values.map { text -> String in
            let array = try! JSONSerialization.data(withJSONObject: [text], options: [.withoutEscapingSlashes])
            let string = String(decoding: array, as: UTF8.self).dropFirst().dropLast()
            return string.unicodeScalars.map { scalar -> String in
                if scalar.value < 128 { return String(scalar) }
                if scalar.value <= 0xffff { return String(format: "\\u%04x", scalar.value) }
                let n = scalar.value - 0x10000
                return String(format: "\\u%04x\\u%04x", 0xd800 + (n >> 10), 0xdc00 + (n & 1023))
            }.joined()
        }
        return Data(("[" + encoded.joined(separator: ", ") + "]").utf8)
    }
    private func cleanupOrphans() throws {
        let retained = Set(receivers.values.compactMap { $0.temporaryName } + [sender?.snapshot?.lastPathComponent].compactMap { $0 })
        for (folder, prefix) in [(quota.directory, ".incoming-"), (directory.appendingPathComponent("OutgoingFiles"), ".outgoing-")] {
            if !PrivateStorage.exists(folder) { continue }
            try PrivateStorage.directory(folder)
            for name in try FileManager.default.contentsOfDirectory(atPath: folder.path) where name.hasPrefix(prefix) && !retained.contains(name) {
                // Only owned regular partials or symlinks themselves; never follow them.
                var info = stat(); let path = folder.appendingPathComponent(name).path
                if lstat(path, &info) == 0 && info.st_uid == getuid() && [S_IFREG, S_IFLNK].contains(info.st_mode & S_IFMT) { _ = unlink(path) }
            }
        }
        let stateFolder = directory.appendingPathComponent("TransferState")
        if PrivateStorage.exists(stateFolder) {
            try PrivateStorage.directory(stateFolder)
            let retainedStates = Set(receivers.values.map { $0.state.url.lastPathComponent })
            for name in try FileManager.default.contentsOfDirectory(atPath: stateFolder.path) where name.hasPrefix("receiver-") && name.hasSuffix(".json") && !retainedStates.contains(name) { _ = unlink(stateFolder.appendingPathComponent(name).path) }
        }
        storage = try? quota.usage()
    }
    private func selectedReceiver() -> FileReceiver? {
        if let peerID, let receiver = receivers[peerID], receiver.status != "idle" { return receiver }
        return receivers.values.first { $0.activeID != nil } ?? lastReceiver
    }
    private func receiveToken(_ receiver: FileReceiver?) -> String {
        guard let receiver, let id = receiver.activeID, let temporary = receiver.temporaryName,
              let peer = receivers.first(where: { $0.value === receiver })?.key,
              let publicKey = authentication.peers[peer]?.publicKey else { return "" }
        return IdentityMaterial.hash(Self.pythonArray([peer, publicKey, id, temporary]))
    }
    private func state() {
        guard !stopping else { return }
        let receiver = selectedReceiver()
        var components = URLComponents(); components.scheme = "macbridge"; components.host = "pair"
        components.queryItems = ["v": "2", "id": identity.deviceID, "name": authentication.name,
            "fingerprint": authentication.pin, "ip": address, "port": String(port), "secret": authentication.secret].sorted(by: { $0.key < $1.key }).map { URLQueryItem(name: $0.key, value: $0.value) }
        emit(["event": "state", "running": true, "connected": active != nil,
            "phoneName": peerID.flatMap { authentication.peers[$0]?.name } ?? "Android Phone",
            "peers": authentication.peers.keys.sorted().map { ["id": $0, "name": authentication.peers[$0]!.name] },
            "clipboardEnabled": clipboard, "filesEnabled": files, "notificationsEnabled": notifications,
            "connectionId": active?.id ?? "", "fileSending": sender?.busy ?? false,
            "fileSendStatus": sender?.status ?? "idle", "fileCanResume": sender?.status == "paused" && active != nil && senderPeer == peerID,
            "sentBytes": sender?.sent ?? 0, "fileSize": sender?.total ?? 0,
            "fileReceiveStatus": receiver?.status ?? "idle", "fileReceiveToken": receiveToken(receiver),
            "receivedBytes": receiver?.received ?? 0, "receivedFileSize": receiver?.total ?? 0,
            "storageUsed": storage.map { $0.0 as Any } ?? NSNull(), "storageFiles": storage.map { $0.1 as Any } ?? NSNull(), "storageLimit": quota.limit,
            "certificateStarts": identity.starts, "certificateExpires": identity.expires, "certificateStatus": identity.status, "certificateRenewing": renewing,
            "endpoint": "\(address):\(port)", "pairingURI": authentication.remaining > 0 && !authentication.secret.isEmpty ? components.string ?? "" : "",
            "expiresAt": Date().timeIntervalSince1970 + authentication.remaining, "lastAction": lastAction,
            "discovery": ["id": identity.deviceID, "name": authentication.name, "fingerprint": authentication.pin, "port": Int(port), "ipv6": host == nil]])
    }
    private func report(_ text: String) { lastAction = text; state() }
    func error(_ text: String) { emit(["event": "error", "message": text]) }
    private func frame(_ session: Transport.Session, _ frame: [String: Any]) throws {
        guard !stopping else { return }
        if !session.authenticated {
            let id = try authentication.authenticate(frame, nonce: session.nonce)
            if let previous = active { transport.close(previous) }
            session.authenticated = true; active = session; peerID = id; mirror.reset()
            if receivers[id] == nil {
                if receivers.count >= 4, let stale = receivers.keys.sorted().first { receivers.removeValue(forKey: stale)?.discard() }
                receivers[id] = FileReceiver(directory: quota.directory, state: receiverCheckpoint(id), quota: quota)
            }
            try transport.send(["type": "AUTH_OK", "deviceId": identity.deviceID], to: session)
            report("Phone connected"); return
        }
        guard active === session else { throw ProtocolFailure.invalidIdentity }
        let kind = frame["type"] as? String ?? ""
        try budget.message(kind)
        switch kind {
        case "HEARTBEAT":
            guard let acknowledged = StrictJSON.boolean(frame["isAck"]) else { throw ProtocolFailure.invalidJSON }
            if !acknowledged { var response = frame; response["isAck"] = true; try transport.send(response, to: session) }
        case "FILE_ACK": if senderPeer == peerID { try sender?.acknowledge(frame) }
        case "FILE_INIT", "FILE_CHUNK", "FILE_CANCEL":
            guard let peerID, let receiver = receivers[peerID] else { throw ProtocolFailure.invalidIdentity }
            let reservations = receivers.values.reduce(Int64(0)) { $0 + $1.reservation }
            let ack = try receiver.process(frame, enabled: files, reservations: reservations)
            try transport.send(ack, to: session); lastReceiver = receiver
            let now = ProcessInfo.processInfo.systemUptime
            if receiver.status != receiveStatus || now >= nextReceiveReport {
                nextReceiveReport = now + 0.25; receiveStatus = receiver.status
                if kind != "FILE_CHUNK" || ["completed", "failed", "cancelled"].contains(receiver.status) { storage = try? quota.usage() }
                report(receiver.status == "completed" ? "File received and verified" : receiver.status == "storage_full" ? "Received storage is full. Move files out or increase the storage limit." : receiver.status == "paused" ? "Receiving paused" : "Receiving file from phone")
            }
        case "NOTIFICATION", "NOTIFICATION_ACTION":
            for var event in try mirror.process(frame, enabled: notifications) {
                event["event"] = "notification"; event["connectionId"] = session.id; emit(event)
            }
        case "CLIPBOARD":
            guard frame["mimeType"] as? String == "text/plain", let text = frame["content"] as? String, text.utf8.count <= 65536 else { throw ProtocolFailure.invalidJSON }
            if echo { try transport.send(["type": "CLIPBOARD", "content": text, "sourceDevice": authentication.name, "mimeType": "text/plain", "timestamp": Int64(Date().timeIntervalSince1970 * 1000)], to: session) }
            if clipboard && !fixture { _ = try Self.clipboardProcess("/usr/bin/pbcopy", input: Data(text.utf8)); report("Phone clipboard copied to Mac") }
        default: throw ProtocolFailure.invalidJSON
        }
    }
    private func disconnected(_ session: Transport.Session) {
        guard active === session else { return }
        if let peerID { try? receivers[peerID]?.pause() }
        sender?.pause(); active = nil; peerID = nil; mirror.reset()
        emit(["event": "notification", "operation": "clear", "connectionId": session.id])
        report("Phone disconnected")
    }
    private func makeSender(_ id: String, send: @escaping ([String: Any]) throws -> Void) -> FileSender {
        FileSender(directory: directory.appendingPathComponent("OutgoingFiles"),
            state: TransferCheckpoint(url: directory.appendingPathComponent("TransferState/sender.json"), identity: binding(id)),
            queue: queue, send: send, report: { [weak self] in
                guard let self else { return }
                self.report(self.sender?.status == "completed" ? "File received and verified by phone. Save it on Android." : self.sender?.status == "paused" ? "Transfer paused. Reconnect the same phone and resume within 10 minutes." : "Sending file to phone")
            })
    }
    private func sendClosure(_ session: Transport.Session) -> ([String: Any]) throws -> Void {
        { [weak self, weak session] frame in
            guard let self, let session, self.active === session else { throw ProtocolFailure.invalidIdentity }
            try self.transport.send(frame, to: session)
        }
    }
    private func current(_ command: [String: Any]) throws -> Transport.Session {
        guard let active, command["connectionId"] as? String == active.id, peerID != nil else { throw ProtocolFailure.invalidIdentity }; return active
    }
    func command(_ command: [String: Any]) throws {
        guard !stopping else { return }
        switch command["action"] as? String {
        case "status": state()
        case "storageStatus": storage = try? quota.usage(); state()
        case "setStorageLimit": guard let value = StrictJSON.integer(command["bytes"]) else { throw ProtocolFailure.invalidJSON }; try quota.setLimit(value); report("Received storage limit updated. Existing files retained.")
        case "setClipboardEnabled": guard let enabled = StrictJSON.boolean(command["enabled"]) else { throw ProtocolFailure.invalidJSON }; clipboard = enabled; report(enabled ? "Clipboard sharing enabled" : "Clipboard sharing paused")
        case "setFilesEnabled":
            guard let enabled = StrictJSON.boolean(command["enabled"]) else { throw ProtocolFailure.invalidJSON }
            files = enabled
            if !enabled { receivers.values.forEach { $0.discard() }; storage = try? quota.usage() }
            report(enabled ? "File receiving enabled" : "File receiving paused")
        case "setNotificationsEnabled":
            guard let enabled = StrictJSON.boolean(command["enabled"]) else { throw ProtocolFailure.invalidJSON }
            notifications = enabled
            if !enabled { mirror.reset(); emit(["event": "notification", "operation": "clear", "connectionId": active?.id ?? ""]) }
            report(enabled ? "Notification receiving enabled" : "Notification receiving paused")
        case "dismissNotification", "replyNotification":
            let session = try current(command)
            guard notifications, let key = command["notificationId"] as? String, key.utf8.count <= 512,
                  let token = command["actionToken"] as? String else { throw ProtocolFailure.invalidJSON }
            let reply: String?
            if command["action"] as? String == "replyNotification" { guard let text = command["replyText"] as? String else { throw ProtocolFailure.invalidJSON }; reply = text } else { reply = nil }
            try transport.send(mirror.request(key: key, token: token, reply: reply), to: session)
        case "pushClipboard":
            guard clipboard, !fixture, let active else { throw ProtocolFailure.invalidIdentity }
            let text = try Self.clipboardProcess("/usr/bin/pbpaste")
            guard text.count <= 65536, let value = String(data: text, encoding: .utf8) else { throw ProtocolFailure.oversized }
            try transport.send(["type": "CLIPBOARD", "content": value, "sourceDevice": authentication.name, "mimeType": "text/plain", "timestamp": Int64(Date().timeIntervalSince1970 * 1000)], to: active)
            report("Sent Mac clipboard to phone")
        case "sendFile":
            let session = try current(command)
            guard let path = command["path"] as? String, !path.isEmpty, path.utf8.count <= 4096,
                  sender?.busy != true, sender?.status != "paused", let peerID else { throw ProtocolFailure.invalidJSON }
            senderPeer = peerID; sender = makeSender(peerID, send: sendClosure(session)); try sender!.start(path: path)
        case "resumeFileSend":
            let session = try current(command)
            guard senderPeer == peerID, let sender else { throw ProtocolFailure.invalidIdentity }
            try sender.resume(send: sendClosure(session))
        case "cancelFileSend":
            if let sender {
                if let active, senderPeer == peerID { try? transport.send(["type": "FILE_CANCEL", "transferId": sender.id], to: active) }
                sender.cancel()
            }
        case "cancelFileReceive":
            let receiver = selectedReceiver(); let token = receiveToken(receiver)
            guard !token.isEmpty, command["transferToken"] as? String == token, let receiver, let id = receiver.activeID else { return }
            if let ack = receiver.cancel(id), let active, receivers[peerID ?? ""] === receiver { try? transport.send(ack, to: active) }
            storage = try? quota.usage(); report("File receiving cancelled")
        case "disconnect": if let active { transport.close(active) }
        case "forget":
            guard let id = command["id"] as? String else { throw ProtocolFailure.invalidJSON }
            try authentication.forget(id)
            receivers.removeValue(forKey: id)?.discard()
            if senderPeer == id { sender?.cancel() }
            if peerID == id, let active { transport.close(active) }
            storage = try? quota.usage(); report("Phone forgotten")
        case "reissueCode":
            let candidate = try command["address"] as? String ?? Self.detectAddress()
            guard Self.validAddress(candidate) else { throw ProtocolFailure.invalidJSON }
            address = candidate; try authentication.rotate(); armPairingExpiry(); report("New pairing code ready")
        case "renewCertificate":
            guard !renewing else { return }
            try identity.renew(); renewing = true; state()
            transport.replaceListener(identity: identity.tls, port: port, host: host) { [weak self] error in
                guard let self, !self.stopping else { return }
                self.renewing = false
                if error != nil { self.error("The renewed certificate is saved. Restart the companion to reopen the listener."); self.state() }
                else { self.report("Mac certificate renewed. Existing pairing preserved.") }
            }
        case "quit": shutdown()
        default: throw ProtocolFailure.invalidJSON
        }
    }
    private func armPairingExpiry() {
        pairingExpiry?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.state() }
        pairingExpiry = work; queue.asyncAfter(deadline: .now() + authentication.remaining, execute: work)
    }
    func shutdown(code: Int32 = 0) {
        guard !stopping else { return }; stopping = true; pairingExpiry?.cancel(); sender?.pause()
        receivers.values.forEach { files ? $0.suspend() : $0.discard() }; mirror.reset(); transport.shutdown(); exit(code)
    }
    static func validAddress(_ value: String) -> Bool {
        var v4 = in_addr(); var v6 = in6_addr()
        return inet_pton(AF_INET, value, &v4) == 1 || inet_pton(AF_INET6, value, &v6) == 1
    }
    static func detectAddress() throws -> String {
        let fd = socket(AF_INET, SOCK_DGRAM, 0); guard fd >= 0 else { throw ProtocolFailure.invalidIdentity }
        defer { close(fd) }
        var remote = sockaddr_in(); remote.sin_family = sa_family_t(AF_INET); remote.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); remote.sin_port = UInt16(9).bigEndian
        inet_pton(AF_INET, "192.0.2.1", &remote.sin_addr)
        let connected = withUnsafePointer(to: &remote) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        guard connected == 0 else { throw ProtocolFailure.invalidIdentity }
        var local = sockaddr_in(); var size = socklen_t(MemoryLayout<sockaddr_in>.size)
        let result = withUnsafeMutablePointer(to: &local) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &size) } }
        var bytes = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard result == 0, inet_ntop(AF_INET, &local.sin_addr, &bytes, socklen_t(bytes.count)) != nil else { throw ProtocolFailure.invalidIdentity }
        let value = String(cString: bytes)
        guard !value.hasPrefix("127."), !value.hasPrefix("169.254."), value != "0.0.0.0" else { throw ProtocolFailure.invalidIdentity }; return value
    }
    private static func clipboardProcess(_ executable: String, input: Data? = nil) throws -> Data {
        let process = Process(); let output = Pipe(); let stdin = Pipe()
        process.executableURL = URL(fileURLWithPath: executable); process.standardOutput = output; process.standardInput = stdin; process.standardError = FileHandle.nullDevice
        try process.run()
        let timeout = DispatchWorkItem { if process.isRunning { process.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + 10, execute: timeout)
        defer { timeout.cancel() }
        if let input { try stdin.fileHandleForWriting.write(contentsOf: input) }; try stdin.fileHandleForWriting.close()
        var result = Data()
        while true {
            let data = output.fileHandleForReading.availableData
            if data.isEmpty { break }
            guard result.count + data.count <= 65536 else { process.terminate(); process.waitUntilExit(); throw ProtocolFailure.oversized }
            result.append(data)
        }
        process.waitUntilExit(); guard process.terminationStatus == 0 else { throw ProtocolFailure.invalidIdentity }; return result
    }
}
