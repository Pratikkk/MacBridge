import Foundation
import Security

@main
struct IdentityPartitionChecks {
    static func encode(_ partitions: [String], extraField: Bool = false) throws -> String {
        var record: [String: Any] = ["Partitions": partitions]
        if extraField { record["UnknownField"] = "fixture" }
        return try PropertyListSerialization.data(fromPropertyList: record, format: .xml, options: 0)
            .map { String(format: "%02x", $0) }.joined()
    }
    static func main() throws {
        let old = "cdhash:" + String(repeating: "a", count: 40)
        let hash = String(repeating: "b", count: 40)
        let updated = try updatedIdentityPartition(encode([old]), codeHash: hash)!
        let bytes = stride(from: 0, to: updated.count, by: 2).map { offset -> UInt8 in
            let start = updated.index(updated.startIndex, offsetBy: offset)
            return UInt8(updated[start..<updated.index(start, offsetBy: 2)], radix: 16)!
        }
        let record = try PropertyListSerialization.propertyList(from: Data(bytes), format: nil) as! [String: Any]
        precondition(record["Partitions"] as! [String] == ["cdhash:" + hash])
        precondition(record.count == 1)
        let unchanged = try updatedIdentityPartition(updated, codeHash: hash)
        precondition(unchanged == nil)
        for description in try ["invalid", "世", String(repeating: "0", count: 16386), encode([]),
                                encode([old, "apple:"]), encode(["teamid:fixture"]), encode(["cdhash:invalid"]), encode([old], extraField: true)] {
            do {
                _ = try updatedIdentityPartition(description, codeHash: hash)
                fatalError("Unsafe partition record accepted")
            } catch IdentityPartitionError.invalidRecord { }
        }
        do {
            _ = try updatedIdentityPartition(updated, codeHash: "invalid")
            fatalError("Invalid hash accepted")
        } catch IdentityPartitionError.invalidRecord { }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MacBridgeRepairFixture-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        let marker = FileManager.default.temporaryDirectory.appendingPathComponent("MacBridgeInjection-" + UUID().uuidString)
        let script = directory.appendingPathComponent("fixture.command")
        let maliciousPath = "missing ' 世界 $(/usr/bin/touch " + marker.path + ") `echo injection`"
        let command = identityRepairCommand(account: String(repeating: "a", count: 64), codeHash: hash,
                                            keychain: maliciousPath, helper: maliciousPath)
        precondition(!command.contains(" -k "))
        try Data(command.utf8).write(to: script)
        let syntax = Process()
        syntax.executableURL = URL(fileURLWithPath: "/bin/zsh")
        syntax.arguments = ["-n", script.path]
        try syntax.run(); syntax.waitUntilExit()
        precondition(syntax.terminationStatus == 0)
        let run = Process()
        run.executableURL = URL(fileURLWithPath: "/bin/zsh")
        run.arguments = [script.path]
        run.standardOutput = FileHandle.nullDevice
        run.standardError = FileHandle.nullDevice
        try run.run(); run.waitUntilExit()
        precondition(run.terminationStatus == 1)
        precondition(!FileManager.default.fileExists(atPath: marker.path))
        precondition(!FileManager.default.fileExists(atPath: script.path))
        var executable: SecStaticCode?
        var signing: CFDictionary?
        let executableURL = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
        precondition(SecStaticCodeCreateWithPath(executableURL as CFURL, [], &executable) == errSecSuccess)
        precondition(SecCodeCopySigningInformation(executable!, [], &signing) == errSecSuccess)
        let actual = ((signing as! [String: Any])[kSecCodeInfoUnique as String] as! Data)
            .map { String(format: "%02x", $0) }.joined()
        let positiveDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("MacBridgePositiveFixture-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: positiveDirectory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: positiveDirectory) }
        let positiveScript = positiveDirectory.appendingPathComponent("fixture.command")
        let invoked = positiveDirectory.appendingPathComponent("invoked")
        var positive = identityRepairCommand(account: String(repeating: "a", count: 64), codeHash: actual,
                                             keychain: "isolated-fixture", helper: executableURL.path)
        // Preserve the real signing guard; substitute only the destructive tool
        // invocation so this check never touches the user's login Keychain.
        let lines = positive.components(separatedBy: "\n").map { line -> String in
            line.hasPrefix("/usr/bin/security set-generic-password-partition-list ")
                ? "/usr/bin/touch '" + invoked.path + "'" : line
        }
        positive = lines.joined(separator: "\n")
        try Data(positive.utf8).write(to: positiveScript)
        let normal = Process()
        normal.executableURL = URL(fileURLWithPath: "/bin/zsh")
        normal.arguments = [positiveScript.path]
        normal.standardOutput = FileHandle.nullDevice
        normal.standardError = FileHandle.nullDevice
        try normal.run(); normal.waitUntilExit()
        precondition(normal.terminationStatus == 0)
        precondition(FileManager.default.fileExists(atPath: invoked.path))
        print("PASS: real signed executable passes the repair guard using its Security-framework hash")
        print("PASS: repair handoff quotes Unicode/metacharacters, rejects changed helper and cleans itself up")
        print("PASS: exact helper hash rotation is idempotent and limited to one grant")
        print("PASS: malformed, oversized, Unicode and broader/multiple grants fail closed")
    }
}
