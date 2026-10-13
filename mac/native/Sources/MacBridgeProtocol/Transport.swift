import Foundation
import Network
import Security

@available(macOS 15, *)
public final class Transport {
    public final class Session {
        public let id = UUID().uuidString
        fileprivate let connection: NWConnection
        fileprivate var buffer = FrameBuffer()
        fileprivate var timeout: DispatchWorkItem?
        fileprivate var writes: [Data] = []
        fileprivate var writing = false
        fileprivate var closed = false
        public var authenticated = false
        public let nonce: String
        fileprivate init(_ connection: NWConnection) throws {
            self.connection = connection
            nonce = try IdentityMaterial.random(32).map { String(format: "%02x", $0) }.joined()
        }
    }
    private let queue: DispatchQueue
    private let budget: WorkBudget
    private var listener: NWListener?
    private var sessions: [String: Session] = [:]
    public var onReady: ((UInt16) -> Void)?
    public var onSession: ((Session) throws -> Void)?
    public var onFrame: ((Session, [String: Any]) throws -> Void)?
    public var onClose: ((Session) -> Void)?
    public var onError: (() -> Void)?
    public init(queue: DispatchQueue, budget: WorkBudget) { self.queue = queue; self.budget = budget }
    public func replaceListener(identity: SecIdentity, port: UInt16, host: String?, completion: @escaping (Error?) -> Void) {
        guard let previous = listener else {
            do { try listen(identity: identity, port: port, host: host); completion(nil) } catch { completion(error) }
            return
        }
        // Cancellation releases the bound port asynchronously. Existing accepted
        // connections own their TLS state and remain open during certificate renewal.
        previous.newConnectionHandler = { $0.cancel() }
        previous.stateUpdateHandler = { [weak self, weak previous] state in
            guard let self, let previous, case .cancelled = state else { return }
            previous.stateUpdateHandler = nil
            guard self.listener === previous else { return }
            self.listener = nil
            // Let the cancelled listener leave this callback and release its
            // endpoint before binding the replacement to the same port.
            self.queue.async { [weak self] in
                guard let self else { return }
                do { try self.listen(identity: identity, port: port, host: host); completion(nil) } catch { completion(error) }
            }
        }
        previous.cancel()
    }
    public func listen(identity: SecIdentity, port: UInt16, host: String? = nil) throws {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, false)
        guard let native = sec_identity_create(identity) else { throw ProtocolFailure.invalidIdentity }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, native)
        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        parameters.allowLocalEndpointReuse = true
        // NWListener's `on` argument supplies the port. A nonzero port in both
        // requiredLocalEndpoint and `on` is rejected by Network.framework.
        if let host { parameters.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(host), port: .any) }
        let next = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port)!)
        listener?.cancel(); listener = next
        next.stateUpdateHandler = { [weak self, weak next] state in
            guard let self, let next, self.listener === next else { return }
            switch state {
            case .ready: if let port = next.port { self.onReady?(port.rawValue) }
            case .failed: self.onError?()
            default: break
            }
        }
        next.newConnectionHandler = { [weak self] connection in
            guard let self, self.sessions.count < 4, let session = try? Session(connection) else { connection.cancel(); return }
            self.sessions[session.id] = session; self.armTimeout(session)
            connection.stateUpdateHandler = { [weak self, weak session] state in
                guard let self, let session, !session.closed else { return }
                switch state {
                case .ready:
                    do { try self.onSession?(session); self.receive(session) } catch { self.close(session) }
                case .failed, .cancelled: self.close(session)
                default: break
                }
            }
            connection.start(queue: self.queue)
        }
        next.start(queue: queue)
    }
    private func armTimeout(_ session: Session) {
        session.timeout?.cancel()
        let work = DispatchWorkItem { [weak self, weak session] in if let session { self?.close(session) } }
        session.timeout = work
        queue.asyncAfter(deadline: .now() + (session.authenticated ? 45 : 10), execute: work)
    }
    private func receive(_ session: Session) {
        guard !session.closed else { return }
        session.connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self, weak session] data, _, complete, error in
            guard let self, let session, !session.closed else { return }
            do {
                if let data, !data.isEmpty {
                    if session.authenticated { try self.budget.take("bytes", amount: Double(data.count), rate: 16 * 1_048_576, capacity: 32 * 1_048_576) }
                    for frame in try session.buffer.append(data) {
                        if session.closed { break }
                        if session.authenticated { try self.budget.take("frames", amount: 1, rate: 512, capacity: 1024) }
                        try self.onFrame?(session, frame)
                        self.armTimeout(session)
                    }
                }
                if complete || error != nil { self.close(session) } else { self.receive(session) }
            } catch { self.close(session) }
        }
    }
    public func send(_ frame: [String: Any], to session: Session) throws {
        guard !session.closed else { throw ProtocolFailure.invalidIdentity }
        var data = try JSONSerialization.data(withJSONObject: frame, options: [.withoutEscapingSlashes])
        guard data.count <= StrictJSON.frameLimit, session.writes.count < 8,
              session.writes.reduce(data.count, { $0 + $1.count }) <= 2 * StrictJSON.frameLimit else { throw ProtocolFailure.oversized }
        data.append(10); session.writes.append(data); flush(session)
    }
    private func flush(_ session: Session) {
        guard !session.closed, !session.writing, !session.writes.isEmpty else { return }
        session.writing = true
        session.connection.send(content: session.writes[0], completion: .contentProcessed { [weak self, weak session] error in
            guard let self, let session, !session.closed else { return }
            session.writing = false
            if error != nil { self.close(session); return }
            session.writes.removeFirst(); self.flush(session)
        })
    }
    public func close(_ session: Session) {
        guard !session.closed else { return }
        session.closed = true; session.timeout?.cancel(); session.timeout = nil
        session.writes.removeAll(); sessions.removeValue(forKey: session.id)
        session.connection.stateUpdateHandler = nil; session.connection.cancel(); onClose?(session)
    }
    public func shutdown() {
        listener?.stateUpdateHandler = nil; listener?.newConnectionHandler = nil; listener?.cancel(); listener = nil
        for session in Array(sessions.values) { close(session) }
    }
}
