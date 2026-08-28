package blog.nextlab.echo.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.model.CallOutcome
import blog.nextlab.echo.core.model.MessageContent
import blog.nextlab.echo.ui.common.formatCallDuration
import blog.nextlab.echo.ui.common.formatClock

/**
 * 通話の記録。
 *
 * 中央ではなくメッセージと同じ左右に置く。誰がかけて誰が受けたかが記録のほとんどだから。
 * Firestore にあるのは1件だけで、読む側によって文言が変わる（同じ通話が、かけた側には
 * 「応答なし」、受けた側には「不在着信」）。
 */
@Composable
internal fun CallNotice(
    call: MessageContent.Call,
    isOutgoing: Boolean,
    timestampMs: Long,
    modifier: Modifier = Modifier,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    // 吹き出しと同じ色にする。灰色の丸のままだと、自分がかけたのか相手からかかって
    // きたのかが文面を読むまで分からない。左右と色で、読む前に分かるようにする。
    val face = if (isOutgoing) colors.bubbleOutgoing else colors.bubbleIncoming
    val ink = if (isOutgoing) colors.onBubbleOutgoing else colors.onBubbleIncoming
    val meta = if (isOutgoing) colors.bubbleOutgoingMeta else colors.bubbleIncomingMeta

    // 不在着信だけ赤。まだ用がある可能性があるのはこれだけなので、色分けの上に足す。
    val missed = call.outcome != CallOutcome.Completed && !isOutgoing
    val accent = if (missed) Color(0xFFE5484D) else ink

    val label = when {
        call.outcome == CallOutcome.Completed -> "通話時間 " + formatCallDuration(call.seconds)
        isOutgoing -> when (call.outcome) {
            CallOutcome.Declined -> "相手が応答しませんでした"
            CallOutcome.Failed -> "つながりませんでした"
            else -> "応答なし"
        }
        else -> when (call.outcome) {
            CallOutcome.Declined -> "通話を拒否しました"
            CallOutcome.Failed -> "つながりませんでした"
            else -> "不在着信"
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(face)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Canvas(Modifier.size(16.dp)) { drawCallGlyph(call.video, accent) }
            Spacer(Modifier.width(9.dp))
            Text(text = label, style = type.labelSmall, color = accent)
            Spacer(Modifier.width(9.dp))
            Text(text = formatClock(timestampMs), style = type.labelSmall, color = meta)
        }
    }
}

/** 音声は受話器、ビデオはカメラ。通話画面と同じ形。 */
private fun DrawScope.drawCallGlyph(video: Boolean, tint: Color) {
    val w = size.width
    if (video) {
        drawPath(
            path = Path().apply {
                moveTo(w * 0.06f, w * 0.26f)
                lineTo(w * 0.62f, w * 0.26f)
                lineTo(w * 0.62f, w * 0.74f)
                lineTo(w * 0.06f, w * 0.74f)
                close()
            },
            color = tint,
        )
        drawPath(
            path = Path().apply {
                moveTo(w * 0.70f, w * 0.50f)
                lineTo(w * 0.96f, w * 0.30f)
                lineTo(w * 0.96f, w * 0.70f)
                close()
            },
            color = tint,
        )
    } else {
        // 受話器。16dp では塗りつぶすと団子になるので、1本の線として描く。
        drawPath(
            path = Path().apply {
                moveTo(w * 0.10f, w * 0.20f)
                cubicTo(w * 0.10f, w * 0.72f, w * 0.28f, w * 0.90f, w * 0.80f, w * 0.90f)
                lineTo(w * 0.80f, w * 0.66f)
                lineTo(w * 0.58f, w * 0.58f)
                lineTo(w * 0.42f, w * 0.42f)
                lineTo(w * 0.34f, w * 0.20f)
                close()
            },
            color = tint,
        )
    }
}
