import SwiftUI

/// Rinowa の色。
///
/// `RinowaColors.kt` の Swift 側。**値は1つも変えていない。**
/// 片方だけ直すと、同じアプリなのに端末によって色が違う、という壊れ方になる。
///
/// 意図して決めていること:
///  - 背景は中立の灰色ではなく温かい紙。やり取りは個人的なもの。
///  - アクセント（オレンジ）はめったに出さない。ほとんどは指が触れているものにだけ。
///    稀だからこそアクセントが出ること自体が**意味を持ち**、見た目と触覚が揃う。
///    ブランド色で画面を埋めると、それが壊れる。
struct RinowaColors {
    let background: Color
    let surface: Color
    let surfaceRaised: Color
    let surfaceSunken: Color
    let outline: Color
    let outlineSoft: Color

    let textPrimary: Color
    let textSecondary: Color
    let textTertiary: Color

    /// 操作のアクセント。スワイプの目印、閾値、未読の印、焦点。
    let accent: Color
    let accentSoft: Color
    let onAccent: Color

    let bubbleOutgoing: Color
    let onBubbleOutgoing: Color
    let bubbleOutgoingMeta: Color
    let bubbleIncoming: Color
    let onBubbleIncoming: Color
    let bubbleIncomingMeta: Color

    let danger: Color
    let success: Color

    // ガラス。屈折は無いので、それらしく見せているのは縁、完全には平らでない面、
    // 紙からの浮き、指の下の光。
    let glassFaceHigh: Color
    let glassFace: Color
    let glassEdge: Color
    let glassEdgeLow: Color
    let glassGlow: Color
    let glassShadow: Color
    /// 上のバーと入力バーで、ぼかした裏側の上に重ねる色。
    let barGlassTint: Color

    let scrim: Color
    let isLight: Bool
}

/// `0xAARRGGBB` から作る。Kotlin 側の値をそのまま書き写せるように。
///
/// 手で 0..1 に割り算し直すと、必ずどこかで丸め間違える。
extension Color {
    init(argb: UInt32) {
        let a = Double((argb >> 24) & 0xFF) / 255
        let r = Double((argb >> 16) & 0xFF) / 255
        let g = Double((argb >> 8) & 0xFF) / 255
        let b = Double(argb & 0xFF) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

extension RinowaColors {

    static let light = RinowaColors(
        // 以前のほぼ白より深くしてある。白い面がその上に乗って見えるように。
        // ガラスには、ガラスより紙が暗いことが要る。でないと何も浮かない。
        background: Color(argb: 0xFFF2EDE7),
        surface: Color(argb: 0xFFFFFFFF),
        surfaceRaised: Color(argb: 0xFFFFFFFF),
        surfaceSunken: Color(argb: 0xFFF3EFEB),
        outline: Color(argb: 0xFFE3DCD4),
        outlineSoft: Color(argb: 0xFFEFE9E3),

        textPrimary: Color(argb: 0xFF17161A),
        textSecondary: Color(argb: 0xFF6B645D),
        textTertiary: Color(argb: 0xFF9A928A),

        accent: Color(argb: 0xFFD2560F),
        accentSoft: Color(argb: 0xFFFBE7DA),
        onAccent: Color(argb: 0xFFFFFFFF),

        bubbleOutgoing: Color(argb: 0xFFB4501E),
        onBubbleOutgoing: Color(argb: 0xFFFDF8F4),
        bubbleOutgoingMeta: Color(argb: 0xFFF0CDB6),
        // 背景に近いベージュではなく白。紙を深くしたら、受信の吹き出しが背景と
        // 紙一重になってほとんど消えた。いまはカードと同じ仲間で、紙の上に乗った面。
        bubbleIncoming: Color(argb: 0xFFFFFFFF),
        onBubbleIncoming: Color(argb: 0xFF17161A),
        bubbleIncomingMeta: Color(argb: 0xFF9A9187),

        danger: Color(argb: 0xFFC0341F),
        success: Color(argb: 0xFF2E7D52),

        glassFaceHigh: Color(argb: 0xFFFFFFFF),
        glassFace: Color(argb: 0xFFFAF6F1),
        glassEdge: Color(argb: 0xFFFFFFFF),
        glassEdgeLow: Color(argb: 0xFFE2DAD0),
        glassGlow: Color(argb: 0xFFD2560F),
        glassShadow: Color(argb: 0xFF4A3B2E),
        barGlassTint: Color(argb: 0xB8FFFFFF),

        scrim: Color(argb: 0x33000000),
        isLight: true
    )

    static let dark = RinowaColors(
        background: Color(argb: 0xFF121110),
        surface: Color(argb: 0xFF1B1917),
        surfaceRaised: Color(argb: 0xFF232120),
        surfaceSunken: Color(argb: 0xFF0D0C0B),
        outline: Color(argb: 0xFF35322F),
        outlineSoft: Color(argb: 0xFF272523),

        textPrimary: Color(argb: 0xFFF2EDE7),
        textSecondary: Color(argb: 0xFFA79F97),
        textTertiary: Color(argb: 0xFF756D66),

        accent: Color(argb: 0xFFFF8A4C),
        accentSoft: Color(argb: 0xFF3A2418),
        onAccent: Color(argb: 0xFF2A1408),

        bubbleOutgoing: Color(argb: 0xFFC25A24),
        onBubbleOutgoing: Color(argb: 0xFFFFF6EF),
        bubbleOutgoingMeta: Color(argb: 0xFFEFC4A8),
        bubbleIncoming: Color(argb: 0xFF262320),
        onBubbleIncoming: Color(argb: 0xFFE7E1DA),
        bubbleIncomingMeta: Color(argb: 0xFF8E867E),

        danger: Color(argb: 0xFFE96A55),
        success: Color(argb: 0xFF5DBA88),

        glassFaceHigh: Color(argb: 0xFF2C2926),
        glassFace: Color(argb: 0xFF201E1C),
        glassEdge: Color(argb: 0xFF4A433B),
        glassEdgeLow: Color(argb: 0xFF191715),
        glassGlow: Color(argb: 0xFFFF8A4C),
        glassShadow: Color(argb: 0xFF000000),
        barGlassTint: Color(argb: 0xA6141312),

        scrim: Color(argb: 0x66000000),
        isLight: false
    )
}

private struct RinowaColorsKey: EnvironmentKey {
    static let defaultValue = RinowaColors.light
}

extension EnvironmentValues {
    var rinowaColors: RinowaColors {
        get { self[RinowaColorsKey.self] }
        set { self[RinowaColorsKey.self] = newValue }
    }
}
