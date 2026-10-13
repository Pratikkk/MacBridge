import Foundation

public final class NotificationMirror {
    private var items: [String: [String: String]] = [:]
    private var order: [String] = []
    private var pending: [String: String] = [:]
    private var requested: [String: String] = [:]
    public init() {}
    public func reset() { items.removeAll(); order.removeAll(); pending.removeAll(); requested.removeAll() }
    private func field(_ object: [String: Any], _ key: String, limit: Int, blank: Bool = false) throws -> String {
        guard let value = object[key] as? String, value.utf8.count <= limit,
              blank || !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ProtocolFailure.invalidJSON }
        return value
    }
    public func process(_ object: [String: Any], enabled: Bool) throws -> [[String: String]] {
        if object["type"] as? String == "NOTIFICATION" {
            let key = try field(object, "notificationId", limit: 512)
            var item = ["notificationId": key, "packageName": try field(object, "packageName", limit: 255),
                "appName": try field(object, "appName", limit: 400, blank: true),
                "title": try field(object, "title", limit: 1024, blank: true), "text": try field(object, "text", limit: 8192, blank: true)]
            for token in ["dismissToken", "replyToken"] {
                if let value = object[token], !(value is NSNull) {
                    guard let string = value as? String, string.utf8.count == 36, UUID(uuidString: string) != nil else { throw ProtocolFailure.invalidJSON }
                    item[token] = string
                }
            }
            for flag in ["isDismissed", "hasReplyAction"] {
                if let value = object[flag], StrictJSON.boolean(value) == nil { throw ProtocolFailure.invalidJSON }
            }
            if StrictJSON.boolean(object["isDismissed"]) == true { return remove(key) }
            guard enabled, items[key] != item else { return [] }
            var events: [[String: String]] = []
            if items[key] == nil, order.count >= 100 { events += remove(order[0]) }
            for token in ["dismissToken", "replyToken"] {
                let action = key + "\0" + token
                pending.removeValue(forKey: action)
                if items[key]?[token] != item[token] { requested.removeValue(forKey: action) }
            }
            if items[key] == nil { order.append(key) }
            items[key] = item; item["operation"] = "post"; events.append(item)
            return events
        }
        guard object["type"] as? String == "NOTIFICATION_ACTION" else { throw ProtocolFailure.invalidJSON }
        switch object["actionType"] as? String {
        case "DISMISS_RESULT", "REPLY_RESULT":
            let key = try field(object, "notificationId", limit: 512)
            let token = try field(object, "actionToken", limit: 36)
            let status = try field(object, "status", limit: 20)
            guard ["REQUESTED", "STALE", "DENIED", "UNAVAILABLE", "LOCKED", "INVALID"].contains(status) else { throw ProtocolFailure.invalidJSON }
            let kind = object["actionType"] as? String == "REPLY_RESULT" ? "replyToken" : "dismissToken"
            let action = key + "\0" + kind
            guard pending[action] == token, items[key]?[kind] == token else { return [] }
            pending.removeValue(forKey: action)
            return [["operation": "result", "notificationId": key, "status": status, kind: token]]
        case "REMOVE": return remove(try field(object, "notificationId", limit: 512))
        case "CLEAR":
            let package = try field(object, "notificationId", limit: 255, blank: true)
            if package.isEmpty { reset(); return [["operation": "clear"]] }
            return order.filter { items[$0]?["packageName"] == package }.flatMap { remove($0) }
        default: return []
        }
    }
    public func request(key: String, token: String, reply: String? = nil) throws -> [String: String] {
        let kind = reply == nil ? "dismissToken" : "replyToken"
        let action = key + "\0" + kind
        guard !token.isEmpty, items[key]?[kind] == token, requested[action] != token else { throw ProtocolFailure.invalidJSON }
        if let reply {
            guard reply.utf8.count <= 4096, !reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  reply.unicodeScalars.allSatisfy({ !($0.value < 32 && $0.value != 9 && $0.value != 10) && !(127...159).contains($0.value) }) else { throw ProtocolFailure.invalidJSON }
        }
        pending[action] = token; requested[action] = token
        var frame = ["type": "NOTIFICATION_ACTION", "notificationId": key, "actionType": reply == nil ? "DISMISS" : "REPLY", "actionToken": token]
        if let reply { frame["replyText"] = reply }
        return frame
    }
    private func remove(_ key: String) -> [[String: String]] {
        for kind in ["dismissToken", "replyToken"] { pending.removeValue(forKey: key + "\0" + kind); requested.removeValue(forKey: key + "\0" + kind) }
        order.removeAll { $0 == key }
        guard items.removeValue(forKey: key) != nil else { return [] }
        return [["operation": "remove", "notificationId": key]]
    }
}
