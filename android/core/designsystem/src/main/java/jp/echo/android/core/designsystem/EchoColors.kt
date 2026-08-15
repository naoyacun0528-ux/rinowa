package jp.echo.android.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Echo's palette.
 *
 * Light is the primary design; dark is derived from the same roles.
 *
 * Deliberate choices:
 *  - The background is warm paper, not neutral grey. Messaging is personal.
 *  - The accent (orange) appears rarely — mostly on things the finger is acting on.
 *    Because it is rare, an accent appearing *means* something, which is what lets the
 *    visual and the haptic line up. Flooding the screen with brand colour would break that.
 */
@Immutable
data class EchoColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val outline: Color,
    val outlineSoft: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    /** Interaction accent: swipe affordances, thresholds, unread markers, focus. */
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

    // Glass. See EchoGlass.kt — no refraction, so what sells it is the edge, the
    // not-quite-flat face, the lift off the page, and the bloom under a finger.
    val glassFaceHigh: Color,
    val glassFace: Color,
    val glassEdge: Color,
    val glassEdgeLow: Color,
    val glassGlow: Color,
    val glassShadow: Color,
    /** Laid over the blurred backdrop on the top and composer bars. */
    val barGlassTint: Color,

    val scrim: Color,
    val isLight: Boolean,
)

val EchoLightColors = EchoColors(
    // Deeper than the near-white it was, so a white pane can visibly sit above it.
    // Glass needs the page to be darker than the glass; otherwise nothing floats.
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
    // White, not the old near-background beige. Once the page was deepened so glass could
    // float, an incoming bubble at #F0EBE5 sat within a hair of the background and all
    // but vanished. It now belongs to the same family as the cards: a pane above paper.
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

val EchoDarkColors = EchoColors(
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

val LocalEchoColors = staticCompositionLocalOf { EchoLightColors }
