import SwiftUI

/// アカウントまわりの4画面。
///
/// `ui/auth/` の Swift 側。**文言はそのまま写した。**
///
/// Firebase Auth は iOS にまだ繋いでいない。画面は Android と同じ形で、
/// 通信するところだけが空いている。繋いだとき、画面は1行も変わらない。

// ------------------------------------------------------------------ アカウント

struct AccountScreen: View {

    let onBack: () -> Void
    var onOpen: (Row) -> Void = { _ in }

    @EnvironmentObject private var store: ConversationStore
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var confirmingSignOut = false

    enum Row: String, Identifiable, CaseIterable {
        case profile, feedback, privacy, backup, direct, delete
        var id: String { rawValue }

        var label: String {
            switch self {
            case .profile:  return "プロフィールを編集"
            case .feedback: return "フィードバックを送る・見る"
            case .privacy:  return "プライバシーと計測"
            case .backup:   return "バックアップ"
            case .direct:   return "Rinowa Direct（検証中）"
            case .delete:   return "アカウントを削除"
            }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "アカウント", onBack: onBack)

            ScrollView {
                VStack(alignment: .leading, spacing: RinowaDimens.gap) {
                    identity

                    VStack(spacing: 0) {
                        ForEach(Row.allCases) { row in
                            Button {
                                haptics.fire(.navigation)
                                onOpen(row)
                            } label: {
                                HStack {
                                    Text(row.label)
                                        .rinowaType(RinowaType.listName)
                                        .foregroundStyle(
                                            row == .delete ? colors.danger : colors.textPrimary
                                        )
                                    Spacer()
                                }
                                .padding(.horizontal, RinowaDimens.gap)
                                .frame(height: 52)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)

                            if row != Row.allCases.last {
                                Divider().overlay(colors.outlineSoft)
                                    .padding(.leading, RinowaDimens.gap)
                            }
                        }
                    }
                    .glassFace()

                    QuietButton(action: { confirmingSignOut = true }) { tint in
                        QuietButtonLabel(text: "ログアウト", color: tint)
                    }
                    .frame(maxWidth: .infinity, alignment: .center)
                }
                .padding(RinowaDimens.screenPadding)
            }
        }
        .background(colors.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar)
        .alert("ログアウトしますか", isPresented: $confirmingSignOut) {
            Button("ログアウト", role: .destructive) { haptics.fire(.destructive) }
            Button("やめる", role: .cancel) {}
        } message: {
            Text("この端末から出るだけで、アカウントは残ります。同じアカウントでいつでも戻れます。")
        }
    }

    private var identity: some View {
        VStack(alignment: .leading, spacing: RinowaDimens.gapSmall) {
            HStack(spacing: RinowaDimens.gap) {
                Avatar(title: store.myName ?? "名前未設定", seed: 3, size: 52)
                VStack(alignment: .leading, spacing: 2) {
                    Text(store.myName ?? "名前未設定")
                        .rinowaType(RinowaType.listName)
                        .foregroundStyle(colors.textPrimary)
                    Text(store.myEmail ?? "アドレス未登録")
                        .rinowaType(RinowaType.labelSmall)
                        .foregroundStyle(colors.textTertiary)
                }
                Spacer()
            }

            Divider().overlay(colors.outlineSoft).padding(.vertical, 4)

            labelled("メールアドレス", store.myEmail ?? "アドレス未登録")
            labelled("招待コード", store.myInviteCode.map(InviteCode.format) ?? "……")
        }
        .padding(RinowaDimens.gap)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassFace()
    }

    private func labelled(_ key: String, _ value: String) -> some View {
        HStack {
            Text(key).rinowaType(RinowaType.labelSmall).foregroundStyle(colors.textTertiary)
            Spacer()
            Text(value).rinowaType(RinowaType.label).foregroundStyle(colors.textSecondary)
        }
    }
}

// ------------------------------------------------------------------ メール確認

struct VerifyEmailScreen: View {

    let onBack: () -> Void

    @EnvironmentObject private var store: ConversationStore
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var busy = false
    @State private var notice: String?

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "メールを確認してください", onBack: onBack)

            VStack(alignment: .leading, spacing: 0) {
                Text("登録したアドレス")
                    .rinowaType(RinowaType.labelSmall)
                    .foregroundStyle(colors.textTertiary)
                Spacer().frame(height: 6)
                Text(store.myEmail ?? "アドレス未登録")
                    .rinowaType(RinowaType.listName)
                    .foregroundStyle(colors.textPrimary)

                Spacer().frame(height: 20)
                Text("メール内のリンクを開いてから、下のボタンを押してください。")
                    .rinowaType(RinowaType.listPreview)
                    .foregroundStyle(colors.textSecondary)

                if notice != nil { Spacer().frame(height: 16) }
                NoticeBanner(text: notice)

                Spacer().frame(height: 24)
                PrimaryButton(enabled: !busy, action: check) { tint in
                    PrimaryButtonLabel(text: busy ? "確認しています" : "確認しました", color: tint)
                }

                Spacer().frame(height: 12)
                QuietButton(enabled: !busy, action: { haptics.fire(.softConfirm) }) { tint in
                    QuietButtonLabel(text: "確認メールを再送する", color: tint)
                }
                QuietButton(enabled: !busy, action: { haptics.fire(.navigation) }) { tint in
                    QuietButtonLabel(text: "別のアカウントを使う", color: tint)
                }

                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)
        }
        .background(colors.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar)
    }

    private func check() {
        busy = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            busy = false
            haptics.fire(.warning)
            notice = "まだ確認できていません。メール内のリンクを開いてから、もう一度お試しください。"
        }
    }
}

