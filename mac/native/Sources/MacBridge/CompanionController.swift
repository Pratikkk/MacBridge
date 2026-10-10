import AppKit
import Foundation
import SwiftUI

@MainActor
final class CompanionController: ObservableObject {
    static let shared = CompanionController()
    @Published private(set) var running = false
    @Published private(set) var connected = false
    @Published private(set) var phoneName = "Android Phone"
    @Published private(set) var peers: [PairedPhone] = []
    @Published private(set) var endpoint = "Starting companion…"
    @Published private(set) var discoveryStatus = "Starting…"
    private let bonjour = BonjourPublisher()
    private let alerts = NotificationPresenter()
    @Published private(set) var notificationsEnabled = UserDefaults.standard.bool(forKey: "notificationsEnabled")
    @Published private(set) var notificationStatus = "Checking macOS access…"
    private var notificationPermissionRequest = 0
    @Published private(set) var pairingURI = ""
    @Published private(set) var expiresAt = Date.distantPast
    @Published private(set) var lastAction = "Starting companion…"
    @Published private(set) var errorMessage: String?
    @Published private(set) var clipboardEnabled = UserDefaults.standard.object(forKey: "clipboardEnabled") as? Bool ?? true
    @Published private(set) var fileCanResume = false
    @Published private(set) var fileSending = false
    @Published private(set) var fileSendStatus = "idle"
    @Published private(set) var sentBytes: Int64 = 0
    @Published private(set) var fileSize: Int64 = 0
    @Published private(set) var receiving = TransferPresentation(status: "idle", bytes: 0, total: 0)
    @Published private(set) var fileReceiveToken = ""
    var sending: TransferPresentation { TransferPresentation(status: fileSendStatus, bytes: sentBytes, total: fileSize) }
    private var filePickerOpen = false
    private var connectionId = ""
    @Published private(set) var filesEnabled = UserDefaults.standard.bool(forKey: "filesEnabled")
    private var child: Process?
    private var input: FileHandle?
    private var generation = 0
    private let commands = DispatchQueue(label: "MacBridge.commands")

    func start() {
        guard child == nil else { return }
        alerts.onStatus = { [weak self] value in self?.update(\.notificationStatus, value) }
        alerts.onAccessDenied = { [weak self] in self?.setNotifications(false) }
        alerts.refreshPermission()
        bonjour.onStatus = { [weak self] value in self?.update(\.discoveryStatus, value) }
        generation += 1
        let token = generation
        errorMessage = nil
        lastAction = "Starting companion…"
        let candidates = ["/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3"]
        guard let python = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else {
            errorMessage = "Python 3 is required for this development version. Install it and click Restart Companion."
            return
        }
        guard let script = Bundle.main.url(forResource: "macbridge", withExtension: "py") else {
            errorMessage = "The bridge engine is missing. Rebuild the MacBridge app with build-app.sh."
            return
        }
        let task = Process()
        let output = Pipe()
        let stdin = Pipe()
        let stderr = Pipe()
        task.executableURL = URL(fileURLWithPath: python)
        task.arguments = [script.path, "--gui"] + (clipboardEnabled ? ["--clipboard"] : []) + (filesEnabled ? ["--files"] : [])
        var environment = ProcessInfo.processInfo.environment
        environment["PATH"] = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
        environment["PYTHONUNBUFFERED"] = "1"
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        task.environment = environment
        task.standardOutput = output
        task.standardInput = stdin
        task.standardError = stderr
        task.terminationHandler = { [weak self] terminated in
            DispatchQueue.main.async {
                guard let self, self.generation == token else { return }
                self.child = nil
                self.bonjour.stop()
                self.alerts.configure(session: "", enabled: false)
                self.input = nil
                self.running = false
                self.connected = false
                self.fileSending = false
                self.fileCanResume = false
                self.fileSendStatus = "idle"
                self.fileReceiveToken = ""
                self.receiving = TransferPresentation(status: "idle", bytes: 0, total: 0)
                self.connectionId = ""
                self.pairingURI = ""
                self.endpoint = "Companion stopped"
                if terminated.terminationStatus != 0 {
                    self.errorMessage = "The companion stopped. Another copy may be using port 8990, or Python/OpenSSL could not start. Close the other companion and click Restart Companion."
                }
            }
        }
        do {
            try task.run()
            child = task
            input = stdin.fileHandleForWriting
        } catch {
            errorMessage = "Could not start the companion: \(error.localizedDescription)"
            return
        }
        // Drain diagnostic output in bounded chunks; retain no paths, secrets or contents.
        DispatchQueue.global(qos: .utility).async {
            while !stderr.fileHandleForReading.availableData.isEmpty {}
        }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            do {
                try CompanionEventStream.read(from: output.fileHandleForReading) { event in
                    DispatchQueue.main.async {
                        guard let self, self.generation == token else { return }
                        self.accept(event)
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    guard let self, self.generation == token else { return }
                    self.errorMessage = "Could not read companion status. Restart the companion."
                }
            }
        }
    }

