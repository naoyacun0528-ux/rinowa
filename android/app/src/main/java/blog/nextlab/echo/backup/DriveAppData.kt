package blog.nextlab.echo.backup

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本人の Google ドライブの、このアプリに属する部分。
 *
 * `appDataFolder` はドライブの中のアプリ専用の隠し領域。本人のファイル一覧には出ず、
 * 間違って片付けられる書類にもならない。そして決め手は、この領域のスコープを Google が
 * **非センシティブ**に分類していること。出荷に必要なのは基本的なアプリ審査だけで、
 * 説明動画と有料の第三者セキュリティ監査は要らない。広いドライブのスコープは両方要る。
 * docs/RESEARCH_E2EE.md §3.1 で、公開されているスコープ表と突き合わせて確認した。
 *
 * このクラスは暗号化ではない。[upload] に渡ってくるものはすでに封をされていて、
 * 読もうと思っても読めない。バックアップと、その置き場所との関係はそうあるべき。
 * [BackupCipher] を参照。
 *
 * アクセストークンは外から [token] で来る。取得には Play Services と activity と
 * 同意画面が絡み、HTTP を4回投げるだけのクラスの仕事ではない。おかげで文字列を
 * 渡すだけで試験できる。
 */
class DriveAppData(private val token: suspend () -> String) {

    /** 隠し領域にあるバックアップ1件。 */
    class Item(val id: String, val name: String, val modifiedAtMs: Long, val bytes: Long)

    /** 新しい順。まだ何も無ければ空で、初回前はその状態。 */
    suspend fun list(): Result<List<Item>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = FILES + "?spaces=appDataFolder" +
                "&orderBy=modifiedTime%20desc" +
                "&fields=" + encode("files(id,name,modifiedTime,size)") +
                "&pageSize=" + PAGE_SIZE
            val body = get(url)
            val files = JSONObject(body).optJSONArray("files") ?: JSONArray()

            buildList {
                for (i in 0 until files.length()) {
                    val file = files.optJSONObject(i) ?: continue
                    add(
                        Item(
                            id = file.optString("id"),
                            name = file.optString("name"),
                            modifiedAtMs = parseTime(file.optString("modifiedTime")),
                            bytes = file.optString("size").toLongOrNull() ?: 0L,
                        ),
                    )
                }
            }
        }
    }

    /**
     * [content] を新しいファイルとして書く。
     *
     * 上書きではなく毎回新規。前のものを置き換える形だと、どちらも完全でない瞬間ができる。
     * これから失われる端末は、まさに送信が途中で切れる端末でもある。古いものは、
     * 代わりに残すものができてから [prune] が消す。
     */
    suspend fun upload(name: String, content: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val boundary = "rinowa" + content.size + "x" + name.hashCode()
                val metadata = JSONObject()
                    .put("name", name)
                    .put("parents", JSONArray().put("appDataFolder"))
                    .toString()

                val head = (
                    "--" + boundary + "\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                        metadata + "\r\n" +
                        "--" + boundary + "\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                    ).toByteArray()
                val tail = ("\r\n--" + boundary + "--\r\n").toByteArray()

                val connection = open(UPLOAD + "?uploadType=multipart&fields=id")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "multipart/related; boundary=" + boundary,
                )
                connection.setFixedLengthStreamingMode(head.size + content.size + tail.size)
                connection.outputStream.use { out ->
                    out.write(head)
                    out.write(content)
                    out.write(tail)
                }

                val status = connection.responseCode
                val body = read(connection, status)
                connection.disconnect()
                if (status !in 200..299) throw IOException("drive upload " + status + ": " + body.take(200))

                JSONObject(body).optString("id")
            }
        }

    suspend fun download(id: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open(FILES + "/" + id + "?alt=media")
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = read(connection, status)
                connection.disconnect()
                throw IOException("drive download " + status + ": " + body.take(200))
            }
            val bytes = connection.inputStream.use { it.readBytes() }
            connection.disconnect()
            bytes
        }
    }

    /**
     * 新しい [keep] 件を残して消す。
     *
     * 送信が成功したあとにだけ呼ぶ。他人のドライブを1年分の日次バックアップで
     * 埋めてはいけないし、終わっていない送信を根拠に空にしてもいけない。
     */
    suspend fun prune(keep: Int = KEEP): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val stale = list().getOrThrow().drop(keep)
            var removed = 0
            for (item in stale) {
                val connection = open(FILES + "/" + item.id)
                connection.requestMethod = "DELETE"
                val status = connection.responseCode
                connection.disconnect()
                if (status in 200..299 || status == HttpURLConnection.HTTP_NOT_FOUND) removed++
            }
            removed
        }
    }

    private suspend fun get(url: String): String {
        val connection = open(url)
        val status = connection.responseCode
        val body = read(connection, status)
        connection.disconnect()
        if (status !in 200..299) throw IOException("drive " + status + ": " + body.take(200))
        return body
    }

    private suspend fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer " + token())
        }

    private fun read(connection: HttpURLConnection, status: Int): String =
        runCatching {
            if (status in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
        }
            // ステータスコードに付いた説明であって結果ではない。本文が読めなければ
            // 呼び出し側が投げるエラーの詳細が減るだけで、*説明について*投げると
            // 本物の失敗をより悪いものに置き換える。
            // swallow-ok: 読めない本文は文言が粗くなるだけ。
            .getOrDefault("")

    /** RFC 3339。ドライブが返す形式。それ以外なら0。 */
    private fun parseTime(value: String): Long =
        runCatching {
            java.time.Instant.parse(value).toEpochMilli()
        }
            // swallow-ok: この時刻は一覧の行に出す表示。日付が解釈できないファイルでも
            // 復元はできるし、並び順はサーバー側から来ている。
            .getOrDefault(0L)

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val TIMEOUT_MS = 30_000
        const val PAGE_SIZE = 20

        /**
         * 残す件数。
         *
         * 1件では足りない。最新のものは、すでに調子がおかしくなっていた端末が書いた
         * 可能性が一番高い。かといって多くもしない。使っているのは本人の15GBだから。
         */
        const val KEEP = 3
    }
}
