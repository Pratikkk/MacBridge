import Foundation
import UserNotifications
import CryptoKit

struct MirroredAlert: Equatable {
    let id: String
    let key: String
    let dismissToken: String?
    let app: String
    let title: String
    let text: String
}

/// Bounded session catalog. Identifiers never embed phone notification keys or message content.
struct NotificationCatalog {
    private(set) var items: [MirroredAlert] = []
    private var requested: Set<String> = []
    mutating func reset() { items.removeAll(); requested.removeAll() }
    func identifier(session: String, key: String) -> String {
        SHA256.hash(data: Data((session + "\0" + key).utf8)).map { String(format: "%02x", $0) }.joined()
    }
    mutating func post(_ value: CompanionEvent, session: String) -> (MirroredAlert, String?)? {
        guard value.connectionId == session, !session.isEmpty,
              let key = value.notificationId, !key.isEmpty, key.utf8.count <= 512,
              let app = value.appName, app.utf8.count <= 400,
              let title = value.title, title.utf8.count <= 1024,
              let text = value.text, text.utf8.count <= 8192 else { return nil }
        let id = identifier(session: session, key: key)
        let token = value.dismissToken.flatMap { UUID(uuidString: $0) != nil ? $0 : nil }
        let alert = MirroredAlert(id: id, key: key, dismissToken: token, app: app, title: title, text: text)
        if let index = items.firstIndex(where: { $0.id == id }) {
            if items[index] == alert { return nil }
            requested.remove(id)
            items[index] = alert
            return (alert, nil)
        }
        let evicted = items.count == 100 ? items.removeFirst().id : nil
        if let evicted { requested.remove(evicted) }
        items.append(alert)
        return (alert, evicted)
    }
    mutating func takeDismiss(id: String, token: String) -> MirroredAlert? {
        guard let alert = items.first(where: { $0.id == id }), alert.dismissToken == token, !requested.contains(id) else { return nil }
        requested.insert(id)
        return alert
    }
    func matchesResult(session: String, key: String, token: String) -> Bool {
        items.contains { $0.id == identifier(session: session, key: key) && $0.dismissToken == token }
    }
    mutating func remove(session: String, key: String) -> String? {
        let id = identifier(session: session, key: key)
        guard let index = items.firstIndex(where: { $0.id == id }) else { return nil }
        requested.remove(id)
        items.remove(at: index)
        return id
    }
}

@MainActor
final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    private var catalog = NotificationCatalog()
    private var session = ""
    private var enabled = false
    private var revision = 0
    private var draining = false
    private var queue: [(CompanionEvent, Int)] = []
    var onDismiss: ((String, String, String) -> Void)?
    var onStatus: ((String) -> Void)?
    var onAccessDenied: (() -> Void)?
    override init() {
        super.init()
        center.delegate = self
        let dismiss = UNNotificationAction(identifier: "DISMISS_ON_PHONE", title: "Dismiss on Phone", options: [.destructive])
        center.setNotificationCategories([UNNotificationCategory(identifier: "PHONE_DISMISS", actions: [dismiss], intentIdentifiers: [], options: [])])
        clear()
    }
    func refreshPermission() {
        center.getNotificationSettings { [weak self] settings in
            Task { @MainActor in
                if settings.authorizationStatus == .denied { self?.onAccessDenied?() }
                self?.onStatus?(settings.authorizationStatus == .authorized ? "Allowed by macOS" : "Allow alerts in macOS notification settings")
            }
        }
    }
    func requestPermission(_ completion: @escaping (Bool) -> Void) {
        center.requestAuthorization(options: [.alert]) { [weak self] granted, _ in
            Task { @MainActor in self?.refreshPermission(); completion(granted) }
        }
    }
    func configure(session: String, enabled: Bool) {
        if self.session != session || self.enabled != enabled {
            clear()
            self.session = session
            self.enabled = enabled
        }
    }
    func clear() {
        revision += 1
        catalog.reset()
        queue.removeAll()
        center.removeAllPendingNotificationRequests()
        center.removeAllDeliveredNotifications()
    }
    func receive(_ event: CompanionEvent) {
        guard enabled, !session.isEmpty, event.connectionId == session else { return }
        if event.operation == "result", let key = event.notificationId, let token = event.dismissToken,
           catalog.matchesResult(session: session, key: key, token: token) {
            onStatus?(event.status == "REQUESTED" ? "Phone received dismiss request" : "Dismiss unavailable · check phone permissions or wait for a new alert")
            return
        }
        if event.operation == "clear" { clear(); return }
        guard queue.count < 64 else { clear(); return }
        queue.append((event, revision))
        guard !draining else { return }
        draining = true
        Task { [weak self] in
            guard let self else { return }
            defer { self.draining = false }
            while !self.queue.isEmpty {
                let (event, token) = self.queue.removeFirst()
                guard self.revision == token, self.enabled, event.connectionId == self.session else { continue }
                switch event.operation {
                case "remove":
                    if let key = event.notificationId, let id = self.catalog.remove(session: self.session, key: key) {
                        self.center.removePendingNotificationRequests(withIdentifiers: [id])
                        self.center.removeDeliveredNotifications(withIdentifiers: [id])
                    }
                case "post":
                    guard let (alert, evicted) = self.catalog.post(event, session: self.session) else { continue }
                    if let evicted { self.center.removeDeliveredNotifications(withIdentifiers: [evicted]) }
                    let content = UNMutableNotificationContent()
                    content.title = alert.app.isEmpty ? "Phone notification" : alert.app
                    content.subtitle = alert.title
                    content.body = alert.text
                    if let token = alert.dismissToken {
                        content.categoryIdentifier = "PHONE_DISMISS"
                        content.userInfo = ["session": self.session, "token": token]
                    }
                    do { try await self.center.add(UNNotificationRequest(identifier: alert.id, content: content, trigger: nil)) }
                    catch { self.onStatus?("macOS could not display an alert"); self.refreshPermission() }
                    if self.revision != token {
                        self.center.removePendingNotificationRequests(withIdentifiers: [alert.id])
                        self.center.removeDeliveredNotifications(withIdentifiers: [alert.id])
                    }
                default: break
                }
            }
        }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void) {
        guard response.actionIdentifier == "DISMISS_ON_PHONE" else { completionHandler(); return }
        let id = response.notification.request.identifier
        let info = response.notification.request.content.userInfo
        let source = info["session"] as? String
        let token = info["token"] as? String
        Task { @MainActor [weak self] in
            defer { completionHandler() }
            guard let self, self.enabled, source == self.session, let token,
                  let alert = self.catalog.takeDismiss(id: id, token: token) else { return }
            self.onStatus?("Requesting dismissal on phone…")
            self.onDismiss?(alert.key, token, self.session)
        }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }
}
