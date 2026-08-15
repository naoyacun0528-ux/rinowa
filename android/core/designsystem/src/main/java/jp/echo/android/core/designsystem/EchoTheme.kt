package jp.echo.android.core.designsystem

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Echo's own theme.
 *
 * MaterialTheme is still installed underneath so that the few Material components we use
 * inherit sane colours, but every screen reads from [EchoTheme] rather than from Material
 * roles. The look is ours, not a Material template.
 */
object EchoTheme {
    val colors: EchoColors
        @Composable @ReadOnlyComposable get() = LocalEchoColors.current

    val type: EchoTypography
        @Composable @ReadOnlyComposable get() = LocalEchoTypography.current
}

object EchoDimens {
    val screenPadding = 16.dp
    val listItemPadding = 14.dp
    val bubbleHorizontalPadding = 13.dp
    val bubbleVerticalPadding = 9.dp
    val bubbleCornerLarge = 20.dp
    val bubbleCornerTail = 7.dp
    val bubbleGapSameSender = 3.dp
    val bubbleGapNewSender = 12.dp
    val bubbleMaxWidthFraction = 0.78f
    val avatarSize = 42.dp
    val touchTarget = 48.dp
    val composerMinHeight = 46.dp
    val reactionChipHeight = 26.dp
}

@Composable
fun EchoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) EchoDarkColors else EchoLightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = colors.isLight
            controller.isAppearanceLightNavigationBars = colors.isLight
        }
    }

    val materialScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.outline,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceRaised,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.outline,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalEchoColors provides colors,
        LocalEchoTypography provides EchoDefaultTypography,
        LocalContentColor provides colors.textPrimary,
    ) {
        MaterialTheme(colorScheme = materialScheme, content = content)
    }
}

/**
 * Text that defaults to Echo's colours instead of Material's.
 *
 * Having one entry point keeps stray `Color.Black` out of screens.
 */
@Composable
fun EchoText(
    text: String,
    style: TextStyle,
    color: Color = EchoTheme.colors.textPrimary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow =
        androidx.compose.ui.text.style.TextOverflow.Clip,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
annotation class EchoPreviews
