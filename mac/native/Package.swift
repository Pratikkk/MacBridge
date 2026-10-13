// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MacBridge",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "MacBridge", targets: ["MacBridge"]),
               .executable(name: "MacBridgeIdentity", targets: ["MacBridgeIdentity"]),
               .executable(name: "MacBridgeEngine", targets: ["MacBridgeEngine"])],
    targets: [
        .executableTarget(name: "MacBridge"),
        .executableTarget(name: "MacBridgeIdentity"),
        .target(name: "MacBridgeProtocol"),
        .executableTarget(name: "MacBridgeEngine", dependencies: ["MacBridgeProtocol"]),
        .executableTarget(name: "MacBridgeProtocolChecks", dependencies: ["MacBridgeProtocol"], path: "Tests/MacBridgeProtocolTests")
    ]
)
