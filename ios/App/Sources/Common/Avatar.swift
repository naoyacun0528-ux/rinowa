import SwiftUI

/// 写真が無いときの仮アイコン。
///
/// **わざと彩度を落としてある。** アイコンはどのメッセージの横にも並ぶので、
/// 鮮やかだとアクセント色と競合する。アクセントは稀であり続けないと意味を失う。
///
/// 写真は任意で、設定しない人のほうが多い。**文字のほうが通常の状態**であって、
/// 失敗の代用ではない。
struct Avatar: View {
    let title: String
    let seed: Int
    var size: CGFloat = RinowaDimens.avatarSize

    @Environment(\.rinowaColors) private var colors

    private static let lightPalette: [Color] = [
        Color(argb: 0xFFB9C7BE), Color(argb: 0xFFC9BBA8), Color(argb: 0xFFB6BCCD),
        Color(argb: 0xFFCDBAC0), Color(argb: 0xFFB4C4C8), Color(argb: 0xFFC6C2AE),
    ]

    private static let darkPalette: [Color] = [
        Color(argb: 0xFF3E4A43), Color(argb: 0xFF4A4136), Color(argb: 0xFF3B4050),
        Color(argb: 0xFF4A3C42), Color(argb: 0xFF36464A), Color(argb: 0xFF474430),
    ]

    var body: some View {
        let palette = colors.isLight ? Self.lightPalette : Self.darkPalette
        let background = palette[((seed % palette.count) + palette.count) % palette.count]

        Circle()
            .fill(background)
            .frame(width: size, height: size)
            .overlay(
                Text(String(title.prefix(1)))
                    .font(.system(size: size * 0.4, weight: .semibold))
                    .foregroundStyle(colors.isLight
                                     ? Color(argb: 0xFF2B2A28)
                                     : Color(argb: 0xFFE6E1DA))
            )
    }
}
