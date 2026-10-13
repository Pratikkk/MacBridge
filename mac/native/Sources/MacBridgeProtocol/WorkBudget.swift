import Foundation

/// One engine-wide budget survives authenticated reconnects. Call on its serial queue.
public final class WorkBudget {
    private var buckets: [String: (Double, Double)] = [:]
    private let clock: () -> Double
    public init(clock: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime }) { self.clock = clock }
    public func take(_ name: String, amount: Double, rate: Double, capacity: Double) throws {
        let now = clock()
        let previous = buckets[name] ?? (capacity, now)
        let available = min(capacity, previous.0 + max(0, now - previous.1) * rate)
        buckets[name] = (max(0, available - amount), now)
        guard amount >= 0, amount <= available else { throw ProtocolFailure.rateLimited }
    }
    public func frame(bytes: Int) throws {
        try take("frames", amount: 1, rate: 512, capacity: 1024)
        try take("bytes", amount: Double(bytes), rate: 16 * 1_048_576, capacity: 32 * 1_048_576)
    }
    public func message(_ kind: String) throws {
        let limits: [String: (Double, Double)] = ["CLIPBOARD": (2, 10), "NOTIFICATION": (30, 60),
            "NOTIFICATION_ACTION": (10, 20), "FILE_INIT": (1, 4), "HEARTBEAT": (10, 20)]
        if let limit = limits[kind] { try take(kind, amount: 1, rate: limit.0, capacity: limit.1) }
    }
}
