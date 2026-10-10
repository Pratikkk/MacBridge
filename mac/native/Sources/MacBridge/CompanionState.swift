import Foundation

struct PairedPhone: Decodable, Identifiable, Equatable {
    let id: String
    let name: String
}

struct CompanionEvent: Decodable {
    let dismissToken: String?
    let status: String?
    let operation: String?
    let notificationId: String?
    let packageName: String?
    let appName: String?
    let title: String?
    let text: String?
    let notificationsEnabled: Bool?
    let discovery: BonjourIdentity?
    let event: String
    let running: Bool?
    let connected: Bool?
    let phoneName: String?
    let peers: [PairedPhone]?
    let clipboardEnabled: Bool?
    let filesEnabled: Bool?
    let connectionId: String?
    let fileSending: Bool?
    let fileSendStatus: String?
    let fileCanResume: Bool?
    let fileReceiveStatus: String?
    let fileReceiveToken: String?
    let receivedBytes: Int64?
    let receivedFileSize: Int64?
    let sentBytes: Int64?
    let fileSize: Int64?
    let endpoint: String?
    let pairingURI: String?
    let expiresAt: TimeInterval?
    let lastAction: String?
    let message: String?
}

struct BonjourIdentity: Decodable, Equatable {
    let id: String
    let name: String
    let fingerprint: String
    let port: Int
    let ipv6: Bool
    var valid: Bool {
        !id.isEmpty && id.utf8.count <= 128 && id.unicodeScalars.allSatisfy { !CharacterSet.controlCharacters.contains($0) } &&
        (1...65535).contains(port) && fingerprint.range(of: "^(?:[0-9A-F]{2}:){31}[0-9A-F]{2}$", options: .regularExpression) != nil
    }
    var serviceName: String { "MacBridge-" + String(id.filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }.prefix(12)) }
    var txt: [String: Data] {
        var boundedName = name.unicodeScalars.filter { !CharacterSet.controlCharacters.contains($0) }.map(String.init).joined()
        while boundedName.utf8.count > 100 { boundedName.removeLast() }
        return ["id": Data(id.utf8), "name": Data(boundedName.utf8), "fingerprint": Data(fingerprint.utf8), "ipv6": Data((ipv6 ? "1" : "0").utf8)]
    }
}

/// stdout is a byte stream; JSON may span several reads or share a read.
struct EventBuffer {
    private var pending = Data()
    mutating func append(_ data: Data) throws -> [CompanionEvent] {
        pending.append(data)
        var events: [CompanionEvent] = []
        while let newline = pending.firstIndex(of: 10) {
            let line = pending[..<newline]
            guard line.count <= 65_536 else { throw BufferError.oversized }
            pending.removeSubrange(...newline)
            events.append(try JSONDecoder().decode(CompanionEvent.self, from: line))
        }
        guard pending.count <= 65_536 else { throw BufferError.oversized }
        return events
    }
    enum BufferError: Error { case oversized }
}

enum CompanionEventStream {
    static func read(from handle: FileHandle, receive: (CompanionEvent) -> Void) throws {
        var buffer = EventBuffer()
        while true {
            // Fixed-length FileHandle reads can wait for a full buffer on a pipe.
            let data = handle.availableData
            if data.isEmpty { return }
            for event in try buffer.append(data) { receive(event) }
        }
    }
}

// Keep menu labels bounded, and availability aligned with the real engine state.
enum CompanionSettingsTab: Hashable { case pairing, devices, status, notifications }

struct CompanionMenuState {
    let running: Bool
    let connected: Bool
    let clipboardEnabled: Bool
    let phoneName: String
    var canSendClipboard: Bool { running && connected && clipboardEnabled }
    var status: String {
        guard running else { return "Companion Stopped" }
        guard connected else { return "No Phone Connected" }
        let name = phoneName.filter { !$0.isNewline && !$0.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) }
        let label = name.isEmpty ? "Android Phone" : String(name.prefix(36)) + (name.count > 36 ? "…" : "")
        return "Connected to \(label)"
    }
}

/// One bounded presentation for both transfer directions; never equate sent bytes with verification.
struct TransferPresentation: Equatable {
    let status: String
    let bytes: Int64
    let total: Int64
    private var size: Int64 { min(104_857_600, max(0, total)) }
    private var count: Int64 { min(size, max(0, bytes)) }
    var visible: Bool { ["preparing", "sending", "receiving", "paused", "completed", "failed", "cancelled"].contains(status) }
    var active: Bool { ["preparing", "sending", "receiving"].contains(status) }
    var fraction: Double { size > 0 ? Double(count) / Double(size) : 0 }
    var detail: String {
        switch status {
        case "preparing": return "Preparing…"
        case "sending", "receiving":
            if size == 0 || count == size { return "Verifying…" }
            return "\(Int(fraction * 100))% · \(Self.format(count)) / \(Self.format(size))"
        case "paused": return "Paused · \(Self.format(count)) / \(Self.format(size))"
        case "completed": return "Verified · \(Self.format(size))"
        case "cancelled": return "Cancelled"
        case "failed": return "Failed — try again"
        default: return ""
        }
    }
    private static func format(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .binary)
    }
}
