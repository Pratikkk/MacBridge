import Foundation
import CoreFoundation

public enum ProtocolFailure: Error { case invalidJSON, oversized, rateLimited, invalidIdentity }

/// Validate untrusted frames before Foundation parsing: duplicate fields, excessive
/// nesting and non-finite numbers must never acquire platform-dependent meanings.
public enum StrictJSON {
    public static let frameLimit = 1_048_576
    public static func object(_ data: Data, limit: Int = frameLimit) throws -> [String: Any] {
        guard data.count <= limit else { throw ProtocolFailure.oversized }
        guard String(data: data, encoding: .utf8) != nil else { throw ProtocolFailure.invalidJSON }
        var parser = Scanner(bytes: Array(data))
        try parser.value(depth: 0)
        parser.whitespace()
        guard parser.index == parser.bytes.count,
              let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ProtocolFailure.invalidJSON
        }
        return result
    }

    public static func integer(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              !["f", "d"].contains(String(cString: number.objCType)),
              let result = Int64(number.stringValue) else { return nil }
        return result
    }
    public static func boolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }

    private struct Scanner {
        let bytes: [UInt8]
        var index = 0
        mutating func whitespace() {
            while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
        }
        mutating func consume(_ byte: UInt8) -> Bool {
            whitespace()
            guard index < bytes.count, bytes[index] == byte else { return false }
            index += 1
            return true
        }
        mutating func string() throws -> String {
            whitespace()
            let start = index
            guard consume(34) else { throw ProtocolFailure.invalidJSON }
            while index < bytes.count {
                let byte = bytes[index]
                index += 1
                if byte == 34 {
                    // JSONDecoder rejects invalid escapes and unpaired UTF-16 surrogates.
                    return try JSONDecoder().decode(String.self, from: Data(bytes[start..<index]))
                }
                guard byte >= 32 else { throw ProtocolFailure.invalidJSON }
                if byte == 92 {
                    guard index < bytes.count else { throw ProtocolFailure.invalidJSON }
                    index += 1
                }
            }
            throw ProtocolFailure.invalidJSON
        }
        mutating func value(depth: Int) throws {
            whitespace()
            guard index < bytes.count else { throw ProtocolFailure.invalidJSON }
            switch bytes[index] {
            case 123:
                guard depth < 32 else { throw ProtocolFailure.invalidJSON }
                index += 1
                var keys = Set<String>()
                if consume(125) { return }
                repeat {
                    let key = try string()
                    guard keys.insert(key).inserted, consume(58) else { throw ProtocolFailure.invalidJSON }
                    try value(depth: depth + 1)
                    if consume(125) { return }
                    guard consume(44) else { throw ProtocolFailure.invalidJSON }
                } while true
            case 91:
                guard depth < 32 else { throw ProtocolFailure.invalidJSON }
                index += 1
                if consume(93) { return }
                repeat {
                    try value(depth: depth + 1)
                    if consume(93) { return }
                    guard consume(44) else { throw ProtocolFailure.invalidJSON }
                } while true
            case 34: _ = try string()
            case 116: try literal("true")
            case 102: try literal("false")
            case 110: try literal("null")
            default:
                let start = index
                while index < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[index]) { index += 1 }
                let token = String(decoding: bytes[start..<index], as: UTF8.self)
                guard token.range(of: "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$", options: .regularExpression) != nil,
                      let number = Double(token), number.isFinite else { throw ProtocolFailure.invalidJSON }
            }
        }
        mutating func literal(_ text: String) throws {
            let sequence = Array(text.utf8)
            guard index + sequence.count <= bytes.count,
                  Array(bytes[index..<(index + sequence.count)]) == sequence else { throw ProtocolFailure.invalidJSON }
            index += sequence.count
        }
    }
}

/// Incremental newline framing bounds memory even when a peer never sends LF.
public struct FrameBuffer {
    private var pending = Data()
    public init() {}
    public mutating func append(_ data: Data) throws -> [[String: Any]] {
        var result: [[String: Any]] = []
        var start = data.startIndex
        while start < data.endIndex {
            let end = data[start...].firstIndex(of: 10) ?? data.endIndex
            guard pending.count + end - start <= StrictJSON.frameLimit else { throw ProtocolFailure.oversized }
            pending.append(data[start..<end])
            if end == data.endIndex { break }
            result.append(try StrictJSON.object(pending))
            pending.removeAll(keepingCapacity: true)
            start = end + 1
        }
        return result
    }
}
