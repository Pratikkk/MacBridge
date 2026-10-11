import Foundation
import Security

@main
struct IdentityAccessChecks {
    static func run(_ executable: String, _ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run(); process.waitUntilExit()
        precondition(process.terminationStatus == 0)
    }
    static func signedFixture(_ directory: URL, name: String, value: Int) throws -> URL {
        let source = directory.appendingPathComponent(name + ".c")
        let output = directory.appendingPathComponent(name)
        try Data("int main(void) { return \(value); }\n".utf8).write(to: source)
        try run("/usr/bin/clang", [source.path, "-o", output.path])
        try run("/usr/bin/codesign", ["--force", "--identifier", "com.macbridge.identity.fixture", "--sign", "-", output.path])
        return output
    }
    static func access(_ apps: [SecTrustedApplication]) -> SecAccess {
        var value: SecAccess?
        precondition(SecAccessCreate("Isolated MacBridge identity fixture" as CFString, apps as CFArray, &value) == errSecSuccess)
        return value!
    }
    static func readACL(_ access: SecAccess) -> SecACL {
        let entries = SecAccessCopyMatchingACLList(access, kSecACLAuthorizationDecrypt) as! [SecACL]
        precondition(entries.count == 1)
        return entries[0]
    }
    static func contents(_ acl: SecACL) -> (CFArray?, CFString?, SecKeychainPromptSelector) {
        var apps: CFArray?, description: CFString?
        var prompt = SecKeychainPromptSelector()
        precondition(SecACLCopyContents(acl, &apps, &description, &prompt) == errSecSuccess)
        return (apps, description, prompt)
    }
    static func main() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MacBridgeAccessFixture-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }
        let helper = try signedFixture(directory, name: "helper ' 世界", value: 0)
        var old: SecTrustedApplication?
        precondition(SecTrustedApplicationCreateFromPath(helper.path, &old) == errSecSuccess)
        let original = old!
        let stale = access([original])
        let before = contents(readACL(stale))
        let oldCopy = directory.appendingPathComponent("old-helper")
        try FileManager.default.copyItem(at: helper, to: oldCopy)
        let next = try signedFixture(directory, name: "replacement", value: 1)
        try FileManager.default.removeItem(at: helper)
        try FileManager.default.moveItem(at: next, to: helper)
        // Same executable path, different signed code: path equality proves nothing.
        precondition(SecTrustedApplicationValidateWithPath(original, helper.path) != errSecSuccess)
        precondition(SecTrustedApplicationValidateWithPath(original, oldCopy.path) == errSecSuccess)
        var all: CFArray?
        precondition(SecAccessCopyACLList(stale, &all) == errSecSuccess)
        let others = (all as! [SecACL]).filter {
            !(SecACLCopyAuthorizations($0) as! [String]).contains(kSecACLAuthorizationDecrypt as String)
        }.map { ($0, contents($0)) }
        precondition(refreshIdentityReadAccess(stale, helper: helper.path) == errSecSuccess)
        let after = contents(readACL(stale))
        let current = (after.0 as! [SecTrustedApplication])[0]
        precondition(SecTrustedApplicationValidateWithPath(current, helper.path) == errSecSuccess)
        precondition(SecTrustedApplicationValidateWithPath(current, oldCopy.path) != errSecSuccess)
        precondition(before.1 == after.1 && before.2 == after.2)
        for (acl, saved) in others {
            let now = contents(acl)
            precondition(saved.1 == now.1 && saved.2 == now.2)
            if let apps = saved.0 { precondition(now.0 != nil && CFEqual(apps, now.0!)) }
            else { precondition(now.0 == nil) }
        }
        precondition(refreshIdentityReadAccess(stale, helper: helper.path) == errSecSuccess)
        precondition(refreshIdentityReadAccess(access([original, current]), helper: helper.path) == errSecParam)
        var other: SecTrustedApplication?
        precondition(SecTrustedApplicationCreateFromPath(oldCopy.path, &other) == errSecSuccess)
        precondition(refreshIdentityReadAccess(access([other!]), helper: helper.path) == errSecParam)
        precondition(refreshIdentityReadAccess(access([]), helper: helper.path) == errSecParam)
        print("PASS: rebuilt helper rejects stale trusted requirement; exact current requirement validates after repair")
        print("PASS: trusted-app repair preserves unrelated ACLs, description and prompt and remains idempotent")
        print("PASS: broader, empty and unrelated trusted-app grants fail closed")
    }
}
