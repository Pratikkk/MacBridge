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
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        CompanionWindows.shared.show(.status)
        return true
    }
    func applicationDidBecomeActive(_ notification: Notification) { CompanionController.shared.refreshNotificationAccess() }
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
            CompanionMenu(controller: controller)
        } label: {
            Label("MacBridge", systemImage: controller.connected ? "link.circle.fill" : "link")
        }
        .menuBarExtraStyle(.menu)
    }
}

struct CompanionMenu: View {
    @ObservedObject var controller: CompanionController
    private var state: CompanionMenuState {
        CompanionMenuState(running: controller.running, connected: controller.connected,
            clipboardEnabled: controller.clipboardEnabled, phoneName: controller.phoneName)
    }
    var body: some View {
        Text(state.status)
        Divider()
        Button("Send Clipboard to Phone", action: controller.pushClipboard)
            .disabled(!state.canSendClipboard)
        Button("Send File to Phone…", action: controller.sendFileToPhone)
            .disabled(!controller.running || !controller.connected || controller.fileSending || controller.fileSendStatus == "paused")
        Button("Show Received Files", action: controller.showReceivedFiles)
        if controller.sending.visible { Text("Sending to Phone · \(controller.sending.detail)") }
        if controller.receiving.visible { Text("Receiving from Phone · \(controller.receiving.detail)") }
        if controller.fileSendStatus == "paused" && !controller.fileSending {
            Button("Resume File Sending", action: controller.resumeFileSend)
                .disabled(!controller.fileCanResume)
        }
        if controller.fileSending || controller.fileSendStatus == "paused" {
            Button("Cancel File Sending", action: controller.cancelFileSend)
        }
        if !controller.fileReceiveToken.isEmpty {
            Button("Cancel File Receiving", action: controller.cancelFileReceive)
        }
        Divider()
        Toggle("Allow Clipboard Sharing", isOn: Binding(
            get: { controller.clipboardEnabled }, set: controller.setClipboard))
            .disabled(!controller.running)
        Toggle("Allow File Receiving", isOn: Binding(
            get: { controller.filesEnabled }, set: controller.setFiles))
            .disabled(!controller.running)
        Divider()
        Button("Pair a Phone…") { CompanionWindows.shared.show(.pairing) }
        Button("Manage Devices…") { CompanionWindows.shared.show(.devices) }
        Button(controller.errorMessage == nil ? "Settings…" : "Settings… (Needs Attention)") {
            CompanionWindows.shared.show(.status)
        }.keyboardShortcut(",")
        Divider()
        Button("Quit MacBridge") { NSApplication.shared.terminate(nil) }.keyboardShortcut("q")
    }
}

/// A single reusable settings window; closing it leaves the menu and engine running.
@MainActor
final class CompanionWindows: ObservableObject {
    static let shared = CompanionWindows()
    @Published var selection: CompanionSettingsTab = .status
    private var window: NSWindow?
    func show(_ tab: CompanionSettingsTab) {
        selection = tab
        if window == nil {
            let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 500, height: 510),
                styleMask: [.titled, .closable, .miniaturizable], backing: .buffered, defer: false)
            window.title = "MacBridge Settings"
            window.isReleasedWhenClosed = false
            window.contentView = NSHostingView(rootView: CompanionSettingsView(
                controller: CompanionController.shared, windows: self))
            window.center()
            self.window = window
        }
        window?.deminiaturize(nil)
        window?.makeKeyAndOrderFront(nil)
        NSApplication.shared.activate(ignoringOtherApps: true)
    }
}

struct CompanionSettingsView: View {
    @ObservedObject var controller: CompanionController
    @ObservedObject var windows: CompanionWindows
    @State private var phoneToForget: PairedPhone?
    @State private var pairingQR = PairingQRCache()

