import Foundation
import Security
import AppKit
import Darwin

// This helper runs in a child process, never on the UI thread. Key bytes travel
// only through private stdin/stdout pipes, never arguments or diagnostics.
let args = CommandLine.arguments
guard args.count == 3, ["read", "add", "authorize"].contains(args[1]),
      args[2].utf8.count == 64, args[2].allSatisfy({ "0123456789abcdef".contains($0) }) else { exit(2) }
let authorizing = args[1] == "authorize"
func unavailable(_ status: OSStatus) -> Never {
    // Only the public OS error number is diagnostic; never log queries or key bytes.
    FileHandle.standardError.write(Data("KeychainStatus=\(status)\n".utf8))
    exit(4)
}
let interactionStatus = SecKeychainSetUserInteractionAllowed(authorizing)
guard interactionStatus == errSecSuccess else { unavailable(interactionStatus) }
// The engine is a direct child of the signed app. Reject arbitrary shell/CLI
// callers before looking up an item, so this is not a general key-export tool.
let parentLookup = Process()
let parentOutput = Pipe()
parentLookup.executableURL = URL(fileURLWithPath: "/bin/ps")
parentLookup.arguments = ["-o", "ppid=", "-p", String(getppid())]
parentLookup.standardOutput = parentOutput
parentLookup.standardError = FileHandle.nullDevice
do { try parentLookup.run() } catch { exit(6) }
parentLookup.waitUntilExit()
let parentText = String(data: parentOutput.fileHandleForReading.readData(ofLength: 32), encoding: .utf8) ?? ""
let bundle = URL(fileURLWithPath: args[0]).resolvingSymlinksInPath()
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
guard parentLookup.terminationStatus == 0,
      let hostPID = Int32(parentText.trimmingCharacters(in: .whitespacesAndNewlines)),
      let host = NSRunningApplication(processIdentifier: hostPID),
      host.bundleIdentifier == "com.macbridge.companion",
      host.bundleURL?.resolvingSymlinksInPath() == bundle else { exit(6) }
var expected: SecStaticCode?
var requirement: SecRequirement?
var running: SecCode?
guard SecStaticCodeCreateWithPath(bundle as CFURL, [], &expected) == errSecSuccess,
      let expected,
      SecCodeCopyDesignatedRequirement(expected, [], &requirement) == errSecSuccess,
      let requirement,
      SecCodeCopyGuestWithAttributes(nil, [kSecGuestAttributePid as String: hostPID] as CFDictionary, [], &running) == errSecSuccess,
      let running, SecCodeCheckValidity(running, [], requirement) == errSecSuccess else { exit(6) }
