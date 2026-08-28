package blog.nextlab.echo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import blog.nextlab.echo.core.model.ContentHash
import blog.nextlab.echo.core.model.MediaId
import blog.nextlab.echo.core.model.PreparedImage
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 選ばれた写真を、送ってよいかたちにする。
 *
 * 元のファイルは端末から出ない。カメラの写真は数MBあり、EXIF には**撮った場所の
 * 座標**が普通に入っている。「家にいる子どもの写真」をそのまま送るのは家の住所を
 * 送ること。
 *
 * 対策はメタデータを消すことではない。消すのは忘れられるし、1箇所忘れれば全部漏れる。
 * ここではピクセルに戻してから**この場所で再エンコードする**ので、出ていくバイト列は
 * 選ばれたファイルの複製ではなくこのファイルが作ったもの。ピクセルは EXIF を持たない
 * ので、通り抜ける経路が存在しない。ProfilePhotos と同じ考え方の写真版。
 *
 * 唯一尊重するメタデータは向きで、それも**ピクセルを回して**尊重する。回したあとは
 * タグに意味が無くなり、他と一緒に消える。
 *
 * 出力は2つ:
 *
 *  - 本体: 長辺2048px、200〜600KB
 *  - サムネイル: 長辺32px、3〜6KB
 *
 * サムネイルは*メッセージの中*を通るので、届いた瞬間に（ぼけてはいるが）その写真だと
 * 分かるものが出る。本体はタップされてから取りに行く。灰色の四角が並ぶ会話は会話に
 * 見えないし、全部落とす会話は通信量を食い潰す。docs/MEDIA_ARCHITECTURE.md §4。
 */
object MediaImages {

    /** 実際に送るものの長辺。これ以上あっても端末の画面では変わらない。 */
    const val FULL_EDGE_PX = 2048

    /**
     * メッセージ内に載せる仮画像の長辺。
     *
     * 32pxは顔も文字も読めない大きさにわざとしてある。本体が来る前に「浜辺の写真」と
     * 言うためだけのもので、メッセージの複製すべてに永久に付いて回るので、ほぼ無料で
     * なければならない。
     */
    const val THUMB_EDGE_PX = 32

    /** 本体の上限。品質設定ではなく予算（[encode] を参照）。 */
    const val FULL_MAX_BYTES = 600 * 1024

    /** サムネイルの上限。メッセージの中に入るので、これが実質の限界。 */
    const val THUMB_MAX_BYTES = 6 * 1024

    /**
     * これを超えると、縮める前のデコードでヒープが危ない。
     *
     * 1億800万画素は ARGB_8888 で432MB。下の間引きデコードでそもそも確保させないが、
     * 明示した境界のほうが分かりやすい。
     */
    private const val MAX_SOURCE_PIXELS = 200_000_000L

    /**
     * 送信用に画像を用意する。
     *
     * 全部ここでやり、送信時に持ち越さない。呼び出し側はバイト列・寸法・内容ハッシュを
     * 受け取り、送るかどうかは別に決める。
     *
     * 失敗はプラットフォームの言葉のまま返す。プロフィール写真のときに、握り潰した例外が
     * 「画像を読めませんでした」という情報の無い1文になって3件誤診した。各段階が自分の
     * 名前を言う。
     */
    fun prepare(source: Uri, reader: Context): PreparedImage {
        val bounds = readBounds(source, reader)
        val upright = decodeSampled(source, reader, bounds, FULL_EDGE_PX)

        val full = scaleToLongEdge(upright, FULL_EDGE_PX)
        val fullBytes = encode(full, FULL_MAX_BYTES, startQuality = 88, floorQuality = 55)

        val thumbSource = scaleToLongEdge(upright, THUMB_EDGE_PX)
        val thumbBytes = encode(thumbSource, THUMB_MAX_BYTES, startQuality = 70, floorQuality = 20)

        // 何かを解放する前に寸法を読む。解放済みのビットマップでも多くの端末は
        // 幅と高さを答えるので、あとで読むのはテストを通り抜けて別の場所で落ちるバグ。
        val width = full.width
        val height = full.height

        if (thumbSource !== upright && thumbSource !== full) thumbSource.recycle()
        if (full !== upright) full.recycle()
        upright.recycle()

        return PreparedImage(
            id = MediaId(ContentHash.of(fullBytes).value),
            bytes = fullBytes,
            thumbnail = thumbBytes,
            width = width,
            height = height,
        )
    }

    // -----------------------------------------------------------------------------------

    private fun readBounds(source: Uri, reader: Context): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            reader.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.exceptionOrNull()?.let {
            error("openInputStream: ${it::class.simpleName} ${it.message.orEmpty()}")
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            error("bounds unreadable (${bounds.outWidth}x${bounds.outHeight}, type=${bounds.outMimeType})")
        }
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (pixels > MAX_SOURCE_PIXELS) {
            error("image is ${bounds.outWidth}x${bounds.outHeight}, beyond what can be decoded")
        }
        return bounds
    }

    /**
     * 目標の約2倍でデコードしてから正確に縮める。
     *
     * `inSampleSize` は半分ずつしか効かないので、目標へ直接デコードすると行き過ぎるか
     * メモリを無駄にする。2倍で取ると、後段の縮小で甘くならず、原寸のビットマップも
     * 抱えない。
     */
    private fun decodeSampled(
        source: Uri,
        reader: Context,
        bounds: BitmapFactory.Options,
        targetEdge: Int,
    ): Bitmap {
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > targetEdge * 2) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 明示する。端末によっては HARDWARE ビットマップを返し、それは読み戻しも
            // 別のキャンバスへの描画もできない。縮小はまさにそれをやる。
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }

        val decoded = runCatching {
            reader.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrElse {
            error("decode: ${it::class.simpleName} ${it.message.orEmpty()}")
        } ?: error("decode returned null (sample=$sample, type=${bounds.outMimeType})")

        return applyExifRotation(source, decoded, reader)
    }

    /**
     * ピクセルを回して、向きのタグを不要にする。
     *
     * 向きをメタデータとして持ち越すのはメタデータを持ち越すことで、このファイルが
     * 止めたいのはそれ。回してしまえば、EXIF を解さないものが開いても正しい向きになる。
     */
    private fun applyExifRotation(source: Uri, bitmap: Bitmap, reader: Context): Bitmap {
        // swallow-ok: EXIF が読めない画像はここでは正立とみなす。「向きのタグが無い」
        // 場合と同じ扱いで、そちらのほうが普通。
        val degrees = runCatching {
            reader.contentResolver.openInputStream(source)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** 縦横比は保つ。元より小さい写真を拡大はしない。 */
    private fun scaleToLongEdge(bitmap: Bitmap, targetEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= targetEdge) return bitmap
        val ratio = targetEdge.toFloat() / longest
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * 予算に収まる品質でエンコードする。
     *
     * 固定値ではなく段階的に。予算はバイト数で、写真とスクリーンショットでは圧縮の
     * 効き方がまるで違う。固定品質だと90KBのスクリーンショットと2MBの写真ができ、
     * 送ってよいのは片方だけ。
     */
    private fun encode(bitmap: Bitmap, maxBytes: Int, startQuality: Int, floorQuality: Int): ByteArray {
        var quality = startQuality
        var bytes: ByteArray
        while (true) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(WEBP_FORMAT, quality, stream)
            bytes = stream.toByteArray()
            if (bytes.size <= maxBytes || quality <= floorQuality) break
            quality -= 8
        }
        return bytes
    }

    @Suppress("DEPRECATION")
    private val WEBP_FORMAT: Bitmap.CompressFormat =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
}
