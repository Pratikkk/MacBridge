import Foundation
import CryptoKit
import Darwin

public enum TransferRules {
    public static let chunk: Int64 = 65536
    public static let maximum: Int64 = 104_857_600
    public static func metadata(_ value: [String: Any]) throws -> (String, String, Int64, String) {
        guard let id = value["id"] as? String, id.range(of: "^[A-Za-z0-9_-]{1,64}$", options: .regularExpression) != nil,
              let name = value["name"] as? String, (1...256).contains(name.unicodeScalars.count),
              let size = StrictJSON.integer(value["size"]), (0...maximum).contains(size),
              let checksum = value["checksum"] as? String, checksum.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw ProtocolFailure.invalidJSON }
        return (id, name, size, checksum)
    }
    public static func hex(_ hash: SHA256) -> String { hash.finalize().map { String(format: "%02x", $0) }.joined() }
    public static func expires(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber, let expiry = Double(number.stringValue), expiry.isFinite,
              expiry > Date().timeIntervalSince1970, expiry <= Date().timeIntervalSince1970 + 601 else { return nil }
        return expiry
    }
    public static func stream(_ url: URL, flags: Int32) throws -> FileHandle {
        let fd = open(url.path, flags | O_NOFOLLOW | O_NONBLOCK, 0o600)
        guard fd >= 0 else { throw ProtocolFailure.invalidIdentity }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_uid == getuid() else { close(fd); throw ProtocolFailure.invalidIdentity }
        return FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    }
}

