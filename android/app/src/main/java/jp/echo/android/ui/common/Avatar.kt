package jp.echo.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import jp.echo.android.core.designsystem.EchoDimens
import jp.echo.android.core.designsystem.EchoTheme

/**
 * Placeholder avatar.
 *
 * Muted on purpose: avatars sit next to every message, so a saturated one would compete
 * with the accent colour, and the accent needs to stay rare to keep meaning what it means.
 */
private val avatarColorsLight = listOf(
    Color(0xFFB9C7BE),
    Color(0xFFC9BBA8),
    Color(0xFFB6BCCD),
    Color(0xFFCDBAC0),
    Color(0xFFB4C4C8),
    Color(0xFFC6C2AE),
)

private val avatarColorsDark = listOf(
    Color(0xFF3E4A43),
    Color(0xFF4A4136),
    Color(0xFF3B4050),
    Color(0xFF4A3C42),
    Color(0xFF36464A),
    Color(0xFF474430),
)

@Composable
fun Avatar(
    title: String,
    seed: Int,
    modifier: Modifier = Modifier,
    size: Dp = EchoDimens.avatarSize,
) {
    val colors = EchoTheme.colors
    val palette = if (colors.isLight) avatarColorsLight else avatarColorsDark
    val background = palette[((seed % palette.size) + palette.size) % palette.size]

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(1),
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold,
            color = if (colors.isLight) Color(0xFF2B2A28) else Color(0xFFE6E1DA),
        )
    }
}
