package blog.nextlab.echo.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Rinowa の色。
 *
 * 明るいほうが主で、暗いほうは同じ役割から導く。
 *
 * 意図して決めていること:
 *  - 背景は中立の灰色ではなく温かい紙。やり取りは個人的なもの。
 *  - アクセント（オレンジ）はめったに出さない。ほとんどは指が触れているものにだけ。
 *    稀だからこそアクセントが出ること自体が*意味を持ち*、見た目と触覚が揃う。
 *    ブランド色で画面を埋めると、それが壊れる。
 */
@Immutable
data class RinowaColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val outline: Color,
    val outlineSoft: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    /** 操作のアクセント。スワイプの目印、閾値、未読の印、焦点。 */
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,

    val bubbleOutgoing: Color,
    val onBubbleOutgoing: Color,
    val bubbleOutgoingMeta: Color,
    val bubbleIncoming: Color,
    val onBubbleIncoming: Color,
    val bubbleIncomingMeta: Color,

    val danger: Color,
    val success: Color,

    // ガラス。RinowaGlass.kt を参照。屈折は無いので、それらしく見せているのは縁、
    // 完全には平らでない面、紙からの浮き、指の下の光。
    val glassFaceHigh: Color,
    val glassFace: Color,
    val glassEdge: Color,
    val glassEdgeLow: Color,
    val glassGlow: Color,
    val glassShadow: Color,
    /** 上のバーと入力バーで、ぼかした裏側の上に重ねる色。 */
    val barGlassTint: Color,

    val scrim: Color,
    val isLight: Boolean,
)

val RinowaLightColors = RinowaColors(
    // 以前のほぼ白より深くしてある。白い面がその上に乗って見えるように。
    // ガラスには、ガラスより紙が暗いことが要る。でないと何も浮かない。
    background = Color(0xFFF2EDE7),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFF3EFEB),
    outline = Color(0xFFE3DCD4),
    outlineSoft = Color(0xFFEFE9E3),

    textPrimary = Color(0xFF17161A),
    textSecondary = Color(0xFF6B645D),
    textTertiary = Color(0xFF9A928A),

    accent = Color(0xFFD2560F),
    accentSoft = Color(0xFFFBE7DA),
    onAccent = Color(0xFFFFFFFF),

    bubbleOutgoing = Color(0xFFB4501E),
    onBubbleOutgoing = Color(0xFFFDF8F4),
    bubbleOutgoingMeta = Color(0xFFF0CDB6),
    // 以前の背景に近いベージュではなく白。ガラスを浮かせるために紙を深くしたら、
    // #F0EBE5 の受信の吹き出しが背景と紙一重になってほとんど消えた。いまはカードと
    // 同じ仲間で、紙の上に乗った面。
    bubbleIncoming = Color(0xFFFFFFFF),
    onBubbleIncoming = Color(0xFF17161A),
    bubbleIncomingMeta = Color(0xFF9A9187),

    danger = Color(0xFFC0341F),
    success = Color(0xFF2E7D52),

    glassFaceHigh = Color(0xFFFFFFFF),
    glassFace = Color(0xFFFAF6F1),
    glassEdge = Color(0xFFFFFFFF),
    glassEdgeLow = Color(0xFFE2DAD0),
    glassGlow = Color(0xFFD2560F),
    glassShadow = Color(0xFF4A3B2E),
    barGlassTint = Color(0xB8FFFFFF),

    scrim = Color(0x33000000),
    isLight = true,
)

val RinowaDarkColors = RinowaColors(
    background = Color(0xFF121110),
    surface = Color(0xFF1B1917),
    surfaceRaised = Color(0xFF232120),
    surfaceSunken = Color(0xFF0D0C0B),
    outline = Color(0xFF35322F),
    outlineSoft = Color(0xFF272523),

    textPrimary = Color(0xFFF2EDE7),
    textSecondary = Color(0xFFA79F97),
    textTertiary = Color(0xFF756D66),

    accent = Color(0xFFFF8A4C),
    accentSoft = Color(0xFF3A2418),
    onAccent = Color(0xFF2A1408),

    bubbleOutgoing = Color(0xFFC25A24),
    onBubbleOutgoing = Color(0xFFFFF6EF),
    bubbleOutgoingMeta = Color(0xFFEFC4A8),
    bubbleIncoming = Color(0xFF262320),
    onBubbleIncoming = Color(0xFFE7E1DA),
    bubbleIncomingMeta = Color(0xFF8E867E),

    danger = Color(0xFFE96A55),
    success = Color(0xFF5DBA88),

    glassFaceHigh = Color(0xFF2C2926),
    glassFace = Color(0xFF201E1C),
    glassEdge = Color(0xFF4A433B),
    glassEdgeLow = Color(0xFF191715),
    glassGlow = Color(0xFFFF8A4C),
    glassShadow = Color(0xFF000000),
    barGlassTint = Color(0xA6141312),

    scrim = Color(0x66000000),
    isLight = false,
)

val LocalRinowaColors = staticCompositionLocalOf { RinowaLightColors }
