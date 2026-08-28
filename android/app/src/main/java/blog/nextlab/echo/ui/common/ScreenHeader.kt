package blog.nextlab.echo.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics

/**
 * 画面の上の、戻る矢印と題名の行。
 *
 * 8つの画面が同じ行を1つずつ持っていて、矢印の描画も8回書いてあった。位置が数 dp
 * ずれていても誰も気づかないまま増えるので、1つにまとめる。
 *
 * 触覚はここで鳴らす。戻るは全画面で同じ手応えでなければならない。キーボードを
 * 閉じるなど画面ごとの後始末は [onBack] の中でする。
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val haptics = LocalRinowaHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = RinowaDimens.screenPadding, top = 6.dp, bottom = 6.dp),
    ) {
        BackArrow {
            haptics.perform(HapticToken.Navigation)
            onBack()
        }
        Text(
            text = title,
            style = RinowaTheme.type.screenTitle,
            color = RinowaTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/**
 * 戻る矢印。
 *
 * 見た目は 20dp だが、押せるのは [RinowaDimens.touchTarget]。左上は親指から遠いので、
 * 見えている絵より広く取らないと外す。
 */
@Composable
fun BackArrow(onClick: () -> Unit) {
    val colors = RinowaTheme.colors
    Box(
        modifier = Modifier
            .size(RinowaDimens.touchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.62f, h * 0.18f)
                lineTo(w * 0.30f, h * 0.5f)
                lineTo(w * 0.62f, h * 0.82f)
            }
            drawPath(path, colors.textPrimary, style = stroke)
        }
    }
}
