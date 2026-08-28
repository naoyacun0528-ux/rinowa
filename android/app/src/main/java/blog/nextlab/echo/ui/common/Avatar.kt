package blog.nextlab.echo.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaTheme

/**
 * 写真が無いときの仮アイコン。
 *
 * わざと彩度を落としてある。アイコンはどのメッセージの横にも並ぶので、鮮やかだと
 * アクセント色と競合する。アクセントは稀であり続けないと意味を失う。
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

/**
 * @param photo 本人の写真。端末が持っていれば。無ければ頭文字に落ちる。
 *   プロフィール写真は任意で、設定しない人のほうが多いので、文字のほうが
 *   通常の状態であって、失敗の代用ではない。
 */
@Composable
fun Avatar(
    title: String,
    seed: Int,
    modifier: Modifier = Modifier,
    size: Dp = RinowaDimens.avatarSize,
    photo: ImageBitmap? = null,
) {
    val colors = RinowaTheme.colors
    val palette = if (colors.isLight) avatarColorsLight else avatarColorsDark
    val background = palette[((seed % palette.size) + palette.size) % palette.size]

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            return@Box
        }
        Text(
            text = title.take(1),
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.SemiBold,
            color = if (colors.isLight) Color(0xFF2B2A28) else Color(0xFFE6E1DA),
        )
    }
}
