import Foundation
import Darwin
import MacBridgeProtocol

func emit(_ value: [String: Any]) {
    guard var data = try? JSONSerialization.data(withJSONObject: value, options: [.withoutEscapingSlashes]), data.count <= 65536 else { return }
    data.append(10); try? FileHandle.standardOutput.write(contentsOf: data)
}

func run() throws {
    guard #available(macOS 15, *) else { emit(["event": "error", "message": "MacBridge requires macOS 15 or later."]); exit(2) }
    var options: [String: String] = [:]; var flags = Set<String>(); var index = 1
    let arguments = CommandLine.arguments
    while index < arguments.count {
        let argument = arguments[index]
        if ["--gui", "--clipboard", "--files", "--test-mode", "--echo"].contains(argument) { flags.insert(argument); index += 1 }
        else if ["--state-dir", "--host", "--port", "--address", "--identity-helper", "--authorize-identity"].contains(argument), index + 1 < arguments.count {
            options[argument] = arguments[index + 1]; index += 2
        } else { throw ProtocolFailure.invalidJSON }
    }
    let fixture = flags.contains("--test-mode")
    let helper = options["--identity-helper"]
    if let account = options["--authorize-identity"] {
        guard let helper, account.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil, !fixture else { throw ProtocolFailure.invalidJSON }
        let process = Process(); let diagnostic = Pipe()
        process.executableURL = URL(fileURLWithPath: helper); process.arguments = ["authorize", account]
        process.standardInput = FileHandle.nullDevice; process.standardOutput = FileHandle.nullDevice; process.standardError = diagnostic
        try process.run()
        var cancellation: [DispatchSourceSignal] = []
        for number in [SIGTERM, SIGINT] {
            signal(number, SIG_IGN)
            let source = DispatchSource.makeSignalSource(signal: number, queue: DispatchQueue.global(qos: .utility))
            source.setEventHandler { if process.isRunning { process.terminate() }; exit(4) }
            source.resume(); cancellation.append(source)
        }
        withExtendedLifetime(cancellation) { process.waitUntilExit() }
        let data = diagnostic.fileHandleForReading.readData(ofLength: 65)
        if let value = String(data: data, encoding: .utf8), value.range(of: "^KeychainStatus=-?[0-9]{1,10}\\n?$", options: .regularExpression) != nil { try? FileHandle.standardOutput.write(contentsOf: data) }
        exit([0, 3, 4, 6, 7].contains(process.terminationStatus) ? process.terminationStatus : 4)
    }
    guard fixture || helper != nil, !fixture || (helper == nil && options["--state-dir"] != nil && options["--host"] == "127.0.0.1"),
          !flags.contains("--echo") || fixture,
          let port = UInt16(options["--port"] ?? "8990"), options["--address"].map(Engine.validAddress) ?? true else { throw ProtocolFailure.invalidJSON }
    umask(0o077)
    let directory = options["--state-dir"].map { URL(fileURLWithPath: $0, isDirectory: true) } ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/MacBridgeDev")
    if fixture {
        let normal = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/MacBridgeDev").standardizedFileURL
        guard directory.resolvingSymlinksInPath().standardizedFileURL != normal.resolvingSymlinksInPath() else { throw ProtocolFailure.invalidJSON }
    }
    let engine = try Engine(directory: directory, helper: helper, fixture: fixture, clipboard: flags.contains("--clipboard"),
        files: flags.contains("--files"), echo: flags.contains("--echo"), host: options["--host"], port: port, address: options["--address"], emit: emit)
    var signals: [DispatchSourceSignal] = []
    for number in [SIGTERM, SIGINT] {
        signal(number, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: number, queue: engine.queue)
        source.setEventHandler { engine.shutdown() }; source.resume(); signals.append(source)
    }
    engine.queue.async { do { try engine.start() } catch { engine.error("Could not start the native companion. Existing identity and pairing have not been replaced."); engine.shutdown(code: 1) } }
    DispatchQueue.global(qos: .utility).async {
        var pending = Data(); var discarding = false
        while true {
            let data = FileHandle.standardInput.availableData
            if data.isEmpty { engine.queue.async { engine.shutdown() }; return }
            for byte in data {
                if byte == 10 {
                    if discarding { discarding = false; continue }
                    let line = pending; pending.removeAll(keepingCapacity: true)
                    engine.queue.async {
                        do { try engine.command(StrictJSON.object(line, limit: 16384)) }
                        catch { engine.error("Could not complete that action. Check connection, sharing permissions, file access and available storage, then try again.") }
                    }
                } else {
                    if discarding { continue }
                    pending.append(byte)
                    if pending.count > 16384 { pending.removeAll(keepingCapacity: true); discarding = true; engine.queue.async { engine.error("Invalid companion command.") } }
                }
            }
        }
    }
    withExtendedLifetime(signals) { dispatchMain() }
}

do { try run() }
catch StartupFailure.identity(let account) {
    emit(["event": "error", "message": "Could not read the existing Mac identity. Allow Keychain access or restore its certificate; no identity was regenerated.", "identityAccount": account.map { $0 as Any } ?? NSNull()]); exit(1)
}
catch { emit(["event": "error", "message": "Could not start the native companion. Check the local network and existing identity files."]); exit(1) }
