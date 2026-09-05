package blog.nextlab.echo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.model.Message
import blog.nextlab.echo.ui.common.formatDaySeparator
import blog.nextlab.echo.ui.common.isSameDay

/**
 * 連続した発言をひとかたまりに見せる間隔。これより離れたら別のかたまりになり、
 * 名前と時刻がまた出る。
 */
private const val GROUP_GAP_MS = 5 * 60_000L

@Composable
internal fun DaySeparator(timestampMs: Long) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
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

internal data class ChatItem(
    val message: Message,
    val isFirstOfGroup: Boolean,
    val isLastOfGroup: Boolean,
    /** 上に日付の区切りを描くときだけ非 null。 */
    val dayHeader: Long?,
)

internal fun buildChatItems(messages: List<Message>): List<ChatItem> =
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