// ------------------------------------------------------------------ 再設定

struct PasswordResetScreen: View {

    let onBack: () -> Void

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var email = ""
    @State private var busy = false
    @State private var sent = false

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "パスワードの再設定", onBack: onBack)

            VStack(alignment: .leading, spacing: 0) {
                if sent {
                    Text("メールを送りました")
                        .rinowaType(RinowaType.listName)
                        .foregroundStyle(colors.textPrimary)
                    Spacer().frame(height: 10)
                    // **「そのアドレスは登録されていません」と言わない。**
                    // 言えば、誰が使っているかを尋ねられる窓口になる。
                    Text("\(email) 宛に、そのアドレスで登録されていれば再設定用のリンクが届きます。")
                        .rinowaType(RinowaType.listPreview)
                        .foregroundStyle(colors.textSecondary)

                    Spacer().frame(height: 24)
                    QuietButton(action: onBack) { tint in
                        QuietButtonLabel(text: "ログインに戻る", color: tint)
                    }
                } else {
                    Text("メールアドレス")
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(colors.textSecondary)
                    Spacer().frame(height: 10)
                    RinowaField(
                        value: $email,
                        placeholder: "メールアドレス",
                        enabled: !busy,
                        keyboard: .emailAddress,
                        onSubmit: send
                    )
                    Spacer().frame(height: 20)
                    PrimaryButton(enabled: !busy && email.contains("@"), action: send) { tint in
                        PrimaryButtonLabel(text: busy ? "送信しています" : "再設定メールを送る", color: tint)
                    }
                }

                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)
        }
        .background(colors.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar)
    }

    private func send() {
        busy = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            busy = false
            haptics.fire(.success)
            sent = true
        }
    }
}

// ------------------------------------------------------------------ 削除

struct DeleteAccountScreen: View {

    let onBack: () -> Void

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var password = ""
    @State private var busy = false
    @State private var confirming = false

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "アカウントの削除", onBack: onBack)

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("削除すると、次のものが失われます。")
                        .rinowaType(RinowaType.listName)
                        .foregroundStyle(colors.textPrimary)

                    Spacer().frame(height: 14)
                    ForEach([
                        "アカウントとプロフィール",
                        "あなたが作ったスタンプ",
                        "会話への参加。あなたの端末にある履歴も開けなくなります",
                    ], id: \.self) { line in
                        HStack(alignment: .top, spacing: 10) {
                            Text("—").foregroundStyle(colors.textTertiary)
                            Text(line)
                                .rinowaType(RinowaType.listPreview)
                                .foregroundStyle(colors.textSecondary)
                        }
                        .padding(.bottom, 6)
                    }

                    Spacer().frame(height: 18)
                    // **できないことも書く。** 削除を「送ったものが消える」と
                    // 受け取ったまま押されると、取り返しがつかない。
                    Text("相手の端末に既に届いたメッセージは、相手の手元に残ります。")
                        .rinowaType(RinowaType.listPreview)
                        .foregroundStyle(colors.textSecondary)
                    Spacer().frame(height: 4)
                    Text("送ったものを取り消す機能ではありません。")
                        .rinowaType(RinowaType.listPreview)
                        .foregroundStyle(colors.textSecondary)

                    Spacer().frame(height: 18)
                    Text("この操作は取り消せません。")
                        .rinowaType(RinowaType.listName)
                        .foregroundStyle(colors.danger)

                    Spacer().frame(height: 24)
                    Text("確認のためパスワードを入力してください。")
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(colors.textSecondary)
                    Spacer().frame(height: 10)
                    RinowaField(
                        value: $password,
                        placeholder: "パスワード",
                        enabled: !busy,
                        secure: true
                    )

                    Spacer().frame(height: 20)
                    PrimaryButton(enabled: !busy && !password.isEmpty, action: {
                        confirming = true
                    }) { tint in
                        PrimaryButtonLabel(text: busy ? "削除しています" : "アカウントを削除", color: tint)
                    }
                    Spacer().frame(height: 20)
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
            }
        }
        .background(colors.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar)
        .alert("本当に削除しますか", isPresented: $confirming) {
            Button("削除する", role: .destructive) { haptics.fire(.destructive) }
            Button("やめる", role: .cancel) {}
        } message: {
            Text("アカウントと、Rinowa に保存されているあなたのデータが消えます。元に戻すことはできません。")
        }
    }
}