let query: [String: Any] = [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: "com.macbridge.companion.identity",
    kSecAttrAccount as String: args[2],
    kSecAttrSynchronizable as String: false
]
if args[1] == "read" || authorizing {
    if authorizing {
        // Always Allow can refresh the trusted-app ACL while retaining an older
        // ad-hoc partition hash. On explicit authorization, ask Keychain to rotate only
        // that one hash to this signed helper, retaining all other ACL entries.
        // Do this before reading: a stale partition can reject a read before
        // presenting any permission prompt. Terminal hands password-authorized rotation to the user.
        var referenceQuery = query
        referenceQuery[kSecReturnRef as String] = true
        referenceQuery[kSecMatchLimit as String] = kSecMatchLimitOne
        var reference: CFTypeRef?
        let referenceStatus = SecItemCopyMatching(referenceQuery as CFDictionary, &reference)
        guard referenceStatus == errSecSuccess, let reference else { unavailable(referenceStatus) }
        let item = reference as! SecKeychainItem
        var access: SecAccess?
        let accessStatus = SecKeychainItemCopyAccess(item, &access)
        guard accessStatus == errSecSuccess, let access else { unavailable(accessStatus) }
        let partitions = SecAccessCopyMatchingACLList(access, kSecACLAuthorizationPartitionID) as? [SecACL] ?? []
        guard partitions.count == 1 else { unavailable(errSecParam) }
        var code: SecCode?
        var staticCode: SecStaticCode?
        var info: CFDictionary?
        guard SecCodeCopySelf([], &code) == errSecSuccess, let code,
              SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode,
              SecCodeCopySigningInformation(staticCode, [], &info) == errSecSuccess,
              let hash = (info as? [String: Any])?[kSecCodeInfoUnique as String] as? Data else { unavailable(errSecParam) }
        let codeHash = hash.map { String(format: "%02x", $0) }.joined()
        var apps: CFArray?
        var description: CFString?
        var prompt = SecKeychainPromptSelector()
        let contentStatus = SecACLCopyContents(partitions[0], &apps, &description, &prompt)
        guard contentStatus == errSecSuccess, let description else { unavailable(contentStatus) }
        do {
            if try updatedIdentityPartition(description as String, codeHash: codeHash) != nil {
                // Apple's tool uses getpass(), which needs a visible terminal.
                // Hand off a reviewed, item-scoped command with no password in
                // argv/pipes/logs; no busy polling or hidden password collection.
                var keychain: SecKeychain?
                let keychainStatus = SecKeychainItemCopyKeychain(item, &keychain)
                guard keychainStatus == errSecSuccess, let keychain else { unavailable(keychainStatus) }
                var pathSize: UInt32 = 4096
                var pathBytes = [CChar](repeating: 0, count: Int(pathSize))
                let pathStatus = SecKeychainGetPath(keychain, &pathSize, &pathBytes)
                guard pathStatus == errSecSuccess else { unavailable(pathStatus) }
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MacBridgeKeychain-" + UUID().uuidString)
                let script = directory.appendingPathComponent("MacBridge Keychain Repair.command")
                let text = identityRepairCommand(account: args[2], codeHash: codeHash,
                    keychain: String(cString: pathBytes), helper: args[0])
                do {
                    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                        attributes: [.posixPermissions: 0o700])
                    guard FileManager.default.createFile(atPath: script.path, contents: Data(text.utf8),
                        attributes: [.posixPermissions: 0o700]) else { unavailable(errSecIO) }
                    let terminal = Process()
                    terminal.executableURL = URL(fileURLWithPath: "/usr/bin/open")
                    terminal.arguments = ["-a", "Terminal", script.path]
                    terminal.standardInput = FileHandle.nullDevice
                    terminal.standardOutput = FileHandle.nullDevice
                    terminal.standardError = FileHandle.nullDevice
                    try terminal.run()
                    terminal.waitUntilExit()
                    guard terminal.terminationStatus == 0 else { unavailable(errSecIO) }
                    exit(7) // Human completes Terminal, then requests final approval.
                } catch { unavailable(errSecIO) }
            }
        } catch { unavailable(errSecParam) }
        // Always Allow can leave the same path with an obsolete ad-hoc code
        // requirement. Explicitly rebuild only this helper's existing read rule.
        // SecKeychainItemSetAccess uses the system's owner-approval UI; no password
        // or private key is collected, passed to Terminal, or exported here.
        let refreshed = refreshIdentityReadAccess(access, helper: args[0])
        guard refreshed == errSecSuccess else { unavailable(refreshed) }
        let savedAccess = SecKeychainItemSetAccess(item, access)
        guard savedAccess == errSecSuccess else { unavailable(savedAccess) }
    }
    var lookup = query
    lookup[kSecReturnData as String] = true
    lookup[kSecMatchLimit as String] = kSecMatchLimitOne
    if !authorizing { lookup[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUIFail }
    var result: CFTypeRef?
    let status = SecItemCopyMatching(lookup as CFDictionary, &result)
    if status == errSecItemNotFound { exit(3) }
    guard status == errSecSuccess else { unavailable(status) }
    guard let data = result as? Data, !data.isEmpty, data.count <= 8192 else { exit(4) }
    if !authorizing { FileHandle.standardOutput.write(data) }
} else {
    let data = FileHandle.standardInput.readData(ofLength: 8193)
    guard !data.isEmpty, data.count <= 8192 else { exit(2) }
    var item = query
    item[kSecValueData as String] = data
    item[kSecAttrLabel as String] = "MacBridge identity private key"
    let status = SecItemAdd(item as CFDictionary, nil)
    if status == errSecDuplicateItem { exit(5) }
    guard status == errSecSuccess else { unavailable(status) }
}
