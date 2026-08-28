package blog.nextlab.echo.media

import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * `echo.nextlab.blog` の保管庫に対する端末側。
 *
 * ここを通るものは全部暗号文（[MediaCipher]）なので、このファイルは転送だけを扱う。
 * 送り、取り戻し、エレベーターやトンネルで切れたら再開する。
 *
 * 分割して送るのは、モバイル回線の動画送信が必ず途切れるから。POST 1回だと復帰は
 * 最初からやり直すしかなく、階段を降りるたびに 200MB を送り直す機能は二度と使われない。
 * サーバーは途中のファイルを持っていて、どこまで受けたかを答える。[upload] はそこから続ける。
 *
 * 無限に再試行しないのは、失敗が送信を押した人に届く必要があるから。良い回線で
 * やり直すか決められるのはその人だけで、黙って再試行するのは、送れたように見えている
 * 間にその人の電池と通信量を使うこと。
 */
class MediaStoreClient(private val auth: FirebaseAuth) {

    /**
     * [ciphertext] を送り、サーバーが [id] として持った時点で戻る。
     *
     * @param id ファイルの SHA-256（小文字16進）。[MediaCipher.seal] から。
     * @param original 送信者が選んだ無加工のファイルなら true。サーバーは期限付きで
     *   保管する。false は全員が見る圧縮版。
     * @param onProgress これまでに受け付けられたバイト数。吹き出しの進捗リング用。
     */
    suspend fun upload(
        id: String,
        ciphertext: File,
        original: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val token = idToken()
            val total = ciphertext.length()
            val klass = klass(original)
            var offset = 0L

            ciphertext.inputStream().use { source ->
                val buffer = ByteArray(CHUNK_BYTES)
                var first = true
                while (true) {
                    val read = maxOf(source.read(buffer), 0)
                    val final = offset + read >= total
                    val reply = try {
                        post(
                            url = ENDPOINT + "?id=" + id + "&class=" + klass + "&offset=" + offset +
                                if (final) "&final=1" else "",
                            token = token,
                            body = buffer,
                            length = read,
                        )
                    } catch (mismatch: OffsetMismatch) {
                        // 前回の送信が途中で切れていて、サーバーはそこまで持っている。
                        //
                        // **1個目だけ受ける。** 2個目以降で食い違ったら、それは
                        // いま書いているファイルについて認識がずれたということで、
                        // そこから続けると穴を書き込む。穴のあるファイルもハッシュは
                        // 取れて保管もされ、数か月後に復号できず、原因を指すものが
                        // 何も残らない。
                        if (!first) throw mismatch
                        resumeFrom(source, mismatch.expected, total)
                        offset = mismatch.expected
                        onProgress(offset, total)
                        first = false
                        continue
                    }
                    first = false

                    // すでにある。同じ写真を2回転送した、または失敗後の再送。
                    // 内容アドレスなのでこれは無料。
                    if (reply.optBoolean("stored")) {
                        onProgress(total, total)
                        return@runCatching
                    }

                    offset += read
                    onProgress(offset, total)
                    if (final) return@runCatching
                }
            }
        }
    }

    /** 保管庫がすでに [id] を持っていれば true。送る必要が無い。 */
    suspend fun exists(id: String, original: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            // 失敗したら「無い」とみなす。余分な送信1回で済むほうが安全側。逆にすると、
            // 実際には起きなかった送信を飛ばして、相手の端末に灰色の箱が残る。
            // swallow-ok: 「分からない」を意図的に「無い」として扱う。
            runCatching {
                val connection = open(DOWNLOAD + "?id=" + id + "&class=" + klass(original), idToken())
                connection.requestMethod = "HEAD"
                val status = connection.responseCode
                connection.disconnect()
                status == HttpURLConnection.HTTP_OK
            }
                // swallow-ok: これが答えるのは「送信を省けるか」。失敗時は無いとみなし、
                // 余分な送信1回を払う。逆向きは、起きなかった送信を飛ばして相手の端末に
                // 永久に灰色の箱を残す。続く送信は自分で失敗を報告する。
                .getOrDefault(false)
        }

    /** オブジェクト全体を [destination] へ取得する。 */
    suspend fun download(
        id: String,
        destination: File,
        original: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(DOWNLOAD + "?id=" + id + "&class=" + klass(original), idToken())
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IOException("media_get returned " + status)
            }
            val total = connection.contentLengthLong
            destination.parentFile?.mkdirs()
            connection.inputStream.use { source ->
                destination.outputStream().use { sink ->
                    val buffer = ByteArray(CHUNK_BYTES)
                    var written = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            connection.disconnect()
        }
    }

    /**
     * 読み取りを範囲要求に変える読み出し専用のチャネル。
     *
     * [MediaCipher.openSeekable] に渡すと、動画がファイル全体を待たずに1秒ほどで
     * 再生を始められる。何も溜めない。プレイヤーはこれから出すところを要求し、
     * 飛ばした部分はそもそも取りに行かない。
     */
    fun channel(id: String, size: Long, original: Boolean = false): SeekableByteChannel =
        RangeChannel(this, id, size, klass(original))

    internal suspend fun idToken(): String =
        auth.currentUser?.getIdToken(false)?.await()?.token
            ?: throw IOException("not signed in")

    /** 範囲をちょうど1つ読む。ブロックする。プレイヤー自身のスレッドから呼ばれる。 */
    internal fun range(id: String, klass: String, token: String, from: Long, to: Long): ByteArray {
        val connection = open(DOWNLOAD + "?id=" + id + "&class=" + klass, token)
        connection.setRequestProperty("Range", "bytes=" + from + "-" + to)
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_PARTIAL && status != HttpURLConnection.HTTP_OK) {
                throw IOException("range request returned " + status)
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** サーバーが「そこではない、ここまで持っている」と答えたときの位置。 */
    private class OffsetMismatch(val expected: Long) :
        IOException("media upload offset mismatch, server has " + expected)

    private fun post(url: String, token: String, body: ByteArray, length: Int): JSONObject {
        val connection = open(url, token)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(length)
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.outputStream.use { it.write(body, 0, length) }

        val status = connection.responseCode
        val text = runCatching {
            if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
        }
            // swallow-ok: これはステータスコードに付いた説明であって結果ではない。
            // 本文が読めなくてもすぐ下で投げる例外の詳細が減るだけで、*説明について*
            // 投げると本物のエラーをより悪いものに置き換えることになる。
            .getOrElse { "" }
        connection.disconnect()

        if (status == HttpURLConnection.HTTP_CONFLICT) {
            // サーバーが実際に持っている量。呼び元がそこから続ける。
            //
            // swallow-ok: 読めない 409 は、再開の指示になっていないというだけ。すぐ下の
            // 分岐が本文つきの IOException で投げるので、失敗は失敗として外へ出る。
            // ここで投げると、409 の意味を調べる処理の都合が、通信の失敗を上書きする。
            val expected = runCatching { JSONObject(text).getLong("expected") }.getOrNull()
            if (expected != null) throw OffsetMismatch(expected)
        }

        if (status !in 200..299) {
            throw IOException("media upload returned " + status + ": " + text.take(200))
        }
        // ステータスはすでに成功。JSON でない返事は項目が欠けているということで、
        // 読み出しには全部既定値がある。空オブジェクトがその状態。ここで投げると、
        // サーバーが受け付けた送信を失敗にしてしまう。
        // swallow-ok: 受理済みの送信に対する解釈できない返事は空オブジェクトとして扱う。
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    private fun open(url: String, token: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer " + token)
        }

    private fun klass(original: Boolean) = if (original) CLASS_ORIGINAL else CLASS_PERMANENT

    private companion object {
        const val ENDPOINT = "https://echo.nextlab.blog/media.php"
        const val DOWNLOAD = "https://echo.nextlab.blog/media_get.php"
        const val CLASS_PERMANENT = "perm"
        const val CLASS_ORIGINAL = "orig"

        /** サーバー側の上限より十分小さく、やり直しても安い大きさ。 */
        const val CHUNK_BYTES = 4 * 1024 * 1024
        const val TIMEOUT_MS = 30_000
    }
}

/**
 * HTTP オブジェクトの、位置を指定して読める側。
 *
 * Tink の復号チャネルは任意の位置から区間を丸ごと読むので、その読み取り1回を
 * `Range:` 要求1回に変える。直前に取った窓は保持する。復号の読み取りは1区間を
 * 数回に分けて要求するので、取り直すと動画の通信量が倍になる。
 */
private class RangeChannel(
    private val client: MediaStoreClient,
    private val id: String,
    private val size: Long,
    private val klass: String,
) : SeekableByteChannel {

    private var position = 0L
    private var open = true
    private var window: ByteArray = ByteArray(0)
    private var windowStart = -1L
    private val token: String by lazy {
        kotlinx.coroutines.runBlocking { client.idToken() }
    }

    override fun read(destination: ByteBuffer): Int {
        if (position >= size) return -1
        val want = minOf(destination.remaining().toLong(), size - position).toInt()
        if (want <= 0) return 0

        val held = windowStart >= 0 &&
            position >= windowStart &&
            position + want <= windowStart + window.size
        if (!held) {
            val from = position
            val to = minOf(size - 1, from + maxOf(want.toLong(), WINDOW) - 1)
            window = client.range(id, klass, token, from, to)
            windowStart = from
        }

        val offset = (position - windowStart).toInt()
        val available = minOf(want, window.size - offset)
        if (available <= 0) return -1
        destination.put(window, offset, available)
        position += available
        return available
    }

    override fun position(): Long = position

    override fun position(newPosition: Long): SeekableByteChannel {
        position = newPosition
        return this
    }

    override fun size(): Long = size
    override fun isOpen(): Boolean = open

    override fun close() {
        open = false
        window = ByteArray(0)
        windowStart = -1
    }

    // 見ている最中のファイルに書き込みは起きない。
    override fun write(source: ByteBuffer): Int = throw UnsupportedOperationException()

    override fun truncate(newSize: Long): SeekableByteChannel =
        throw UnsupportedOperationException()

    private companion object {
        /** 1区間＋その付随分。区間の読み取りが要求1回で済むように。 */
        const val WINDOW = (1L shl 20) + 4096
    }
}

/**
 * サーバーがすでに持っているところまで読み飛ばす。
 *
 * [java.io.InputStream.skip] は要求より少なく進んでよい、と契約に書いてある。戻り値を
 * 見ずに1回で済ませると、そのぶんずれた位置から送ることになり、**中身のずれたファイルが
 * 正しいハッシュの名前で保管される**。転送は成功し、画面には何も出ない。
 *
 * クラスの外にあるのは、鍵も通信も要らないから。UploadResumeTest を参照。
 */
internal fun resumeFrom(source: java.io.InputStream, target: Long, total: Long) {
    if (target <= 0L || target > total) {
        throw IOException("media upload resume out of range: " + target + " of " + total)
    }
    var skipped = 0L
    while (skipped < target) {
        val step = source.skip(target - skipped)
        if (step <= 0L) throw IOException("media upload could not resume at " + target)
        skipped += step
    }
}
