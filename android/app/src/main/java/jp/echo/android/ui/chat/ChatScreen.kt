package jp.echo.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import jp.echo.android.core.analytics.AnalyticsEvent
import jp.echo.android.core.analytics.AttachmentType
import jp.echo.android.core.analytics.ConversationType
import jp.echo.android.core.designsystem.EchoDimens
import jp.echo.android.core.designsystem.EchoSwipe
import jp.echo.android.core.designsystem.EchoTheme
import jp.echo.android.core.haptics.HapticToken
import jp.echo.android.core.haptics.LocalEchoHaptics
import jp.echo.android.model.Conversation
import jp.echo.android.model.Message
import jp.echo.android.model.MessageStatus
import jp.echo.android.model.MessageText
import jp.echo.android.model.Reaction
import jp.echo.android.model.ReplyPreview
import jp.echo.android.model.SampleData
import jp.echo.android.ui.LocalAnalytics
import jp.echo.android.ui.common.Avatar
import jp.echo.android.ui.common.formatDaySeparator
import jp.echo.android.ui.common.isSameDay
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val GROUP_GAP_MS = 5 * 60_000L

@Composable
fun ChatScreen(
    conversation: Conversation,
    onBack: () -> Unit,
) {
    val colors = EchoTheme.colors
    val haptics = LocalEchoHaptics.current
    val analytics = LocalAnalytics.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val messages = remember(conversation.id) {
        mutableStateListOf<Message>().apply { addAll(SampleData.messagesFor(conversation.id)) }
    }
    var nextId by remember(conversation.id) {
        mutableLongStateOf((messages.maxOfOrNull { it.id } ?: 0L) + 1)
    }
    var composerText by remember(conversation.id) { mutableStateOf("") }
    var replyingTo by remember(conversation.id) { mutableStateOf<Message?>(null) }

    val listState = rememberLazyListState()
    val conversationType =
        if (conversation.isGroup) ConversationType.Group else ConversationType.Direct

    val thresholdPx = with(density) { EchoSwipe.ThresholdDistance.toPx() }
    val maxSwipePx = with(density) { EchoSwipe.MaxDistance.toPx() }

    // ---- reaction picker --------------------------------------------------------
    val bubbleBounds = remember { mutableMapOf<Long, Rect>() }
    var picker by remember { mutableStateOf<ReactionPickerState?>(null) }
    var hoveredIndices by remember { mutableStateOf(setOf<Int>()) }
    var pickerOpenedAt by remember { mutableLongStateOf(0L) }

    // Where the long press landed, and whether the finger has since moved far enough to
    // mean it. The finger sits inside the bubble, which is directly under the picker, so
    // proximity alone would mark a reaction as chosen the instant the picker appeared.
    // Intent has to be expressed by movement, not by where the press happened to land.
    var pickerOrigin by remember { mutableStateOf(Offset.Zero) }
    var pickerEngaged by remember { mutableStateOf(false) }

    val geometry = rememberPickerGeometry()

    /**
     * One reaction per person.
     *
     * Picking a second replaces the first rather than stacking. Note that "one" means one
     * of *mine*: other people's reactions on the same message are untouched, so their
     * counts only lose the vote that was mine.
     */
    fun applyReaction(messageId: Long, paletteIndex: Int) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val message = messages[index]
        val previous = message.reactions.firstOrNull { it.mine }

        // Withdraw whatever I had, dropping the chip if nobody else voted for it.
        var updated = message.reactions.mapNotNull { reaction ->
            when {
                !reaction.mine -> reaction
                reaction.count <= 1 -> null
                else -> reaction.copy(count = reaction.count - 1, mine = false)
            }
        }

        // Tapping the one I already had is a toggle off; anything else replaces it.
        if (previous?.paletteIndex != paletteIndex) {
            updated = if (updated.none { it.paletteIndex == paletteIndex }) {
                updated + Reaction(paletteIndex, count = 1, mine = true)
            } else {
                updated.map {
                    if (it.paletteIndex == paletteIndex) {
                        it.copy(count = it.count + 1, mine = true)
                    } else {
                        it
                    }
                }
            }
        }

        messages[index] = message.copy(reactions = updated.toPersistentList())
    }

    fun closePicker(committedIndex: Int) {
        val state = picker ?: return
        val openMs = System.currentTimeMillis() - pickerOpenedAt
        if (committedIndex >= 0) {
            haptics.perform(HapticToken.Reaction)
            applyReaction(state.messageId, committedIndex)
            analytics.log(AnalyticsEvent.ReactionSelected(committedIndex, openMs))
            analytics.log(AnalyticsEvent.ReactionAdded(committedIndex, conversationType))
        } else {
            analytics.log(AnalyticsEvent.ReactionPickerDismissed(openMs, hoveredIndices.size))
        }
        picker = null
        hoveredIndices = emptySet()
    }

    fun openPicker(message: Message, local: Offset, holdMs: Long) {
        val bounds = bubbleBounds[message.id] ?: return
        haptics.perform(HapticToken.SoftConfirm)

        pickerOrigin = Offset(bounds.left + local.x, bounds.top + local.y)
        pickerEngaged = false

        val left = ReactionPickerMetrics.leftPx(
            anchorCenterX = bounds.center.x,
            widthPx = geometry.pillWidthPx,
            screenWidthPx = geometry.screenWidthPx,
            marginPx = geometry.marginPx,
        )
        val above = bounds.top - geometry.pillHeightPx - geometry.gapPx
        // Flip below the bubble when there is no room above it.
        val top = if (above < geometry.topLimitPx) bounds.bottom + geometry.gapPx else above

        pickerOpenedAt = System.currentTimeMillis()
        hoveredIndices = emptySet()
        picker = ReactionPickerState(
            messageId = message.id,
            anchorBounds = bounds,
            pillLeftPx = left,
            pillTopPx = top,
            highlightedIndex = -1,
            alreadyReactedIndex = message.reactions.firstOrNull { it.mine }?.paletteIndex,
            latched = false,
        )
        analytics.log(AnalyticsEvent.MessageLongPressed(holdMs))
        analytics.log(AnalyticsEvent.ReactionPickerOpened)
    }

    fun trackPicker(message: Message, local: Offset) {
        val state = picker ?: return
        if (state.latched) return
        val bounds = bubbleBounds[message.id] ?: return
        val root = Offset(bounds.left + local.x, bounds.top + local.y)

        // Until the finger travels a deliberate distance, nothing is selected. A resting
        // finger — and every finger trembles — must not choose a reaction.
        if (!pickerEngaged) {
            if ((root - pickerOrigin).getDistance() < geometry.engageSlopPx) return
            pickerEngaged = true
        }

        val index = ReactionPickerMetrics.indexFor(
            pointer = root,
            pillLeftPx = state.pillLeftPx,
            pillTopPx = state.pillTopPx,
            pillHeightPx = geometry.pillHeightPx,
            itemSizePx = geometry.itemSizePx,
            itemGapPx = geometry.itemGapPx,
            innerPaddingPx = geometry.innerPaddingPx,
            toleranceAbovePx = geometry.toleranceAbovePx,
            toleranceBelowPx = geometry.toleranceBelowPx,
        )
        if (index != state.highlightedIndex) {
            if (index >= 0) {
                // Tiny and throttled: the finger is still moving.
                haptics.perform(HapticToken.Selection)
                hoveredIndices = hoveredIndices + index
            }
            picker = state.copy(highlightedIndex = index)
        }
    }

    /**
     * The finger lifted. If it was resting on a reaction, commit it; otherwise leave the
     * picker open so it can simply be tapped. Lifting must not cancel the interaction —
     * "keep holding and slide" is a shortcut, not a requirement.
     */
    fun releasePicker() {
        val state = picker ?: return
        if (state.highlightedIndex >= 0) {
            closePicker(state.highlightedIndex)
        } else {
            picker = state.copy(latched = true)
        }
    }

    fun sendMessage() {
        val body = composerText.trim()
        if (body.isEmpty()) return

        // Fired on press, on the same frame the bubble starts moving.
        haptics.perform(HapticToken.Send)

        val message = Message(
            id = nextId++,
            text = MessageText(body),
            timestampMs = System.currentTimeMillis(),
            isOutgoing = true,
            senderName = "自分",
            status = MessageStatus.Sending,
            replyTo = replyingTo?.let { ReplyPreview(it.id, it.senderName, it.text) },
            reactions = persistentListOf(),
        )
        val wasReply = replyingTo != null
        messages.add(message)

        analytics.log(
            AnalyticsEvent.MessageSent(
                // Only the length reaches analytics. The body cannot: this API has no
                // parameter that accepts text.
                characterCount = body.length,
                conversationType = conversationType,
                isReply = wasReply,
                attachmentType = AttachmentType.None,
                deliveryLatencyMs = 0,
                sendSuccess = true,
            ),
        )
        if (wasReply) analytics.log(AnalyticsEvent.ReplySent(conversationType))

        composerText = ""
        replyingTo = null

        scope.launch { listState.animateScrollToItem(0) }
        scope.launch {
            // Local-only status progression. Prototype 0 has no network, so no success
            // haptic fires here — feedback claiming delivery would be a lie.
            delay(450)
            messages.indexOfFirst { it.id == message.id }
                .takeIf { it >= 0 }
                ?.let { messages[it] = messages[it].copy(status = MessageStatus.Sent) }
            delay(700)
            messages.indexOfFirst { it.id == message.id }
                .takeIf { it >= 0 }
                ?.let { messages[it] = messages[it].copy(status = MessageStatus.Delivered) }
        }
    }

    // derivedStateOf, not remember(messages.toList()): the latter copied and compared the
    // whole thread on every recomposition, including every keystroke in the composer.
    val chatItems by remember { derivedStateOf { buildChatItems(messages) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            ChatTopBar(
                conversation = conversation,
                onBack = {
                    haptics.perform(HapticToken.Navigation)
                    onBack()
                },
            )

            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = EchoDimens.screenPadding,
                    vertical = 8.dp,
                ),
            ) {
                items(chatItems.asReversed(), key = { it.message.id }) { item ->
                    val message = item.message

                    Column {
                        item.dayHeader?.let { DaySeparator(it) }

                        MessageRow(
                            message = message,
                            isFirstOfGroup = item.isFirstOfGroup,
                            isLastOfGroup = item.isLastOfGroup,
                            dimmed = picker != null && picker?.messageId != message.id,
                            raised = picker?.messageId == message.id,
                            thresholdPx = thresholdPx,
                            maxPx = maxSwipePx,
                            callbacks = MessageRowCallbacks(
                                onReply = { replyingTo = message },
                                onSwipeStarted = {
                                    analytics.log(AnalyticsEvent.ReplySwipeStarted)
                                },
                                onThresholdReached = { ms ->
                                    analytics.log(AnalyticsEvent.ReplySwipeThresholdReached(ms))
                                },
                                onSwipeCancelled = { percent, everPast ->
                                    analytics.log(
                                        AnalyticsEvent.ReplySwipeCancelled(percent, everPast),
                                    )
                                },
                                onSwipeCompleted = { ms ->
                                    analytics.log(AnalyticsEvent.ReplySwipeCompleted(ms))
                                },
                                onLongPressStart = { local, holdMs ->
                                    openPicker(message, local, holdMs)
                                },
                                onLongPressMove = { local -> trackPicker(message, local) },
                                onLongPressFinish = { releasePicker() },
                                onBoundsChanged = { bounds -> bubbleBounds[message.id] = bounds },
                            ),
                        )
                    }
                }
            }

            Composer(
                text = composerText,
                onTextChange = { composerText = it },
                replyingTo = replyingTo,
                onCancelReply = {
                    haptics.perform(HapticToken.SoftConfirm)
                    replyingTo = null
                },
                onSend = { sendMessage() },
                onAttachmentClick = {
                    haptics.perform(HapticToken.SoftConfirm)
                    analytics.log(AnalyticsEvent.AttachmentPickerOpened)
                },
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }

        AnimatedVisibility(
            visible = picker != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            val latched = picker?.latched == true
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.scrim)
                    .then(
                        // Only tappable once latched; while the finger is down the
                        // gesture owns the interaction.
                        if (latched) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { closePicker(-1) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }

        picker?.let { state ->
            ReactionPickerOverlay(state = state, onSelect = { index -> closePicker(index) })
        }
    }
}

