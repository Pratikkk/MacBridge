import Foundation
import CryptoKit
import Security
import CommonCrypto

public enum IdentityImportFailure: Error { case status(OSStatus) }

public enum IdentityMaterial {
    public static func random(_ count: Int) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else { throw ProtocolFailure.invalidIdentity }
        return Data(bytes)
    }
    public static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    public static func fingerprint(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02X", $0) }.joined(separator: ":") }
    public static func certificate(key: P256.Signing.PrivateKey, now: Date = Date(), serial: Data? = nil) throws -> Data {
        let algorithm = DER.sequence(DER.oid([1,2,840,10045,4,3,2]))
        let name = DER.sequence(DER.tag(0x31, DER.sequence(DER.oid([2,5,4,3]) + DER.tag(0x0c, Data("MacBridge Development Peer".utf8)))))
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMddHHmmss'Z'"
        let validity = DER.sequence(DER.tag(0x18, Data(formatter.string(from: now.addingTimeInterval(-60)).utf8)) +
            DER.tag(0x18, Data(formatter.string(from: now.addingTimeInterval(365 * 86400)).utf8)))
        var serialBytes = try serial ?? random(16)
        guard (1...20).contains(serialBytes.count), serialBytes.contains(where: { $0 != 0 }) else { throw ProtocolFailure.invalidIdentity }
        while serialBytes.count > 1 && serialBytes.first == 0 { serialBytes.removeFirst() }
        if serialBytes.first! & 0x80 != 0 { serialBytes.insert(0, at: 0) }
        let encodedSerial = DER.tag(2, serialBytes)
        let tbs = DER.sequence(DER.tag(0xa0, DER.integer(2)) + encodedSerial + algorithm + name + validity + name + key.publicKey.derRepresentation)
        return DER.sequence(tbs + algorithm + DER.tag(3, Data([0]) + (try key.signature(for: tbs)).derRepresentation))
    }
    public static func pemCertificate(_ der: Data) -> Data {
        let base64 = der.base64EncodedString()
        var lines: [String] = []
        var offset = base64.startIndex
        while offset < base64.endIndex {
            let end = base64.index(offset, offsetBy: 64, limitedBy: base64.endIndex) ?? base64.endIndex
            lines.append(String(base64[offset..<end])); offset = end
        }
        return Data(("-----BEGIN CERTIFICATE-----\n" + lines.joined(separator: "\n") + "\n-----END CERTIFICATE-----\n").utf8)
    }
    public static func certificateDER(_ pem: Data) throws -> Data {
        guard let text = String(data: pem, encoding: .utf8), text.hasPrefix("-----BEGIN CERTIFICATE-----"),
              let end = text.range(of: "-----END CERTIFICATE-----") else { throw ProtocolFailure.invalidIdentity }
        let encoded = text.dropFirst("-----BEGIN CERTIFICATE-----".count).prefix(upTo: end.lowerBound).filter { !$0.isWhitespace }
        guard let der = Data(base64Encoded: String(encoded)), SecCertificateCreateWithData(nil, der as CFData) != nil else { throw ProtocolFailure.invalidIdentity }
        return der
    }
    /// RFC 7292 password-integrity PFX. All bags and derived material stay in memory;
    /// macOS 15's explicit memory-only import never writes a second Keychain key.
    @available(macOS 15, *)
    public static func tlsIdentity(key: P256.Signing.PrivateKey, certificate: Data) throws -> SecIdentity {
        let keyID = DER.sequence(DER.oid([1,2,840,113549,1,9,21]) + DER.tag(0x31, DER.tag(4, Data(Insecure.SHA1.hash(data: certificate)))))
        let attributes = DER.tag(0x31, keyID)
        let password = "MacBridge transient TLS"
        let passwordBytes = Array(password.utf8)
        let keySalt = try random(16)
        let iv = try random(16)
        var encryptionKey = [UInt8](repeating: 0, count: 32)
        let derivation = keySalt.withUnsafeBytes { salt in
            CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), password, passwordBytes.count,
                salt.bindMemory(to: UInt8.self).baseAddress, keySalt.count,
                CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 2048, &encryptionKey, encryptionKey.count)
        }
        guard derivation == kCCSuccess else { throw ProtocolFailure.invalidIdentity }
        let privateDER = key.derRepresentation
        var encrypted = Data(count: privateDER.count + kCCBlockSizeAES128)
        var written = 0
        let encryption = encrypted.withUnsafeMutableBytes { output in
            privateDER.withUnsafeBytes { input in
                iv.withUnsafeBytes { vector in
                    CCCrypt(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionPKCS7Padding),
                        encryptionKey, encryptionKey.count, vector.baseAddress, input.baseAddress, privateDER.count,
                        output.baseAddress, output.count, &written)
                }
            }
        }
        guard encryption == kCCSuccess else { throw ProtocolFailure.invalidIdentity }
        encrypted.count = written
        let prf = DER.sequence(DER.oid([1,2,840,113549,2,9]) + DER.tag(5, Data()))
        let kdf = DER.sequence(DER.oid([1,2,840,113549,1,5,12]) + DER.sequence(DER.tag(4, keySalt) + DER.integer(2048) + DER.integer(32) + prf))
        let cipher = DER.sequence(DER.oid([2,16,840,1,101,3,4,1,42]) + DER.tag(4, iv))
        let algorithm = DER.sequence(DER.oid([1,2,840,113549,1,5,13]) + DER.sequence(kdf + cipher))
        let wrapped = DER.sequence(algorithm + DER.tag(4, encrypted))
        let keyBag = DER.sequence(DER.oid([1,2,840,113549,1,12,10,1,2]) + DER.tag(0xa0, wrapped) + attributes)
        let certBag = DER.sequence(DER.oid([1,2,840,113549,1,12,10,1,3]) + DER.tag(0xa0,
            DER.sequence(DER.oid([1,2,840,113549,1,9,22,1]) + DER.tag(0xa0, DER.tag(4, certificate)))) + attributes)
        func content(_ data: Data) -> Data { DER.sequence(DER.oid([1,2,840,113549,1,7,1]) + DER.tag(0xa0, DER.tag(4, data))) }
        let safe = DER.sequence(content(DER.sequence(keyBag + certBag)))
        let salt = try random(32)
        // PKCS#12 MAC derivation: ID=3, SHA-256 u=32/v=64, BMP password.
        let bmp: [UInt8] = password.utf16.flatMap { scalar -> [UInt8] in [UInt8(scalar >> 8), UInt8(scalar & 255)] } + [0, 0]
        let paddedPassword = Data((0..<(((bmp.count + 63) / 64) * 64)).map { bmp[$0 % bmp.count] })
        let input = Data(repeating: 3, count: 64) + Data(salt + salt) + paddedPassword
        var derived = Data(SHA256.hash(data: input))
        for _ in 1..<2048 { derived = Data(SHA256.hash(data: derived)) }
        let mac = Data(HMAC<SHA256>.authenticationCode(for: safe, using: SymmetricKey(data: derived)))
        let digestInfo = DER.sequence(DER.sequence(DER.oid([2,16,840,1,101,3,4,2,1]) + DER.tag(5, Data())) + DER.tag(4, mac))
        let pfx = DER.sequence(DER.integer(3) + content(safe) + DER.sequence(digestInfo + DER.tag(4, salt) + DER.integer(2048)))
        var items: CFArray?
        let status = SecPKCS12Import(pfx as CFData, [kSecImportExportPassphrase as String: password, kSecImportToMemoryOnly as String: true] as CFDictionary, &items)
        guard status == errSecSuccess else { throw IdentityImportFailure.status(status) }
        guard let values = items as? [[String: Any]],
              let identity = values.first?[kSecImportItemIdentity as String] else { throw ProtocolFailure.invalidIdentity }
        return identity as! SecIdentity
    }
}

