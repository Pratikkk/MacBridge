import Foundation
import CryptoKit

public struct Peer: Codable, Equatable {
    public let publicKey: String
    public let name: String
    public init(publicKey: String, name: String) { self.publicKey = publicKey; self.name = name }
}

/// Authentication is serialized by the engine. A pairing code is consumed only
/// after the new peer has been durably saved; existing pins cannot be replaced.
public final class Authentication {
    public let deviceID: String
    public let publicKey: Data
    public let name: String
    public var pin: String { IdentityMaterial.fingerprint(publicKey) }
    public private(set) var peers: [String: Peer]
    public private(set) var secret = ""
    public private(set) var expiry = 0.0
    private let clock: () -> Double
    private let save: ([String: Peer]) throws -> Void
    public init(deviceID: String, publicKey: Data, name: String, peers: [String: Peer],
                clock: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime },
                save: @escaping ([String: Peer]) throws -> Void) throws {
        self.deviceID = deviceID; self.publicKey = publicKey; self.name = name
        self.peers = peers; self.clock = clock; self.save = save
        try rotate()
    }
    public func rotate() throws { secret = (try IdentityMaterial.random(32)).map { String(format: "%02x", $0) }.joined(); expiry = clock() + 300 }
    public var remaining: Double { max(0, expiry - clock()) }
    public func challenge(nonce: String) -> [String: Any] {
        ["type": "AUTH_CHALLENGE", "protocolVersion": 2, "nonce": nonce, "deviceId": deviceID,
         "deviceName": name, "fingerprint": pin, "publicKey": publicKey.base64EncodedString()]
    }
    public func authenticate(_ proof: [String: Any], nonce: String) throws -> String {
        guard proof["type"] as? String == "AUTH_PROOF", let id = proof["deviceId"] as? String,
              !id.isEmpty, id.unicodeScalars.count <= 128,
              let encoded = proof["publicKey"] as? String, encoded.utf8.count <= 4096,
              let signed = proof["signature"] as? String, signed.utf8.count <= 256,
              let keyBytes = Data(base64Encoded: encoded), let signature = Data(base64Encoded: signed),
              let key = try? P256.Signing.PublicKey(derRepresentation: keyBytes),
              let sig = try? P256.Signing.ECDSASignature(derRepresentation: signature) else { throw ProtocolFailure.invalidIdentity }
        let transcript = "macbridge-auth-v2\n\(nonce)\n\(deviceID)\n\(pin)\n\(id)\n\(IdentityMaterial.fingerprint(keyBytes))"
        guard key.isValidSignature(sig, for: Data(transcript.utf8)) else { throw ProtocolFailure.invalidIdentity }
        let known = peers[id]
        let supplied = proof["secret"]
        if let supplied, !(supplied is NSNull) {
            guard let text = supplied as? String, text.utf8.count == 64, !secret.isEmpty,
                  clock() < expiry, constantTimeEqual(Data(text.utf8), Data(secret.utf8)) else { throw ProtocolFailure.invalidIdentity }
        } else if known == nil { throw ProtocolFailure.invalidIdentity }
        guard known == nil || known?.publicKey == encoded else { throw ProtocolFailure.invalidIdentity }
        let peerName = String((proof["deviceName"] as? String ?? "Android Phone").trimmingCharacters(in: .whitespacesAndNewlines).unicodeScalars.prefix(128))
        var updated = peers
        updated[id] = Peer(publicKey: encoded, name: peerName)
        if updated != peers { try save(updated); peers = updated }
        if supplied != nil && !(supplied is NSNull) { secret = ""; expiry = 0 }
        return id
    }
    public func forget(_ id: String) throws { var updated = peers; updated.removeValue(forKey: id); try save(updated); peers = updated }
    private func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        return zip(a, b).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
}
