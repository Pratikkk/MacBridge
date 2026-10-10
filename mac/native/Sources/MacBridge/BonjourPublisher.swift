import Foundation

/// Created and used on the main run loop. No timers or subprocess polling.
final class BonjourPublisher: NSObject, NetServiceDelegate {
    private let makeService: (BonjourIdentity) -> NetService
    private var service: NetService?
    private var identity: BonjourIdentity?
    var onStatus: ((String) -> Void)?
    init(makeService: @escaping (BonjourIdentity) -> NetService = {
        NetService(domain: "local.", type: "_macbridge._tcp.", name: $0.serviceName, port: Int32($0.port))
    }) { self.makeService = makeService; super.init() }

    func apply(_ value: BonjourIdentity?) {
        let next = value?.valid == true ? value : nil
        guard next != identity else { return }
        service?.delegate = nil
        service?.stop()
        service = nil
        identity = next
        guard let next else { onStatus?("Off"); return }
        let advertisement = makeService(next)
        advertisement.delegate = self
        advertisement.setTXTRecord(NetService.data(fromTXTRecord: next.txt))
        service = advertisement
        onStatus?("Starting…")
        advertisement.publish()
    }
    func netServiceDidPublish(_ sender: NetService) {
        guard sender === service else { return }
        onStatus?("Available on your network")
    }
    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        guard sender === service else { return }
        sender.stop()
        onStatus?("Unavailable · use the QR code")
    }
    func stop() { apply(nil) }
    deinit { service?.stop() }
}
