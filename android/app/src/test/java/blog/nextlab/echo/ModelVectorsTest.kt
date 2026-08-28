package blog.nextlab.echo

import blog.nextlab.echo.model.BuiltInStickers
import blog.nextlab.echo.model.CallOutcome
import blog.nextlab.echo.model.MessageContent
import blog.nextlab.echo.model.MessageStatus
import blog.nextlab.echo.model.MessageText
import blog.nextlab.echo.model.ReactionPalette
import blog.nextlab.echo.model.StickerId
import blog.nextlab.echo.model.StickerLimits
import blog.nextlab.echo.model.previewText
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * research/vectors/model.json と突き合わせる。
 *
 * 入っているのは**画面に出る文字列と、凍結された並び**。実装ごとに違うと、
 * 同じ会話が端末によって違う説明になる。一覧に「写真」と出る端末と
 * 「画像」と出る端末があってはいけない。
 *
 * Swift 側の同じテストは ios/RinowaCore/Tests/RinowaCoreTests/ModelVectorsTests.swift。
 */
class ModelVectorsTest {

    private val file = File("../../research/vectors/model.json")

    private fun root(): JSONObject {
        assertTrue("research/vectors/model.json が無い", file.exists())
        return JSONObject(file.readText())
    }

    private fun JSONObject.strings(key: String): List<String> =
        getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }

    // ---------------------------------------------------------------- 一覧の文字列

    @Test
    fun `preview strings match`() {
        val p = root().getJSONObject("preview")

        assertEquals(p.getString("sticker"), MessageContent.Sticker(StickerId("st_ok")).previewText().value)
        assertEquals(p.getString("locked"), MessageContent.Locked("x").previewText().value)
        assertEquals(p.getString("retracted"), MessageContent.Retracted.previewText().value)

        // 本文だけは中身がそのまま出る。代わりの文字列ではない。
        assertEquals("おはよう", MessageContent.Text(MessageText("おはよう")).previewText().value)
    }

    // ---------------------------------------------------------------- 凍結された並び

    @Test
    fun `reaction palette is frozen`() {
        val r = root().getJSONObject("reactionPalette")
        assertEquals(r.getInt("version"), ReactionPalette.VERSION)
        assertEquals(
            "並べ替えると、過去の反応が別のものになる",
            r.strings("emoji"),
            ReactionPalette.emoji,
        )
    }

    @Test
    fun `message status order is frozen`() {
        val order = root().getJSONObject("messageStatus").strings("order")
        assertEquals("序数が線の上を通る", order, MessageStatus.entries.map { it.name })
    }

    @Test
    fun `call outcome order is frozen`() {
        val order = root().getJSONObject("callOutcome").strings("order")
        assertEquals(order, CallOutcome.entries.map { it.name })
    }

    // ---------------------------------------------------------------- スタンプ

    @Test
    fun `sticker limits match`() {
        val l = root().getJSONObject("stickerLimits")
        assertEquals(l.getInt("maxDimensionPx"), StickerLimits.MAX_DIMENSION_PX)
        assertEquals(l.getInt("maxBytes"), StickerLimits.MAX_BYTES)
    }

    @Test
    fun `built-in stickers match`() {
        val b = root().getJSONObject("builtInStickers")
        assertEquals(b.getString("packId"), BuiltInStickers.packId.value)
        assertEquals(b.getString("title"), BuiltInStickers.pack.title)
        assertEquals(b.getInt("version"), BuiltInStickers.pack.version)

        val want = b.getJSONArray("entries")
        assertEquals(want.length(), BuiltInStickers.entries.size)
        for (i in 0 until want.length()) {
            val w = want.getJSONObject(i)
            val got = BuiltInStickers.entries[i]
            assertEquals(w.getString("id"), got.id.value)
            assertEquals(w.getString("fileName"), got.fileName)
            assertEquals(w.getString("label"), got.label)
        }
        assertEquals(BuiltInStickers.entries.map { it.id }, BuiltInStickers.pack.stickerIds)
    }

    // ---------------------------------------------------------------- 本文を漏らさない

    /**
     * **本文を持つ型は、説明に中身を出さない。**
     *
     * `Log.d(TAG, "sending $text")` の1行が、本文を端末の外へ出す一番ありがちな経路。
     * docs/PRIVACY_PRINCIPLES.md 防御層3。
     */
    @Test
    fun `message text never prints its body`() {
        val secret = "口座の暗証番号は4649"
        val text = MessageText(secret)

        assertFalse("文字列展開から漏れた", "$text".contains(secret))
        assertFalse("toString から漏れた", text.toString().contains(secret))

        // 長さは出してよい。封の外からも見えるものなので、隠しても得がない。
        assertEquals("MessageText(len=${secret.length})", text.toString())
    }
}
