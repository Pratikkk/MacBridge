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

private struct CompanionPanel<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 14) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(Color.white.opacity(0.045), in: RoundedRectangle(cornerRadius: 22))
            .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(Color.white.opacity(0.09)))
    }
}

private struct CompanionPrimaryButton: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13, weight: .semibold))
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundStyle(enabled ? Color.black : Color.white.opacity(0.4))
            .background(enabled ? Color.white.opacity(configuration.isPressed ? 0.8 : 1) : Color.white.opacity(0.08),
                in: RoundedRectangle(cornerRadius: 14))
    }
}

struct CompanionView: View {
    @ObservedObject var controller: CompanionController
    @State private var showingPairing = false
    @State private var phoneToForget: PairedPhone?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 12) {
                    Image(systemName: "link")
                        .font(.system(size: 22, weight: .semibold))
                        .frame(width: 44, height: 44)
                        .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                    VStack(alignment: .leading, spacing: 4) {
                        Text("MacBridge").font(.system(size: 22, weight: .semibold))
                        Text("Your devices. One flow.").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                CompanionPanel {
                    Label(controller.connected ? "Connected" : (controller.running ? "Ready to connect" : "Companion stopped"),
                        systemImage: controller.connected ? "checkmark.circle.fill" : "circle.dotted")
                        .font(.caption.weight(.medium))
                    Text(controller.connected ? controller.phoneName : "Connect your phone")
                        .font(.system(size: 24, weight: .semibold)).lineLimit(2)
                    Text(controller.endpoint).font(.caption.monospaced()).foregroundStyle(.secondary)
                        .textSelection(.enabled)
                    Text(controller.lastAction).font(.caption).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .background {
                    RoundedRectangle(cornerRadius: 22).fill(.white.opacity(0.07)).blur(radius: 24)
                        .padding(18).allowsHitTesting(false).accessibilityHidden(true)
                }
                CompanionPanel {
                    Label("Clipboard", systemImage: "doc.on.clipboard").font(.headline)
                    Text("Send copied text to your phone.").font(.caption).foregroundStyle(.secondary)
                    Button(action: controller.pushClipboard) {
                        Label("Send clipboard to phone", systemImage: "arrow.up.right")
                    }
                    .buttonStyle(CompanionPrimaryButton())
                    .disabled(!controller.running || !controller.connected || !controller.clipboardEnabled)
                    .accessibilityIdentifier("sendClipboard")
                    Toggle("Allow clipboard sharing", isOn: Binding(
                        get: { controller.clipboardEnabled }, set: controller.setClipboard))
                        .disabled(!controller.running)
                }
                CompanionPanel {
                    Label("Files", systemImage: "doc").font(.headline)
                    Text("Receive documents from your phone. Verified files stay in Received Files.")
                        .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Toggle("Allow file receiving", isOn: Binding(
                        get: { controller.filesEnabled }, set: controller.setFiles))
                        .disabled(!controller.running)
                    Button(action: controller.showReceivedFiles) {
                        Label("Show Received Files", systemImage: "folder")
                            .frame(maxWidth: .infinity, minHeight: 32)
                    }.buttonStyle(.bordered)
                }
                if let error = controller.errorMessage {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(.white).fixedSize(horizontal: false, vertical: true)
                        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                        .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                        .accessibilityLabel("Error: \(error)")
                }
                CompanionPanel {
                    DisclosureGroup("Pair a phone", isExpanded: $showingPairing) {
                        TimelineView(.periodic(from: .now, by: 1)) { timeline in
                            let remaining = max(0, Int(controller.expiresAt.timeIntervalSince(timeline.date)))
                            VStack(spacing: 12) {
                                if !controller.pairingURI.isEmpty && remaining > 0,
                                   let qr = PairingQR.image(for: controller.pairingURI) {
                                    Image(nsImage: qr).resizable().interpolation(.none)
                                        .scaledToFit().frame(width: 204, height: 204)
                                        .padding(12).background(.white, in: RoundedRectangle(cornerRadius: 14))
                                        .accessibilityLabel("MacBridge pairing QR code")
                                    Text("Code expires in \(remaining / 60):\(String(format: "%02d", remaining % 60))")
                                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                    Button("Copy Pairing Code", action: controller.copyCode).buttonStyle(.bordered)
                                } else {
                                    Text(controller.running ? "Generate a fresh pairing code to add a phone." : "Start the companion to generate a code.")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Text("On Android, open Devices → Pair a Mac and scan this QR or paste its code. Both devices need the same local network.")
                                    .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                                Button("Generate New Code", action: controller.newCode)
                                    .buttonStyle(.bordered).disabled(!controller.running)
                            }.padding(.top, 14).frame(maxWidth: .infinity)
                        }
                    }.font(.headline)
                    if !controller.peers.isEmpty {
                        Divider()
                        Text("Paired phones").font(.caption).foregroundStyle(.secondary)
                        ForEach(controller.peers) { phone in
                            HStack(spacing: 12) {
                                Label(phone.name, systemImage: "iphone").font(.body).lineLimit(2)
                                Spacer()
                                Button("Forget") { phoneToForget = phone }.buttonStyle(.bordered)
                            }
                        }
                    }
                }
                HStack {
                    Button("Restart Companion", action: controller.restart)
                    Spacer()
                    Button("Quit") { NSApplication.shared.terminate(nil) }
                }.font(.caption).buttonStyle(.borderless).padding(.horizontal, 4)
            }.padding(20)
        }
        .background(Color(red: 0.035, green: 0.035, blue: 0.035))
        .preferredColorScheme(.dark).tint(.white).toggleStyle(.switch)
        .frame(width: 400)
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
