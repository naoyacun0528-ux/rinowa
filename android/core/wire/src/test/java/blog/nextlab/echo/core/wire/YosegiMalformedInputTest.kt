package blog.nextlab.echo.core.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * 敵意のある、あるいは壊れた相手が送ってきうるもの全部。
 *
 * 契約: **どんな**バイト列に対しても、[Yosegi.decode] は次の2つのどちらかしかしない。
 * 正しいメッセージ一覧を返すか、[YosegiError] を投げるか。それ以外は駄目。他の例外を
 * 投げない、無制限に確保しない、無制限にループしない、そして静かなやつ —
 * **送られていないバイトから組み立てたメッセージを返さない**。
 *
 * 形式ばった話ではない。Direct ではフレームが、相手だと名乗っているだけの端末から、
 * 間にサーバーを挟まずに届く。誰が送ったかの根拠はフレームの中にあるので、それを
 * 確かめる前に復号器が動く。つまりここが攻撃面。
 *
 * 形式の設計中、JavaScript の参照実装を92,362件のファズにかけ、本物の欠陥が1つ出た。
 * 知らないワイヤ型で [YosegiError] ではなく一般の例外を投げていて、そのままなら
 * ただの電波の乱れがクラッシュ報告の上で内部の不具合に見えていた。このテストは、
 * Kotlin 側がそこへ戻らないためにある。
 */
class YosegiMalformedInputTest {

    private val context = YosegiContext(
        conversationId = "Conv0000000000000001",
        memberIds = listOf("AaBbCcDdEeFfGgHhIiJjKkLlMmNn", "OoPpQqRrSsTtUuVvWwXxYyZz0011"),
        stickerCatalogue = listOf("echo.core.smile", "echo.core.heart"),
    )

    private fun seedFrame(count: Int): ByteArray = Yosegi.encode(
        (0 until count).map { i ->
            WireMessage(
                id = "aB3xQ9zL0pRt7YmK2vN" + ('a' + (i % 26)),
                senderId = context.memberIds[i % 2],
                timestampMs = 1_755_302_400_000L + i * 60_000L,
                text = "メッセージ $i",
                replyTo = if (i % 3 == 0) WireReply("Zz9YyXxWwVvUuTtSsRrQ", "みなと", "引用") else null,
                reactions = if (i % 4 == 0) mapOf(context.memberIds[0] to 2) else emptyMap(),
            )
        },
        context,
    )

    /**
     * 入力を1つ流して契約を確かめる。
     *
     * 受理してよい（乱数のバイト列がたまたま正しいフレームになることはあるし、
     * `01 00 00` は本当に空のフレーム）。見ているのは、受理したものが構造として
     * 成立していること。半端に組み上がったものが画面に出ないように。
     */
    private fun check(label: String, bytes: ByteArray) {
        val result = try {
            Yosegi.decode(bytes, context)
        } catch (e: YosegiError) {
            return
        } catch (e: Throwable) {
            fail("$label: threw ${e::class.simpleName} instead of YosegiError — ${e.message}")
            return
        }
        for (m in result) {
            assertEquals("$label: id length", 20, m.id.length)
            assertNotNull("$label: sender", m.senderId)
            assertEquals("$label: sender length", 28, m.senderId.length)
            assertNotNull("$label: status", m.status)
            for ((_, palette) in m.reactions) {
                assertTrue("$label: palette $palette", palette in 0 until Yosegi.REACTION_PALETTE_SIZE)
            }
        }
    }

    @Test
    fun `every prefix of a valid frame is refused or valid, never half a message`() {
        for (count in listOf(1, 6, 40)) {
            val frame = seedFrame(count)
            for (n in 0..frame.size) {
                check("truncate@$n/$count", frame.copyOf(n))
            }
        }
    }

    @Test
    fun `every single-byte corruption is handled`() {
        val frame = seedFrame(6)
        for (i in frame.indices) {
            for (value in listOf(0x00, 0x01, 0x7F, 0x80, 0xFF)) {
                val copy = frame.copyOf()
                copy[i] = value.toByte()
                check("flip@$i=$value", copy)
            }
        }
    }

    @Test
    fun `random multi-byte corruption is handled`() {
        val rnd = Random(0xF0F0BEEF)
        val seeds = listOf(seedFrame(1), seedFrame(6), seedFrame(40))
        repeat(20_000) {
            val copy = seeds[rnd.nextInt(seeds.size)].copyOf()
            repeat(1 + rnd.nextInt(8)) {
                copy[rnd.nextInt(copy.size)] = rnd.nextInt(256).toByte()
            }
            check("corrupt", copy)
        }
    }

    @Test
    fun `pure noise is handled`() {
        val rnd = Random(0xBADC0DE)
        repeat(20_000) {
            val bytes = ByteArray(rnd.nextInt(300)) { rnd.nextInt(256).toByte() }
            check("noise", bytes)
        }
        // 版の関門を通り抜ける雑音。面白い経路はその先にある。
        repeat(20_000) {
            val bytes = ByteArray(1 + rnd.nextInt(300)) { rnd.nextInt(256).toByte() }
            bytes[0] = Yosegi.VERSION
            check("noise-v1", bytes)
        }
    }

