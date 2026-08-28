package blog.nextlab.echo.core.wire

/** 必要に応じて伸びる。呼び出し側が大きさを先に見積もらなくてよいように。 */
internal class YosegiWriter {
    private var buf = ByteArray(256)
    private var len = 0

    private fun need(n: Int) {
        if (len + n <= buf.size) return
        var size = buf.size * 2
        while (size < len + n) size *= 2
        buf = buf.copyOf(size)
    }

    fun u8(value: Int) {
        need(1)
        buf[len++] = (value and 0xFF).toByte()
    }

    fun varint(value: Long) {
        require(value >= 0) { "varint is unsigned; zigzag before writing a negative" }
        need(10)
        var v = value
        while (v >= 0x80) {
            buf[len++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        buf[len++] = v.toByte()
    }

    fun bytes(source: ByteArray) {
        need(source.size)
        source.copyInto(buf, len)
        len += source.size
    }

    fun string(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        varint(encoded.size.toLong())
        bytes(encoded)
    }

    /** 長さ付きのブロック。古い読み手が複合項目を読み飛ばせるのはこれのおかげ。 */
    fun block(body: ByteArray) {
        varint(body.size.toLong())
        bytes(body)
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)
}

/**
 * 境界を必ず確認する読み手。
 *
 * どのメソッドも進む前に確認する。それがこのクラスの全部。JavaScript の参照実装は
 * していなくて、あちらでは末尾を越えて読むと `undefined` になり、それが0に変換される。
 * つまり途中で切れたフレームから、*誰も送っていないバイトで組み立てた、それらしい
 * メッセージ*ができていた。例外もログも無しで。JVM なら代わりに例外を投げるので
 * まだましだが、深いところから `ArrayIndexOutOfBoundsException` が飛ぶ。
 * 不正な入力を内部の不具合として報告する復号器は、その不具合を無視する習慣を作る。
 */
internal class YosegiReader(private val buf: ByteArray) {
    var pos = 0
        private set

    val remaining: Int get() = buf.size - pos

    fun u8(): Int {
        if (pos >= buf.size) throw YosegiError("truncated: expected a byte")
        return buf[pos++].toInt() and 0xFF
    }

    /**
     * 上限は [Yosegi.MAX_VARINT_BYTES]。
     *
     * 8バイトなのは、フレームの基準時刻がミリ秒のエポックで、それだけで6バイト要るから。
     * そもそも上限があるのは、無いと継続バイトの連なりが、長さを送り手が決めるループに
     * なるから。
     */
    fun varint(): Long {
        var result = 0L
        var shift = 0
        for (i in 0 until Yosegi.MAX_VARINT_BYTES) {
            if (pos >= buf.size) throw YosegiError("truncated: varint runs off the end")
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                if (result < 0) throw YosegiError("varint out of range")
                return result
            }
            shift += 7
        }
        throw YosegiError("varint longer than ${Yosegi.MAX_VARINT_BYTES} bytes")
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0) throw YosegiError("negative length")
        if (n > remaining) throw YosegiError("truncated: wanted $n bytes, $remaining left")
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /**
     * 宣言された長さを、まだ Long のうちに確認する。
     *
     * `varint().toInt()` は穴になる。宣言された 2^32 + 5 は 5 に丸まり、4ギガバイトを
     * 名乗るフレームが5バイトとして読まれ、残りの項目はそのあとに続いていたものから
     * 解析される。先に [remaining] と比べておけば、そのあとの縮小変換も安全
     * （remaining 自体が Int なので）。
     */
    fun length(): Int {
        val declared = varint()
        if (declared > remaining) {
            throw YosegiError("declared length $declared exceeds the $remaining bytes left")
        }
        return declared.toInt()
    }

    fun string(): String = String(bytes(length()), Charsets.UTF_8)
}