public final class FileReceiver {
    private final class Incoming {
        let id: String, name: String, size: Int64, checksum: String, temporary: URL, destination: URL, stream: FileHandle
        var offset: Int64, digest: SHA256, expiry: Double
        init(id: String, name: String, size: Int64, checksum: String, temporary: URL, destination: URL,
             stream: FileHandle, offset: Int64 = 0, digest: SHA256 = SHA256(), expiry: Double = Date().timeIntervalSince1970 + 600) {
            self.id = id; self.name = name; self.size = size; self.checksum = checksum
            self.temporary = temporary; self.destination = destination; self.stream = stream
            self.offset = offset; self.digest = digest; self.expiry = expiry
        }
    }
    private let directory: URL
    public let state: TransferCheckpoint
    private let quota: ReceiveQuota
    private var incoming: Incoming?
    private var receipts: [[String: Any]] = []
    private var cancelled = Set<String>()
    private var nextCheckpoint = 0.0
    public private(set) var status = "idle"
    public private(set) var received: Int64 = 0
    public private(set) var total: Int64 = 0
    public var activeID: String? { incoming?.id }
    public var temporaryName: String? { incoming?.temporary.lastPathComponent }
    public var reservation: Int64 { incoming.map { max(0, $0.size - $0.offset) } ?? 0 }
    public init(directory: URL, state: TransferCheckpoint, quota: ReceiveQuota) {
        self.directory = directory; self.state = state; self.quota = quota
        restore()
    }
    private func restore() {
        let saved = state.load()
        for receipt in (saved["receipts"] as? [[String: Any]] ?? []).prefix(64) {
            do {
                let (id, _, size, checksum) = try TransferRules.metadata(receipt)
                guard TransferRules.expires(receipt["expires"]) != nil, let name = receipt["destination"] as? String,
                      name.hasPrefix("verified-"), !name.contains("/") else { continue }
                let destination = directory.appendingPathComponent(name)
                var record = receipt
                let source: URL
                if PrivateStorage.exists(destination) { source = try PrivateStorage.file(directory, name: name, prefix: "verified-") }
                else { source = try PrivateStorage.file(directory, name: receipt["temporary"], prefix: ".incoming-") }
                var info = stat()
                guard lstat(source.path, &info) == 0, info.st_size == size,
                      TransferRules.hex(try PrivateStorage.digest(source)) == checksum else { continue }
                if source != destination { guard rename(source.path, destination.path) == 0 else { continue } }
                record["temporary"] = NSNull(); record["id"] = id; receipts.append(record)
            } catch { continue }
        }
        if let value = saved["incoming"] as? [String: Any] {
            do {
                let (id, name, size, checksum) = try TransferRules.metadata(value)
                guard !receipts.contains(where: { $0["id"] as? String == id }), let expiry = TransferRules.expires(value["expires"]),
                      let offset = StrictJSON.integer(value["offset"]), offset >= 0, offset < size, offset % TransferRules.chunk == 0,
                      let destinationName = value["destination"] as? String, destinationName.hasPrefix("verified-"), !destinationName.contains("/") else { throw ProtocolFailure.invalidJSON }
                let temporary = try PrivateStorage.file(directory, name: value["temporary"], prefix: ".incoming-")
                let digest = try PrivateStorage.digest(temporary, length: offset)
                guard TransferRules.hex(digest) == value["prefix"] as? String else { throw ProtocolFailure.invalidIdentity }
                let destination = directory.appendingPathComponent(destinationName)
                guard !PrivateStorage.exists(destination) else { throw ProtocolFailure.invalidIdentity }
                let stream = try TransferRules.stream(temporary, flags: O_RDWR)
                try stream.truncate(atOffset: UInt64(offset)); try stream.seek(toOffset: UInt64(offset))
                incoming = Incoming(id: id, name: name, size: size, checksum: checksum, temporary: temporary,
                    destination: destination, stream: stream, offset: offset, digest: digest, expiry: expiry)
                status = "paused"; received = offset; total = size
            } catch { incoming = nil }
        }
        try? checkpoint()
    }
    private func checkpoint(force: Bool = true) throws {
        let now = ProcessInfo.processInfo.systemUptime
        if !force && now < nextCheckpoint { return }
        nextCheckpoint = now + 0.25
        var value: Any = NSNull()
        if let item = incoming {
            try item.stream.synchronize()
            value = ["id": item.id, "name": item.name, "size": item.size, "checksum": item.checksum,
                "offset": item.offset, "temporary": item.temporary.lastPathComponent, "destination": item.destination.lastPathComponent,
                "prefix": TransferRules.hex(item.digest), "expires": item.expiry] as [String: Any]
        }
        receipts = Array(receipts.filter { TransferRules.expires($0["expires"]) != nil }.suffix(64))
        try state.save(["incoming": value, "receipts": receipts])
    }
    public func pause() throws {
        if let item = incoming {
            if status != "paused" { item.expiry = Date().timeIntervalSince1970 + 600 }
            status = "paused"; try checkpoint()
        }
    }
    public func suspend() { try? pause(); try? incoming?.stream.close(); incoming = nil }
    public func abort() {
        if let item = incoming { try? item.stream.close(); _ = unlink(item.temporary.path) }
        incoming = nil; status = "failed"; try? checkpoint()
    }
    public func discard() { abort(); receipts.removeAll(); state.clear() }
    public func cancel(_ id: String) -> [String: Any]? {
        guard incoming?.id == id else { return nil }
        let offset = incoming!.offset
        rememberCancelled(id); abort(); status = "cancelled"
        return ack(id, "CANCELLED", offset)
    }
    private func rememberCancelled(_ id: String) { if cancelled.count >= 64 { cancelled.remove(cancelled.first!) }; cancelled.insert(id) }
    private func ack(_ id: String, _ status: String, _ count: Int64 = 0, _ checksum: String? = nil) -> [String: Any] {
        var value: [String: Any] = ["type": "FILE_ACK", "transferId": id, "status": status, "receivedBytes": count]
        if let checksum { value["sha256Checksum"] = checksum }; return value
    }
    public func process(_ message: [String: Any], enabled: Bool, reservations: Int64) throws -> [String: Any] {
        guard let id = message["transferId"] as? String, id.range(of: "^[A-Za-z0-9_-]{1,64}$", options: .regularExpression) != nil else { throw ProtocolFailure.invalidJSON }
        guard enabled else { abort(); return ack(id, "REJECTED") }
        do {
            let kind = message["type"] as? String
            if kind == "FILE_CANCEL" {
                let receiptChanged = receipts.contains { $0["id"] as? String == id }
                rememberCancelled(id); receipts.removeAll { $0["id"] as? String == id }
                if incoming?.id == id { abort(); status = "cancelled" }
                else if receiptChanged { try checkpoint() }
                return ack(id, "CANCELLED")
            }
            if kind == "FILE_INIT" {
                if let item = incoming, item.expiry <= Date().timeIntervalSince1970 { abort() }
                if StrictJSON.boolean(message["resume"]) == true {
                    guard !cancelled.contains(id) else { return ack(id, "REJECTED") }
                    if let receipt = receipts.first(where: { $0["id"] as? String == id && TransferRules.expires($0["expires"]) != nil }),
                       receipt["name"] as? String == message["fileName"] as? String,
                       StrictJSON.integer(receipt["size"]) == StrictJSON.integer(message["fileSize"]),
                       receipt["checksum"] as? String == message["sha256Checksum"] as? String,
                       StrictJSON.integer(message["chunkSize"]) == TransferRules.chunk {
                        let path = try PrivateStorage.file(directory, name: receipt["destination"], prefix: "verified-")
                        guard TransferRules.hex(try PrivateStorage.digest(path)) == receipt["checksum"] as? String else { receipts.removeAll { $0["id"] as? String == id }; try checkpoint(); return ack(id, "REJECTED") }
                        let size = StrictJSON.integer(receipt["size"])!
                        if incoming == nil { status = "completed"; received = size; total = size }
                        return ack(id, "COMPLETED", size, receipt["checksum"] as? String)
                    }
                    guard let item = incoming, item.id == id,
                          item.name == message["fileName"] as? String, item.size == StrictJSON.integer(message["fileSize"]),
                          item.checksum == message["sha256Checksum"] as? String, StrictJSON.integer(message["chunkSize"]) == TransferRules.chunk else { return ack(id, "REJECTED") }
                    var info = stat()
                    guard fstat(item.stream.fileDescriptor, &info) == 0, info.st_size == item.offset else { abort(); return ack(id, "FAILED") }
                    item.digest = try PrivateStorage.digest(item.temporary); status = "receiving"
                    return ack(id, "READY", item.offset)
                }
                guard incoming == nil else { return ack(id, "BUSY") }
                let (_, name, size, checksum) = try TransferRules.metadata(["id": id, "name": message["fileName"] ?? NSNull(), "size": message["fileSize"] ?? NSNull(), "checksum": message["sha256Checksum"] ?? NSNull()])
                guard StrictJSON.integer(message["chunkSize"]) == TransferRules.chunk else { throw ProtocolFailure.invalidJSON }
                try PrivateStorage.directory(directory)
                guard try quota.permits(size, reservations: reservations) else { status = "storage_full"; return ack(id, "STORAGE_FULL") }
                let base = name.replacingOccurrences(of: "\\", with: "/").components(separatedBy: "/").last ?? "download"
                let safe = String(base.unicodeScalars.map { CharacterSet.controlCharacters.contains($0) || "/\\:".unicodeScalars.contains($0) ? "_" : String($0) }.joined().trimmingCharacters(in: CharacterSet(charactersIn: " .")).prefix(100))
                let temporary = directory.appendingPathComponent(".incoming-" + UUID().uuidString)
                let destination = directory.appendingPathComponent("verified-" + UUID().uuidString + "-" + (safe.isEmpty ? "download" : safe))
                let stream = try TransferRules.stream(temporary, flags: O_CREAT | O_EXCL | O_RDWR)
                incoming = Incoming(id: id, name: name, size: size, checksum: checksum, temporary: temporary, destination: destination, stream: stream)
                status = "receiving"; received = 0; total = size; try checkpoint()
                return size == 0 ? try finish() : ack(id, "READY")
            }
            guard kind == "FILE_CHUNK", let item = incoming, item.id == id else { return ack(id, "REJECTED") }
            let expected = min(TransferRules.chunk, item.size - item.offset)
            guard StrictJSON.integer(message["offset"]) == item.offset,
                  StrictJSON.integer(message["chunkIndex"]) == item.offset / TransferRules.chunk,
                  StrictJSON.integer(message["totalChunks"]) == (item.size + TransferRules.chunk - 1) / TransferRules.chunk,
                  StrictJSON.integer(message["chunkLength"]) == expected,
                  let encoded = message["dataBase64"] as? String, encoded.utf8.count <= 87384,
                  let data = Data(base64Encoded: encoded), data.count == expected else { throw ProtocolFailure.invalidJSON }
            try item.stream.write(contentsOf: data); item.digest.update(data: data); item.offset += Int64(data.count)
            received = item.offset; item.expiry = Date().timeIntervalSince1970 + 600
            if item.offset == item.size { return try finish() }
            try checkpoint(force: false); return ack(id, "IN_PROGRESS", item.offset)
        } catch {
            abort(); return ack(id, "FAILED")
        }
    }
    private func finish() throws -> [String: Any] {
        guard let item = incoming else { throw ProtocolFailure.invalidJSON }
        guard item.offset == item.size, TransferRules.hex(item.digest) == item.checksum else { abort(); return ack(item.id, "VERIFICATION_FAILED") }
        try item.stream.synchronize(); try item.stream.close()
        receipts.append(["id": item.id, "name": item.name, "size": item.size, "checksum": item.checksum,
            "destination": item.destination.lastPathComponent, "temporary": item.temporary.lastPathComponent, "expires": Date().timeIntervalSince1970 + 600])
        incoming = nil; try checkpoint()
        guard rename(item.temporary.path, item.destination.path) == 0 else { throw ProtocolFailure.invalidIdentity }
        receipts[receipts.count - 1]["temporary"] = NSNull(); try checkpoint()
        status = "completed"; return ack(item.id, "COMPLETED", item.size, item.checksum)
    }
}

