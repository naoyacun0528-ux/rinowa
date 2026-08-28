package blog.nextlab.echo.calls

import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.PeerConnection

/**
 * 2台の端末の間の経路を、どこに尋ねるか。
 *
 * 埋め込まず取りに行くのは、**TURN の資格情報が短命でなければならない**から。
 * APK に焼いた中継のパスワードは数分で抜き出せて、そのあと中継は他人の帯域になる。
 * 標準のやり方は `<期限>:<uid>` を利用者名にし、その HMAC をパスワードにするもので、
 * 秘密鍵を持つサーバーが要る。それが `server/ice.php` で、窓口の形も
 * トークン検証も `push.php` と同じ。
 *
 * もう1つの理由も同じくらい重い。**TURN の提供元は変わる。** 埋め込んでいると
 * 変更のたびにアプリのリリースが要り、半分の端末が古い一覧のまま動く時間ができる。
 * 取りに行く形なら PHP を1つ直すだけで、各端末は次の通話から拾う。
 *
 * 取得に失敗したら公開の STUN に落とす。同じ Wi-Fi にいる2台にはそれで足りるし、
 * 通話が試されるのはたいていその状況。設定の窓口が一瞬届かなかったせいで通話が
 * 始まらないより、中継なしでも繋がるほうがよい。
 */
object IceServers {

    private const val ENDPOINT = "https://echo.nextlab.blog/ice.php"

    /** 同じ回線なら足りる。別のキャリア同士では絶対に足りない。 */
    private val stunOnly: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
    )

    private var cached: List<PeerConnection.IceServer>? = null
    private var cachedUntilMs: Long = 0

    /** 取得に失敗すると入る。通話画面が、中継が無い理由を出せるように。 */
    var lastError: String? = null
        private set

    suspend fun current(): List<PeerConnection.IceServer> {
        val now = System.currentTimeMillis()
        cached?.let { if (now < cachedUntilMs) return it }

        val fetched = runCatching { fetch() }.getOrElse {
            lastError = "${it::class.simpleName}: ${it.message.orEmpty()}"
            null
        }

        return if (fetched != null && fetched.isNotEmpty()) {
            lastError = null
            cached = fetched
            // 30分。サーバーが発行する資格情報の有効期間には十分収まる。2回目の通話が
            // 往復を払わない程度に長く、提供元の変更が同じ日のうちに全員へ届く程度に短く。
            cachedUntilMs = now + 30 * 60_000
            fetched
        } else {
            stunOnly
        }
    }

    /** 一覧に STUN だけでなく本当に中継が入っていれば true。 */
    fun hasRelay(servers: List<PeerConnection.IceServer>): Boolean =
        servers.any { server -> server.urls.any { it.startsWith("turn:") || it.startsWith("turns:") } }

    private suspend fun fetch(): List<PeerConnection.IceServer> = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser ?: error("not signed in")
        val token = user.getIdToken(false).await().token ?: error("no id token")

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
        }
        connection.outputStream.use { it.write("{}".toByteArray()) }

        val status = connection.responseCode
        val payload = if (status in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("ice.php returned $status $detail")
        }

        val array = JSONObject(payload).getJSONArray("iceServers")
        buildList {
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val urls = entry.getJSONArray("urls").let { list ->
                    List(list.length()) { index -> list.getString(index) }
                }
                val builder = PeerConnection.IceServer.builder(urls)
                entry.optString("username").takeIf { it.isNotEmpty() }?.let(builder::setUsername)
                entry.optString("credential").takeIf { it.isNotEmpty() }?.let(builder::setPassword)
                add(builder.createIceServer())
            }
        }
    }
}
