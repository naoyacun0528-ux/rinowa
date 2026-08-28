package blog.nextlab.echo

import blog.nextlab.echo.media.resumeFrom
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 途中で切れた送信を、サーバーが持っている位置から続けるところ。
 *
 * 端末の外で試す価値があるのは、間違えたときの壊れ方が静かだから。読み飛ばす量が
 * ずれると、**中身のずれたファイルが正しいハッシュの名前で保管される**。転送は成功し、
 * 画面にも何も出ず、数か月後に復号できず、原因を指すものが残らない。
 *
 * [InputStream.skip] は要求より少なく進んでよい、と契約に書いてある。実際に少なく
 * 進むのは、圧縮された流れや遅い記憶装置のときで、開発機では起こらない。だから
 * 「少なく進む流れ」をここで作って通す。
 */
class UploadResumeTest {

    /** 契約どおり、1回につき最大 [step] バイトしか進まない流れ。 */
    private class StubbornStream(bytes: ByteArray, private val step: Long) :
        InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun skip(n: Long): Long = inner.skip(minOf(n, step))
    }

    @Test
    fun `resuming leaves the stream exactly at the requested byte`() {
        val body = ByteArray(1000) { it.toByte() }
        val source = ByteArrayInputStream(body)

        resumeFrom(source, target = 400, total = 1000)

        assertArrayEquals(body.copyOfRange(400, 1000), source.readBytes())
    }

    @Test
    fun `a stream that skips less than asked still lands on the right byte`() {
        val body = ByteArray(1000) { it.toByte() }
        // 1回に7バイトしか進まない。400 に着くまで57回かかる。
        val source = StubbornStream(body, step = 7)

        resumeFrom(source, target = 400, total = 1000)

        assertArrayEquals(body.copyOfRange(400, 1000), source.readBytes())
    }

    @Test
    fun `a position past the end of the file is refused`() {
        val source = ByteArrayInputStream(ByteArray(100))

        assertThrows(IOException::class.java) {
            resumeFrom(source, target = 200, total = 100)
        }
    }

    @Test
    fun `a stream that stops moving is refused instead of sending from the wrong place`() {
        // 進めなくなった流れ。ここで諦めないと、着いていない位置から送ることになる。
        val stuck = object : InputStream() {
            override fun read(): Int = -1
            override fun skip(n: Long): Long = 0
        }

        assertThrows(IOException::class.java) {
            resumeFrom(stuck, target = 400, total = 1000)
        }
    }
}