/// Sequential ACK-driven sender; no waiting loop, one outstanding chunk and a
/// cancellable deadline. Calls and callbacks share the engine's serial queue.
public final class FileSender {
    private let directory: URL, queue: DispatchQueue
    public let state: TransferCheckpoint
    private var send: ([String: Any]) throws -> Void
    private let report: () -> Void
    private var deadline: DispatchWorkItem?
    private var stream: FileHandle?
    private var expiry = 0.0
    private var nextCheckpoint = 0.0
    private var initPending = false
    private var resumed = false
    private var awaiting: Int64 = 0
    public private(set) var snapshot: URL?
    public private(set) var id = "mac-file-v1-" + UUID().uuidString
    public private(set) var name = "", checksum = "", status = "idle"
    public private(set) var sent: Int64 = 0, total: Int64 = 0
    public var busy: Bool { ["preparing", "sending"].contains(status) }
    public init(directory: URL, state: TransferCheckpoint, queue: DispatchQueue, send: @escaping ([String: Any]) throws -> Void, report: @escaping () -> Void) {
        self.directory = directory; self.state = state; self.queue = queue; self.send = send; self.report = report
        restore()
    }
    private func restore() {
        guard let value = state.load()["outgoing"] as? [String: Any] else { return }
        do {
            let (id, name, size, checksum) = try TransferRules.metadata(value)
            guard let expiry = TransferRules.expires(value["expires"]) else { throw ProtocolFailure.invalidJSON }
            let path = try PrivateStorage.file(directory, name: value["snapshot"], prefix: ".outgoing-")
            var info = stat()
            guard lstat(path.path, &info) == 0, info.st_size == size, TransferRules.hex(try PrivateStorage.digest(path)) == checksum else { throw ProtocolFailure.invalidIdentity }
            self.id = id; self.name = name; self.total = size; self.checksum = checksum; self.expiry = expiry
            snapshot = path; status = "paused"
        } catch { state.clear() }
    }
    private func checkpoint() throws {
        guard let snapshot else { return }
        try state.save(["outgoing": ["id": id, "name": name, "size": total, "checksum": checksum,
            "snapshot": snapshot.lastPathComponent, "expires": expiry]])
    }
    public func start(path: String) throws {
        guard !busy, status != "paused" else { throw ProtocolFailure.invalidJSON }
        status = "preparing"; report()
        do {
            try PrivateStorage.directory(directory)
            let source = try TransferRules.stream(URL(fileURLWithPath: path), flags: O_RDONLY)
            defer { try? source.close() }
            var info = stat()
            guard fstat(source.fileDescriptor, &info) == 0, info.st_size >= 0, info.st_size <= TransferRules.maximum else { throw ProtocolFailure.oversized }
            total = info.st_size; sent = 0; name = URL(fileURLWithPath: path).lastPathComponent
            guard !name.isEmpty, name.unicodeScalars.count <= 256 else { throw ProtocolFailure.invalidJSON }
            snapshot = directory.appendingPathComponent(".outgoing-" + UUID().uuidString)
            let target = try TransferRules.stream(snapshot!, flags: O_WRONLY | O_CREAT | O_EXCL)
            defer { try? target.close() }
            var hash = SHA256(); var remaining = total
            while remaining > 0 {
                guard let block = try source.read(upToCount: Int(min(remaining, TransferRules.chunk))), !block.isEmpty else { throw ProtocolFailure.invalidIdentity }
                try target.write(contentsOf: block); hash.update(data: block); remaining -= Int64(block.count)
            }
            guard (try source.read(upToCount: 1))?.isEmpty != false else { throw ProtocolFailure.invalidIdentity }
            try target.synchronize(); checksum = TransferRules.hex(hash); expiry = Date().timeIntervalSince1970 + 600
            try checkpoint(); try offer(resume: false)
        } catch { fail(); throw error }
    }
    public func resume(send: @escaping ([String: Any]) throws -> Void) throws {
        guard status == "paused", snapshot != nil, expiry > Date().timeIntervalSince1970 else { cancel(); throw ProtocolFailure.invalidJSON }
        guard TransferRules.hex(try PrivateStorage.digest(snapshot!)) == checksum else { fail(); throw ProtocolFailure.invalidIdentity }
        self.send = send; try offer(resume: true)
    }
    private func offer(resume: Bool) throws {
        stream = try TransferRules.stream(snapshot!, flags: O_RDONLY)
        status = "sending"; initPending = true; resumed = resume
        try transmit(["type": "FILE_INIT", "transferId": id, "fileName": name, "fileSize": total,
            "sha256Checksum": checksum, "chunkSize": TransferRules.chunk, "mimeType": "application/octet-stream", "resume": resume])
        report()
    }
    private func transmit(_ frame: [String: Any]) throws {
        do { try send(frame) } catch { pause(); throw error }
        deadline?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.pause() }
        deadline = work; queue.asyncAfter(deadline: .now() + 15, execute: work)
    }
    public func acknowledge(_ value: [String: Any]) throws {
        guard value["transferId"] as? String == id, busy || status == "paused" else { return }
        if value["status"] as? String == "CANCELLED" { cancel(); return }
        guard busy else { return }
        deadline?.cancel(); deadline = nil
        do {
            guard let offset = StrictJSON.integer(value["receivedBytes"]) else { throw ProtocolFailure.invalidJSON }
            if initPending {
                initPending = false
                if value["status"] as? String == "COMPLETED", offset == total, value["sha256Checksum"] as? String == checksum { sent = total; complete(); return }
                guard value["status"] as? String == "READY", offset >= 0, offset < total, offset % TransferRules.chunk == 0,
                      resumed || offset == 0 else { throw ProtocolFailure.invalidJSON }
                sent = offset; try stream?.seek(toOffset: UInt64(offset))
            } else {
                let final = awaiting == total
                guard offset == awaiting, value["status"] as? String == (final ? "COMPLETED" : "IN_PROGRESS"),
                      !final || value["sha256Checksum"] as? String == checksum else { throw ProtocolFailure.invalidJSON }
                sent = offset
                if final { complete(); return }
            }
            guard let data = try stream?.read(upToCount: Int(min(total - sent, TransferRules.chunk))), !data.isEmpty else { throw ProtocolFailure.invalidIdentity }
            awaiting = sent + Int64(data.count)
            try transmit(["type": "FILE_CHUNK", "transferId": id, "offset": sent, "chunkIndex": sent / TransferRules.chunk,
                "totalChunks": (total + TransferRules.chunk - 1) / TransferRules.chunk, "chunkLength": data.count, "dataBase64": data.base64EncodedString()])
            if ProcessInfo.processInfo.systemUptime >= nextCheckpoint {
                nextCheckpoint = ProcessInfo.processInfo.systemUptime + 0.25
                expiry = Date().timeIntervalSince1970 + 600; try checkpoint(); report()
            }
        } catch { if status != "paused" { fail() }; throw error }
    }
    public func pause() {
        guard snapshot != nil, busy else { return }
        deadline?.cancel(); deadline = nil; try? stream?.close(); stream = nil
        status = "paused"; expiry = Date().timeIntervalSince1970 + 600
        do { try checkpoint() } catch { fail(); return }; report()
    }
    public func cancel() { cleanup(); status = "cancelled"; report() }
    private func fail() { try? send(["type": "FILE_CANCEL", "transferId": id]); cleanup(); status = "failed"; report() }
    private func complete() { cleanup(); status = "completed"; report() }
    private func cleanup() {
        deadline?.cancel(); deadline = nil; try? stream?.close(); stream = nil
        if let snapshot { _ = unlink(snapshot.path) }; snapshot = nil; state.clear()
    }
}
