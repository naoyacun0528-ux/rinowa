package blog.nextlab.echo.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Type scale.
 *
 * Prototype 0 uses the platform font. A custom face is a later decision — shipping one
 * now would cost app size and CJK coverage before the layout has settled.
 */
@Immutable
data class EchoTypography(
    val screenTitle: TextStyle,
    val listName: TextStyle,
    val listPreview: TextStyle,
    val messageBody: TextStyle,
    val messageMeta: TextStyle,
    val quotedBody: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
    val composer: TextStyle,
)

private val trimmedLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

val EchoDefaultTypography = EchoTypography(
    screenTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        lineHeightStyle = trimmedLineHeight,
    ),
    listName = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = (-0.1).sp,
        lineHeightStyle = trimmedLineHeight,
    ),
    listPreview = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        lineHeightStyle = trimmedLineHeight,
    ),
    messageBody = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = trimmedLineHeight,
    ),
    messageMeta = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.2.sp,
        lineHeightStyle = trimmedLineHeight,
    ),
    quotedBody = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        lineHeightStyle = trimmedLineHeight,
    ),
    label = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        lineHeightStyle = trimmedLineHeight,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = trimmedLineHeight,
    ),
    composer = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        lineHeightStyle = trimmedLineHeight,
    ),
)

val LocalEchoTypography = staticCompositionLocalOf { EchoDefaultTypography }
