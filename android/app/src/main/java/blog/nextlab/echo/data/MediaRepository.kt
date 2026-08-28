package blog.nextlab.echo.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import blog.nextlab.echo.media.MediaCipher
import blog.nextlab.echo.media.MediaStoreClient
import blog.nextlab.echo.model.ContentHash
import blog.nextlab.echo.model.MediaId
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 写真と動画そのもの。
 *
 * バイト列は XServer の保管庫に暗号化して置く（docs/MEDIA_ARCHITECTURE.md §2）。
 * 以前は Firestore のドキュメントに入れていたが、それは写真が数百KBのうちだけの話で、
 * オリジナルや動画を送った瞬間に破綻する（1MB上限、バイト範囲の概念が無い）。
 * 古い写真はいまも Firestore にあり、そこから読む。鍵を持たないメッセージがそれ（[fetch]）。
 *
 * ファイルごとに乱数の鍵を作り、鍵は封をしたメッセージの中を通る。だから保管庫には
 * **サーバーを運用する者にも読めない**バイト列だけが残る。HTTP で配るファイルの前には
 * ルールエンジンが無いので、これが唯一の防ぎ方。
 *
 * 代わりに §3 が柱にしていたものを1つ捨てた。鍵がファイルごとに乱数だと、同じ写真を
 * 2回送っても暗号文が変わり、id も別になる＝**送信者をまたいだ重複排除は無い**。
 * 残すには鍵を平文から導く必要があり、そうするとサーバー上で同一ファイルが同一になる
 * ＝どの利用者が同じ写真を持っているかがサーバーに分かる。§3 自身がそれを重複排除の
 * 代償として挙げていた。保管庫を暗号化したいま、その代償は払う価値が無いので逆を取る。
 * 1回の送信の中での重複排除は、送る前に id を照会するので今も効く。
 *
 * 取得したものは `filesDir/media/<id>` に置いて以後そこから読む。届いた暗号文は
 * ハッシュを取り、要求した id と合わなければ捨てる（id そのものが検査になるので、
 * サーバーが正しいものを返すことを信用しなくてよい）。復号でもう一度、区間ごとに、
 * サーバーが持っていない鍵で検査される。
 */
