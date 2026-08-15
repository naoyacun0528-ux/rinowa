package jp.echo.android.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import jp.echo.android.core.designsystem.EchoDimens
import jp.echo.android.core.designsystem.EchoMotion
import jp.echo.android.core.designsystem.EchoTheme
import jp.echo.android.core.designsystem.preferHighFrameRate
import jp.echo.android.core.haptics.HapticToken
import jp.echo.android.core.haptics.LocalEchoHaptics
import jp.echo.android.model.Message
import jp.echo.android.model.MessageContent
import jp.echo.android.model.MessageStatus
import jp.echo.android.model.StickerId
import jp.echo.android.model.previewText
import jp.echo.android.ui.LocalStickers
import jp.echo.android.ui.common.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val affordanceSize = 30.dp
private val affordanceGap = 12.dp

/** Once past the threshold, the drag must fall back to this fraction of it to release. */
private const val THRESHOLD_HYSTERESIS = 0.78f

class MessageRowCallbacks(
    val onReply: () -> Unit = {},
    val onSwipeStarted: () -> Unit = {},
    val onThresholdReached: (timeToThresholdMs: Long) -> Unit = {},
    val onSwipeCancelled: (maxDragPercent: Int, everPastThreshold: Boolean) -> Unit = { _, _ -> },
    val onSwipeCompleted: (durationMs: Long) -> Unit = {},
    val onLongPressStart: (localPosition: Offset, holdMs: Long) -> Unit = { _, _ -> },
    val onLongPressMove: (localPosition: Offset) -> Unit = {},
    val onLongPressFinish: () -> Unit = {},
    val onBoundsChanged: (Rect) -> Unit = {},
    /** A reaction chip under the bubble was tapped. */
    val onReactionChipClick: () -> Unit = {},
)

/**
 * One message.
 *
 * The swipe offset lives here, per row, rather than being hoisted into the screen. When
 * it was shared, starting a swipe on one bubble while another bubble's release animation
 * was still running left two writers on the same value, and the second bubble snapped
 * back mid-drag when the first animation finished.
 */
