package jp.echo.android.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * A message body.
 *
 * [toString] deliberately omits the text. String interpolation into a log line is the
 * most common accidental route for a message body to leave the device, and this closes
 * it: `Log.d(TAG, "sending $text")` prints `MessageText(len=24)`.
 *
 * See docs/PRIVACY_PRINCIPLES.md, defence layer 3.
 */
@JvmInline
value class MessageText(val value: String) {
    val length: Int get() = value.length
    val isBlank: Boolean get() = value.isBlank()

    override fun toString(): String = "MessageText(len=$length)"
}

enum class MessageStatus { Sending, Sent, Delivered, Read, Failed }

@Immutable
data class Reaction(
    val paletteIndex: Int,
    val count: Int,
    val mine: Boolean,
) {
    val emoji: String get() = ReactionPalette.emojiAt(paletteIndex)
}

/** A fixed palette, so analytics can report an index instead of the emoji itself. */
object ReactionPalette {
    const val VERSION = 1

    val emoji: List<String> = listOf("❤️", "😂", "😮", "😢", "👍", "🔥")

    fun emojiAt(index: Int): String = emoji.getOrElse(index) { "❓" }
}

@Immutable
data class ReplyPreview(
    val messageId: Long,
    val senderName: String,
    val excerpt: MessageText,
)

@Immutable
data class Message(
    val id: Long,
    val text: MessageText,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val senderName: String,
    val status: MessageStatus = MessageStatus.Read,
    val replyTo: ReplyPreview? = null,
    val reactions: ImmutableList<Reaction> = persistentListOf(),
)

@Immutable
data class Conversation(
    val id: Long,
    val title: String,
    val preview: MessageText,
    val lastTimestampMs: Long,
    val unreadCount: Int,
    val isGroup: Boolean,
    /** Drives the placeholder avatar colour. Real avatars arrive in Prototype 1. */
    val avatarSeed: Int,
    val previewIsOutgoing: Boolean = false,
)