    private func accept(_ value: CompanionEvent) {
        if value.event == "notification" { alerts.receive(value); return }
        if value.event == "error" {
            errorMessage = value.message ?? "The companion could not complete that action."
            return
        }
        guard value.event == "state" else { return }
        bonjour.apply(value.running == true ? value.discovery : nil)
        update(\.running, value.running ?? false)
        update(\.connected, value.connected ?? false)
        update(\.phoneName, value.phoneName ?? "Android Phone")
        update(\.peers, value.peers ?? [])
        update(\.endpoint, value.endpoint ?? "No network address")
        update(\.pairingURI, value.pairingURI ?? "")
        update(\.expiresAt, Date(timeIntervalSince1970: value.expiresAt ?? 0))
        update(\.clipboardEnabled, value.clipboardEnabled ?? false)
        update(\.filesEnabled, value.filesEnabled ?? false)
        let newSession = value.connected == true ? (value.connectionId ?? "") : ""
        alerts.configure(session: newSession, enabled: notificationsEnabled)
        if value.notificationsEnabled != notificationsEnabled {
            command(["action": "setNotificationsEnabled", "enabled": notificationsEnabled])
        }
        update(\.connectionId, value.connectionId ?? "")
        update(\.fileSending, value.fileSending ?? false)
        update(\.fileSendStatus, value.fileSendStatus ?? "idle")
        update(\.fileCanResume, value.fileCanResume ?? false)
        update(\.fileReceiveToken, value.fileReceiveToken ?? "")
        update(\.receiving, TransferPresentation(status: value.fileReceiveStatus ?? "idle", bytes: value.receivedBytes ?? 0, total: value.receivedFileSize ?? 0))
        update(\.sentBytes, value.sentBytes ?? 0)
        update(\.fileSize, value.fileSize ?? 0)
        update(\.lastAction, value.lastAction ?? "Ready")
        update(\.errorMessage, nil)
    }

    // Avoid invalidating the whole menu/settings view for unchanged state fields.
    private func update<Value: Equatable>(_ keyPath: ReferenceWritableKeyPath<CompanionController, Value>, _ value: Value) {
        if self[keyPath: keyPath] != value { self[keyPath: keyPath] = value }
    }

    private func command(_ payload: [String: Any]) {
        guard let input, let data = try? JSONSerialization.data(withJSONObject: payload) else { return }
        let token = generation
        commands.async { [weak self] in
            do { try input.write(contentsOf: data + Data([10])) }
            catch {
                DispatchQueue.main.async {
                    guard let self, self.generation == token else { return }
                    self.errorMessage = "Companion connection lost. Click Restart Companion."
                }
            }
        }
    }

    func setClipboard(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: "clipboardEnabled")
        command(["action": "setClipboardEnabled", "enabled": enabled])
    }
    func refreshNotificationAccess() { alerts.refreshPermission() }
    func setNotifications(_ enabled: Bool) {
        notificationPermissionRequest += 1
        let request = notificationPermissionRequest
        if !enabled {
            notificationsEnabled = false
            UserDefaults.standard.set(false, forKey: "notificationsEnabled")
            alerts.configure(session: connectionId, enabled: false)
            command(["action": "setNotificationsEnabled", "enabled": false])
            return
        }
        alerts.requestPermission { [weak self] allowed in
            guard let self, self.notificationPermissionRequest == request else { return }
            self.notificationsEnabled = allowed
            UserDefaults.standard.set(allowed, forKey: "notificationsEnabled")
            self.alerts.configure(session: self.connectionId, enabled: allowed)
            self.command(["action": "setNotificationsEnabled", "enabled": allowed])
        }
    }
    func setFiles(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: "filesEnabled")
        command(["action": "setFilesEnabled", "enabled": enabled])
    }
    func sendFileToPhone() {
        guard running, connected, !fileSending, fileSendStatus != "paused", !filePickerOpen, !connectionId.isEmpty else { return }
        filePickerOpen = true
        let destination = connectionId
        let panel = NSOpenPanel()
        panel.title = "Send File to Phone"
        panel.message = "Choose one file up to 100 MB. Enable File sharing for this Mac in Android Devices."
        panel.prompt = "Send"
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.allowsMultipleSelection = false
        NSApplication.shared.activate(ignoringOtherApps: true)
        panel.begin { [weak self] result in
            self?.filePickerOpen = false
            guard result == .OK, let url = panel.url else { return }
            self?.command(["action": "sendFile", "path": url.path, "connectionId": destination])
        }
    }
    func resumeFileSend() { command(["action": "resumeFileSend", "connectionId": connectionId]) }
    func cancelFileReceive() { command(["action": "cancelFileReceive", "transferToken": fileReceiveToken]) }
    func cancelFileSend() { command(["action": "cancelFileSend"]) }
    func showReceivedFiles() {
        let folder = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/MacBridgeDev/ReceivedFiles", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700])
            NSWorkspace.shared.open(folder)
        } catch { errorMessage = "Could not open Received Files." }
    }
    func pushClipboard() { command(["action": "pushClipboard"]) }
    func newCode() { command(["action": "reissueCode"]) }
    func forget(_ id: String) { command(["action": "forget", "id": id]) }
    func copyCode() {
        guard !pairingURI.isEmpty, expiresAt > Date() else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(pairingURI, forType: .string)
        lastAction = "Pairing code copied — paste it in Android’s Devices screen"
    }
    func restart() {
        let previous = child
        stop()
        let token = generation
        DispatchQueue.global(qos: .utility).async { [weak self] in
            previous?.waitUntilExit()
            DispatchQueue.main.async {
                guard let self, self.generation == token else { return }
                self.start()
            }
        }
    }
    func stop() {
        alerts.configure(session: "", enabled: false)
        bonjour.stop()
        generation += 1
        let previous = child
        child = nil
        input?.closeFile()
        input = nil
        if previous?.isRunning == true { previous?.terminate() }
        running = false
        connected = false
        fileSending = false
        fileCanResume = false
        fileSendStatus = "idle"
        fileReceiveToken = ""
        receiving = TransferPresentation(status: "idle", bytes: 0, total: 0)
        connectionId = ""
        pairingURI = ""
    }
}
