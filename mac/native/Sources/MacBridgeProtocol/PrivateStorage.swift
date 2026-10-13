import Foundation
import Darwin
import CryptoKit

public enum PrivateStorage {
    public static func exists(_ url: URL) -> Bool { var info = stat(); return lstat(url.path, &info) == 0 }
    public static func directory(_ url: URL) throws {
        var info = stat()
        if lstat(url.path, &info) == 0 {
            guard info.st_mode & S_IFMT == S_IFDIR, info.st_uid == getuid() else { throw ProtocolFailure.invalidIdentity }
        } else {
            guard errno == ENOENT else { throw ProtocolFailure.invalidIdentity }
            try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        }
        guard chmod(url.path, 0o700) == 0 else { throw ProtocolFailure.invalidIdentity }
    }
    public static func read(_ url: URL, limit: Int) throws -> Data {
        let fd = open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw ProtocolFailure.invalidIdentity }
        defer { close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_uid == getuid(),
              info.st_size >= 0, info.st_size <= limit else { throw ProtocolFailure.invalidIdentity }
        var result = Data()
        var buffer = [UInt8](repeating: 0, count: min(limit + 1, 65536))
        while true {
            let count = Darwin.read(fd, &buffer, buffer.count)
            if count == 0 { return result }
            if count < 0 { if errno == EINTR { continue }; throw ProtocolFailure.invalidIdentity }
            guard result.count + count <= limit else { throw ProtocolFailure.oversized }
            result.append(contentsOf: buffer.prefix(count))
        }
    }
    public static func write(_ data: Data, to url: URL) throws {
        try directory(url.deletingLastPathComponent())
        let temporary = url.deletingLastPathComponent().appendingPathComponent(".checkpoint-" + UUID().uuidString)
        let fd = open(temporary.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw ProtocolFailure.invalidIdentity }
        defer { close(fd); _ = unlink(temporary.path) }
        try data.withUnsafeBytes { bytes in
            var offset = 0
            while offset < bytes.count {
                let count = Darwin.write(fd, bytes.baseAddress!.advanced(by: offset), bytes.count - offset)
                if count < 0 && errno == EINTR { continue }
                guard count > 0 else { throw ProtocolFailure.invalidIdentity }
                offset += count
            }
        }
        guard fsync(fd) == 0, rename(temporary.path, url.path) == 0 else { throw ProtocolFailure.invalidIdentity }
        let parent = open(url.deletingLastPathComponent().path, O_RDONLY | O_NOFOLLOW)
        guard parent >= 0 else { throw ProtocolFailure.invalidIdentity }
        defer { close(parent) }
        guard fsync(parent) == 0 else { throw ProtocolFailure.invalidIdentity }
    }
    public static func save(_ object: [String: Any], to url: URL) throws {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes])
        guard data.count <= 128 * 1024 else { throw ProtocolFailure.oversized }
        try write(data, to: url)
    }
    public static func digest(_ url: URL, length: Int64? = nil) throws -> SHA256 {
        let fd = open(url.path, O_RDONLY | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw ProtocolFailure.invalidIdentity }
        defer { close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG,
              info.st_size <= 104_857_600 else { throw ProtocolFailure.invalidIdentity }
        var hash = SHA256(); var remaining = length
        var buffer = [UInt8](repeating: 0, count: 65536)
        while remaining == nil || remaining! > 0 {
            let count = Darwin.read(fd, &buffer, min(buffer.count, Int(remaining ?? 65536)))
            if count < 0 && errno == EINTR { continue }
            guard count >= 0 else { throw ProtocolFailure.invalidIdentity }
            if count == 0 { guard remaining == nil else { throw ProtocolFailure.invalidIdentity }; break }
            hash.update(data: Data(buffer.prefix(count)))
            if remaining != nil { remaining! -= Int64(count) }
        }
        return hash
    }
    public static func file(_ directory: URL, name: Any?, prefix: String) throws -> URL {
        guard let name = name as? String, name.hasPrefix(prefix), !name.contains("/"), name != ".", name != ".." else { throw ProtocolFailure.invalidIdentity }
        var folder = stat(); var info = stat()
        let url = directory.appendingPathComponent(name)
        guard lstat(directory.path, &folder) == 0, folder.st_mode & S_IFMT == S_IFDIR,
              lstat(url.path, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_uid == getuid() else { throw ProtocolFailure.invalidIdentity }
        return url
    }
}

public final class TransferCheckpoint {
    public let url: URL
    public let identity: [String]
    public init(url: URL, identity: [String]) { self.url = url; self.identity = identity }
    public func load() -> [String: Any] {
        var parent = stat()
        guard lstat(url.deletingLastPathComponent().path, &parent) == 0,
              parent.st_mode & S_IFMT == S_IFDIR, parent.st_uid == getuid() else { return [:] }
        guard let data = try? PrivateStorage.read(url, limit: 128 * 1024), let value = try? StrictJSON.object(data),
              StrictJSON.integer(value["version"]) == 1, value["identity"] as? [String] == identity else { return [:] }
        return value
    }
    public func save(_ values: [String: Any]) throws {
        var values = values; values["version"] = 1; values["identity"] = identity
        try PrivateStorage.save(values, to: url)
    }
    public func clear() { _ = unlink(url.path) }
}

public final class ReceiveQuota {
    public static let limits: [Int64] = [250, 500, 1000].map { $0 * 1_048_576 }
    public private(set) var limit: Int64 = 500 * 1_048_576
    public let directory: URL
    private let settings: URL
    public init(directory: URL, settings: URL) {
        self.directory = directory; self.settings = settings
        if let data = try? PrivateStorage.read(settings, limit: 4096), let object = try? StrictJSON.object(data),
           let value = StrictJSON.integer(object["limit"]), Self.limits.contains(value) { limit = value }
    }
    public func setLimit(_ value: Int64) throws {
        guard Self.limits.contains(value) else { throw ProtocolFailure.invalidJSON }
        try PrivateStorage.save(["limit": value, "version": 1, "identity": []], to: settings); limit = value
    }
    public func usage() throws -> (Int64, Int) {
        if !PrivateStorage.exists(directory) { return (0, 0) }
        try PrivateStorage.directory(directory)
        let names = try FileManager.default.contentsOfDirectory(atPath: directory.path)
        guard names.count < 10000 else { throw ProtocolFailure.oversized }
        var size: Int64 = 0
        for name in names {
            let file = try PrivateStorage.file(directory, name: name, prefix: "")
            var info = stat(); guard lstat(file.path, &info) == 0 else { throw ProtocolFailure.invalidIdentity }
            size += info.st_size
        }
        return (size, names.count)
    }
    public func permits(_ size: Int64, reservations: Int64) throws -> Bool {
        let (used, count) = try usage()
        var disk = statfs()
        guard statfs(directory.path, &disk) == 0 else { return false }
        return count < 100 && used + reservations + size <= limit &&
            UInt64(disk.f_bavail) * UInt64(disk.f_bsize) >= UInt64(size + 8 * 1_048_576)
    }
}
