import SwiftUI
import RinowaCore

/// 設定まわりの画面をまとめたもの。
///
/// Android では `profile/` `privacy/` `backup/` `auth/` に分かれているが、
/// どれも「並べて見せる」だけの構造なので、共通の部品を1つ作って共有する。
/// **同じ見た目のものを4回書くと、4か所ずれる。**

// ---------------------------------------------------------------- 共通の部品

struct SettingsCard<Content: View>: View {
    let heading: String?
    @ViewBuilder let content: Content
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: RinowaDimens.gapSmall) {
            if let heading {
                Text(heading)
                    .rinowaType(RinowaType.labelSmall)
                    .foregroundStyle(colors.textTertiary)
            }
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(RinowaDimens.gap)
        .background(
            RoundedRectangle(cornerRadius: RinowaDimens.cardRadius, style: .continuous)
                .fill(colors.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: RinowaDimens.cardRadius, style: .continuous)
                .strokeBorder(colors.outlineSoft, lineWidth: 1)
        )
    }
}

struct SettingsRow: View {
    let label: String
    var value: String? = nil
    var detail: String? = nil
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline) {
                Text(label)
                    .rinowaType(RinowaType.label)
                    .foregroundStyle(colors.textPrimary)
                Spacer(minLength: RinowaDimens.gap)
                if let value {
                    Text(value)
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(colors.textSecondary)
                        .multilineTextAlignment(.trailing)
                }
            }
            if let detail {
                Text(detail)
                    .rinowaType(RinowaType.labelSmall)
                    .foregroundStyle(colors.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 3)
    }
}

// ---------------------------------------------------------------- プロフィール

struct ProfileScreen: View {
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics
    @State private var displayName = "自分"

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()
            ScrollView {
                VStack(spacing: RinowaDimens.gap) {
                    Avatar(title: displayName, seed: 0, size: 88)
                        .padding(.top, RinowaDimens.gapLarge)

                    SettingsCard(heading: "表示される名前") {
                        TextField("名前", text: $displayName)
                            .rinowaType(RinowaType.label)
                            .foregroundStyle(colors.textPrimary)
                        Text("相手の画面に出ます。あとから変えられます。")
                            .rinowaType(RinowaType.labelSmall)
                            .foregroundStyle(colors.textTertiary)
                    }

                    NavigationLink { PrivacyScreen() } label: {
                        SettingsCard(heading: nil) {
                            SettingsRow(label: "プライバシー", value: "→")
                        }
                    }
                    .buttonStyle(.plain)

                    NavigationLink { BackupScreen() } label: {
                        SettingsCard(heading: nil) {
                            SettingsRow(label: "バックアップ", value: "→")
                        }
                    }
                    .buttonStyle(.plain)
                }
                .padding(RinowaDimens.screenPadding)
            }
        }
        .navigationTitle("プロフィール")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// ---------------------------------------------------------------- プライバシー

struct PrivacyScreen: View {
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()
            ScrollView {
                VStack(spacing: RinowaDimens.gap) {
                    SettingsCard(heading: "封の対象") {
                        // docs/RINOWA_SIGIL.md の表がそのまま約束。
                        ForEach(["メッセージ", "グループ", "写真・動画", "サムネイル",
                                 "スタンプ", "音声・ビデオ通話", "通話の記録", "バックアップ"], id: \.self) {
                            SettingsRow(label: $0, value: "○")
                        }
                    }

                    SettingsCard(heading: "守っていないもの") {
                        // **書いていないものは守られていない。それも書く。**
                        ForEach(["会話の題名", "誰と誰が、いつ話したか", "既読の位置"], id: \.self) {
                            SettingsRow(label: $0, value: "—")
                        }
                        Text("中身を守る仕組みであって、関係を隠す仕組みではありません。")
                            .rinowaType(RinowaType.labelSmall)
                            .foregroundStyle(colors.textTertiary)
                    }

                    SettingsCard(heading: "本文の扱い") {
                        SettingsRow(
                            label: "ログに本文を出さない",
                            value: "\(MessageText("これは本文"))",
                            detail: "本文を持つ型は、説明を求められても長さしか返しません。"
                        )
                    }
                }
                .padding(RinowaDimens.screenPadding)
            }
        }
        .navigationTitle("プライバシー")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// ---------------------------------------------------------------- バックアップ

