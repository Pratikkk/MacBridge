// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MacBridge",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "MacBridge", targets: ["MacBridge"]),
               .executable(name: "MacBridgeIdentity", targets: ["MacBridgeIdentity"])],
    targets: [
        .executableTarget(name: "MacBridge"),
        .executableTarget(name: "MacBridgeIdentity")
    ]
)
