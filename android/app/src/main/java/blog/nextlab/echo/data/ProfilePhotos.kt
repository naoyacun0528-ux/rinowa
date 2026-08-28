package blog.nextlab.echo.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import blog.nextlab.echo.model.ContentHash
import blog.nextlab.echo.model.UserId
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * プロフィール画像。選び、縮め、保存し、手元に置く。
 *
 * 元の写真は端末から出ない。スタンプの設計と同じ規則。カメラの写真は数MBあり、
 * EXIF には撮影地の座標が入っていることが多い。40dp の丸を描いてもらうために
 * それを上げるのは、位置の履歴を送ること。
 *
 * なので切り抜き、[SIZE_PX] に縮め、ここで再エンコードする。メタデータが落ちるのは
 * 再エンコードのおかげで、Firestore へ行くバイト列はこのプロセスが作ったものであって、
 * 選ばれたファイルの複製ではない。
 *
 * 取得は1回で、内容ハッシュを名前にして持っておく。利用者ドキュメントにも同じ
 * ハッシュがあるので、画像を落とさずに手元の複製が最新か分かる。写真を変えれば
 * ハッシュが変わり、全員が1回だけ取り直す。
 */
class ProfilePhotos(
    private val context: Context,
    private val db: FirebaseFirestore,
) {

    private val directory = File(context.filesDir, DIR)
    private val decoded = ConcurrentHashMap<String, ImageBitmap>()
    private val missing = ConcurrentHashMap<UserId, Boolean>()

    /** 写真が届いたら増やす。頭文字を描いていたところがそれを拾う。 */
    var revision by mutableStateOf(0)
        private set

    /** このアカウントの画像。端末が最新のものを持っていれば。 */
    fun photo(id: UserId, hash: String?): ImageBitmap? {
        if (hash.isNullOrEmpty()) return null
        decoded[hash]?.let { return it }

        val file = File(directory, "$hash.webp").takeIf(File::exists) ?: return null
        // swallow-ok: 手元の複製がデコードできなければ「無い」として取り直す。
        // 伝えたところで呼び出し側にできることは変わらない。
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        return bitmap.asImageBitmap().also { decoded[hash] = it }
    }

    /**
     * まだ持っていない写真を取りに行く。
     *
     * 取るものが無ければ黙って戻る。プロフィール画像が無いのは異常ではなく普通の状態
     * （設定しない人のほうが多い）。
     */
    suspend fun fetch(id: UserId, hash: String?): Boolean {
        if (hash.isNullOrEmpty()) return false
        if (photo(id, hash) != null) return false
        if (missing[id] == true) return false

        val bytes = runCatching {
            db.collection(RinowaDb.Users.COLLECTION).document(id.value)
                .collection(RinowaDb.Users.PUBLIC).document(RinowaDb.Users.PUBLIC_PHOTO_DOC)
                .get().await()
                .getBlob(RinowaDb.Users.PHOTO_BYTES)?.toBytes()
        }
            // 頭文字に落ちる。それは「写真を設定していない」と読め、「取得できなかった」
            // とは別のこと。区別できるのはログだけ。
            .onFailure { android.util.Log.w("Rinowa/photo", "fetch failed", it) }
            .getOrNull()

        if (bytes == null) {
            missing[id] = true
            return false
        }

        withContext(Dispatchers.IO) {
            directory.mkdirs()
            File(directory, "$hash.webp").writeBytes(bytes)
        }
        revision++
        return true
    }

    /**
     * 呼び出し側が切り抜き済みの画像を公開する。
     *
     * 切り抜き画面が本人の決めた構図そのものを作るので、ここで切り直すとその選択を
     * 台無しにする。かけるのはエンコードと大きさの上限だけ。
     */
    suspend fun publish(owner: UserId, cropped: Bitmap): Result<String> = runCatching {
        val bytes = withContext(Dispatchers.IO) { encode(cropped) }
        publishBytes(owner, bytes)
    }

    private suspend fun publishBytes(owner: UserId, bytes: ByteArray): String {
        val hash = ContentHash.of(bytes).value

        db.collection(RinowaDb.Users.COLLECTION).document(owner.value)
            .collection(RinowaDb.Users.PUBLIC).document(RinowaDb.Users.PUBLIC_PHOTO_DOC)
            .set(
                mapOf(
                    RinowaDb.Users.PHOTO_BYTES to Blob.fromBytes(bytes),
                    RinowaDb.Users.UPDATED_AT to FieldValue.serverTimestamp(),
                ),
            ).await()

        withContext(Dispatchers.IO) {
            directory.mkdirs()
            File(directory, "$hash.webp").writeBytes(bytes)
        }
        revision++
        return hash
    }

    /**
     * 予算に収まる品質でエンコードする。
     *
     * 固定ではなく段階的に落とす。写真と平面的な図では圧縮の効き方が大きく違い、
     * ここでの制限は品質の数値ではなくバイト数。
     */
    private fun encode(bitmap: Bitmap): ByteArray {
        val square = if (bitmap.width == SIZE_PX && bitmap.height == SIZE_PX) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, SIZE_PX, SIZE_PX, true)
        }

        var quality = 90
        var bytes: ByteArray
        do {
            val stream = ByteArrayOutputStream()
            square.compress(WEBP_FORMAT, quality, stream)
            bytes = stream.toByteArray()
            quality -= 10
        } while (bytes.size > MAX_BYTES && quality >= 40)
        return bytes
    }

    suspend fun remove(owner: UserId): Result<Unit> = runCatching {
        db.collection(RinowaDb.Users.COLLECTION).document(owner.value)
            .collection(RinowaDb.Users.PUBLIC).document(RinowaDb.Users.PUBLIC_PHOTO_DOC)
            .delete().await()
    }

    /**
     * 選ばれた画像を、扱える大きさで、正しい向きにしてデコードする。
     *
     * 切り抜き画面が同じピクセルを見られるように公開している。先に間引くのは、
     * 1200万画素を丸ごとデコードすると、アイコンを選んでいる最中にメモリが尽きるから。
     *
     * @param reader Uri を読むための context。既定はこのクラスが持っているものだが、
     *   切り抜き画面は自分のものを渡す。ピッカーの Uri は一時的な許可で、結果を受け
     *   取った context から読むと、端末ごとの許可の切り方に依存しない。
     */
    fun decodeForCrop(
        source: Uri,
        maxEdge: Int = CROP_EDGE_PX,
        reader: Context = context,
    ): Bitmap {
        // 以下の失敗はどれもどの段階かを言う。最初の2版は null を返し、画面は
        // 「画像を読めませんでした」と出していた。情報の無い1文で、Rinowa Direct で
        // 2回誤診したのと同じ握り潰し。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = runCatching {
            reader.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        opened.exceptionOrNull()?.let {
            error("openInputStream: ${it::class.simpleName} ${it.message.orEmpty()}")
        }

        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) {
            error("bounds unreadable (${bounds.outWidth}x${bounds.outHeight}, type=${bounds.outMimeType})")
        }

        var sample = 1
        while (longest / sample > maxEdge * 2) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // ARGB_8888 を明示する。端末によっては HARDWARE ビットマップを返し、
            // それは読み戻しも別のキャンバスへの描画もできない。切り抜きがやるのは
            // まさにキャンバスへの描画。
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }

        val loaded = runCatching {
            reader.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrElse {
            error("decode: ${it::class.simpleName} ${it.message.orEmpty()}")
        } ?: error("decode returned null (sample=$sample, type=${bounds.outMimeType})")

        return applyExifRotation(source, loaded, reader)
    }

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
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private companion object {
        const val DIR = "avatars"

        /** 切り抜き画面での作業用の大きさ。拡大できる程度に大きく、抱えられる程度に小さく。 */
        const val CROP_EDGE_PX = 1024

        /** 写真を描く一番大きな場所に足りる大きさ。それ以上にはしない。 */
        const val SIZE_PX = 256
        const val MAX_BYTES = 100 * 1024

        @Suppress("DEPRECATION")
        val WEBP_FORMAT: Bitmap.CompressFormat =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
    }
}
