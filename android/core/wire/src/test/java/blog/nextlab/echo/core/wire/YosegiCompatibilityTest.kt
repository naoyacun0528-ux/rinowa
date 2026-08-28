package blog.nextlab.echo.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 今日のアプリが、明日のメッセージをどう扱うか。
 *
 * 一番飛ばしやすく、飛ばすと一番高くつくテスト。あとの版が項目を1つ足した瞬間、
 * すでに入っている全クライアントが見たことのないバイトに出会う。読み飛ばせないと、
 * 新しい項目が出ないだけでは済まない。その項目がどこで終わるか分からないので
 * **そのあとが全部失われる**。メッセンジャーでは「黙って届かないメッセージ」として現れる。
 *
 * すべてのタグにワイヤ型が入っているのはこのためだけで、費用はフレームの0.3%ほど。
 */
class YosegiCompatibilityTest {

    private val context = YosegiContext(
        conversationId = "Conv0000000000000001",
        memberIds = listOf("AaBbCcDdEeFfGgHhIiJjKkLlMmNn", "OoPpQqRrSsTtUuVvWwXxYyZz0011"),
        stickerCatalogue = listOf("echo.core.smile", "echo.core.heart"),
    )

    private val message = WireMessage(
        id = "aB3xQ9zL0pRt7YmK2vNc",
        senderId = context.memberIds[1],
        timestampMs = 1_755_302_400_000L,
        status = WireStatus.Delivered,
        text = "今日の夕飯なににする",
        replyTo = WireReply("Zz9YyXxWwVvUuTtSsRrQ", "みなと", "なんでもいいよ"),
        reactions = mapOf(context.memberIds[0] to 3),
    )

    /** 項目7,8,9,10 は v1 に存在しない。v2 が使いうるワイヤ型を1つずつ。 */
    private val futureFields = byteArrayOf(
        ((7 shl 3) or 1).toByte(), 0x96.toByte(), 0x01,             // varint、値150
        ((8 shl 3) or 2).toByte(), 4, 0xDE.toByte(), 0xAD.toByte(), // 長さ付き
        0xBE.toByte(), 0xEF.toByte(),
        ((9 shl 3) or 0).toByte(),                                  // フラグ、本体なし
        ((10 shl 3) or 5).toByte(), 3, 0x01, 0x02, 0x03,            // 複合ブロック
        ((11 shl 3) or 3).toByte(),                                 // 固定長15
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    )

    /** 1件のフレームは必ず1つの END で終わる。v2 が足すとしたらそこ。 */
    private fun withFutureFields(): ByteArray {
        val base = Yosegi.encode(listOf(message), context)
        assertEquals("frame should end with END", 0, base.last().toInt())
        return base.copyOf(base.size - 1) + futureFields + byteArrayOf(0)
    }

    @Test
    fun `unknown fields are stepped over and the message survives intact`() {
        val back = Yosegi.decode(withFutureFields(), context)
        assertEquals(1, back.size)
        assertEquals(message, back[0])
    }

    @Test
    fun `unknown fields do not damage the messages that follow them`() {
        // 本当に危ないのは、新しい項目を持つメッセージそのものではなく、
        // 同じフレームの中でそのあとに続く全部。
        val second = message.copy(id = "Pp1Qq2Rr3Ss4Tt5Uu6Vv", timestampMs = message.timestampMs + 90_000)
        val base = Yosegi.encode(listOf(message, second), context)

        // 未来の項目を*最初の*メッセージの END の前に差し込む。位置は、1件だけの形を
        // 復号してその長さを取って求める。
        val single = Yosegi.encode(listOf(message), context)
        val firstEnd = single.size - 1
        val header = Yosegi.encode(listOf(message, second), context).copyOf(0)
        val spliced = base.copyOf(firstEnd) + futureFields + base.copyOfRange(firstEnd, base.size)

        val back = Yosegi.decode(header + spliced, context)
        assertEquals(2, back.size)
        assertEquals(message, back[0])
        assertEquals(second, back[1])
    }

    @Test
    fun `a wire type with no length is refused rather than guessed at`() {
        // 型4,6,7 は未定義。そういう項目の長さは知りようがないので、続ければ
        // フレームの残りを間違った位置から解析することになり、本物に見えて
        // 本物でないメッセージができる。
        for (wireType in listOf(4, 6, 7)) {
            val base = Yosegi.encode(listOf(message), context)
            val hostile = base.copyOf(base.size - 1) +
                byteArrayOf(((12 shl 3) or wireType).toByte(), 0)
            assertThrows(YosegiError::class.java) { Yosegi.decode(hostile, context) }
        }
    }

    @Test
    fun `an unknown version is refused`() {
        for (version in listOf(0, 2, 127, 255)) {
            val frame = Yosegi.encode(listOf(message), context).copyOf()
            frame[0] = version.toByte()
            assertThrows(YosegiError::class.java) { Yosegi.decode(frame, context) }
        }
    }

    @Test
    fun `status ordinals are frozen, because the wire carries the number`() {
        // この enum を並べ替えると、保存済みと転送中の全フレームの意味が黙って変わる。
        assertEquals(0, WireStatus.Sending.ordinal)
        assertEquals(1, WireStatus.Sent.ordinal)
        assertEquals(2, WireStatus.Delivered.ordinal)
        assertEquals(3, WireStatus.Read.ordinal)
        assertEquals(4, WireStatus.Failed.ordinal)
        assertEquals(5, WireStatus.entries.size)
    }

    @Test
    fun `a member list that does not match is refused, not silently misattributed`() {
        // 送信者は添字。短い一覧に対しては、その添字は別人か、誰でもない。
        // 別人のメッセージとして出すのは破損ではなく成りすましなので、
        // はっきり失敗させる必要がある。RESEARCH_ADAPTIVE_TRANSPORT.md §4。
        val frame = Yosegi.encode(listOf(message), context)
        val shorter = YosegiContext(context.conversationId, listOf(context.memberIds[0]), context.stickerCatalogue)
        assertThrows(YosegiError::class.java) { Yosegi.decode(frame, shorter) }
    }

    @Test
    fun `a sticker catalogue that does not match is refused`() {
        val frame = Yosegi.encode(
            listOf(message.copy(text = null, stickerId = "echo.core.heart", replyTo = null, reactions = emptyMap())),
            context,
        )
        val empty = YosegiContext(context.conversationId, context.memberIds, emptyList())
        assertThrows(YosegiError::class.java) { Yosegi.decode(frame, empty) }
    }

    @Test
    fun `an empty frame is a frame, not a crash`() {
        val frame = Yosegi.encode(emptyList(), context)
        assertEquals(emptyList<WireMessage>(), Yosegi.decode(frame, context))
    }

    @Test
    fun `absent fields cost nothing and come back absent`() {
        val bare = WireMessage(
            id = "aB3xQ9zL0pRt7YmK2vNc",
            senderId = context.memberIds[0],
            timestampMs = 1_755_302_400_000L,
            text = "は",
        )
        val back = Yosegi.decode(Yosegi.encode(listOf(bare), context), context)[0]
        assertNull(back.replyTo)
        assertNull(back.stickerId)
        assertNull(back.senderName)
        assertEquals(emptyMap<String, Int>(), back.reactions)
    }
}