    @Test
    fun `a declared message count is checked before anything is allocated for it`() {
        // 3バイトのヘッダで2^35件を宣言する。信じた復号器はメモリ爆弾になる
        // （相手は6バイト送り、こちらは延々と確保する）。
        val hugeCount = byteArrayOf(Yosegi.VERSION, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x02, 0x00)
        assertThrows(YosegiError::class.java) { Yosegi.decode(hugeCount, context) }

        // フレームの上限のすぐ下で、本体は無し。
        val emptyBody = byteArrayOf(Yosegi.VERSION, 0xFF.toByte(), 0x1F, 0x00)
        assertThrows(YosegiError::class.java) { Yosegi.decode(emptyBody, context) }

        // ちょうど上限で、やはり本体は無し。
        val atLimit = byteArrayOf(Yosegi.VERSION, 0x80.toByte(), 0x20, 0x00)
        assertThrows(YosegiError::class.java) { Yosegi.decode(atLimit, context) }
    }

    @Test
    fun `a varint cannot run for ever`() {
        val endless = byteArrayOf(Yosegi.VERSION) + ByteArray(64) { 0x80.toByte() }
        assertThrows(YosegiError::class.java) { Yosegi.decode(endless, context) }

        val justOver = byteArrayOf(Yosegi.VERSION) + ByteArray(Yosegi.MAX_VARINT_BYTES) { 0x80.toByte() } + byteArrayOf(0x7F)
        assertThrows(YosegiError::class.java) { Yosegi.decode(justOver, context) }
    }

    @Test
    fun `a declared length larger than the frame is refused, not truncated into something plausible`() {
        // 巧妙なやつ。長さを検査する前に Int へ丸めていると、宣言された 2^32 + 5 が
        // 5 になる。4ギガバイトを名乗るフレームが5バイトとして読まれ、残りは
        // そのあとに続いていたものから解析され、まったく本物に見えるメッセージができる。
        val frame = seedFrame(1)
        for (length in listOf(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F),               // 約268MB
            byteArrayOf(0x85.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10), // 2^32 + 5
        )) {
            val hostile = frame.copyOf(frame.size - 1) +
                byteArrayOf(((8 shl 3) or 2).toByte()) + length + byteArrayOf(0)
            assertThrows(YosegiError::class.java) { Yosegi.decode(hostile, context) }
        }
    }

    @Test
    fun `a composite block cannot reach outside itself`() {
        val frame = seedFrame(2)
        val hostile = frame.copyOf(frame.size - 1) +
            byteArrayOf(((3 shl 3) or 5).toByte(), 0xFF.toByte(), 0x7F) + byteArrayOf(0)
        assertThrows(YosegiError::class.java) { Yosegi.decode(hostile, context) }
    }

    @Test
    fun `an out of range status is refused`() {
        val frame = seedFrame(1)
        // status は id、送信者の添字、時刻の差分のすぐあと。
        val statusPos = frame.indexOfStatus()
        for (value in listOf(5, 6, 200, 255)) {
            val copy = frame.copyOf()
            copy[statusPos] = value.toByte()
            assertThrows(YosegiError::class.java) { Yosegi.decode(copy, context) }
        }
    }

    /** 復号器と同じ手順でヘッダを歩き、最初の status バイトを見つける。 */
    private fun ByteArray.indexOfStatus(): Int {
        var p = 1
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = this[p++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        varint() // 件数
        varint() // 基準時刻
        p += Yosegi.ID20_BYTES
        val sender = this[p++].toInt() and 0xFF
        if (sender == 0xFF) p += Yosegi.ID28_BYTES
        varint() // 時刻の差分
        return p
    }

    @Test
    fun `the empty input is refused`() {
        assertThrows(YosegiError::class.java) { Yosegi.decode(ByteArray(0), context) }
    }

    @Test
    fun `decoding stays bounded on the worst input the fuzzing finds`() {
        // 速度の計測ではない。1つのフレームだけ他の100倍かかるなら、どこかが
        // 実際のバイト数ではなく宣言された数に比例している。件数と長さの検査は
        // まさにそれを防ぐためにある。
        val rnd = Random(0x5EED)
        var worst = ByteArray(0)
        var worstNanos = 0L
        repeat(4000) {
            val bytes = ByteArray(1 + rnd.nextInt(300)) { rnd.nextInt(256).toByte() }
            bytes[0] = Yosegi.VERSION
            val t0 = System.nanoTime()
            try { Yosegi.decode(bytes, context) } catch (e: YosegiError) { /* the usual outcome */ }
            val elapsed = System.nanoTime() - t0
            if (elapsed > worstNanos) { worstNanos = elapsed; worst = bytes }
        }

        val samples = LongArray(2000) {
            val t0 = System.nanoTime()
            try { Yosegi.decode(worst, context) } catch (e: YosegiError) { /* same */ }
            System.nanoTime() - t0
        }
        samples.sort()
        val medianMicros = samples[samples.size / 2] / 1000.0
        assertTrue(
            "worst input took a median of $medianMicros us, which suggests unbounded work",
            medianMicros < 500.0,
        )
    }
}