    var body: some View {
        TabView(selection: $windows.selection) {
            pairing.tabItem { Text("Pair a Phone") }.tag(CompanionSettingsTab.pairing)
            devices.tabItem { Text("Devices") }.tag(CompanionSettingsTab.devices)
            notifications.tabItem { Text("Notifications") }.tag(CompanionSettingsTab.notifications)
            status.tabItem { Text("General") }.tag(CompanionSettingsTab.status)
        }
        .padding(20).frame(width: 500, height: 510)
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

    private var pairing: some View {
        TimelineView(.periodic(from: .now, by: 1)) { timeline in
            let remaining = max(0, Int(controller.expiresAt.timeIntervalSince(timeline.date)))
            let qr = pairingQR.image(for: remaining > 0 ? controller.pairingURI : "")
            VStack(spacing: 12) {
                Text("Pair with your Android phone").font(.headline)
                Text("On Android, open Devices → Pair a Mac. Scan the QR code, then review and confirm the Mac identity.")
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                if !controller.pairingURI.isEmpty && remaining > 0,
                   let qr = qr {
                    Image(nsImage: qr).resizable().interpolation(.none).scaledToFit()
                        .frame(width: 200, height: 200).padding(10)
                        .background(.white, in: RoundedRectangle(cornerRadius: 8))
                        .accessibilityLabel("MacBridge pairing QR code")
                    Text("Expires in \(remaining / 60):\(String(format: "%02d", remaining % 60))")
                        .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                } else {
                    ContentUnavailablePairing(running: controller.running)
                }
                HStack {
                    Button("Copy Pairing Code", action: controller.copyCode)
                        .disabled(!controller.running || controller.pairingURI.isEmpty || remaining == 0)
                    Button("Generate New Code", action: controller.newCode).disabled(!controller.running)
                }
                Text("Keep both devices on the same local network. Codes expire after five minutes and work once.")
                    .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }.padding(20).frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var devices: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Paired Phones").font(.headline)
            if controller.peers.isEmpty {
                Text("No paired phones yet.").foregroundStyle(.secondary)
                Button("Pair a Phone…") { windows.selection = .pairing }
                Spacer()
            } else {
                List(controller.peers) { phone in
                    HStack {
                        Label(phone.name, systemImage: "iphone").lineLimit(2)
                        Spacer()
                        Button("Forget…") { phoneToForget = phone }.disabled(!controller.running)
                    }.padding(.vertical, 4)
                }
            }
            Text("Forgetting a phone revokes its access on this Mac. Existing received files stay saved.")
                .font(.caption).foregroundStyle(.secondary)
        }.padding(20)
    }

    private func transferProgress(_ direction: String, value: TransferPresentation) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(direction) · \(value.detail)").font(.callout).monospacedDigit()
            if value.active {
                if value.status == "preparing" { ProgressView().controlSize(.small) }
                else { ProgressView(value: value.fraction).accessibilityLabel(direction).accessibilityValue(value.detail) }
            }
        }
    }

    private var notifications: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Phone notifications").font(.headline)
            Toggle("Allow phone notifications", isOn: Binding(get: { controller.notificationsEnabled }, set: controller.setNotifications))
            Text(controller.notificationStatus).font(.callout).foregroundStyle(.secondary)
            Text("On Android, enable notification sharing for this Mac in Devices. In Settings → Notifications, grant access and select apps. Previews start hidden on your phone.").font(.callout)
            Text("Alerts are cleared when sharing stops or the phone disconnects. Existing phone notifications are never replayed. Dismiss and reply actions are planned.").font(.callout).foregroundStyle(.secondary)
            Button("Open macOS notification settings") {
                if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") { NSWorkspace.shared.open(url) }
            }
            Spacer()
        }.padding(20)
    }
    private var status: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("MacBridge").font(.headline)
            Text("Sharing controls are available directly in the menu bar.")
                .font(.callout).foregroundStyle(.secondary)
            Divider()
            LabeledContent("Connection", value: CompanionMenuState(running: controller.running,
                connected: controller.connected, clipboardEnabled: controller.clipboardEnabled,
                phoneName: controller.phoneName).status)
            LabeledContent("Local address", value: controller.endpoint)
            LabeledContent("Nearby discovery", value: controller.discoveryStatus)
            if controller.sending.visible { transferProgress("Sending to Phone", value: controller.sending) }
            if controller.receiving.visible { transferProgress("Receiving from Phone", value: controller.receiving) }
            Text(controller.errorMessage ?? controller.lastAction)
                .font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
            Spacer()
            Button("Restart Companion", action: controller.restart)
        }.padding(20)
    }
}

private struct ContentUnavailablePairing: View {
    let running: Bool
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "qrcode").font(.system(size: 48)).foregroundStyle(.secondary)
            Text(running ? "Generate a new code to pair a phone." : "Start the companion in General to generate a code.")
                .foregroundStyle(.secondary).multilineTextAlignment(.center)
        }.frame(height: 220)
    }
}
