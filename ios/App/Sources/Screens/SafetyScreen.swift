import SwiftUI
import RinowaCore

/// 鍵を確かめる画面。
///
/// `ui/security/SafetyScreen.kt` の Swift 側。
///
/// **「信じてください」とは言わない画面。** 指紋を並べて見せ、相手の端末を隠さず、
/// 鍵が入れ替わったら黙って続けない。
///
/// ここに書いてある約束は `docs/RINOWA_SIGIL.md` の表がすべてで、
/// **書いていないものは守られていない。** それも画面に出す。
struct SafetyScreen: View {

    let title: String
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics
    @State private var verified = false

    /// 実物の鍵はまだ繋いでいない。**形だけ先に正しくしてある。**
    /// 4文字ずつ区切るのは、目で読み合わせるため。続けて書くと必ず読み飛ばす。
    private let fingerprint = "K7QM 3XPD 9WRT 5NBH 2FGC 8LVA 4JYS 6EZU"

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: RinowaDimens.gapLarge) {

                    section("この会話の指紋") {
                        Text(fingerprint)
                            .font(.system(size: 15, weight: .medium, design: .monospaced))
                            .foregroundStyle(colors.textPrimary)
                            .lineSpacing(6)
                        Text("相手の画面と見比べて、同じなら本人です。違っていたら、途中に誰かがいます。")
                            .rinowaType(RinowaType.listPreview)
                            .foregroundStyle(colors.textSecondary)
                    }

                    section("\(title) の端末") {
                        DeviceRow(name: "iPhone", added: "3日前", current: true)
                        DeviceRow(name: "Pixel 10", added: "2週間前", current: false)
                        Text("見覚えのないものがあれば、そこから読まれている可能性があります。")
                            .rinowaType(RinowaType.listPreview)
                            .foregroundStyle(colors.textSecondary)
                    }

                    Toggle(isOn: $verified) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("確かめた")
                                .rinowaType(RinowaType.label)
                                .foregroundStyle(colors.textPrimary)
                            Text("この端末の中だけの印。いつでも取り消せます。")
                                .rinowaType(RinowaType.labelSmall)
                                .foregroundStyle(colors.textTertiary)
                        }
                    }
                    .tint(colors.accent)
                    .onChange(of: verified) { _ in haptics.fire(.softConfirm) }

                    section("守っていないもの") {
                        // **できないことを先に言う。** できることを大きく言うより、
                        // 「守る」という言葉が重くなる。
                        ForEach(["会話の題名", "誰と誰が、いつ話したか", "既読の位置"], id: \.self) { item in
                            HStack(spacing: RinowaDimens.gapSmall) {
                                Text("—").foregroundStyle(colors.textTertiary)
                                Text(item)
                                    .rinowaType(RinowaType.listPreview)
                                    .foregroundStyle(colors.textSecondary)
                            }
                        }
                        Text("これらはサーバーから見えます。中身を守る仕組みであって、関係を隠す仕組みではありません。")
                            .rinowaType(RinowaType.labelSmall)
                            .foregroundStyle(colors.textTertiary)
                    }
                }
                .padding(RinowaDimens.screenPadding)
            }
        }
        .navigationTitle("安全")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func section<Content: View>(_ heading: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: RinowaDimens.gapSmall) {
            Text(heading)
                .rinowaType(RinowaType.labelSmall)
                .foregroundStyle(colors.textTertiary)
                .textCase(.uppercase)
            content()
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

private struct DeviceRow: View {
    let name: String
    let added: String
    let current: Bool
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .rinowaType(RinowaType.label)
                    .foregroundStyle(colors.textPrimary)
                Text("登録 \(added)")
                    .rinowaType(RinowaType.labelSmall)
                    .foregroundStyle(colors.textTertiary)
            }
            Spacer()
            if current {
                Text("この端末")
                    .rinowaType(RinowaType.labelSmall)
                    .foregroundStyle(colors.accent)
            }
        }
        .padding(.vertical, 4)
    }
}
