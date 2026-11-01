// swift-tools-version: 6.0
// Purpose: write appearance / layout files with the iOS packages' own encoders, and decode files
// written by Android with the iOS decoders. Driven by scripts/ios-interop.sh, which stages this package
// together with symlinks to the pure-Foundation iOS sources (CandidateKit's SwiftUI parts need Xcode
// macros, and the format code does not use them).
import Foundation
import PackageDescription

let ios = ProcessInfo.processInfo.environment["IOS_REPO"] ?? ""

let package = Package(
    name: "interop",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(path: ios + "/Packages/KeyboardCore"),
        .package(path: ios + "/Packages/LayoutKit"),
    ],
    targets: [
        .target(name: "SkinKit"),
        .target(name: "CandidateKit", dependencies: [.product(name: "KeyboardCore", package: "KeyboardCore")]),
        .target(name: "AppearanceKit", dependencies: ["SkinKit", "CandidateKit"]),
        .executableTarget(
            name: "interop",
            dependencies: [
                "AppearanceKit", "SkinKit", "CandidateKit",
                .product(name: "LayoutKit", package: "LayoutKit"),
            ]),
    ],
    swiftLanguageModes: [.v5]
)
