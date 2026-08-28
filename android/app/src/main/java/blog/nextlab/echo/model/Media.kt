package blog.nextlab.echo.model

import androidx.compose.runtime.Immutable

/**
 * 写真か動画を、中身で指し示す。
 *
 * id は**加工後**のバイト列の SHA-256 で、選ばれたファイルのものではない。
 * そこから2つのことが出てきて、どちらも狙いどおり:
 *
 *  - **同じ写真は1つしか保管されない。** 3人に送っても2回転送しても、サーバー上では
 *    1つのオブジェクト。スタンプの設計でいう STORE ONCE が、仕組みを足さずに手に入る。
 *  - **完全性も一緒に付いてくる。** 届いたバイト列が、要求した id にハッシュされない
 *    なら、それは要求したものではないので捨てる。サーバーが正しいものを返すことを
 *    信用しなくてよい。
 *
 * 設計で消せない代償なので書いておく。サーバーは、2つのアカウントが同じ id を
 * 要求したこと＝同じ写真を持っていることを知れる。重複排除とその推測は、同じ事実の
 * 表と裏。docs/MEDIA_ARCHITECTURE.md §3。
 */
@JvmInline
value class MediaId(val value: String) {
    override fun toString(): String = "MediaId(${value.take(8)}…)"
}

/**
 * 送れるかたちにした写真で、まだ送っていないもの。
 *
 * すべて端末上で [blog.nextlab.echo.data.MediaImages] が作る。元のファイルは
 * ここに一切現れない。持つ項目をわざと作っていないので、あとの変更でうっかり
 * 送り始めることもできない。
 *
 * [toString] はどちらのバイト配列も出さない。画像はメッセージの中身で、本文をログに
 * 出さない規則は文字とまったく同じように当てはまる。
 */
@Immutable
class PreparedImage(
    val id: MediaId,
    /** 本体。WebP、長辺2048、数百KB。 */
    val bytes: ByteArray,
    /**
     * メッセージ内の仮画像。WebP、長辺32、数KB。
     *
     * メッセージ自体の中を通るので、届いた瞬間に写真が出る。複製すべてに永久に
     * 付いて回っても構わない大きさで、顔を読むには小さすぎる。
     */
    val thumbnail: ByteArray,
    val width: Int,
    val height: Int,
) {
    val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 1f

    override fun toString(): String =
        "PreparedImage(${width}x$height, ${bytes.size}B, thumb ${thumbnail.size}B)"
}
