package jp.echo.android.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import jp.echo.android.core.designsystem.EchoDimens
import jp.echo.android.core.designsystem.EchoMotion
import jp.echo.android.core.designsystem.EchoTheme
import jp.echo.android.model.Message
import jp.echo.android.model.MessageStatus
import jp.echo.android.ui.common.formatClock
import kotlin.math.roundToInt

private val affordanceSize = 30.dp
private val affordanceGap = 12.dp

@Composable
fun MessageRow(
    message: Message,
    isFirstOfGroup: Boolean,
    isLastOfGroup: Boolean,
    swipeOffsetPx: Float,
    swipeProgress: Float,
    pastThreshold: Boolean,
    dimmed: Boolean,
    raised: Boolean,
    modifier: Modifier = Modifier,
    bubbleModifier: Modifier = Modifier,
) {
    val colors = EchoTheme.colors
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * EchoDimens.bubbleMaxWidthFraction

    val dimAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.45f else 1f,
        animationSpec = EchoMotion.settleSpring(),
        label = "dim",
    )
    val raiseScale by animateFloatAsState(
        targetValue = if (raised) 1.04f else 1f,
        animationSpec = EchoMotion.popSpring(),
        label = "raise",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = if (isFirstOfGroup) EchoDimens.bubbleGapNewSender else EchoDimens.bubbleGapSameSender,
            )
            .alpha(dimAlpha),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            Column(
                horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
                modifier = Modifier
                    .offset { IntOffset(swipeOffsetPx.roundToInt(), 0) }
                    .scale(raiseScale),
            ) {
                Box(
                    modifier = bubbleModifier
                        .widthIn(max = maxWidth)
                        .clip(bubbleShape(message.isOutgoing, isFirstOfGroup, isLastOfGroup))
                        .background(
                            if (message.isOutgoing) colors.bubbleOutgoing else colors.bubbleIncoming,
                        ),
                ) {
                    BubbleContent(message)
                }

                if (message.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    ReactionChips(message)
                }
            }

            // Slides out from behind the bubble's leading edge as the bubble moves away.
            ReplyAffordance(
                progress = swipeProgress,
                pastThreshold = pastThreshold,
                modifier = Modifier.offset {
                    IntOffset(
                        (swipeOffsetPx - (affordanceSize + affordanceGap).toPx()).roundToInt(),
                        0,
                    )
                },
            )
        }
    }
}

