package jp.echo.android.ui.chatlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.echo.android.core.analytics.AnalyticsEvent
import jp.echo.android.core.designsystem.EchoColors
import jp.echo.android.core.designsystem.EchoDimens
import jp.echo.android.core.designsystem.EchoTheme
import jp.echo.android.core.designsystem.EchoTypography
import jp.echo.android.core.designsystem.GlassSurface
import jp.echo.android.core.designsystem.preferHighFrameRate
import jp.echo.android.core.haptics.HapticToken
import jp.echo.android.core.haptics.LocalEchoHaptics
import jp.echo.android.model.Conversation
import jp.echo.android.model.SampleData
import jp.echo.android.ui.LocalAnalytics
import jp.echo.android.ui.common.Avatar
import jp.echo.android.ui.common.formatListTime

@Composable
fun ChatListScreen(
    onOpenConversation: (Conversation) -> Unit,
    onOpenHapticLab: () -> Unit,
) {
    val colors = EchoTheme.colors
    val haptics = LocalEchoHaptics.current
    val analytics = LocalAnalytics.current
    val conversations = SampleData.conversations

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ChatListTopBar(onOpenHapticLab = {
            haptics.perform(HapticToken.Navigation)
            onOpenHapticLab()
        })

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .preferHighFrameRate(listState.isScrollInProgress),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(conversations, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    onClick = {
                        haptics.perform(HapticToken.Navigation)
                        analytics.log(AnalyticsEvent.ChatOpened(conversation.unreadCount))
                        onOpenConversation(conversation)
                    },
                )
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ChatListTopBar(onOpenHapticLab: () -> Unit) {
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
                .padding(start = EchoDimens.screenPadding, end = 6.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                text = "Echo",
                style = type.screenTitle.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))

            // Prototype 0 only. The Haptic Lab is a development surface, not a product feature.
            Box(
                modifier = Modifier
                    .size(EchoDimens.touchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenHapticLab),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val stroke = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                    val w = size.width
                    val h = size.height
                    // Three ripples: the Echo mark, reused as the lab entry point.
                    val path = Path().apply {
                        moveTo(w * 0.30f, h * 0.34f)
                        quadraticTo(w * 0.14f, h * 0.5f, w * 0.30f, h * 0.66f)
                        moveTo(w * 0.52f, h * 0.20f)
                        quadraticTo(w * 0.30f, h * 0.5f, w * 0.52f, h * 0.80f)
                        moveTo(w * 0.74f, h * 0.10f)
                        quadraticTo(w * 0.46f, h * 0.5f, w * 0.74f, h * 0.90f)
                    }
                    drawPath(path, colors.textSecondary, style = stroke)
                }
            }
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
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    val unread = conversation.unreadCount > 0

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = EchoDimens.glassCardMargin,
                vertical = EchoDimens.glassCardGap / 2,
            ),
        onClick = onClick,
    ) {
        ConversationRowContent(conversation, colors, type, unread)
    }
}

@Composable
private fun ConversationRowContent(
    conversation: Conversation,
    colors: EchoColors,
    type: EchoTypography,
    unread: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = EchoDimens.listItemPadding,
                vertical = EchoDimens.listItemPadding,
            ),
    ) {
        Avatar(title = conversation.title, seed = conversation.avatarSeed)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title,
                    style = type.listName,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatListTime(conversation.lastTimestampMs),
                    style = type.messageMeta,
                    color = if (unread) colors.accent else colors.textTertiary,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (conversation.previewIsOutgoing) {
                        "自分: ${conversation.preview.value}"
                    } else {
                        conversation.preview.value
                    },
                    style = type.listPreview,
                    color = if (unread) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Fills the row so the unread badge always sits at the right edge
                    // instead of drifting with the length of the preview.
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    UnreadBadge(conversation.unreadCount)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    Box(
        modifier = Modifier
            .height(19.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.accent)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = type.labelSmall,
            color = colors.onAccent,
        )
    }
}
