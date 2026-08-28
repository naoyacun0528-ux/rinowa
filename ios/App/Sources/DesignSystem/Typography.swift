import SwiftUI

/// 文字の大きさの体系。
///
/// `RinowaTypography.kt` の Swift 側。**数値は変えていない。**
///
/// 独自フォントはまだ入れない。レイアウトが固まる前に入れると、アプリの大きさと
/// 日中韓の文字の網羅性を先に払うことになる。
///
/// Android の `sp` と iOS の `pt` は同じではないが、どちらも「利用者の文字設定で
/// 拡大される単位」という役目は同じなので、数値をそのまま使う。
/// ずれるとしたら見え方であって、意味ではない。
enum RinowaType {

    /// 行の高さ。SwiftUI には `lineHeight` が無いので、既定との差を行間として足す。
    /// 素の `.font()` だけだと Android より詰まって見える。
    struct Style {
        let size: CGFloat
        let weight: Font.Weight
        let lineHeight: CGFloat
        let tracking: CGFloat

        var font: Font { .system(size: size, weight: weight) }
        /// おおよその既定行高（フォントサイズの約1.2倍）との差。
        var extraLineSpacing: CGFloat { max(0, lineHeight - size * 1.2) }
    }

    static let screenTitle = Style(size: 20, weight: .semibold, lineHeight: 26, tracking: -0.2)
    static let listName = Style(size: 16, weight: .semibold, lineHeight: 21, tracking: -0.1)
    static let listPreview = Style(size: 14.5, weight: .regular, lineHeight: 20, tracking: 0)
    static let messageBody = Style(size: 16, weight: .regular, lineHeight: 22, tracking: 0)
    static let messageMeta = Style(size: 11, weight: .medium, lineHeight: 13, tracking: 0.2)
    static let quotedBody = Style(size: 13.5, weight: .regular, lineHeight: 18, tracking: 0)
    static let label = Style(size: 14, weight: .medium, lineHeight: 19, tracking: 0)
    static let labelSmall = Style(size: 12, weight: .medium, lineHeight: 16, tracking: 0.3)
    static let composer = Style(size: 16, weight: .regular, lineHeight: 22, tracking: 0)
}

extension View {
    /// 大きさ・太さ・字間・行間をまとめて当てる。
    func rinowaType(_ style: RinowaType.Style) -> some View {
        self
            .font(style.font)
            .tracking(style.tracking)
            .lineSpacing(style.extraLineSpacing)
    }
}

/// 余白と寸法。
///
/// `RinowaDimens.kt` に当たるもの。**画面ごとに数字を決めない。**
/// 決めると、直したいときに探す場所が46か所になる。
enum RinowaDimens {
    static let avatarSize: CGFloat = 44
    static let avatarSmall: CGFloat = 32

    static let screenPadding: CGFloat = 16
    static let rowPadding: CGFloat = 14
    static let bubbleRadius: CGFloat = 18
    static let cardRadius: CGFloat = 14
    static let barHeight: CGFloat = 52

    static let gapTiny: CGFloat = 4
    static let gapSmall: CGFloat = 8
    static let gap: CGFloat = 12
    static let gapLarge: CGFloat = 20
    static let gapHuge: CGFloat = 32

    /// 指で押せる最小。これを下回るものは作らない。
    static let touchTarget: CGFloat = 44
}
