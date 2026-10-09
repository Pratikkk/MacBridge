import SwiftUI
import AppKit

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        let bundleID = Bundle.main.bundleIdentifier ?? "com.macbridge.companion"
        let others = NSRunningApplication.runningApplications(withBundleIdentifier: bundleID)
            .filter { $0.processIdentifier != ProcessInfo.processInfo.processIdentifier }
        if let existing = others.first {
            existing.activate(options: [.activateIgnoringOtherApps])
            NSApplication.shared.terminate(nil)
            return
        }
        CompanionController.shared.start()
    }
    func applicationWillTerminate(_ notification: Notification) {
        CompanionController.shared.stop()
    }
}

@main
struct MacBridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var controller = CompanionController.shared
    var body: some Scene {
        MenuBarExtra {
            CompanionView(controller: controller)
        } label: {
            Label("MacBridge", systemImage: controller.connected ? "link.circle.fill" : "link")
        }
        .menuBarExtraStyle(.window)
    }
}

struct CompanionView: View {
    @ObservedObject var controller: CompanionController
    @State private var showingPairing = true
    @State private var phoneToForget: PairedPhone?

    var body: some View {
        ScrollView {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Image(systemName: "link")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(.cyan)
                    .frame(width: 44, height: 44)
                    .background(.cyan.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 3) {
                    Text("MacBridge").font(.title2.bold())
                    Text("Your phone, connected.").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Circle().fill(controller.connected ? Color.green : Color.orange)
                    .frame(width: 9, height: 9)
                    .accessibilityLabel(controller.connected ? "Phone connected" : "No connected phone")
            }
            VStack(alignment: .leading, spacing: 5) {
                Text(controller.connected ? controller.phoneName : (controller.running ? "Waiting for your phone" : "Companion stopped"))
                    .font(.headline).lineLimit(2)
                Text(controller.endpoint).font(.caption.monospaced()).foregroundStyle(.secondary)
            }
            Button(action: controller.pushClipboard) {
                Label("Send Clipboard to Phone", systemImage: "arrow.up.doc.on.clipboard")
                    .frame(maxWidth: .infinity).padding(.vertical, 5)
            }
            .buttonStyle(.borderedProminent)
            .disabled(!controller.running || !controller.connected || !controller.clipboardEnabled)
            .accessibilityIdentifier("sendClipboard")
            Toggle("Allow clipboard sharing", isOn: Binding(
                get: { controller.clipboardEnabled }, set: controller.setClipboard))
                .disabled(!controller.running)
            Toggle("Allow file receiving", isOn: Binding(
                get: { controller.filesEnabled }, set: controller.setFiles))
                .disabled(!controller.running)
            Button("Show Received Files", action: controller.showReceivedFiles)
            Text(controller.lastAction).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            if let error = controller.errorMessage {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
            }
            Divider()
            DisclosureGroup("Pair a phone", isExpanded: $showingPairing) {
                TimelineView(.periodic(from: .now, by: 1)) { timeline in
                    let remaining = max(0, Int(controller.expiresAt.timeIntervalSince(timeline.date)))
                    VStack(spacing: 10) {
                        if !controller.pairingURI.isEmpty && remaining > 0,
                           let qr = PairingQR.image(for: controller.pairingURI) {
                            Image(nsImage: qr).resizable().interpolation(.none)
                                .scaledToFit().frame(width: 204, height: 204)
                                .padding(12).background(.white, in: RoundedRectangle(cornerRadius: 12))
                                .accessibilityLabel("MacBridge pairing QR code")
                            Text("Code expires in \(remaining / 60):\(String(format: "%02d", remaining % 60))")
                                .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            Button("Copy Pairing Code", action: controller.copyCode)
                        } else {
                            Text(controller.running ? "Generate a fresh pairing code to add a phone." : "Start the companion to generate a code.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Text("On Android, open Devices → Pair a Mac and scan this QR or paste its code. Both devices need the same local network.")
                            .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        Button("Generate New Code", action: controller.newCode).disabled(!controller.running)
                    }.padding(.top, 10).frame(maxWidth: .infinity)
                }
            }
            if !controller.peers.isEmpty {
                Divider()
                Text("PAIRED PHONES").font(.caption2.bold()).foregroundStyle(.secondary)
                ForEach(controller.peers) { phone in
                    HStack {
                        Label(phone.name, systemImage: "iphone").font(.caption).lineLimit(1)
                        Spacer()
                        Button("Forget") { phoneToForget = phone }.font(.caption)
                    }
                }
            }
            Divider()
            HStack {
                Button("Restart Companion", action: controller.restart).font(.caption)
                Spacer()
                Button("Quit") { NSApplication.shared.terminate(nil) }.font(.caption)
            }
        }
        .padding(20)
        }
        .frame(width: 380)
        .frame(maxHeight: min(780, (NSScreen.main?.visibleFrame.height ?? 900) - 80))
        .confirmationDialog("Forget this phone?", isPresented: Binding(
            get: { phoneToForget != nil }, set: { if !$0 { phoneToForget = nil } }), titleVisibility: .visible) {
            Button("Forget Phone", role: .destructive) {
                if let phone = phoneToForget { controller.forget(phone.id) }
                phoneToForget = nil
            }
            Button("Cancel", role: .cancel) { phoneToForget = nil }
        } message: {
            Text("The phone will need a new pairing code to connect to this Mac again.")
        }
    }
}
