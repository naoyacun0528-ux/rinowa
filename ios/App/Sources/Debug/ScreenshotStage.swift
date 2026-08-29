#if DEBUG
import SwiftUI
import UIKit
import RinowaCore

/// 画面を1枚ずつ、名前で呼び出して出すだけの足場。
///
/// Mac が手元に無いので、画面は CI の Simulator で撮って持ち帰るしかない。
/// UI を自動操作して辿る方法もあるが、あれは**押す場所が1ピクセルずれただけで
/// 撮れなくなる**。ここでは起動時の引数で行き先を直接指定する。
///
///     xcrun simctl launch <UDID> blog.nextlab.echo --rinowa-screen chat
///
/// `#if DEBUG` で囲ってあるので、配布する形には1バイトも入らない。
/// 製品側のコードには手を入れていない——足場が触るのは「最初に何を出すか」だけ。
enum ScreenshotHarness {

    static var requested: String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "--rinowa-screen"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }

    /// 見本の写真。撮影のためだけに作る。
    ///
    /// 実際に送られてきた写真を置くわけにはいかないし、真っ白でも意味が無い。
    /// **形と色が分かれば足りる**ので、その場で描く。
    static let sampleImage: UIImage = {
        let size = CGSize(width: 1200, height: 1600)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let colors = [UIColor(red: 0.16, green: 0.20, blue: 0.30, alpha: 1).cgColor,
                          UIColor(red: 0.55, green: 0.42, blue: 0.36, alpha: 1).cgColor]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                      colors: colors as CFArray, locations: [0, 1])!
            ctx.cgContext.drawLinearGradient(gradient, start: .zero,
                                             end: CGPoint(x: size.width, y: size.height),
                                             options: [])
            UIColor(white: 1, alpha: 0.16).setFill()
            for i in 0..<7 {
                let r = CGFloat(90 + i * 130)
                ctx.cgContext.fillEllipse(in: CGRect(x: size.width / 2 - r, y: size.height / 2 - r,
                                                     width: r * 2, height: r * 2).insetBy(dx: 0, dy: 0))
                UIColor(white: 1, alpha: 0.05).setFill()
            }
        }
    }()
}

/// 名前を1つ受け取って、その画面だけを出す。
struct ScreenshotStage: View {

    let name: String

    @EnvironmentObject private var store: ConversationStore
    @StateObject private var call = CallController()

    private var firstConversation: String {
        store.conversations.first?.id ?? ""
    }

    var body: some View {
        content
            .onAppear(perform: prepare)
    }

    @ViewBuilder
    private var content: some View {
        switch name {
        case "list":
            NavigationStack { ChatListScreen() }

        case "photo-crop":
            PhotoCropScreen(source: ScreenshotHarness.sampleImage, onBack: {})

        case "stickers":
            // 引き出しを開いた会話。押すと即送信されるので、開いた形だけ撮る。
            NavigationStack { ChatScreen(conversationId: firstConversation, stickersOpen: true) }

        case "feedback":
            FeedbackScreen(onBack: {})

        case "account":
            AccountScreen(onBack: {})

        case "verify-email":
            VerifyEmailScreen(onBack: {})

        case "password-reset":
            PasswordResetScreen(onBack: {})

        case "delete-account":
            DeleteAccountScreen(onBack: {})

        case "new-conversation":
            NewConversationScreen(onBack: {})

        case "new-group":
            NewGroupScreen(onBack: {})

        case "list-compose":
            // ＋ を押した状態。押した先の画面はまだ無いので、ここまで。
            NavigationStack { ChatListScreen(composeOpen: true) }

        case "chat":
            NavigationStack { ChatScreen(conversationId: firstConversation) }

        case "photo":
            PhotoViewer(images: [ScreenshotHarness.sampleImage]) {}

        case "call-active", "call-ringing", "call-ended":
            ZStack {
                NavigationStack { ChatScreen(conversationId: firstConversation) }
                CallOverlay(call: call)
            }

        case "safety":
            NavigationStack { SafetyScreen(title: store.conversations.first?.title ?? "") }

        case "profile":
            NavigationStack { ProfileScreen() }

        case "privacy":
            NavigationStack { PrivacyScreen() }

        case "backup":
            NavigationStack { BackupScreen() }

        case "signin":
            SignInScreen()

        default:
            // 名前を間違えたまま撮って、後から「どれが撮れていないのか」を
            // 探すことになるのが一番困る。**画面に出して分かるようにする。**
            Text("知らない画面: \(name)")
                .font(.system(size: 20, weight: .semibold))
        }
    }

    private func prepare() {
        store.attachSampleMedia(ScreenshotHarness.sampleImage)
        switch name {
        case "call-active":
            call.dial(to: store.conversations.first?.title ?? "", video: false)
            call.connected()
        case "call-ringing":
            call.incoming(from: store.conversations.first?.title ?? "", video: true)
        case "call-ended":
            call.dial(to: store.conversations.first?.title ?? "", video: false)
            call.hangUp()
        default:
            break
        }
    }
}
#endif