private class PickerGeometry(
    val screenWidthPx: Float,
    val pillWidthPx: Float,
    val pillHeightPx: Float,
    val marginPx: Float,
    val gapPx: Float,
    val itemSizePx: Float,
    val itemGapPx: Float,
    val innerPaddingPx: Float,
    val toleranceAbovePx: Float,
    val toleranceBelowPx: Float,
    val topLimitPx: Float,
    /** How far the finger must travel before a slide counts as choosing a reaction. */
    val engageSlopPx: Float,
)

@Composable
private fun rememberPickerGeometry(): PickerGeometry {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return remember(density, screenWidthDp) {
        with(density) {
            PickerGeometry(
                screenWidthPx = screenWidthDp.dp.toPx(),
                pillWidthPx = ReactionPickerMetrics.width().toPx(),
                pillHeightPx = ReactionPickerMetrics.height.toPx(),
                marginPx = ReactionPickerMetrics.screenMargin.toPx(),
                gapPx = ReactionPickerMetrics.gapAboveAnchor.toPx(),
                itemSizePx = ReactionPickerMetrics.itemSize.toPx(),
                itemGapPx = ReactionPickerMetrics.itemGap.toPx(),
                innerPaddingPx = ReactionPickerMetrics.innerPadding.toPx(),
                toleranceAbovePx = 70.dp.toPx(),
                // Was 150.dp, which reached far enough down to cover the whole bubble the
                // finger was resting on.
                toleranceBelowPx = 96.dp.toPx(),
                topLimitPx = 76.dp.toPx(),
                engageSlopPx = 18.dp.toPx(),
            )
        }
    }
}