struct BackupScreen: View {
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics
    @State private var enabled = false

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()
            ScrollView {
                VStack(spacing: RinowaDimens.gap) {
                    SettingsCard(heading: nil) {
                        Toggle(isOn: $enabled) {
                            Text("バックアップを取る")
                                .rinowaType(RinowaType.label)
                                .foregroundStyle(colors.textPrimary)
                        }
                        .tint(colors.accent)
                        .onChange(of: enabled) { _ in haptics.fire(.softConfirm) }
                    }

                    SettingsCard(heading: "仕組み") {
                        SettingsRow(
                            label: "封をする場所",
                            value: "この端末",
                            detail: "書庫は端末の中で封をしてからクラウドへ出ます。"
                        )
                        SettingsRow(
                            label: "鍵を作る回数",
                            value: "60万回",
                            detail: "総当たりで開けようとする人は、1回試すたびに同じ時間を払います。"
                        )
                    }

                    SettingsCard(heading: nil) {
                        // **合鍵は作らない。**
                        Text("暗証番号を忘れたら、二度と開きません。")
                            .rinowaType(RinowaType.label)
                            .foregroundStyle(colors.danger)
                        Text("復旧手段を用意しないことが、「あなたにしか開けない」ということです。")
                            .rinowaType(RinowaType.labelSmall)
                            .foregroundStyle(colors.textSecondary)
                    }
                }
                .padding(RinowaDimens.screenPadding)
            }
        }
        .navigationTitle("バックアップ")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// ---------------------------------------------------------------- サインイン

struct SignInScreen: View {
    @EnvironmentObject private var store: ConversationStore
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var email = ""
    @State private var password = ""

    private var canSubmit: Bool { email.contains("@") && password.count >= 6 }

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()

            VStack(spacing: RinowaDimens.gapLarge) {
                Spacer()

                VStack(spacing: RinowaDimens.gapTiny) {
                    Text("Rinowa")
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(colors.textPrimary)
                    Text("開けられるのは、あなたと相手の端末だけ。")
                        .rinowaType(RinowaType.listPreview)
                        .foregroundStyle(colors.textSecondary)
                }

                VStack(spacing: RinowaDimens.gapSmall) {
                    field("メールアドレス", text: $email, secure: false)
                    field("暗証番号", text: $password, secure: true)
                }

                Button {
                    haptics.fire(.success)
                    store.signedIn = true
                } label: {
                    Text("はじめる")
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(canSubmit ? colors.onAccent : colors.textTertiary)
                        .frame(maxWidth: .infinity, minHeight: RinowaDimens.touchTarget)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(canSubmit ? colors.accent : colors.surfaceSunken)
                        )
                }
                .buttonStyle(.plain)
                .disabled(!canSubmit)
                .animation(RinowaMotion.standard(RinowaMotion.durationQuick), value: canSubmit)

                Spacer()
            }
            .padding(RinowaDimens.gapHuge)
        }
    }

    private func field(_ label: String, text: Binding<String>, secure: Bool) -> some View {
        Group {
            if secure {
                SecureField(label, text: text)
            } else {
                TextField(label, text: text)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.emailAddress)
            }
        }
        .rinowaType(RinowaType.composer)
        .foregroundStyle(colors.textPrimary)
        .padding(.horizontal, 14)
        .frame(minHeight: RinowaDimens.touchTarget)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous).fill(colors.surface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(colors.outline, lineWidth: 1)
        )
    }
}
