import Foundation

struct PairedPhone: Decodable, Identifiable {
    let id: String
    let name: String
}

struct CompanionEvent: Decodable {
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
    let sentBytes: Int64?
    let fileSize: Int64?
    let endpoint: String?
    let pairingURI: String?
    let expiresAt: TimeInterval?
    let lastAction: String?
    let message: String?
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
enum CompanionSettingsTab: Hashable { case pairing, devices, status }

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