class MediaRepository(
    private val context: Context,
    private val db: FirebaseFirestore,
    /** Firebase の無いビルドでは null。その場合は取得済みの写真だけが読める。 */
    private val store: MediaStoreClient? = null,
) {

    private val directory = File(context.filesDir, DIR)

    /** デコード済みの画像。同じ写真を通り過ぎるたびにデコードし直さないため。 */
    private val decoded = ConcurrentHashMap<String, ImageBitmap>()

    /** 無いと分かっている id。毎フレーム取りに行かないため。 */
    private val absent = ConcurrentHashMap<String, Boolean>()

    /**
     * 送信側がメッセージに入れる必要があるもの。
     *
     * [sealedBytes] は**暗号化後**の大きさ。範囲要求で動画を読む再生側は、これを
     * 知らないと何も探せない。動画1本につき1往復増やす代わりに一緒に運ぶ。
     */
    class Stored(
        val id: MediaId,
        val key: ByteArray,
        val byteCount: Int,
        val sealedBytes: Long = 0,
    )

    /** すでに端末にある写真。通信は一切しない。 */
    fun cached(id: MediaId): ImageBitmap? {
        decoded[id.value]?.let { return it }
        val file = fileFor(id).takeIf(File::exists) ?: return null
        // swallow-ok: 手元の複製がデコードできなければ「無い」として取り直す。
        // 伝えたところで呼び出し側にできることは変わらない。
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        return bitmap.asImageBitmap().also { decoded[id.value] = it }
    }

    fun isKnownMissing(id: MediaId): Boolean = absent[id.value] == true

    /** 端末上の平文ファイル。ギャラリー保存や再生に渡す。 */
    fun fileOf(id: MediaId): File? = fileFor(id).takeIf(File::exists)

    /**
     * 端末に無い写真を取りに行く。
     *
     * @param key 封をしたメッセージから来る。null は保管庫より前の写真で、その場合は
     *   Firestore から読む（そのメッセージはまだ2台の端末にあり、開ける必要がある）。
     * @return 新しく届いたら true。呼び出し側が描き直すため。
     */
    suspend fun fetch(id: MediaId, key: ByteArray? = null): Boolean {
        if (cached(id) != null) return false
        if (absent[id.value] == true) return false

        return if (key == null) fetchLegacy(id) else fetchStored(id, key)
    }

    private suspend fun fetchStored(id: MediaId, key: ByteArray): Boolean {
        val client = store ?: return false
        val sealed = File(directory, id.value + SEALED_SUFFIX)

        val ok = withContext(Dispatchers.IO) {
            directory.mkdirs()
            client.download(id.value, sealed)
                .onFailure {
                    // これが無いと「圏外」「消された」「ルールに拒否された」が、
                    // どこにも説明の無い1つの灰色い箱になる。
                    android.util.Log.w("Rinowa/media", "download " + id.value.take(8) + " failed", it)
                }
                .isSuccess
        }
        if (!ok) {
            sealed.delete()
            absent[id.value] = true
            return false
        }

        // id は暗号文のハッシュ。合わないバイト列は、サーバーが何と言おうと
        // 要求したファイルではない。
        if (hashOf(sealed) != id.value) {
            android.util.Log.w("Rinowa/media", "hash mismatch for " + id.value.take(8))
            sealed.delete()
            absent[id.value] = true
            return false
        }

        val opened = withContext(Dispatchers.IO) {
            runCatching {
                MediaCipher.open(sealed, key).use { plain ->
                    fileFor(id).outputStream().use(plain::copyTo)
                }
            }
                // 開かない鍵は本物の失敗なので見えないといけない。メッセージと
                // オブジェクトが食い違っているということで、再試行では直らない。
                .onFailure { android.util.Log.w("Rinowa/media", "decrypt failed", it) }
                .isSuccess
        }
        sealed.delete()

        if (!opened) {
            fileFor(id).delete()
            absent[id.value] = true
            return false
        }
        return true
    }

    /** 保管庫より前に送られた写真。Firestore に平文のまま、当時のかたちで残っている。 */
    private suspend fun fetchLegacy(id: MediaId): Boolean {
        val bytes = runCatching {
            db.collection(RinowaDb.Media.COLLECTION).document(id.value)
                .get().await()
                .getBlob(RinowaDb.Media.BYTES)?.toBytes()
        }
            .onFailure { android.util.Log.w("Rinowa/media", "legacy fetch failed", it) }
            .getOrNull()

        if (bytes == null || ContentHash.of(bytes).value != id.value) {
            absent[id.value] = true
            return false
        }

        withContext(Dispatchers.IO) {
            directory.mkdirs()
            fileFor(id).writeBytes(bytes)
        }
        return true
    }

    /**
     * 写真を暗号化して送り、メッセージが運ぶべきものを返す。
     *
     * 送信はメッセージより先。逆だと、まだ無いバイト列を指すメッセージが画面に出る。
     */
    suspend fun publish(bytes: ByteArray): Result<Stored> = runCatching {
        val client = store ?: error("写真の保存先が使えません")
        val key = MediaCipher.newKey()

        val sealed = withContext(Dispatchers.IO) {
            directory.mkdirs()
            File.createTempFile("upload", null, directory)
        }

        val id = try {
            val id = withContext(Dispatchers.IO) { MediaCipher.seal(bytes, sealed, key) }

            // まず手元に、メッセージが運ぶのと同じ id で置く。送信が成功してもしなくても
            // 送信者には自分の写真がすぐ見えるべきで、そもそも端末にあるものなので。
            withContext(Dispatchers.IO) { fileFor(MediaId(id)).writeBytes(bytes) }
            decoded.remove(id)
            absent.remove(id)

            if (!client.exists(id)) client.upload(id, sealed).getOrThrow()
            id
        } finally {
            sealed.delete()
        }

        Stored(MediaId(id), key, bytes.size)
    }

    /**
     * すでにディスク上にあるファイル（変換済みの動画）を暗号化して送る。
     *
     * バイト配列版と分けているのは、動画が数百MBになるから。暗号化のために丸ごと
     * メモリに読むと中位機では殺される。ここは全部ストリームで流す。
     */
    suspend fun publishFile(
        source: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Stored> = runCatching {
        val client = store ?: error("動画の保存先が使えません")
        val key = MediaCipher.newKey()

        val sealed = withContext(Dispatchers.IO) {
            directory.mkdirs()
            File.createTempFile("upload", null, directory)
        }

        try {
            val id = withContext(Dispatchers.IO) { MediaCipher.seal(source, sealed, key) }

            // メッセージが運ぶ id で手元にも置く。送信者が、いま送った動画を
            // 取り直さずに再生できるように。
            withContext(Dispatchers.IO) { source.copyTo(fileFor(MediaId(id)), overwrite = true) }

            if (!client.exists(id)) client.upload(id, sealed, onProgress = onProgress).getOrThrow()
            Stored(MediaId(id), key, source.length().toInt(), sealed.length())
        } finally {
            sealed.delete()
        }
    }

    /**
     * 送信者が選んだ、手を加えていないファイルを送る。
     *
     * メモリに読まずに流す（オリジナルはMB単位で、しかも同じ端末がこのあと表示する）。
     * 他と同じく専用の鍵で暗号化するので、サーバーにはもう1つ読めない塊が増えるだけ。
     */
    suspend fun publishOriginal(source: java.io.InputStream): Result<Stored> = runCatching {
        val client = store ?: error("写真の保存先が使えません")
        val key = MediaCipher.newKey()

        val plain = withContext(Dispatchers.IO) {
            directory.mkdirs()
            File.createTempFile("original", null, directory).also { file ->
                file.outputStream().use { out -> source.copyTo(out) }
            }
        }
        val sealed = withContext(Dispatchers.IO) {
            File.createTempFile("sealed", null, directory)
        }

        try {
            val id = withContext(Dispatchers.IO) { MediaCipher.seal(plain, sealed, key) }
            if (!client.exists(id, original = true)) {
                client.upload(id, sealed, original = true).getOrThrow()
            }
            Stored(MediaId(id), key, plain.length().toInt(), sealed.length())
        } finally {
            plain.delete()
            sealed.delete()
        }
    }

    /**
     * オリジナルを取りに行く。頼まれたときだけで、スレッドの表示では絶対に呼ばない。
     *
     * 保管庫は30日で消す（media_common.php）。過ぎれば失敗し、それが正直な結果。
     * 圧縮版は残っていて、画面は「オリジナルは期限切れ」と言う。
     */
    suspend fun fetchOriginal(id: MediaId, key: ByteArray): Result<File> = runCatching {
        val client = store ?: error("写真の保存先が使えません")
        val sealed = File(directory, id.value + SEALED_SUFFIX)
        val plain = File(directory, id.value + ORIGINAL_SUFFIX)

        try {
            withContext(Dispatchers.IO) { directory.mkdirs() }
            client.download(id.value, sealed, original = true).getOrThrow()
            check(hashOf(sealed) == id.value) { "元のファイルが壊れています" }
            withContext(Dispatchers.IO) {
                MediaCipher.open(sealed, key).use { opened ->
                    plain.outputStream().use(opened::copyTo)
                }
            }
            plain
        } finally {
            sealed.delete()
        }
    }

    /**
     * オリジナルを黙って送らない理由。
     *
     * docs/MEDIA_ARCHITECTURE.md では、両方の端末で「オリジナルを保存」が効くように
     * 送信側が任意で無加工のファイルも上げる案だった。**そうはしていない。**
     * クラウドに置くのは圧縮版だけという指示があり、それが正しい。オリジナルは写真で
     * 3〜15MB、動画なら数百MBで、それを溜める保管庫はこの設計が避けたかったもの。
     *
     * 結果として（ボタンを灰色にして隠すのではなく、はっきり書く）オリジナルは
     * 撮った端末にしか無い。送信者は元から持っているので保存できるが、受け取った側に
     * あるのは圧縮版だけで、選ぶものが無い。サーバー側の `orig` 区分は、この
     * クライアントからは使っていない。
     */

    private suspend fun hashOf(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fileFor(id: MediaId) = File(directory, id.value)

    private companion object {
        const val DIR = "media"

        /** 出入りする暗号文。置きっぱなしにはしない。 */
        const val SEALED_SUFFIX = ".sealed"

        /** 取得したオリジナル。ギャラリーに書き出すまでの置き場。 */
        const val ORIGINAL_SUFFIX = ".original"
    }
}
