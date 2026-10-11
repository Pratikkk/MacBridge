import Foundation

enum IdentityPartitionError: Error { case invalidRecord }

// Local ad-hoc updates change the partition's code hash. Rotate only a single
// existing code-hash grant for this item; never introduce broad apple/team grants.
func updatedIdentityPartition(_ description: String, codeHash: String) throws -> String? {
    let hex = Set("0123456789abcdef")
    guard codeHash.count == 40, codeHash.allSatisfy({ hex.contains($0) }),
          description.utf8.count <= 16384, description.count % 2 == 0,
          description.allSatisfy({ hex.contains($0) }) else { throw IdentityPartitionError.invalidRecord }
    var bytes = Data()
    var index = description.startIndex
    while index < description.endIndex {
        let end = description.index(index, offsetBy: 2)
        guard let value = UInt8(description[index..<end], radix: 16) else { throw IdentityPartitionError.invalidRecord }
        bytes.append(value)
        index = end
    }
    guard var record = (try? PropertyListSerialization.propertyList(from: bytes, format: nil)) as? [String: Any],
          record.count == 1, let partitions = record["Partitions"] as? [String], partitions.count == 1,
          partitions[0].hasPrefix("cdhash:"), partitions[0].count == 47,
          partitions[0].dropFirst(7).allSatisfy({ hex.contains($0) }) else { throw IdentityPartitionError.invalidRecord }
    let current = "cdhash:" + codeHash
    if partitions == [current] { return nil }
    record["Partitions"] = [current]
    return try PropertyListSerialization.data(fromPropertyList: record, format: .xml, options: 0)
        .map { String(format: "%02x", $0) }.joined()
}


func identityRepairCommand(account: String, codeHash: String, keychain: String, helper: String) -> String {
    func quote(_ value: String) -> String { "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'" }
    return """
    #!/bin/zsh
    trap '/bin/rm -f -- "$0"; /bin/rmdir -- "${0:h}" 2>/dev/null' EXIT
    print -r -- 'MacBridge identity access repair'
    print -r -- 'This changes only the MacBridge identity item to trust the current helper.'
    print -r -- 'Enter the login-keychain password at the Apple security tool prompt. Input is hidden.'
    actual_hash=$(/usr/bin/codesign -d --verbose=4 \(quote(helper)) 2>&1 | /usr/bin/sed -n 's/^CDHash=//p')
    if [[ "$actual_hash" != \(quote(codeHash)) ]]; then
      print -r -- 'The app changed. Return to MacBridge and request repair again.'
      exit 1
    fi
    /usr/bin/security set-generic-password-partition-list -s 'com.macbridge.companion.identity' -a \(quote(account)) -S \(quote("cdhash:" + codeHash)) \(quote(keychain)) >/dev/null
    repair_status=$?
    if [[ "$repair_status" == 0 ]]; then
      print -r -- 'Access rule updated. Return to MacBridge and click Allow Keychain Access to finish.'
    else
      print -r -- 'Repair did not finish. No identity was replaced; retry from MacBridge when ready.'
    fi
    exit "$repair_status"
    """ + "\n"
}
