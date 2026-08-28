// swift-tools-version: 5.9
import PackageDescription

/// Rinowa のうち、画面を持たない部分。
///
/// iOS アプリより先にこれがある理由。形式は契約（docs/WIRE_FORMATS.md）で、
/// 形式のバグがついに姿を見せるのは2つ目の実装の上。しかも見せる場所は他人の端末の、
/// 送り直せないメッセージ。この半分をいま、Windows で、Android のテストと同じベクタに
/// 対して書いておけば、契約は独立した2人の読み手に、どちらかが本番になるずっと前から
/// 確かめられる。
///
/// ここは SwiftUI も UIKit も AVFoundation も import しない。意図的で、そのおかげで
/// Apple の SDK がまったく無い機械でもビルドできる。SDK が要る部分は、SDK 無しでは
/// 検証もできない部分だから。
let package = Package(
    name: "RinowaCore",
    products: [
        .library(name: "RinowaCore", targets: ["RinowaCore"])
    ],
    dependencies: [
        // Apple 自身のもので、CryptoKit ではなくこちらを選んだ理由は、Linux でも
        // ARM64 Windows でもビルドできること。CryptoKit は Apple のプラットフォームに
        // しか無いので、それを使うコアは Mac が来るまで試せない。
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0")
    ],
    targets: [
        .target(
            name: "RinowaCore",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto"),
                .product(name: "_CryptoExtras", package: "swift-crypto")
            ]
        ),
        .testTarget(
            name: "RinowaCoreTests",
            dependencies: ["RinowaCore"]
        )
    ]
)