@Composable
private fun BubbleContent(message: Message) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    val onBubble = if (message.isOutgoing) colors.onBubbleOutgoing else colors.onBubbleIncoming
    val meta = if (message.isOutgoing) colors.bubbleOutgoingMeta else colors.bubbleIncomingMeta

    Column(
        modifier = Modifier.padding(
            horizontal = EchoDimens.bubbleHorizontalPadding,
            vertical = EchoDimens.bubbleVerticalPadding,
        ),
    ) {
        message.replyTo?.let { reply ->
            Row(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(onBubble.copy(alpha = 0.10f))
                    .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(onBubble.copy(alpha = 0.55f)),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = reply.senderName,
                        style = type.labelSmall,
                        color = onBubble.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                    Text(
                        text = reply.excerpt.value,
                        style = type.quotedBody,
                        color = onBubble.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Text(
            text = message.text.value,
            style = type.messageBody,
            color = onBubble,
        )

        Spacer(Modifier.height(3.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(
                text = formatClock(message.timestampMs),
                style = type.messageMeta,
                color = meta,
            )
            if (message.isOutgoing) {
                Spacer(Modifier.width(4.dp))
                StatusIndicator(message.status, meta)
            }
        }
    }
}

@Composable
private fun StatusIndicator(status: MessageStatus, color: Color) {
    val colors = EchoTheme.colors
    val tint = when (status) {
        MessageStatus.Failed -> colors.danger
        MessageStatus.Read -> colors.onBubbleOutgoing
        else -> color
    }

    Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun tick(offsetX: Float) {
            val path = Path().apply {
                moveTo(offsetX + size.width * 0.06f, size.height * 0.52f)
                lineTo(offsetX + size.width * 0.26f, size.height * 0.78f)
                lineTo(offsetX + size.width * 0.62f, size.height * 0.22f)
            }
            drawPath(path, tint, style = stroke)
        }

        when (status) {
            MessageStatus.Sending -> {
                drawCircle(
                    color = tint,
                    radius = size.height * 0.34f,
                    center = Offset(size.width * 0.4f, size.height * 0.5f),
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }
            MessageStatus.Failed -> {
                val path = Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.2f)
                    lineTo(size.width * 0.64f, size.height * 0.8f)
                    moveTo(size.width * 0.64f, size.height * 0.2f)
                    lineTo(size.width * 0.16f, size.height * 0.8f)
                }
                drawPath(path, tint, style = stroke)
            }
            MessageStatus.Sent -> tick(0f)
            MessageStatus.Delivered, MessageStatus.Read -> {
                tick(0f)
                tick(size.width * 0.3f)
            }
        }
    }
}

@Composable
private fun ReactionChips(message: Message) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        message.reactions.forEach { reaction ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(EchoDimens.reactionChipHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (reaction.mine) colors.accentSoft else colors.surfaceSunken)
                    .padding(horizontal = 8.dp),
            ) {
                Text(text = reaction.emoji, style = type.labelSmall)
                if (reaction.count > 1) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = reaction.count.toString(),
                        style = type.labelSmall,
                        color = if (reaction.mine) colors.accent else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * The reply arrow.
 *
 * It fills with the accent colour exactly when the threshold haptic fires, so the finger
 * and the eye are told the same thing at the same instant.
 */
@Composable
private fun ReplyAffordance(
    progress: Float,
    pastThreshold: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = EchoTheme.colors
    val fill by animateColorAsState(
        targetValue = if (pastThreshold) colors.accent else colors.surfaceSunken,
        animationSpec = EchoMotion.commitSpring(),
        label = "affordanceFill",
    )
    val glyph by animateColorAsState(
        targetValue = if (pastThreshold) colors.onAccent else colors.textTertiary,
        animationSpec = EchoMotion.commitSpring(),
        label = "affordanceGlyph",
    )
    val pop by animateFloatAsState(
        targetValue = if (pastThreshold) 1.12f else 1f,
        animationSpec = EchoMotion.commitSpring(),
        label = "affordancePop",
    )

    Canvas(
        modifier = modifier
            .size(affordanceSize)
            .alpha(progress.coerceIn(0f, 1f))
            .scale((0.5f + progress * 0.5f).coerceIn(0.5f, 1f) * pop),
    ) {
        val w = size.width
        val h = size.height
        drawCircle(color = fill, radius = w / 2f)

        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tail = Path().apply {
            moveTo(w * 0.74f, h * 0.70f)
            quadraticTo(w * 0.74f, h * 0.38f, w * 0.36f, h * 0.38f)
        }
        drawPath(tail, glyph, style = stroke)

        val head = Path().apply {
            moveTo(w * 0.50f, h * 0.24f)
            lineTo(w * 0.32f, h * 0.38f)
            lineTo(w * 0.50f, h * 0.52f)
        }
        drawPath(head, glyph, style = stroke)
    }
}

private fun bubbleShape(
    isOutgoing: Boolean,
    isFirstOfGroup: Boolean,
    isLastOfGroup: Boolean,
): RoundedCornerShape {
    val large = EchoDimens.bubbleCornerLarge
    val tail = EchoDimens.bubbleCornerTail
    // Only the corner pointing at the sender is tightened, and only on the edges that
    // touch another bubble from the same sender. That is what makes a run of messages
    // read as one block instead of a stack of identical pills.
    return if (isOutgoing) {
        RoundedCornerShape(
            topStart = large,
            topEnd = if (isFirstOfGroup) large else tail,
            bottomEnd = if (isLastOfGroup) tail else tail,
            bottomStart = large,
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstOfGroup) large else tail,
            topEnd = large,
            bottomEnd = large,
            bottomStart = if (isLastOfGroup) tail else tail,
        )
    }
}
