import Foundation
import CoreImage
import AppKit
import ServiceManagement

// Standalone checks work with Command Line Tools, without installing full Xcode/XCTest.
@main
struct CompanionChecks {
    final class RecordingLoginItem: LoginItemService {
        var status: SMAppService.Status = .notRegistered
        var registrations = 0
        var removals = 0
        var fail = false
        func register() throws {
            registrations += 1
            if fail { throw Failure(message: "Registration rejected") }
            status = .requiresApproval
        }
        func unregister() throws {
            removals += 1
            if fail { throw Failure(message: "Removal rejected") }
            status = .notRegistered
        }
    }
    final class RecordingService: NetService {
        var publishes = 0
        var stops = 0
        override func publish() { publishes += 1 }
        override func stop() { stops += 1 }
    }
    struct Failure: Error { let message: String }
    static func require(_ value: Bool, _ message: String) throws {
        if !value { throw Failure(message: message) }
    }
    @MainActor static func main() throws {
        let loginService = RecordingLoginItem()
        let login = LoginItemController(service: loginService)
        try require(!login.requested && loginService.registrations == 0, "Login startup must not register itself")
        login.setEnabled(true)
        try require(login.requiresApproval && login.requested, "Pending approval must stay visible")
        loginService.status = .enabled
        login.refresh()
        try require(login.requested && !login.requiresApproval, "OS approval must refresh")
        loginService.status = .notRegistered
        login.refresh()
        try require(!login.requested, "External revocation must refresh")
        loginService.fail = true
        login.setEnabled(true)
        try require(login.errorMessage != nil && !login.requested, "Failed registration must not claim enabled")
        loginService.fail = false
        loginService.status = .enabled
        loginService.fail = true
        login.setEnabled(false)
        try require(login.requested && login.errorMessage != nil, "Failed removal must retain actual enabled state")
        loginService.fail = false
        login.setEnabled(false)
        try require(!login.requested && loginService.removals == 2 && login.errorMessage == nil, "Explicit removal must clear error and OS state")
        loginService.status = .notFound
        login.refresh()
        try require(!login.requested, "Missing app service must not claim enabled")
        print("PASS: login opt-in, OS approval/revocation, failed registration, removal and missing service")
        func alertEvent(_ key: String, session: String = "session", text: String = "Body 🌉") throws -> CompanionEvent {
            let data = try JSONSerialization.data(withJSONObject: ["event": "notification", "operation": "post",
                "connectionId": session, "notificationId": key, "appName": "Chat", "title": "Hello 世界", "text": text])
            return try JSONDecoder().decode(CompanionEvent.self, from: data)
        }
        var alerts = NotificationCatalog()
        let firstAlert = try alerts.post(alertEvent("key"), session: "session")!
        try require(firstAlert.0.title == "Hello 世界" && !firstAlert.0.id.contains("key"), "Unicode or opaque notification identifier failed")
        let duplicateAlert = try alerts.post(alertEvent("key"), session: "session")
        try require(duplicateAlert == nil, "Duplicate must not redraw an alert")
        let changedAlert = try alerts.post(alertEvent("key", text: "Updated"), session: "session")
        try require(changedAlert?.0.text == "Updated", "Update must replace the same alert")
        let staleAlert = try alerts.post(alertEvent("old", session: "old-session"), session: "session")
        try require(staleAlert == nil, "Stale session published an alert")
        let oversizedAlert = try alerts.post(alertEvent("x", text: String(repeating: "x", count: 8193)), session: "session")
        try require(oversizedAlert == nil, "Oversized notification reached presentation")
        for index in 0..<1000 { _ = try alerts.post(alertEvent("key-\(index)"), session: "session") }
        try require(alerts.items.count == 100, "Notification catalog grew without bound")
        try require(alerts.remove(session: "session", key: "unknown") == nil, "Unknown removal changed alerts")
        try require(alerts.remove(session: "session", key: "key-999") != nil, "Known removal failed")
        alerts.reset()
        try require(alerts.items.isEmpty, "Session teardown retained notification content")
        print("PASS: bounded notification catalog, Unicode, duplicates, updates, stale sessions, removal and teardown")

        let actionToken = "11111111-1111-1111-1111-111111111111"
        let newActionToken = "22222222-2222-2222-2222-222222222222"
        func actionable(_ token: String) throws -> CompanionEvent {
            let value: [String: Any] = ["event": "notification", "operation": "post", "connectionId": "current",
                "notificationId": "key", "appName": "Chat", "title": "Hello", "text": "World", "dismissToken": token]
            return try JSONDecoder().decode(CompanionEvent.self, from: JSONSerialization.data(withJSONObject: value))
        }
        var actionCatalog = NotificationCatalog()
        let actionAlert = try actionCatalog.post(actionable(actionToken), session: "current")!.0
        try require(actionCatalog.takeDismiss(id: actionAlert.id, token: "wrong") == nil, "Wrong handle authorized dismissal")
        try require(actionCatalog.takeDismiss(id: actionAlert.id, token: actionToken)?.key == "key", "Current handle did not route to original key")
        try require(actionCatalog.takeDismiss(id: actionAlert.id, token: actionToken) == nil, "Handle reused")
        _ = try actionCatalog.post(actionable(newActionToken), session: "current")
        try require(actionCatalog.takeDismiss(id: actionAlert.id, token: actionToken) == nil, "Old banner dismissed replacement")
        try require(actionCatalog.matchesResult(session: "current", key: "key", token: newActionToken), "Current result did not match")
        try require(!actionCatalog.matchesResult(session: "old", key: "key", token: newActionToken), "Stale session result matched")
        actionCatalog.reset()
        try require(actionCatalog.takeDismiss(id: actionAlert.id, token: newActionToken) == nil, "Restart retained action handles")
        print("PASS: dismiss handles bind original alert, reject stale/reused actions and clear across restart")

        func replyEvent(_ token: String, text: String = "World") throws -> CompanionEvent {
            let value: [String: Any] = ["event": "notification", "operation": "post", "connectionId": "current",
                "notificationId": "reply-key", "appName": "Chat", "title": "Hello", "text": text, "replyToken": token]
            return try JSONDecoder().decode(CompanionEvent.self, from: JSONSerialization.data(withJSONObject: value))
        }
        var replyCatalog = NotificationCatalog()
        let replyAlert = try replyCatalog.post(replyEvent(actionToken), session: "current")!.0
        try require(replyCatalog.takeReply(id: replyAlert.id, token: actionToken, text: " ") == nil, "Blank reply consumed action")
        try require(replyCatalog.takeReply(id: replyAlert.id, token: "wrong", text: "Hi") == nil, "Wrong reply handle accepted")
        try require(replyCatalog.takeReply(id: replyAlert.id, token: actionToken, text: "世界 🌉")?.key == "reply-key", "Unicode reply lost original target")
        try require(replyCatalog.takeReply(id: replyAlert.id, token: actionToken, text: "again") == nil, "Reply reused")
        _ = try replyCatalog.post(replyEvent(actionToken, text: "Updated text"), session: "current")
        try require(replyCatalog.takeReply(id: replyAlert.id, token: actionToken, text: "again") == nil, "Same handle update reset one-use guard")
        _ = try replyCatalog.post(replyEvent(newActionToken), session: "current")
        try require(replyCatalog.takeReply(id: replyAlert.id, token: actionToken, text: "old") == nil, "Replaced reply accepted")
        try require(replyCatalog.matchesResult(session: "current", key: "reply-key", token: newActionToken, reply: true), "Current reply result rejected")
        try require(!replyCatalog.matchesResult(session: "old", key: "reply-key", token: newActionToken, reply: true), "Stale reply result accepted")
        replyCatalog.reset()
        try require(replyCatalog.takeReply(id: replyAlert.id, token: newActionToken, text: "restart") == nil, "Restart retained reply")
        try require(NotificationCatalog.validReply(String(repeating: "🌉", count: 1024)), "Unicode boundary rejected")
        try require(!NotificationCatalog.validReply(String(repeating: "🌉", count: 1025)) && !NotificationCatalog.validReply("bad\u{0}"), "Malformed reply accepted")
        print("PASS: inline reply Unicode bounds, original generation, one-use handles and session teardown")

        let identity = BonjourIdentity(id: "mac-public-id", name: String(repeating: "🌉", count: 100),
            fingerprint: Array(repeating: "AB", count: 32).joined(separator: ":"), port: 8990, ipv6: true)
        try require(identity.valid && identity.serviceName.utf8.count <= 63, "Bonjour descriptor is invalid")
        try require(identity.txt.keys.sorted() == ["fingerprint", "id", "ipv6", "name"], "TXT must contain only public discovery metadata")
        try require(identity.txt["name"]!.count <= 100 && String(data: identity.txt["name"]!, encoding: .utf8) != nil,
            "Unicode service name must be bounded without splitting UTF-8")
        var registrations: [RecordingService] = []
        var discoveryStatus = ""
        let publisher = BonjourPublisher(makeService: { value in
            let service = RecordingService(domain: "local.", type: "_macbridge._tcp.", name: value.serviceName, port: Int32(value.port))
            registrations.append(service)
            return service
        })
        publisher.onStatus = { discoveryStatus = $0 }
        publisher.apply(identity)
        publisher.apply(identity)
        try require(registrations.count == 1 && registrations[0].publishes == 1, "Unchanged events must not republish Bonjour")
        publisher.netServiceDidPublish(registrations[0])
        try require(discoveryStatus == "Available on your network", "Publication success was not presented")
        let replacement = BonjourIdentity(id: identity.id, name: "New name", fingerprint: identity.fingerprint, port: 9000, ipv6: false)
        publisher.apply(replacement)
        try require(registrations.count == 2 && registrations[0].stops == 1, "Endpoint changes must withdraw the old advertisement")
        publisher.netServiceDidPublish(registrations[0])
        try require(discoveryStatus == "Starting…", "Late publication callback changed current status")
        publisher.netService(registrations[1], didNotPublish: [:])
        try require(discoveryStatus.contains("QR"), "Unavailable multicast must retain QR guidance")
        publisher.stop()
        try require(discoveryStatus == "Off" && registrations[1].stops == 2, "Shutdown must withdraw discovery")
        publisher.apply(BonjourIdentity(id: "bad", name: "Bad", fingerprint: "invalid", port: 0, ipv6: true))
        try require(registrations.count == 2, "Invalid metadata must not publish")
        print("PASS: bounded public Bonjour metadata, idle suppression, replacement, failure and shutdown")

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
        var pausedBuffer = EventBuffer()
        let paused = try pausedBuffer.append(Data(#"{"event":"state","fileSendStatus":"paused","fileCanResume":false}"#.utf8) + Data([10]))
        try require(paused.first?.fileSendStatus == "paused" && paused.first?.fileCanResume == false, "Different peer must not enable resume")
        print("PASS: paused transfer and original peer resume availability")
        print("PASS: file sending progress and original connection identity")

        var receivingBuffer = EventBuffer()
        let incoming = try receivingBuffer.append(Data(#"{"event":"state","fileReceiveStatus":"receiving","receivedBytes":65536,"receivedFileSize":80000,"fileReceiveToken":"opaque-transfer"}"#.utf8) + Data([10]))
        try require(incoming.first?.fileReceiveToken == "opaque-transfer" && incoming.first?.fileReceiveStatus == "receiving" && incoming.first?.receivedBytes == 65536 && incoming.first?.receivedFileSize == 80000, "Incoming progress fields must decode")
        print("PASS: incoming transfer status and byte counts")
        let progress = TransferPresentation(status: "receiving", bytes: 65536, total: 80000)
        try require(progress.visible && progress.active && progress.detail.hasPrefix("81%"), "Incoming percentage is inaccurate")
        try require(TransferPresentation(status: "sending", bytes: Int64.max, total: 80000).detail == "Verifying…", "Malformed counts must not overflow or claim verification")
        try require(TransferPresentation(status: "receiving", bytes: -1, total: 80000).fraction == 0, "Negative progress must clamp")
        print("PASS: bounded percentages and verification transition")
        try require(TransferPresentation(status: "completed", bytes: 0, total: 0).detail.hasPrefix("Verified"), "Empty verified file needs a final outcome")
        try require(TransferPresentation(status: "paused", bytes: 65536, total: 80000).visible && !TransferPresentation(status: "paused", bytes: 65536, total: 80000).active, "Paused state must not animate")
        try require(!TransferPresentation(status: "idle", bytes: 0, total: 0).visible && !TransferPresentation(status: "unknown", bytes: 0, total: 0).visible, "Idle/unknown progress must stay hidden")
        print("PASS: paused, empty verified and idle transfer presentation")

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
        var renders = 0
        let cache = PairingQRCache { value in renders += 1; return PairingQR.image(for: value) }
        let cached = cache.image(for: code)
        for _ in 0..<300 {
            try require(cache.image(for: code) === cached, "Countdown should reuse the pairing raster")
        }
        try require(renders == 1, "Countdown regenerated the QR raster")
        try require(cache.image(for: "") == nil, "Expired code must clear the cached QR")
        _ = cache.image(for: code)
        try require(renders == 2, "Cleared code should generate a new raster")
        _ = cache.image(for: code + "&new=1")
        try require(renders == 3, "Rotated code must replace the cache")
        print("PASS: 300 QR countdown requests use one raster; expiry and rotation refresh correctly")
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
