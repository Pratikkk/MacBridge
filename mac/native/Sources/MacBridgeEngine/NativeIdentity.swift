import Foundation
import CryptoKit
import Security
import MacBridgeProtocol
import Darwin

enum StartupFailure: Error { case identity(String?) }

/// Only the unchanged signed helper reads the durable Keychain item. Network
/// identity import explicitly uses memory-only storage on macOS 15 and later.
@available(macOS 15, *)
final class NativeIdentity {
    let directory: URL, helper: String?
    let key: P256.Signing.PrivateKey
    let publicKey: Data
    let account: String
    let deviceID: String
    var certificate: Data
    var tls: SecIdentity
    var starts = 0.0, expires = 0.0
    init(directory: URL, helper: String?, fixture: Bool) throws {
        self.directory = directory; self.helper = helper
        try PrivateStorage.directory(directory)
        let certURL = directory.appendingPathComponent("identity.crt")
        let keyURL = directory.appendingPathComponent("identity.key")
        let markerURL = directory.appendingPathComponent("identity-keychain.json")
        let idURL = directory.appendingPathComponent("device-id")
        let peersURL = directory.appendingPathComponent("peers.json")
        var material: Data?
        let exists = PrivateStorage.exists(certURL)
        if !exists {
            guard ![keyURL, markerURL, idURL, peersURL].contains(where: { FileManager.default.fileExists(atPath: $0.path) }) else { throw StartupFailure.identity(nil) }
            let generated = P256.Signing.PrivateKey()
            material = Data(generated.pemRepresentation.utf8)
            certificate = try IdentityMaterial.certificate(key: generated)
        } else { certificate = try IdentityMaterial.certificateDER(PrivateStorage.read(certURL, limit: 65536)) }
        guard let cert = SecCertificateCreateWithData(nil, certificate as CFData), let certKey = SecCertificateCopyKey(cert),
              let raw = SecKeyCopyExternalRepresentation(certKey, nil) as Data? else { throw StartupFailure.identity(nil) }
        publicKey = try P256.Signing.PublicKey(x963Representation: raw).derRepresentation
        account = IdentityMaterial.hash(publicKey)
        let markerExists = PrivateStorage.exists(markerURL)
        if markerExists {
            guard let marker = try? StrictJSON.object(PrivateStorage.read(markerURL, limit: 1024)),
                  StrictJSON.integer(marker["version"]) == 1, marker["account"] as? String == account else { throw StartupFailure.identity(nil) }
        }
        var legacy: Data?
        if PrivateStorage.exists(keyURL) { legacy = try PrivateStorage.read(keyURL, limit: 8192) }
        if let legacy {
            guard let text = String(data: legacy, encoding: .utf8),
                  let parsed = try? P256.Signing.PrivateKey(pemRepresentation: text), parsed.publicKey.derRepresentation == publicKey else { throw StartupFailure.identity(nil) }
        }
        if let helper {
            let read = try Self.helper(helper, operation: "read", account: account)
            if read.0 == 3 {
                guard !markerExists, let candidate = material ?? legacy else { throw StartupFailure.identity(account) }
                let add = try Self.helper(helper, operation: "add", account: account, input: candidate)
                guard add.0 == 0 else { throw StartupFailure.identity(account) }
                let verified = try Self.helper(helper, operation: "read", account: account)
                guard verified.0 == 0, verified.1 == candidate else { throw StartupFailure.identity(account) }
                material = verified.1
            } else {
                guard read.0 == 0 else { throw StartupFailure.identity(account) }; material = read.1
            }
        } else {
            guard fixture, !markerExists else { throw StartupFailure.identity(nil) }
            material = material ?? legacy
        }
        guard let material, material.count <= 8192, let text = String(data: material, encoding: .utf8),
              let parsed = try? P256.Signing.PrivateKey(pemRepresentation: text), parsed.publicKey.derRepresentation == publicKey else { throw StartupFailure.identity(nil) }
        key = parsed; tls = try IdentityMaterial.tlsIdentity(key: parsed, certificate: certificate)
        if !exists { try PrivateStorage.write(IdentityMaterial.pemCertificate(certificate), to: certURL) }
        if helper != nil {
            try PrivateStorage.save(["version": 1, "account": account], to: markerURL)
            if let legacy {
                guard try PrivateStorage.read(keyURL, limit: 8192) == legacy else { throw StartupFailure.identity(nil) }
                guard unlink(keyURL.path) == 0 else { throw StartupFailure.identity(nil) }
            }
        } else if !exists { try PrivateStorage.write(material, to: keyURL) }
        if PrivateStorage.exists(idURL) {
            guard let value = String(data: try PrivateStorage.read(idURL, limit: 512), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !value.isEmpty, value.utf8.count <= 128, !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { throw StartupFailure.identity(nil) }
            deviceID = value
        } else {
            deviceID = UUID().uuidString.lowercased(); try PrivateStorage.write(Data(deviceID.utf8), to: idURL)
        }
        try updateDates()
    }
    static func helper(_ path: String, operation: String, account: String, input: Data? = nil) throws -> (Int32, Data) {
        let process = Process(); let output = Pipe(); let stdin = Pipe()
        process.executableURL = URL(fileURLWithPath: path); process.arguments = [operation, account]
        process.standardInput = stdin; process.standardOutput = output; process.standardError = FileHandle.nullDevice
        try process.run()
        if let input { try stdin.fileHandleForWriting.write(contentsOf: input) }
        try stdin.fileHandleForWriting.close()
        let timeout = DispatchWorkItem { if process.isRunning { process.terminate() } }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 15, execute: timeout)
        defer { timeout.cancel() }
        var data = Data()
        while true {
            let chunk = output.fileHandleForReading.availableData
            if chunk.isEmpty { break }
            guard data.count + chunk.count <= 8192 else { process.terminate(); process.waitUntilExit(); throw StartupFailure.identity(nil) }
            data.append(chunk)
        }
        process.waitUntilExit(); return (process.terminationStatus, data)
    }
    func renew() throws {
        let certURL = directory.appendingPathComponent("identity.crt")
        let before = try PrivateStorage.read(certURL, limit: 65536)
        guard try IdentityMaterial.certificateDER(before) == certificate else { throw StartupFailure.identity(nil) }
        let candidate = try IdentityMaterial.certificate(key: key)
        let next = try IdentityMaterial.tlsIdentity(key: key, certificate: candidate)
        guard try PrivateStorage.read(certURL, limit: 65536) == before else { throw StartupFailure.identity(nil) }
        try PrivateStorage.write(IdentityMaterial.pemCertificate(candidate), to: certURL)
        certificate = candidate; tls = next; try updateDates()
    }
    private func updateDates() throws {
        guard let cert = SecCertificateCreateWithData(nil, certificate as CFData),
              let values = SecCertificateCopyValues(cert, [kSecOIDX509V1ValidityNotBefore, kSecOIDX509V1ValidityNotAfter] as CFArray, nil) as? [String: Any],
              let before = values[kSecOIDX509V1ValidityNotBefore as String] as? [String: Any],
              let after = values[kSecOIDX509V1ValidityNotAfter as String] as? [String: Any],
              let start = before[kSecPropertyKeyValue as String] as? NSNumber,
              let end = after[kSecPropertyKeyValue as String] as? NSNumber else { throw StartupFailure.identity(nil) }
        // Security certificate date values use CFAbsoluteTime (January 2001).
        starts = start.doubleValue + kCFAbsoluteTimeIntervalSince1970
        expires = end.doubleValue + kCFAbsoluteTimeIntervalSince1970
        guard starts < expires else { throw StartupFailure.identity(nil) }
    }
    var status: String {
        let now = Date().timeIntervalSince1970
        if now < starts { return "notYetValid" }; if now >= expires { return "expired" }
        return expires - now <= 30 * 86400 ? "expiring" : "valid"
    }
}
