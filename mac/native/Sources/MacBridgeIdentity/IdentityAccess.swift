import Foundation
import Security

// A partition hash and a trusted application's designated requirement are
// independent checks. Replacing a partition alone leaves the old requirement.
// Prepare a narrow edit of the existing read ACL, retaining its rights, prompt,
// description and every unrelated ACL. The caller commits it with macOS UI.
func refreshIdentityReadAccess(_ access: SecAccess, helper: String) -> OSStatus {
    let entries = SecAccessCopyMatchingACLList(access, kSecACLAuthorizationDecrypt) as? [SecACL] ?? []
    guard entries.count == 1 else { return errSecParam }
    var applications: CFArray?
    var description: CFString?
    var prompt = SecKeychainPromptSelector()
    let copied = SecACLCopyContents(entries[0], &applications, &description, &prompt)
    guard copied == errSecSuccess else { return copied }
    guard let description, let old = applications as? [SecTrustedApplication], old.count == 1 else { return errSecParam }
    var pathData: CFData?
    let pathStatus = SecTrustedApplicationCopyData(old[0], &pathData)
    guard pathStatus == errSecSuccess else { return pathStatus }
    guard let pathData else { return errSecParam }
    // Compare the exact recorded executable path, including its terminating NUL.
    // Never narrow/overwrite a wildcard, another application or a broader list.
    guard pathData as Data == Data((helper + "\0").utf8) else { return errSecParam }
    var current: SecTrustedApplication?
    let created = SecTrustedApplicationCreateFromPath(helper, &current)
    guard created == errSecSuccess else { return created }
    guard let current else { return errSecParam }
    return SecACLSetContents(entries[0], [current] as CFArray, description, prompt)
}
