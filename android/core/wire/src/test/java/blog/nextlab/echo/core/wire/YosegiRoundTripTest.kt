package blog.nextlab.echo.core.wire

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 入れたものが、そのまま出てくるか。
 *
 * 転送形式について他のことは交渉できるが、これだけはできない。小さくて速くて、
 * 1万文字に1文字失う形式は形式ではなく、仕様書の付いたバグ。
 */
class YosegiRoundTripTest {

    private val context = YosegiContext(
        conversationId = "Conv0000000000000001",
        memberIds = listOf(
            "AaBbCcDdEeFfGgHhIiJjKkLlMmNn",
            "OoPpQqRrSsTtUuVvWwXxYyZz0011",
            "2233445566778899AaBbCcDdEeFf",
        ),
        stickerCatalogue = listOf("echo.core.smile", "echo.core.cry", "echo.core.heart"),
    )

    private fun id(seed: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = Random(seed)
        return (1..20).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun assertRoundTrip(messages: List<WireMessage>) {
        val frame = Yosegi.encode(messages, context)
        val back = Yosegi.decode(frame, context)
        assertEquals("message count", messages.size, back.size)
        for (i in messages.indices) {
            assertEquals("message $i", messages[i], back[i])
        }
    }

    @Test
    fun `a plain text message survives`() {
        assertRoundTrip(
            listOf(
                WireMessage(
                    id = id(1),
                    senderId = context.memberIds[0],
                    timestampMs = 1_755_302_400_000L,
                    text = "おはよう",
                ),
            ),
        )
    }

    @Test
    fun `every field at once survives`() {
        assertRoundTrip(
            listOf(
                WireMessage(
                    id = id(2),
                    senderId = context.memberIds[1],
                    timestampMs = 1_755_302_400_000L,
                    status = WireStatus.Read,
                    text = "了解です🙏",
                    replyTo = WireReply(id(3), "なおや", "明日の予定どうする"),
                    senderName = "みゆき",
                    reactions = mapOf(context.memberIds[0] to 4, context.memberIds[2] to 0),
                ),
            ),
        )
    }

    @Test
    fun `the text Rinowa actually carries survives`() {
        // コーデックを壊す種類のもの。`research/wire/corpus2.js` から: BMP の外の絵文字、
        // URL、複数の文字体系の混在、数字、そして実通信の1/3を占める1文字の相槌。
        val samples = listOf(
            "うん", "OK", "り", "😂😂😂", "🙇‍♀️",
            "https://youtu.be/aB3xQ9zL0pRt7YmK",
            "090-1234-5678", "1200円だった", "9月14日 19:00",
            "sounds good", "lol same",
            "𠮷野家で🍜たべた",
            "Ωμέγα", "日本語とEnglishが混ざった文章",
            "",
            "あ".repeat(2000),
        )
        samples.forEachIndexed { index, text ->
            assertRoundTrip(
                listOf(
                    WireMessage(
                        id = id(100 + index),
                        senderId = context.memberIds[index % context.memberIds.size],
                        timestampMs = 1_755_302_400_000L + index * 1000L,
                        text = text,
                    ),
                ),
            )
        }
    }

    @Test
    fun `stickers use the catalogue index, and fall back to a string when they cannot`() {
        assertRoundTrip(
            listOf(
                WireMessage(id(4), context.memberIds[0], 1_755_302_400_000L, stickerId = "echo.core.heart"),
                WireMessage(id(5), context.memberIds[0], 1_755_302_401_000L, stickerId = "custom.something.new"),
            ),
        )
    }

    @Test
    fun `a retracted message survives`() {
        assertRoundTrip(
            listOf(WireMessage(id(6), context.memberIds[2], 1_755_302_400_000L, retracted = true)),
        )
    }

    @Test
    fun `messages out of order survive, because they arrive that way`() {
        // 負の差分が10バイトになってはいけないし、でたらめな時刻になってもいけない。
        assertRoundTrip(
            listOf(
                WireMessage(id(7), context.memberIds[0], 1_755_302_500_000L, text = "second"),
                WireMessage(id(8), context.memberIds[1], 1_755_302_400_000L, text = "first"),
                WireMessage(id(9), context.memberIds[2], 1_755_302_600_000L, text = "third"),
            ),
        )
    }

    @Test
    fun `a sender the context does not know still arrives`() {
        val stranger = "ZzYyXxWwVvUuTtSsRrQqPpOoNnMm"
        val frame = Yosegi.encode(
            listOf(WireMessage(id(10), stranger, 1_755_302_400_000L, text = "新しく入った人")),
            context,
        )
        val back = Yosegi.decode(frame, context)
        assertEquals(stranger, back[0].senderId)
    }

    @Test
    fun `a full thread survives`() {
        val messages = (0 until 400).map { i ->
            WireMessage(
                id = id(1000 + i),
                senderId = context.memberIds[i % context.memberIds.size],
                timestampMs = 1_755_302_400_000L + i * 47_000L,
                status = WireStatus.entries[i % WireStatus.entries.size],
                text = if (i % 9 == 0) null else "メッセージ $i",
                stickerId = if (i % 9 == 0) "echo.core.smile" else null,
                replyTo = if (i % 11 == 0) WireReply(id(2000 + i), "だれか", "引用$i") else null,
                reactions = if (i % 7 == 0) mapOf(context.memberIds[0] to i % 6) else emptyMap(),
            )
        }
        assertRoundTrip(messages)
    }

    @Test
    fun `ids survive at both ends of the alphabet`() {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        for (value in listOf(alphabet.first().toString().repeat(20), alphabet.last().toString().repeat(20))) {
            assertEquals(value, Yosegi.unpackId(Yosegi.packId(value, 15), 15, 20))
        }
        for (value in listOf(alphabet.first().toString().repeat(28), alphabet.last().toString().repeat(28))) {
            assertEquals(value, Yosegi.unpackId(Yosegi.packId(value, 21), 21, 28))
        }
    }

    @Test
    fun `encoding is deterministic, because a wire format that is not cannot be cached or compared`() {
        val messages = listOf(
            WireMessage(id(11), context.memberIds[0], 1_755_302_400_000L, text = "同じ入力"),
        )
        assertArrayEquals(Yosegi.encode(messages, context), Yosegi.encode(messages, context))
    }

    @Test
    fun `it is very much smaller than the JSON it replaces`() {
        // 速度の計測ではなく引っかけ線。形式の変更で節約が1/3こっそり減ったら、
        // 通信量の請求書で気付くのではなく、ここで落ちるべき。
        val messages = (0 until 100).map { i ->
            WireMessage(
                id = id(3000 + i),
                senderId = context.memberIds[i % context.memberIds.size],
                timestampMs = 1_755_302_400_000L + i * 60_000L,
                text = "今日の夕飯なににする",
            )
        }
        val yosegiBytes = Yosegi.encode(messages, context).size
        val jsonish = messages.joinToString("") { m ->
            """{"id":"${m.id}","conversationId":"${context.conversationId}",""" +
                """"senderId":"${m.senderId}","timestampMs":${m.timestampMs},""" +
                """"status":"${m.status}","text":"${m.text}"},"""
        }.toByteArray(Charsets.UTF_8).size

        assertTrue(
            "Yosegi $yosegiBytes bytes vs JSON $jsonish — expected under half",
            yosegiBytes * 2 < jsonish,
        )
    }
}
