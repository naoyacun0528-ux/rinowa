package jp.echo.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.echo.android.core.designsystem.EchoDimens
import jp.echo.android.core.designsystem.EchoMotion
import jp.echo.android.core.designsystem.EchoTheme
import jp.echo.android.model.Message

@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    replyingTo: Message?,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
    onAttachmentClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    val canSend = text.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background),
    ) {
        AnimatedVisibility(
            visible = replyingTo != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            ReplyBanner(replyingTo, onCancelReply)
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(
                start = 10.dp,
                end = 10.dp,
                top = 6.dp,
                bottom = 8.dp,
            ),
        ) {
            IconCircleButton(
                onClick = onAttachmentClick,
                contentDescription = "添付",
            ) { stroke, tint ->
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.28f)
                    lineTo(w * 0.5f, h * 0.72f)
                    moveTo(w * 0.28f, h * 0.5f)
                    lineTo(w * 0.72f, h * 0.5f)
                }
                drawPath(path, tint, style = stroke)
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = EchoDimens.composerMinHeight, max = 140.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.outlineSoft, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = "メッセージ",
                        style = type.composer,
                        color = colors.textTertiary,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = type.composer.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.width(8.dp))

            SendButton(enabled = canSend, onClick = onSend)
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = EchoTheme.colors
    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.82f,
        animationSpec = EchoMotion.commitSpring(),
        label = "sendScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.35f,
        animationSpec = EchoMotion.commitSpring(),
        label = "sendAlpha",
    )

    Box(
        modifier = Modifier
            .size(EchoDimens.composerMinHeight)
            .scale(scale)
            .clip(CircleShape)
            .background(colors.accent.copy(alpha = alpha))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = Stroke(
                width = 2.1.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val w = size.width
            val h = size.height
            // Upward arrow: sending is "away from me", not "to the right".
            val path = Path().apply {
                moveTo(w * 0.5f, h * 0.80f)
                lineTo(w * 0.5f, h * 0.20f)
                moveTo(w * 0.22f, h * 0.47f)
                lineTo(w * 0.5f, h * 0.19f)
                lineTo(w * 0.78f, h * 0.47f)
            }
            drawPath(path, colors.onAccent, style = stroke)
        }
    }
}

@Composable
private fun ReplyBanner(replyingTo: Message?, onCancel: () -> Unit) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    val message = replyingTo ?: return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceSunken)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(
            Modifier
                .width(2.5.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "${message.senderName} に返信",
                style = type.labelSmall,
                color = colors.accent,
                maxLines = 1,
            )
            Text(
                text = message.text.value,
                style = type.quotedBody,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconCircleButton(onClick = onCancel, contentDescription = "返信をやめる") { stroke, tint ->
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.32f, h * 0.32f)
                lineTo(w * 0.68f, h * 0.68f)
                moveTo(w * 0.68f, h * 0.32f)
                lineTo(w * 0.32f, h * 0.68f)
            }
            drawPath(path, tint, style = stroke)
        }
    }
}

/**
 * A tappable glyph drawn with Canvas.
 *
 * Prototype 0 draws its own icons rather than pulling in the Material icon set, which
 * would bring a Material look with it.
 */
@Composable
private fun IconCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.(
        stroke: Stroke,
        tint: androidx.compose.ui.graphics.Color,
    ) -> Unit,
) {
    val colors = EchoTheme.colors
    Box(
        modifier = Modifier
            .size(EchoDimens.composerMinHeight)
            .defaultMinSize(EchoDimens.touchTarget, EchoDimens.touchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            draw(
                Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                colors.textSecondary,
            )
        }
    }
}
