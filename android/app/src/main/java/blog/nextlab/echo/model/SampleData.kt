package blog.nextlab.echo.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * Local-only data for Prototype 0.
 *
 * Prototype 0 has no network by design — the only question it needs to answer is whether
 * the app feels good to touch. See docs/ROADMAP.md.
 */
object SampleData {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    private fun text(value: String): MessageContent = MessageContent.Text(MessageText(value))

    private fun sticker(id: String): MessageContent = MessageContent.Sticker(StickerId(id))

    val conversations: List<Conversation> = listOf(
        Conversation(
            id = 1,
            title = "ゆうた",
            preview = MessageText("そっちの方が絶対いいと思う"),
            lastTimestampMs = now() - 4 * MINUTE,
            unreadCount = 2,
            isGroup = false,
            avatarSeed = 0,
        ),
        Conversation(
            id = 2,
            title = "テスト部屋",
            preview = MessageText("スワイプの手応え、かなり変わった"),
            lastTimestampMs = now() - 38 * MINUTE,
            unreadCount = 0,
            isGroup = true,
            avatarSeed = 1,
            previewIsOutgoing = true,
        ),
        Conversation(
            id = 3,
            title = "はると",
            preview = MessageText("あとで送るね"),
            lastTimestampMs = now() - 3 * HOUR,
            unreadCount = 0,
            isGroup = false,
            avatarSeed = 2,
        ),
        Conversation(
            id = 4,
            title = "family",
            preview = MessageText("写真ありがとう！"),
            lastTimestampMs = now() - 9 * HOUR,
            unreadCount = 5,
            isGroup = true,
            avatarSeed = 3,
        ),
        Conversation(
            id = 5,
            title = "みお",
            preview = MessageText("スタンプ"),
            lastTimestampMs = now() - DAY - 2 * HOUR,
            unreadCount = 0,
            isGroup = false,
            avatarSeed = 4,
            previewIsOutgoing = true,
        ),
        Conversation(
            id = 6,
            title = "そうた",
            preview = MessageText("これ長いメッセージのプレビューがどう省略されるかを確認するためのやつ。折り返さずに一行で切れてほしい"),
            lastTimestampMs = now() - 2 * DAY,
            unreadCount = 0,
            isGroup = false,
            avatarSeed = 5,
        ),
    )

    fun messagesFor(conversationId: Long): List<Message> = when (conversationId) {
        1L -> conversationOne()
        else -> genericThread(conversationId)
    }

    private fun conversationOne(): List<Message> {
        var id = 100L
        val base = now() - 3 * HOUR
        fun next() = id++

        return listOf(
            Message(next(), text("おはよう"), base, false, "ゆうた"),
            Message(next(), text("おはよ"), base + 2 * MINUTE, true, "自分"),
            Message(
                next(),
                text("昨日言ってたやつ作ってみた。触ってみてほしい"),
                base + 4 * MINUTE,
                true,
                "自分",
                reactions = persistentListOf(Reaction(paletteIndex = 5, count = 1, mine = false)),
            ),
            Message(next(), text("え、もうできたの？"), base + 6 * MINUTE, false, "ゆうた"),
            Message(next(), text("まだ触り心地の部分だけ"), base + 7 * MINUTE, true, "自分"),
            Message(
                next(),
                text("返信のスワイプ、途中でやめられるようにしてある"),
                base + 8 * MINUTE,
                true,
                "自分",
            ),
            Message(
                next(),
                text("超えた瞬間だけ振動するのめっちゃいい"),
                base + 22 * MINUTE,
                false,
                "ゆうた",
                replyTo = ReplyPreview(105, "自分", MessageText("返信のスワイプ、途中でやめられるようにしてある")),
                reactions = persistentListOf(Reaction(paletteIndex = 0, count = 1, mine = true)),
            ),
            Message(next(), sticker("st_iine"), base + 23 * MINUTE, false, "ゆうた"),
            Message(next(), text("それが一番作り込んだところ"), base + 24 * MINUTE, true, "自分"),
            Message(
                next(),
                text("長押ししてリアクションも付けられる。指を離さずに横に動かすと選べるよ"),
                base + 25 * MINUTE,
                true,
                "自分",
            ),
            Message(next(), text("ほんとだ、これ気持ちいい"), base + 40 * MINUTE, false, "ゆうた"),
            Message(
                next(),
                text("LINEより明らかに触ってて楽しい"),
                base + 41 * MINUTE,
                false,
                "ゆうた",
                reactions = persistentListOf(
                    Reaction(paletteIndex = 0, count = 2, mine = true),
                    Reaction(paletteIndex = 4, count = 1, mine = false),
                ),
            ),
            Message(next(), text("それが目標だった"), base + 43 * MINUTE, true, "自分"),
            Message(next(), sticker("st_arigato"), base + 44 * MINUTE, true, "自分"),
            Message(
                next(),
                text("スタンプも入れた。左下のボタンから出せる"),
                base + 45 * MINUTE,
                true,
                "自分",
            ),
            Message(next(), text("見てみる"), base + 50 * MINUTE, false, "ゆうた"),
            Message(next(), text("そっちの方が絶対いいと思う"), base + 56 * MINUTE, false, "ゆうた"),
        )
    }

    /** A long thread, so scrolling and recomposition can be judged on a real device. */
    private fun genericThread(conversationId: Long): List<Message> {
        val partner = conversations.firstOrNull { it.id == conversationId }?.title ?: "相手"
        val lines = listOf(
            "これはスクロールを確認するためのメッセージ",
            "短い",
            "少し長めのメッセージを入れておくと、バブルの折り返しと最大幅の見え方が確認できる",
            "うん",
            "ここは連続した送信のかたまり。間隔が詰まって見えるかを確認する",
            "確かに",
            "そうだね",
            "これくらいの長さが一番よくある形かもしれない",
            "了解",
            "👍",
        )
        val stickerIds = listOf("st_ok", "st_ukeru", "st_otsukare", "st_matteru")
        val base = now() - 2 * DAY

        return List(46) { index ->
            val outgoing = (index / 2 + index % 3) % 2 == 0
            Message(
                id = conversationId * 1_000 + index,
                content = if (index % 11 == 5) {
                    sticker(stickerIds[(index / 11) % stickerIds.size])
                } else {
                    text(lines[index % lines.size])
                },
                timestampMs = base + index * 7 * MINUTE,
                isOutgoing = outgoing,
                senderName = if (outgoing) "自分" else partner,
                reactions = if (index == 30) {
                    persistentListOf(Reaction(paletteIndex = 1, count = 1, mine = false))
                } else {
                    persistentListOf()
                },
            )
        }.toPersistentList()
    }

    private fun now() = System.currentTimeMillis()
}
