import Foundation
import CoreImage
import AppKit

// Standalone checks work with Command Line Tools, without installing full Xcode/XCTest.
@main
struct CompanionChecks {
    struct Failure: Error { let message: String }
    static func require(_ value: Bool, _ message: String) throws {
        if !value { throw Failure(message: message) }
    }
    static func main() throws {
        let state = Data("{\"event\":\"state\",\"running\":true,\"phoneName\":\"Phone 🌉\",\"peers\":[{\"id\":\"phone-1\",\"name\":\"My phone\"}]}\n".utf8)
        let error = Data("{\"event\":\"error\",\"message\":\"No connected phone\"}\n".utf8)
        let split = state.firstIndex(of: 0xF0)! + 2
        var parser = EventBuffer()
        try require(try parser.append(state.prefix(split)).isEmpty, "Incomplete UTF-8 should wait for the rest of the frame")
        let values = try parser.append(state.suffix(from: split) + error)
        try require(values.count == 2, "Batched status events were lost")
        try require(values[0].phoneName == "Phone 🌉", "Split UTF-8 did not survive")
        try require(values[0].peers?.first?.id == "phone-1", "Phone identity did not decode")
        try require(values[1].message == "No connected phone", "Error did not decode")
        print("PASS: split UTF-8 and batched status events")

        for running in [false, true] {
            for connected in [false, true] {
                for enabled in [false, true] {
                    let menu = CompanionMenuState(running: running, connected: connected,
                        clipboardEnabled: enabled, phoneName: "Phone 🌉")
                    try require(menu.canSendClipboard == (running && connected && enabled),
                        "Clipboard action must require running, connected and permitted")
                    try require(menu.status == (!running ? "Companion Stopped" :
                        (!connected ? "No Phone Connected" : "Connected to Phone 🌉")),
                        "Menu connection status is incorrect")
                }
            }
        }
        let longName = CompanionMenuState(running: true, connected: true, clipboardEnabled: true,
            phoneName: String(repeating: "🌉", count: 200) + "\n\t")
        try require(longName.status.count <= 50 && longName.status.hasSuffix("…"),
            "Long phone names must not expand the menu without bound")
        let blankName = CompanionMenuState(running: true, connected: true, clipboardEnabled: true,
            phoneName: "\n\t")
        try require(blankName.status == "Connected to Android Phone", "Empty names need a readable fallback")
        print("PASS: compact menu status, Unicode and action availability")

        var permission = EventBuffer()
        let fileState = try permission.append(Data("{\"event\":\"state\",\"filesEnabled\":true}\n".utf8))
        try require(fileState.first?.filesEnabled == true, "File receiving permission did not decode")
        print("PASS: file receiving permission status")

        var sending = EventBuffer()
        let sendState = try sending.append(Data("{\"event\":\"state\",\"connectionId\":\"session\",\"fileSending\":true,\"sentBytes\":65536,\"fileSize\":80000}\n".utf8))
        try require(sendState.first?.connectionId == "session" && sendState.first?.fileSending == true &&
            sendState.first?.sentBytes == 65536 && sendState.first?.fileSize == 80000,
            "File sending state and connection identity did not decode")
        print("PASS: file sending progress and original connection identity")

        let pipe = Pipe()
        let delivered = DispatchSemaphore(value: 0)
        let ended = DispatchSemaphore(value: 0)
        DispatchQueue.global().async {
            try? CompanionEventStream.read(from: pipe.fileHandleForReading) { _ in delivered.signal() }
            ended.signal()
        }
        pipe.fileHandleForWriting.write(Data("{\"event\":\"state\",\"running\":true}\n".utf8))
        let arrivedWhileOpen = delivered.wait(timeout: .now() + 2) == .success
        pipe.fileHandleForWriting.closeFile()
        _ = ended.wait(timeout: .now() + 2)
        try require(arrivedWhileOpen, "Live status waited for a full buffer or pipe closure")
        print("PASS: live status arrives while the pipe stays open")

        var bounded = EventBuffer()
        var rejected = false
        do { _ = try bounded.append(Data(repeating: 65, count: 65_537)) }
        catch { rejected = true }
        try require(rejected, "Unterminated status can grow without bound")
        print("PASS: bounded status input")

        for invalid in [Data("{invalid JSON}\n".utf8), Data([0xC3, 10])] {
            var malformed = EventBuffer()
            var failed = false
            do { _ = try malformed.append(invalid) } catch { failed = true }
            try require(failed, "Malformed status or invalid UTF-8 was accepted")
        }
        print("PASS: malformed JSON and invalid UTF-8 status are rejected")

        try require(PairingQR.image(for: "") == nil, "An absent or consumed code must not render a QR")
        print("PASS: no QR for absent or consumed code")

        let code = "macbridge://pair?v=2&id=mac-test&name=My%20Mac&fingerprint=" +
            Array(repeating: "AB", count: 32).joined(separator: "%3A") +
            "&ip=192.168.0.100&port=8990&secret=" + String(repeating: "b", count: 64)
        guard let image = PairingQR.image(for: code),
              let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil),
              let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: CIContext(),
                options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]) else {
            throw Failure(message: "QR generation or detector failed")
        }
        let decoded = detector.features(in: CIImage(cgImage: cg))
            .compactMap { ($0 as? CIQRCodeFeature)?.messageString }
        try require(decoded == [code], "QR did not decode to the original pairing URI")
        print("PASS: real QR decodes to exact pairing URI")
    }
}