@Composable
fun MessageRow(
    message: Message,
    isFirstOfGroup: Boolean,
    isLastOfGroup: Boolean,
    dimmed: Boolean,
    raised: Boolean,
    thresholdPx: Float,
    maxPx: Float,
    callbacks: MessageRowCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = EchoTheme.colors
    val haptics = LocalEchoHaptics.current
    val scope = rememberCoroutineScope()
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * EchoDimens.bubbleMaxWidthFraction

    // Callbacks are recreated by the caller on every recomposition, but the gesture's
    // handlers are remembered, so read them through a state holder to avoid capturing a
    // stale set for the lifetime of the row.
    val cb by rememberUpdatedState(callbacks)

    var offsetPx by remember { mutableFloatStateOf(0f) }
    var pastThreshold by remember { mutableStateOf(false) }
    var swipeStartedAt by remember { mutableLongStateOf(0L) }
    var releaseJob by remember { mutableStateOf<Job?>(null) }

    val handlers = remember(message.id) {
        MessageGestureHandlers(
            onSwipeStart = {
                // Cancel any in-flight release so the two never fight over the offset.
                releaseJob?.cancel()
                releaseJob = null
                offsetPx = 0f
                pastThreshold = false
                swipeStartedAt = System.currentTimeMillis()
                cb.onSwipeStarted()
            },
            onSwipeUpdate = { px -> offsetPx = px },
            onThresholdEnter = {
                pastThreshold = true
                // The one and only haptic during the drag.
                haptics.perform(HapticToken.Threshold)
                cb.onThresholdReached(System.currentTimeMillis() - swipeStartedAt)
            },
            onThresholdExit = {
                pastThreshold = false
                haptics.perform(HapticToken.ThresholdRelease)
            },
            onSwipeFinish = { committed, maxRatio, everPast, durationMs ->
                if (committed) {
                    cb.onReply()
                    cb.onSwipeCompleted(durationMs)
                } else {
                    cb.onSwipeCancelled((maxRatio * 100).toInt(), everPast)
                }
                releaseJob = scope.launch {
                    animate(
                        initialValue = offsetPx,
                        targetValue = 0f,
                        animationSpec = EchoMotion.commitSpring(),
                    ) { value, _ -> offsetPx = value }
                    pastThreshold = false
                    releaseJob = null
                }
            },
            onLongPressStart = { local, holdMs -> cb.onLongPressStart(local, holdMs) },
            onLongPressMove = { local -> cb.onLongPressMove(local) },
            onLongPressFinish = { cb.onLongPressFinish() },
        )
    }

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
        // Stationary: this box never moves, so pointer coordinates stay in a fixed frame.
        // Its measured size is unaffected by the offset applied to its child, because
        // Modifier.offset {} runs at placement time.
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .onGloballyPositioned { cb.onBoundsChanged(it.boundsInRoot()) }
                .messageGestures(
                    key = message.id,
                    enabled = true,
                    thresholdPx = thresholdPx,
                    releaseThresholdPx = thresholdPx * THRESHOLD_HYSTERESIS,
                    maxPx = maxPx,
                    handlers = handlers,
                ),
        ) {
            Column(
                horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
                modifier = Modifier
                    // Only while this bubble is actually moving. Asking for the panel's
                    // top rate for a row sitting still would cost battery for nothing.
                    .preferHighFrameRate(offsetPx != 0f || raised)
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .scale(raiseScale),
            ) {
                // A sticker gets no bubble. Wrapping it in one would read as a picture
                // that was sent, rather than as the expression itself.
                if (message.content is MessageContent.Sticker) {
                    StickerMessage(message, message.content.stickerId)
                } else {
                    Box(
                        modifier = Modifier
                            .widthIn(max = maxWidth)
                            .clip(bubbleShape(message.isOutgoing, isFirstOfGroup, isLastOfGroup))
                            .background(
                                if (message.isOutgoing) {
                                    colors.bubbleOutgoing
                                } else {
                                    colors.bubbleIncoming
                                },
                            ),
                    ) {
                        BubbleContent(message)
                    }
                }

                if (message.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    ReactionChips(message, onClick = { cb.onReactionChipClick() })
                }
            }

            // Slides out from behind the bubble's leading edge as the bubble moves away.
            ReplyAffordance(
                progress = if (thresholdPx > 0f) offsetPx / thresholdPx else 0f,
                pastThreshold = pastThreshold,
                modifier = Modifier.offset {
                    IntOffset(
                        (offsetPx - (affordanceSize + affordanceGap).toPx()).roundToInt(),
                        0,
                    )
                },
            )
        }
    }
}

private val stickerSize = 148.dp

@Composable
private fun StickerMessage(message: Message, stickerId: StickerId) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    val store = LocalStickers.current

    Column(
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
    ) {
        StickerImage(
            store = store,
            id = stickerId,
            modifier = Modifier.size(stickerSize),
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatClock(message.timestampMs),
                style = type.messageMeta,
                color = colors.textTertiary,
            )
            if (message.isOutgoing) {
                Spacer(Modifier.width(4.dp))
                StatusIndicator(message.status, colors.textTertiary)
            }
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
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Text(
            text = message.content.previewText().value,
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
                StatusIndicator(message.status, meta, readColor = colors.onBubbleOutgoing)
            }
        }
    }
}

/**
 * @param readColor used only for [MessageStatus.Read], which sits on the bubble fill in a
 *   text message but on the page background under a sticker.
 */
@Composable
private fun StatusIndicator(
    status: MessageStatus,
    color: Color,
    readColor: Color = color,
) {
    val colors = EchoTheme.colors
    val tint = when (status) {
        MessageStatus.Failed -> colors.danger
        MessageStatus.Read -> readColor
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

/**
 * Reactions already on the message.
 *
 * Tapping one reopens the picker. Long-pressing the bubble is the way in the first time,
 * but once a reaction is visible it is the obvious thing to press to change it, and it
 * being inert would read as broken.
 */
@Composable
private fun ReactionChips(message: Message, onClick: () -> Unit) {
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
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
    // Only the corner pointing at the sender is tightened, and only on edges that touch
    // another bubble from the same sender. That is what makes a run of messages read as
    // one block instead of a stack of identical pills.
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
