import SwiftUI

/// iOS 版の入口。
///
/// **いまはまだ殻。** 最初の CI が確かめたいのは1つだけで、
/// 「Windows で書いた RinowaCore が、Apple の SDK の上で本当に動くか」。
///
/// 画面を積むのはそれが通ってから。通らないまま14,000行を積むと、
/// 最初のビルドで数百個のエラーが同時に出て、どこから直せばいいか分からなくなる。
@main
struct RinowaApp: App {
    var body: some Scene {
        WindowGroup {
            SmokeScreen()
        }
    }
}
