import Foundation
import CryptoKit
import Security
import MacBridgeProtocol

func XCTAssertTrue(_ value: Bool) { precondition(value) }
func XCTAssertNil(_ value: Any?) { precondition(value == nil) }
func XCTAssertEqual<T: Equatable>(_ a: T, _ b: T) { precondition(a == b) }
func XCTAssertThrowsError<T>(_ value: @autoclosure () throws -> T, _ message: String = "") {
    do { _ = try value(); fatalError("Expected rejection: " + message) } catch {}
}

final class StrictJSONTests {
    func testUnicodeAndEscapedBrackets() throws {
        let value = try StrictJSON.object(Data(#"{"text":"你好 👋 [{{","nested":{"ok":true}}"#.utf8))
        XCTAssertEqual(value["text"] as? String, "你好 👋 [{{")
    }
    func testRejectsAmbiguousAndMalformedFrames() {
        for text in [#"{"x":1,"x":2}"#, #"{"x":1,"\u0078":2}"#, #"{"n":{"x":1,"x":2}}"#,
                     #"{"x":NaN}"#, #"{"x":1e999}"#, #"{"x":01}"#, #"{"x":"\ud800"}"#,
                     #"{"x":true,}"#, "[]", "{}{}", "", "{\"x\":\"\n\"}"] {
            XCTAssertThrowsError(try StrictJSON.object(Data(text.utf8)), text)
        }
        XCTAssertThrowsError(try StrictJSON.object(Data([123, 34, 255, 34, 58, 49, 125])))
    }
    func testDepthAndSizeBoundaries() throws {
        _ = try StrictJSON.object(Data(("{\"a\":" + String(repeating: "[", count: 31) + "0" + String(repeating: "]", count: 31) + "}").utf8))
        XCTAssertThrowsError(try StrictJSON.object(Data(("{\"a\":" + String(repeating: "[", count: 32) + "0" + String(repeating: "]", count: 32) + "}").utf8)))
        XCTAssertThrowsError(try StrictJSON.object(Data("{}".utf8), limit: 1))
    }
    func testIntegerAndBoolHaveDistinctMeanings() throws {
        let values = try StrictJSON.object(Data(#"{"int":4,"float":4.5,"decimal":4.0,"exponent":4e0,"bool":true,"huge":18446744073709551615}"#.utf8))
        XCTAssertEqual(StrictJSON.integer(values["int"]), 4)
        XCTAssertNil(StrictJSON.integer(values["float"]))
        XCTAssertNil(StrictJSON.integer(values["decimal"]))
        XCTAssertNil(StrictJSON.integer(values["exponent"]))
        XCTAssertNil(StrictJSON.integer(values["bool"]))
        XCTAssertNil(StrictJSON.integer(values["huge"]))
        XCTAssertNil(StrictJSON.boolean(values["int"]))
        XCTAssertEqual(StrictJSON.boolean(values["bool"]), true)
    }
    func testFragmentedAndCoalescedFrames() throws {
        var buffer = FrameBuffer()
        XCTAssertTrue(try buffer.append(Data("{\"text\":\"你".utf8)).isEmpty)
        let values = try buffer.append(Data("好\"}\n{\"x\":2}\n".utf8))
        XCTAssertEqual(values.count, 2)
        XCTAssertEqual(values[0]["text"] as? String, "你好")
        XCTAssertTrue(try buffer.append(Data("{".utf8)).isEmpty)
        XCTAssertThrowsError(try buffer.append(Data(repeating: 32, count: StrictJSON.frameLimit)))
    }
    func testReconnectDoesNotResetBudgetAndIdleRefills() throws {
        var now = 0.0
        let budget = WorkBudget(clock: { now })
        for _ in 0..<10 { try budget.message("CLIPBOARD") }
        XCTAssertThrowsError(try budget.message("CLIPBOARD"))
        now = 0.5
        try budget.message("CLIPBOARD")
        XCTAssertThrowsError(try budget.message("CLIPBOARD"))
        now = -10
        XCTAssertThrowsError(try budget.message("CLIPBOARD"))
        XCTAssertThrowsError(try budget.frame(bytes: 33 * 1_048_576))
    }
}

setbuf(stdout, nil)
let checks = StrictJSONTests()
try checks.testUnicodeAndEscapedBrackets()
checks.testRejectsAmbiguousAndMalformedFrames()
try checks.testDepthAndSizeBoundaries()
try checks.testIntegerAndBoolHaveDistinctMeanings()
try checks.testFragmentedAndCoalescedFrames()
try checks.testReconnectDoesNotResetBudgetAndIdleRefills()
print("PASS: six native framing, JSON and reconnect budget checks")

if #available(macOS 15, *) {
    let key = P256.Signing.PrivateKey()
    let certificate = try IdentityMaterial.certificate(key: key)
    let identity = try IdentityMaterial.tlsIdentity(key: key, certificate: certificate)
    var imported: SecCertificate?
    precondition(SecIdentityCopyCertificate(identity, &imported) == errSecSuccess)
    precondition(imported.map { SecCertificateCopyData($0) as Data } == certificate)
    let decoded = try IdentityMaterial.certificateDER(IdentityMaterial.pemCertificate(certificate))
    precondition(decoded == certificate)
    print("PASS: native certificate and memory-only TLS identity import")
}

func authenticationChecks() throws {
    let mac = P256.Signing.PrivateKey(), phone = P256.Signing.PrivateKey()
    var now = 0.0, failSave = false
    let auth = try Authentication(deviceID: "mac", publicKey: mac.publicKey.derRepresentation, name: "Mac", peers: [:], clock: { now }, save: { _ in
        if failSave { throw ProtocolFailure.invalidIdentity }
    })
    let nonce = "fixture-challenge"
    func proof(_ key: P256.Signing.PrivateKey, secret: String?) throws -> [String: Any] {
        let publicKey = key.publicKey.derRepresentation
        let text = "macbridge-auth-v2\n\(nonce)\nmac\n\(auth.pin)\nphone\n\(IdentityMaterial.fingerprint(publicKey))"
        var value: [String: Any] = ["type": "AUTH_PROOF", "deviceId": "phone", "deviceName": "Phone 世界", "publicKey": publicKey.base64EncodedString(),
            "signature": try key.signature(for: Data(text.utf8)).derRepresentation.base64EncodedString()]
        if let secret { value["secret"] = secret }; return value
    }
    let original = auth.secret
    now = 300
    XCTAssertThrowsError(try auth.authenticate(proof(phone, secret: original), nonce: nonce))
    now = 0; failSave = true
    XCTAssertThrowsError(try auth.authenticate(proof(phone, secret: original), nonce: nonce))
    precondition(auth.secret == original && auth.peers.isEmpty)
    failSave = false
    let paired = try auth.authenticate(proof(phone, secret: original), nonce: nonce)
    precondition(paired == "phone")
    XCTAssertThrowsError(try auth.authenticate(proof(phone, secret: original), nonce: nonce))
    _ = try auth.authenticate(proof(phone, secret: nil), nonce: nonce)
    try auth.rotate()
    XCTAssertThrowsError(try auth.authenticate(proof(P256.Signing.PrivateKey(), secret: auth.secret), nonce: nonce))
    XCTAssertThrowsError(try auth.authenticate(proof(phone, secret: nil), nonce: "different challenge"))
    try auth.forget("phone")
    XCTAssertThrowsError(try auth.authenticate(proof(phone, secret: nil), nonce: nonce))
    print("PASS: expiry, durable pairing, reused code, changed pin and signed nonce checks")
}
try authenticationChecks()

for serial in [Data([1]), Data([0, 0, 127]), Data([128]), Data([255, 255])] {
    let cert = try IdentityMaterial.certificate(key: P256.Signing.PrivateKey(), serial: serial)
    let process = Process(), input = Pipe()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/openssl")
    process.arguments = ["x509", "-noout"]
    process.standardInput = input; process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
    try process.run(); try input.fileHandleForWriting.write(contentsOf: IdentityMaterial.pemCertificate(cert)); try input.fileHandleForWriting.close()
    process.waitUntilExit(); precondition(process.terminationStatus == 0)
}
print("PASS: positive, leading-zero and high-bit serial certificates parse in OpenSSL")
