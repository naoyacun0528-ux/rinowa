package blog.nextlab.echo.core.wire

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * research/vectors/yosegi.json と突き合わせる。
 *
 * このファイルは Kotlin・Swift・JavaScript の3つの実装が**同じ1つのものを見る**ための
 * 固定バイト列。互いのコードを見ずに、これだけに合わせる。だから一致は、
 * 「同じ人が同じ間違いを2回した」ではなく、仕様の一致を意味する。
 *
 * `FormatVectorsTest` が封筒とバックアップに対してやっていることの、Yosegi 版。
 * 形式のバグが姿を見せるのは2つ目の実装の上で、見せる場所は他人の端末の、
 * 送り直せないメッセージ。
 *
 * **このベクタは v2 を作るときにしか書き換えない。** 値が変わったなら、それは
 * 形式が変わったということで、凍結を破っている。作り直すのは
 * `node research/wire/vectors.js`。
 */
class YosegiVectorsTest {

    // モジュールのディレクトリからの相対。Gradle が単体テストを走らせる場所がそこ。
    private val file = File("../../../research/vectors/yosegi.json")

    private fun root(): JSONObject {
        assertTrue(
            "research/vectors/yosegi.json が無い。node research/wire/vectors.js で作る",
            file.exists(),
        )
        return JSONObject(file.readText())
    }

    private fun context(root: JSONObject): YosegiContext {
        val members = root.getJSONArray("memberIds").let { a -> List(a.length()) { a.getString(it) } }
        val stickers = root.getJSONArray("stickerCatalogue").let { a -> List(a.length()) { a.getString(it) } }
        return YosegiContext(root.getString("conversationId"), members, stickers)
    }

    private fun status(name: String) = when (name) {
        "Sending" -> WireStatus.Sending
        "Delivered" -> WireStatus.Delivered
        "Read" -> WireStatus.Read
        "Failed" -> WireStatus.Failed
        else -> WireStatus.Sent
    }

    private fun messages(o: JSONObject): List<WireMessage> {
        val a = o.getJSONArray("messages")
        return List(a.length()) { i ->
            val m = a.getJSONObject(i)
            WireMessage(
                id = m.getString("id"),
                senderId = m.getString("senderId"),
                timestampMs = m.getLong("timestampMs"),
                status = status(m.getString("status")),
                text = if (m.has("text")) m.getString("text") else null,
                stickerId = if (m.has("stickerId")) m.getString("stickerId") else null,
                replyTo = if (m.has("replyTo")) {
                    val r = m.getJSONObject("replyTo")
                    WireReply(r.getString("messageId"), r.getString("senderName"), r.getString("excerpt"))
                } else null,
            )
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun bytes(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    @Test
    fun `writes the fixed bytes`() {
        val root = root()
        val ctx = context(root)
        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val frame = Yosegi.encode(messages(c), ctx)
            assertEquals(c.getString("name") + " — " + c.getString("note"), c.getString("hex"), hex(frame))
        }
    }

    @Test
    fun `reads the fixed bytes`() {
        val root = root()
        val ctx = context(root)
        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = Yosegi.decode(bytes(c.getString("hex")), ctx)
            val want = messages(c)
            val name = c.getString("name")
            assertEquals("$name: 通数", want.size, got.size)
            for ((g, w) in got.zip(want)) {
                assertEquals("$name: id", w.id, g.id)
                assertEquals("$name: 送信者", w.senderId, g.senderId)
                assertEquals("$name: 時刻", w.timestampMs, g.timestampMs)
                assertEquals("$name: 状態", w.status, g.status)
                assertEquals("$name: 本文", w.text, g.text)
                assertEquals("$name: スタンプ", w.stickerId, g.stickerId)
                assertEquals("$name: 返信", w.replyTo, g.replyTo)
            }
        }
    }

    /**
     * **読むことは、渡されたものを壊さない。**
     *
     * 参照実装（`research/wire/yosegi.js`）がここで壊れていた。id を base62 に戻す
     * 割り算が作業用の配列を破壊しながら進む作りで、その配列が複製ではなく参照だった。
     * 結果として `decode` が呼び出し元のフレームの id を全部ゼロにしていた。
     *
     * Kotlin は `copyOf()` を明示していて無事だったが、無事であることを
     * 誰も確かめていなかった。確かめる。
     */
    @Test
    fun `decoding does not modify the frame it was given`() {
        val root = root()
        val ctx = context(root)
        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val frame = bytes(c.getString("hex"))
            val before = hex(frame)
            Yosegi.decode(frame, ctx)
            assertEquals(c.getString("name") + ": decode がフレームを書き換えた", before, hex(frame))
        }
    }
}
