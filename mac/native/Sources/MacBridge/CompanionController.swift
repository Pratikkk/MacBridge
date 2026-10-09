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
    @Published private(set) var pairingURI = ""
    @Published private(set) var expiresAt = Date.distantPast
    @Published private(set) var lastAction = "Starting companion…"
    @Published private(set) var errorMessage: String?
    @Published private(set) var clipboardEnabled = UserDefaults.standard.object(forKey: "clipboardEnabled") as? Bool ?? true
    @Published private(set) var filesEnabled = UserDefaults.standard.bool(forKey: "filesEnabled")
    private var child: Process?
    private var input: FileHandle?
    private var generation = 0
    private let commands = DispatchQueue(label: "MacBridge.commands")

    func start() {
        guard child == nil else { return }
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
                self.input = nil
                self.running = false
                self.connected = false
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
        // Drain stderr without exposing identity files, secrets or clipboard content in the UI.
        DispatchQueue.global(qos: .utility).async {
            _ = stderr.fileHandleForReading.readDataToEndOfFile()
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
        if value.event == "error" {
            errorMessage = value.message ?? "The companion could not complete that action."
            return
        }
        guard value.event == "state" else { return }
        running = value.running ?? false
        connected = value.connected ?? false
        phoneName = value.phoneName ?? "Android Phone"
        peers = value.peers ?? []
        endpoint = value.endpoint ?? "No network address"
        pairingURI = value.pairingURI ?? ""
        expiresAt = Date(timeIntervalSince1970: value.expiresAt ?? 0)
        clipboardEnabled = value.clipboardEnabled ?? false
        filesEnabled = value.filesEnabled ?? false
        lastAction = value.lastAction ?? "Ready"
        errorMessage = nil
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
    func setFiles(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: "filesEnabled")
        command(["action": "setFilesEnabled", "enabled": enabled])
    }
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
        generation += 1
        let previous = child
        child = nil
        input?.closeFile()
        input = nil
        if previous?.isRunning == true { previous?.terminate() }
        running = false
        connected = false
        pairingURI = ""
    }
}
