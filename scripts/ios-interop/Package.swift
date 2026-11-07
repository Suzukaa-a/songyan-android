// swift-tools-version: 6.0
// Purpose: write appearance, layout and keyboard-tool files with the iOS packages' own encoders,
// and decode files written by Android with the iOS decoders. Driven by scripts/ios-interop.sh.
//
// Use the production packages so their localized messages resolve through their own resource
// bundles. Building String Catalogs and SwiftUI macros requires the full Xcode toolchain.
import Foundation
import PackageDescription

let ios = ProcessInfo.processInfo.environment["IOS_REPO"] ?? ""

let package = Package(
    name: "interop",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(path: ios + "/Packages/KeyboardCore"),
        .package(path: ios + "/Packages/LayoutKit"),
        .package(path: ios + "/Packages/QuickContentKit"),
        .package(path: ios + "/Packages/SkinKit"),
        .package(path: ios + "/Packages/CandidateKit"),
        .package(path: ios + "/Packages/AppearanceKit"),
    ],
    targets: [
        .executableTarget(
            name: "interop",
            dependencies: [
                .product(name: "AppearanceKit", package: "AppearanceKit"),
                .product(name: "SkinKit", package: "SkinKit"),
                .product(name: "CandidateKit", package: "CandidateKit"),
                .product(name: "LayoutKit", package: "LayoutKit"),
                .product(name: "QuickContentKit", package: "QuickContentKit"),
            ]),
    ],
    swiftLanguageModes: [.v5]
)
