package blog.nextlab.echo.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * 文字の大きさの体系。
 *
 * Prototype 0 は端末の標準フォントを使う。独自フォントはあとで決める。いま入れると、
 * レイアウトが固まる前にアプリの大きさと日中韓の文字の網羅性を払うことになる。
 */
@Immutable
data class RinowaTypography(
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

val RinowaDefaultTypography = RinowaTypography(
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

val LocalRinowaTypography = staticCompositionLocalOf { RinowaDefaultTypography }
