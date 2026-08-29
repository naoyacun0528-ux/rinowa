import SwiftUI

/// 気づいたことを送る、見る。
///
/// `ui/feedback/FeedbackScreen.kt` の Swift 側。文言はそのまま写した。
///
/// **ここに書いたものはメッセージ本文ではない。** 開発者が読む前提で書かれた文で、
/// 会話とは扱いが別。その区別を画面に出しておかないと、同じアプリの中に
/// 「読まれないもの」と「読まれるもの」が並んでいることが伝わらない。
struct FeedbackScreen: View {

    let onBack: () -> Void

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var writing = false
    @State private var category: Category = .bug
    @State private var summary = ""
    @State private var detail = ""
    @State private var busy = false
    @State private var mine: [Item] = []

    enum Category: String, CaseIterable, Identifiable {
        case bug, feature, ui, haptic, other
        var id: String { rawValue }
        var label: String {
            switch self {
            case .bug:     return "不具合"
            case .feature: return "ほしい機能"
            case .ui:      return "画面・操作"
            case .haptic:  return "触覚"
            case .other:   return "その他"
            }
        }
    }

    struct Item: Identifiable, Equatable {
        let id = UUID()
        let category: Category
        let summary: String
    }

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "フィードバック", onBack: onBack) {
                Button {
                    haptics.fire(.navigation)
                    withAnimation(RinowaMotion.settle) { writing.toggle() }
                } label: {
                    Text(writing ? "やめる" : "書く")
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(colors.accent)
                }
                .buttonStyle(.plain)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if writing { form } else { list }
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(colors.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar)
    }

    // ---------------------------------------------------------------- 書く

    private var form: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 種類は先に選ばせる。あとから聞くと、書いたあとで
            // 「これはどれだろう」と考え直すことになる。
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 96), spacing: 8)],
                alignment: .leading,
                spacing: 8
            ) {
                ForEach(Category.allCases) { c in
                    let on = c == category
                    Text(c.label)
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(on ? colors.onAccent : colors.textSecondary)
                        .padding(.horizontal, 14)
                        .frame(height: 38)
                        .background(
                            Capsule().fill(on ? colors.accent : colors.surfaceSunken)
                        )
                        .contentShape(Capsule())
                        .onTapGesture {
                            haptics.fire(.selection)
                            category = c
                        }
                }
            }
            .animation(RinowaMotion.pop, value: category)

            Spacer().frame(height: 20)
            RinowaField(
                value: Binding(get: { summary }, set: { summary = String($0.prefix(80)) }),
                placeholder: "ひとことで言うと",
                enabled: !busy,
                capitalization: .sentences
            )

            Spacer().frame(height: 12)
            RinowaField(
                value: Binding(get: { detail }, set: { detail = String($0.prefix(600)) }),
                placeholder: "くわしく（任意）",
                enabled: !busy,
                capitalization: .sentences,
                submitLabel: .return
            )

            Spacer().frame(height: 20)
            PrimaryButton(
                enabled: !busy && !summary.trimmingCharacters(in: .whitespaces).isEmpty,
                action: send
            ) { tint in
                PrimaryButtonLabel(text: busy ? "送っています" : "送る", color: tint)
            }

            Spacer().frame(height: 14)
            Text("ここに書いた内容は開発者が読みます。メッセージの本文とは別の扱いです。")
                .rinowaType(RinowaType.labelSmall)
                .foregroundStyle(colors.textTertiary)
            Spacer().frame(height: 24)
        }
    }

    // ---------------------------------------------------------------- 見る

    private var list: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("あなたの投稿 ・ 長押しで取り下げ")
                .rinowaType(RinowaType.labelSmall)
                .foregroundStyle(colors.textTertiary)
            Spacer().frame(height: 12)

            if mine.isEmpty {
                Text("まだ何も送っていません")
                    .rinowaType(RinowaType.listPreview)
                    .foregroundStyle(colors.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 40)
            } else {
                VStack(spacing: RinowaDimens.glassCardGap) {
                    ForEach(mine) { item in
                        HStack(alignment: .top, spacing: 10) {
                            Text(item.category.label)
                                .rinowaType(RinowaType.labelSmall)
                                .foregroundStyle(colors.accent)
                            Text(item.summary)
                                .rinowaType(RinowaType.listPreview)
                                .foregroundStyle(colors.textPrimary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(RinowaDimens.rowPadding)
                        .frame(maxWidth: .infinity)
                        .glassFace()
                        .onLongPressGesture {
                            haptics.fire(.destructive)
                            withAnimation(RinowaMotion.settle) {
                                mine.removeAll { $0.id == item.id }
                            }
                        }
                    }
                }
            }
            Spacer().frame(height: 24)
        }
    }

    private func send() {
        busy = true
        let entry = Item(category: category, summary: summary.trimmingCharacters(in: .whitespaces))
        // 送る先は iOS にまだ無い。手元の一覧にだけ積む。
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            busy = false
            haptics.fire(.success)
            mine.insert(entry, at: 0)
            summary = ""
            detail = ""
            withAnimation(RinowaMotion.settle) { writing = false }
        }
    }
}
