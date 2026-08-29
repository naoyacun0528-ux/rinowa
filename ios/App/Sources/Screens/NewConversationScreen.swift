import SwiftUI

/// 会話を始める。
///
/// `ui/chatlist/NewConversationScreen.kt` の Swift 側。文言はそのまま写した。
///
/// 検索欄ではなくコードにしている理由。メールアドレスや電話番号を打って、その人が
/// 使っているかどうかが見えるメッセンジャーは、構造として「この人はここにいますか」に
/// 誰にでも答えるサービスを作っている。その答えは、答えることに同意していない人についてのもの。
///
/// 本人が渡すと決めたコードなら逆になる。持ち主が明かすまで、何も見つからない。
struct NewConversationScreen: View {

    let onBack: () -> Void
    var onOpened: (String) -> Void = { _ in }

    @EnvironmentObject private var store: ConversationStore
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var code = ""
    @State private var busy = false
    @State private var notice: String?
    @State private var copied = false

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "会話をはじめる", onBack: onBack)

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer().frame(height: 8)

                    Text("あなたの招待コード")
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(colors.textSecondary)
                    Spacer().frame(height: 10)

                    myCodeRow

                    Spacer().frame(height: 8)
                    Text("このコードを渡した相手だけが、あなたを見つけられます。")
                        .rinowaType(RinowaType.labelSmall)
                        .foregroundStyle(colors.textTertiary)

                    Spacer().frame(height: 32)

                    Text("相手の招待コード")
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(colors.textSecondary)
                    Spacer().frame(height: 10)

                    RinowaField(
                        value: Binding(
                            get: { code },
                            set: { code = String($0.uppercased().prefix(12)); notice = nil }
                        ),
                        placeholder: "ABCD-EFGH",
                        enabled: !busy,
                        capitalization: .characters,
                        onSubmit: start
                    )

                    if notice != nil { Spacer().frame(height: 14) }
                    NoticeBanner(text: notice)

                    Spacer().frame(height: 20)

                    PrimaryButton(
                        enabled: !busy && InviteCode.normalise(code).count == InviteCode.length,
                        action: start
                    ) { tint in
                        PrimaryButtonLabel(text: busy ? "探しています" : "この相手と話す", color: tint)
                    }

                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 24)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(colors.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar)
    }

    private var myCodeRow: some View {
        HStack(spacing: 0) {
            Text(store.myInviteCode.map(InviteCode.format) ?? "……")
                .font(.system(size: 20, weight: .bold))
                .tracking(3)
                .foregroundStyle(colors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)

            if let mine = store.myInviteCode {
                Button {
                    haptics.fire(.softConfirm)
                    UIPasteboard.general.string = InviteCode.format(mine)
                    copied = true
                } label: {
                    Text(copied ? "コピーしました" : "コピー")
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(colors.accent)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.leading, 18)
        .padding(.trailing, 6)
        .padding(.vertical, 14)
        .glassFace(shape: RoundedRectangle(cornerRadius: 16, style: .continuous), elevation: 2)
    }

    private func start() {
        guard !busy else { return }
        busy = true
        notice = nil

        let entered = InviteCode.normalise(code)
        // 相手を探す仕組みは iOS にまだ無い。見つからなかったときと同じ形で返す。
        // ここを Firebase に差し替えたとき、画面は1行も変わらない。
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            busy = false
            if entered == store.myInviteCode {
                haptics.fire(.warning)
                notice = "自分のコードです。相手のコードを入れてください。"
            } else {
                haptics.fire(.error)
                notice = "そのコードの相手が見つかりませんでした。"
            }
        }
    }
}