@Composable
private fun ChatTopBar(conversation: Conversation, onBack: () -> Unit) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(EchoDimens.touchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val stroke = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                    val path = Path().apply {
                        moveTo(size.width * 0.62f, size.height * 0.16f)
                        lineTo(size.width * 0.30f, size.height * 0.5f)
                        lineTo(size.width * 0.62f, size.height * 0.84f)
                    }
                    drawPath(path, colors.textPrimary, style = stroke)
                }
            }
            Spacer(Modifier.width(2.dp))
            Avatar(title = conversation.title, seed = conversation.avatarSeed, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(text = conversation.title, style = type.screenTitle, color = colors.textPrimary)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineSoft),
        )
    }
}

@Composable
private fun DaySeparator(timestampMs: Long) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatDaySeparator(timestampMs),
            style = type.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.surfaceSunken)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

private data class ChatItem(
    val message: Message,
    val isFirstOfGroup: Boolean,
    val isLastOfGroup: Boolean,
    /** Non-null when a date separator should be drawn above this message. */
    val dayHeader: Long?,
)

private fun buildChatItems(messages: List<Message>): List<ChatItem> =
    messages.mapIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)

        val newDay = previous == null || !isSameDay(previous.timestampMs, message.timestampMs)
        val firstOfGroup = newDay ||
            previous.isOutgoing != message.isOutgoing ||
            message.timestampMs - previous.timestampMs > GROUP_GAP_MS
        val lastOfGroup = next == null ||
            next.isOutgoing != message.isOutgoing ||
            next.timestampMs - message.timestampMs > GROUP_GAP_MS ||
            !isSameDay(next.timestampMs, message.timestampMs)

        ChatItem(
            message = message,
            isFirstOfGroup = firstOfGroup,
            isLastOfGroup = lastOfGroup,
            dayHeader = if (newDay) message.timestampMs else null,
        )
    }
