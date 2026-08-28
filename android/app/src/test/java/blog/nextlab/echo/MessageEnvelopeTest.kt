package blog.nextlab.echo

import blog.nextlab.echo.data.MessageEnvelope
import blog.nextlab.echo.model.CallOutcome
import blog.nextlab.echo.model.MediaId
import blog.nextlab.echo.model.MessageContent
import blog.nextlab.echo.model.MessageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暗号化の中に入る形式。
 *
 * 間違えると高くつくものに対する、安いテスト。ここで項目を1つ落とす変更は、
 * ビルドも通るし送信側の端末でも通る。落ちるのは他人の端末で、あとになって、
 * 送り直せないメッセージの上で。
 */
class MessageEnvelopeTest {

    @Test
    fun `text survives the round trip`() {
        val content = MessageContent.Text(MessageText("""こんばんは 🌙 "  {} 改行
も"""))
        assertEquals(content, MessageEnvelope.open(MessageEnvelope.seal(content)))
    }

    @Test
    fun `a photo carries its key`() {
        val content = MessageContent.Image(
            mediaId = MediaId("a".repeat(64)),
            width = 1440,
            height = 1920,
            thumbnail = byteArrayOf(1, 2, 3, -4, -5),
            byteCount = 812_345,
            mediaKey = ByteArray(32) { it.toByte() },
        )

        val opened = MessageEnvelope.open(MessageEnvelope.seal(content)) as MessageContent.Image

        assertEquals(content, opened)
        assertTrue(content.thumbnail.contentEquals(opened.thumbnail))
        assertTrue(content.mediaKey.contentEquals(opened.mediaKey))
    }

    @Test
    fun `a call record survives the round trip`() {
        val content = MessageContent.Call(video = true, outcome = CallOutcome.Missed, seconds = 0)
        assertEquals(content, MessageEnvelope.open(MessageEnvelope.seal(content)))
    }

    /**
     * 封ができる前に封じられたメッセージは、本文そのものだった。それはまだ2台の端末に
     * あり、いまも開ける必要がある。
     */
    @Test
    fun `a plaintext from before the envelope opens as text`() {
        val opened = MessageEnvelope.open("E2EE-final-1552")
        assertEquals(MessageContent.Text(MessageText("E2EE-final-1552")), opened)
    }

    /** 友達に JSON を打っている人は、打っているのであって封を送っているのではない。 */
    @Test
    fun `a typed JSON object is not mistaken for an envelope`() {
        val typed = """{"hello":"world"}"""
        assertEquals(MessageContent.Text(MessageText(typed)), MessageEnvelope.open(typed))
    }

    /** 封じる側が、長い本文を通す抜け道になってはいけない。 */
    @Test
    fun `an envelope of a long body is still just that body`() {
        val long = "あ".repeat(5000)
        val opened = MessageEnvelope.open(MessageEnvelope.seal(MessageContent.Text(MessageText(long))))
        assertEquals(MessageContent.Text(MessageText(long)), opened)
    }
}
