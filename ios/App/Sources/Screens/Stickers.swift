import SwiftUI
import UIKit

/// スタンプ。
///
/// `core/model/Sticker.kt` の `BuiltInStickers` と `ui/chat/StickerPanel.kt` の Swift 側。
///
/// **メッセージが運ぶのは id だけで、絵そのものは運ばない。** 画像のバイト列を
/// メッセージに入れると、同じ絵を送るたびに費用を払い、会話の読み込みが重くなり、
/// あとで封をするときに添付と本文が絡まる。
///
/// 8枚は Android の `assets/stickers/` から同じファイルを持ってきている。
/// **同じ id が同じ絵を指していないと、送った側と受けた側で違う絵が出る。**
enum BuiltInStickers {

    struct Entry: Identifiable, Equatable {
        let id: String
        let fileName: String
        let label: String
    }

    static let packTitle = "Rinowa"

    static let entries: [Entry] = [
        Entry(id: "st_iine", fileName: "st_iine", label: "いいね"),
        Entry(id: "st_arigato", fileName: "st_arigato", label: "ありがと"),
        Entry(id: "st_ok", fileName: "st_ok", label: "OK!"),
        Entry(id: "st_ukeru", fileName: "st_ukeru", label: "うける"),
        Entry(id: "st_gomen", fileName: "st_gomen", label: "ごめん"),
        Entry(id: "st_tasukaru", fileName: "st_tasukaru", label: "たすかる"),
        Entry(id: "st_otsukare", fileName: "st_otsukare", label: "おつかれ"),
        Entry(id: "st_matteru", fileName: "st_matteru", label: "まってる"),
    ]

    static func entry(_ id: String) -> Entry? {
        entries.first { $0.id == id }
    }

    /// 一度読んだら持っておく。会話を遡ると同じ絵が何十回も出る。
    private static var cache: [String: UIImage] = [:]

    static func image(_ id: String) -> UIImage? {
        if let hit = cache[id] { return hit }
        guard let entry = entry(id) else { return nil }
        // 束ねると平らに並ぶので、名前だけで引ける。
        guard let url = Bundle.main.url(forResource: entry.fileName, withExtension: "png"),
              let image = UIImage(contentsOfFile: url.path)
        else { return nil }
        cache[id] = image
        return image
    }
}

// ------------------------------------------------------------------ 1枚

/// 会話の中に出るスタンプ。
///
/// 絵が見つからないときは名前を出す。**空欄にしない。**
/// 空だと「送られていない」と受け取られる。
struct StickerImage: View {
    let id: String
    var size: CGFloat = 96

    @Environment(\.rinowaColors) private var colors

    var body: some View {
        Group {
            if let image = BuiltInStickers.image(id) {
                Image(uiImage: image)
                    .resizable()
                    .interpolation(.high)
                    .scaledToFit()
            } else {
                Text(BuiltInStickers.entry(id)?.label ?? "スタンプ")
                    .rinowaType(RinowaType.labelSmall)
                    .foregroundStyle(colors.textTertiary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(colors.surfaceSunken, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .frame(width: size, height: size)
    }
}

// ------------------------------------------------------------------ 引き出し

/// スタンプの引き出し。
///
/// **押したら選択ではなく即送信。** スタンプは1回の表現で、2タップにするとフォームになる。
struct StickerPanel: View {

    let onSelect: (String) -> Void

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    private static let height: CGFloat = 268

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(BuiltInStickers.packTitle)
                    .rinowaType(RinowaType.labelSmall)
                    .fontWeight(.semibold)
                    .foregroundStyle(colors.textSecondary)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 4)

            ScrollView {
                LazyVGrid(
                    columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4),
                    spacing: 8
                ) {
                    ForEach(BuiltInStickers.entries) { entry in
                        Button {
                            haptics.fire(.send)
                            onSelect(entry.id)
                        } label: {
                            StickerImage(id: entry.id, size: 68)
                                .padding(6)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
            }
        }
        .frame(height: Self.height)
        .frame(maxWidth: .infinity)
        .background(colors.surfaceSunken)
    }
}