enum DER {
    static func tag(_ tag: UInt8, _ payload: Data) -> Data {
        var length = payload.count
        var encoded = Data()
        if length < 128 { encoded.append(UInt8(length)) }
        else {
            var bytes: [UInt8] = []
            while length > 0 { bytes.insert(UInt8(length & 255), at: 0); length >>= 8 }
            encoded.append(0x80 | UInt8(bytes.count)); encoded.append(contentsOf: bytes)
        }
        return Data([tag]) + encoded + payload
    }
    static func sequence(_ payload: Data) -> Data { tag(0x30, payload) }
    static func integer(_ value: Int) -> Data {
        var n = value; var bytes: [UInt8] = []
        repeat { bytes.insert(UInt8(n & 255), at: 0); n >>= 8 } while n > 0
        if bytes[0] & 0x80 != 0 { bytes.insert(0, at: 0) }
        return tag(2, Data(bytes))
    }
    static func oid(_ components: [Int]) -> Data {
        var data = Data([UInt8(components[0] * 40 + components[1])])
        for value in components.dropFirst(2) {
            var n = value; var encoded = [UInt8(n & 127)]; n >>= 7
            while n > 0 { encoded.insert(UInt8(n & 127) | 128, at: 0); n >>= 7 }
            data.append(contentsOf: encoded)
        }
        return tag(6, data)
    }
}
