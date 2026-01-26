// swift-tools-version: 5.8
// NOTE: some of the configuration is shared with swiftBenchmark {} in build.gradle.kts
import PackageDescription

let package = Package(
    name: "swiftInterop",
    products: [
        .executable(name: "swiftInterop", targets: ["swiftInterop"])
    ],
    dependencies: [
        .package(path: "build/swiftpkg/benchmark")
    ],
    targets: [
        .executableTarget(
            name: "swiftInterop",
            dependencies: [
                .product(name: "benchmark", package: "benchmark")
            ],
            path: "swiftSrc"
        )
   ]
)