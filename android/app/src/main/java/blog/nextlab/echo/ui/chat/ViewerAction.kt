package blog.nextlab.echo.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** [ViewerAction] が描く絵柄。 */
internal enum class ViewerIcon { Delete, Share, Save }

/**
 * 写真ビューアのボタン。
 *
 * 絵柄の下に文字は置かない（文字のほうが目立ってメニューに見えるため）。label は
 * TalkBack 用に残す。円は白の18%。12%だと白いカーテンや雪の上で消えた。
 *
 * 56dp。文字を消したぶん、円だけでボタンの大きさを持たせる必要がある。
 */
@Composable
internal fun ViewerAction(
    label: String,
    icon: ViewerIcon,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            // 黒い円は写真の上下の黒帯で消える。淡い面＋縁＋影なら黒の上でも白の上でも見える。
            .shadow(8.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .background(Color.White.copy(alpha = 0.18f)),
    ) {
        Canvas(Modifier.size(24.dp)) { drawViewerIcon(icon, tint) }
    }
}

/**
 * 3つの絵柄を自前で描く。
 *
 * この程度の形のために Material のアイコンを取り込むと線の太さが揃わない。
 * 座標はキャンバスに対する比率なので、どの大きさでも同じ形になる。
 */
private fun DrawScope.drawViewerIcon(icon: ViewerIcon, tint: Color) {
    val w = size.width
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
        color = tint,
        start = Offset(w * x1, w * y1),
        end = Offset(w * x2, w * y2),
        strokeWidth = stroke.width,
        cap = StrokeCap.Round,
    )

    when (icon) {
        ViewerIcon.Delete -> {
            // 蓋、本体、縦線2本。縦線がないとゴミ箱に見えない。
            line(0.12f, 0.26f, 0.88f, 0.26f)
            line(0.38f, 0.26f, 0.42f, 0.14f)
            line(0.62f, 0.26f, 0.58f, 0.14f)
            line(0.42f, 0.14f, 0.58f, 0.14f)
            drawPath(
                path = Path().apply {
                    moveTo(w * 0.22f, w * 0.26f)
                    lineTo(w * 0.28f, w * 0.88f)
                    lineTo(w * 0.72f, w * 0.88f)
                    lineTo(w * 0.78f, w * 0.26f)
                },
                color = tint,
                style = stroke,
            )
            line(0.42f, 0.40f, 0.44f, 0.74f)
            line(0.58f, 0.40f, 0.56f, 0.74f)
        }

        ViewerIcon.Share -> {
            // 点3つを線で結ぶ形。以前は保存と同じ箱で矢印の向きだけ違い、並ぶと読まないと
            // 区別できなかった。形そのものを変える。
            val radius = w * 0.115f
            line(0.344f, 0.450f, 0.676f, 0.290f)
            line(0.344f, 0.550f, 0.676f, 0.710f)
            drawCircle(color = tint, radius = radius, center = Offset(w * 0.24f, w * 0.50f), style = stroke)
            drawCircle(color = tint, radius = radius, center = Offset(w * 0.78f, w * 0.24f), style = stroke)
            drawCircle(color = tint, radius = radius, center = Offset(w * 0.78f, w * 0.76f), style = stroke)
        }

        ViewerIcon.Save -> {
            // 箱に矢印が入る形。ほかに箱＋矢印の絵柄はもう無い。
            drawPath(
                path = Path().apply {
                    moveTo(w * 0.28f, w * 0.42f)
                    lineTo(w * 0.14f, w * 0.42f)
                    lineTo(w * 0.14f, w * 0.90f)
                    lineTo(w * 0.86f, w * 0.90f)
                    lineTo(w * 0.86f, w * 0.42f)
                    lineTo(w * 0.72f, w * 0.42f)
                },
                color = tint,
                style = stroke,
            )
            line(0.50f, 0.10f, 0.50f, 0.62f)
            line(0.50f, 0.62f, 0.32f, 0.44f)
            line(0.50f, 0.62f, 0.68f, 0.44f)
        }
    }
}
