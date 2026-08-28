package blog.nextlab.echo.ui.direct

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.nextlab.echo.auth.AuthState
import blog.nextlab.echo.calls.NetworkProbe
import blog.nextlab.echo.core.wire.YosegiBenchmark
import blog.nextlab.echo.crypto.CryptoEngine
import blog.nextlab.echo.direct.AuthenticatedPeer
import blog.nextlab.echo.direct.DirectConnectionState
import blog.nextlab.echo.direct.DirectPreference
import blog.nextlab.echo.direct.DirectTier
import blog.nextlab.echo.direct.DirectTransport
import blog.nextlab.echo.direct.DiscoveredPeer
import blog.nextlab.echo.direct.LanDirectTransport
import blog.nextlab.echo.direct.NearbyDirectTransport
import blog.nextlab.echo.direct.PeerLink
import blog.nextlab.echo.model.ConversationId
import blog.nextlab.echo.model.UserId
import com.google.android.gms.nearby.connection.BandwidthInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Direct-1 の検証画面を動かす。
 *
 * 2つの経路を同じ2台・同じ場所で比べられるよう両方を持つ。製品ではこの選択を
 * 見せない（docs/DIRECT_ARCHITECTURE.md §8.1）が、どちらを優先するかは実測から
 * 決める必要があり、その数字はここから出る。
 */
class DirectLabViewModel(context: Context) : ViewModel() {

    private val app = context.applicationContext
    private val nearby: DirectTransport by lazy { NearbyDirectTransport(app) }
    private val lan: DirectTransport by lazy { LanDirectTransport(app) }

    var tier by mutableStateOf(DirectTier.Nearby)
        private set

    /**
     * 自動ではなく明示的に選ぶ。
     *
     * 放っておくと Nearby は同じ回線にいる限り Wi-Fi に切り替わるので、繋がっても
     * 「回線が無くても繋がるか」の答えにならない。[DirectPreference.OfflineCapable] は
     * Bluetooth に留める。遅いが、オフラインの主張が意味を持つ唯一の設定。
     */
    var preference by mutableStateOf(DirectPreference.Fastest)
        private set

    fun selectPreference(next: DirectPreference) {
        if (next == preference) return
        val wasRunning = discovering
        if (wasRunning) stop()
        preference = next
        peers.clear()
        connected.clear()
        // この設定は広告を始めた時点で固定される。次回から効く設定にしないよう、
        // ここで開始し直す。
        if (wasRunning) start()
    }

    var discovering by mutableStateOf(false)
        private set

    var status by mutableStateOf<String?>(null)
        private set

    /**
     * 広告に載せるこの端末の名前。
     *
     * 機種名。2台を手に持つ開発者が区別できる必要がある。**検証専用** —
     * docs/DIRECT_THREAT_MODEL.md T1 は識別できるものの発信を禁じており、
     * Direct-2 では入れ替わるトークンに置き換える。
     */
    val myLabel: String = "${Build.MODEL}-${(1000..9999).random()}"

    val peers = mutableStateListOf<DiscoveredPeer>()
    val log = mutableStateListOf<String>()

    private val connected = mutableMapOf<String, AuthenticatedPeer>()

    /**
     * 相手を見つけ次第つなぐ。
     *
     * 製品もこう動く（誰も何も押さない）。切り替えを出しているのは、発見と接続を
     * 別々に計りたいときのため。docs/ROADMAP.md の Direct-1。
     */
    var autoConnect by mutableStateOf(true)
        private set

    fun toggleAutoConnect() {
        autoConnect = !autoConnect
    }
    private var discoveryJob: Job? = null
    private var receiveJob: Job? = null
    private var startedAt = 0L

    private val transport: DirectTransport get() = if (tier == DirectTier.Nearby) nearby else lan

    fun selectTier(next: DirectTier) {
        if (next == tier) return
        stop()
        tier = next
        peers.clear()
        connected.clear()
        status = null
    }

    /** Nearby 側が、何も尋ねずに開始できるなら true。 */
    fun hasPermissions(): Boolean = nearbyPermissions().all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * この端末が実際に必要とするものを、この端末に聞く。
     *
     * `ACCESS_LOCAL_NETWORK` は Android 16 で入ったので `SDK_INT >= 36` で判定したく
     * なるが、それは違う。arrows We2 (F-52E) は API 36 を名乗りながら**その権限を
     * 持っていない**（`pm grant` は Unknown permission と答える）。そこで要求すると
     * 永久に許可されない要求になり、アプリは拒否されたと判断して探索を始めなかった。
     *
     * 同じ形の間違いはこれで2度目。1度目は触覚で、同じ端末が API 36 でエンベロープを
     * 持っておらず、段階の判定をバージョンではなく振動子への問い合わせに変えた。
     * バージョン番号が表すのは API であって端末ではない。
     */
    private fun nearbyPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            add(android.Manifest.permission.BLUETOOTH_SCAN)
            add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissionExists(ACCESS_LOCAL_NETWORK)) add(ACCESS_LOCAL_NETWORK)
    }

    /** この Android がその権限を定義しているかをパッケージマネージャに聞く。 */
    // swallow-ok: 例外そのものが答え。定義されていなければ NameNotFoundException が
    // 飛び、それが「存在しない」ということ。
    private fun permissionExists(name: String): Boolean = runCatching {
        app.packageManager.getPermissionInfo(name, 0)
        true
    }.getOrDefault(false)

    /** 画面がこの端末に必要な分だけ要求できるように公開する。 */
    fun requiredPermissions(): Array<String> = nearbyPermissions().toTypedArray()

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            start()
        } else {
            // 断るのも正しい答えで、どちらでもクラウド経由の Rinowa は動く。
            status = "権限が無いと近くの端末を探せません。クラウド経由の送受信はそのまま使えます。"
        }
    }

    fun start() {
        if (discovering) return
        discovering = true
        status = null
        startedAt = System.currentTimeMillis()
        note("探索開始 (${tier.name})")

        discoveryJob = viewModelScope.launch {
            runCatching {
                transport.discoverPeers(myLabel, preference).collect { peer ->
                    if (peers.none { it.endpointId == peer.endpointId }) {
                        peers.add(peer)
                        // Direct-1 が出したい数字。
                        note("発見 ${peer.advertisedLabel} — ${System.currentTimeMillis() - startedAt}ms")
                        // 見つけたら繋ぐ。製品では誰にも「接続」を押させない
                        // （docs/DIRECT_ARCHITECTURE.md §2.2）ので、検証画面でも押させない。
                        if (autoConnect) connect(peer)
                    }
                }
            }.onFailure { error ->
                // 中止は失敗ではない。
                //
                // stop() はこのジョブを取り消し、collect の中で CancellationException に
                // なる。runCatching はそれも捕まえるので、停止を押した人に
                // 「開始できませんでした」と表示していた。
                // コルーチンで runCatching を使うときの定番の罠で、suspend を包む場所ごとに
                // 手で外す必要がある。
                if (error is kotlinx.coroutines.CancellationException) return@onFailure

                discovering = false
                // プラットフォームの言葉のまま出す。気を利かせた推測のせいで1回誤診した
                // （Bluetooth はずっと入っていた）。
                //
                // クラス名は最後の手段。release では R8 が名前を潰すので `simpleName` は
                // "ol1" のような無意味な字になる。一番診断しづらいビルドで診断が空になった。
                val detail = (error as? NearbyDirectTransport.NearbyStartFailure)
                    ?.let { "${it.statusName} (${it.statusCode ?: "-"})" }
                    ?: error.message
                    ?: error::class.simpleName.orEmpty()
                status = when (tier) {
                    DirectTier.Nearby -> "開始できませんでした — $detail"
                    DirectTier.Lan ->
                        "開始できませんでした — $detail\n" +
                            "同じWi-Fiか、APアイソレーションで端末間通信が遮断されていないかも確認してください。"
                }
                note("失敗 $detail")
            }
        }

        receiveJob = viewModelScope.launch {
            transport.receive().collect { payload ->
                note("受信 ← ${payload.asText}")
            }
        }
    }

    fun stop() {
        discoveryJob?.cancel()
        receiveJob?.cancel()
        viewModelScope.launch { transport.stopDiscovery() }
        discovering = false
        note("探索停止")
    }

    fun connect(peer: DiscoveredPeer) {
        val began = System.currentTimeMillis()
        viewModelScope.launch {
            transport.connect(peer).fold(
                onSuccess = { link ->
                    // Direct-1 限定。Direct-2 では docs/DIRECT_ARCHITECTURE.md §6 の
                    // チャレンジ・レスポンスに置き換わり、コンパイラがここを指す。
                    connected[peer.endpointId] = transport.trustForTesting(link)
                    note("接続成立 ${peer.advertisedLabel} — ${System.currentTimeMillis() - began}ms")
                },
                onFailure = { error ->
                    // 接続済みは失敗ではない。両方が同時に広告と探索をするので、相手側が
                    // 先に繋ぐことがよくある。製品としてはそれでよく、エラーに見えていたのは
                    // この画面が「自分から頼んだ接続」だけを数えていたから。
                    val code = (error as? NearbyDirectTransport.NearbyConnectFailure)?.statusCode
                    if (code == ALREADY_CONNECTED) {
                        connected[peer.endpointId] = transport.trustForTesting(
                            PeerLink(peer.endpointId, peer.tier),
                        )
                        note("既に接続済み ${peer.advertisedLabel}")
                        return@fold
                    }
                    val detail = (error as? NearbyDirectTransport.NearbyConnectFailure)
                        ?.let { "${it.statusName} (${it.statusCode ?: "-"})" }
                        ?: error::class.simpleName.orEmpty()
                    note("接続失敗 ${peer.advertisedLabel} — $detail")
                    status = "接続できませんでした — $detail"
                },
            )
        }
    }

    fun sendHello(peer: DiscoveredPeer) {
        val target = peerFor(peer)
        val began = System.currentTimeMillis()
        val text = "Hello from $myLabel"
        viewModelScope.launch {
            transport.send(target, text.toByteArray()).fold(
                onSuccess = { note("送信 → ${System.currentTimeMillis() - began}ms") },
                onFailure = { note("送信失敗") },
            )
        }
    }

    /**
     * この回線から通話が出られるかを調べる。
     *
     * 1台で足りる。通話が繋がるかを決める要素はほとんど回線ごとの性質なので、
     * 家で1回・モバイルで1回やれば比べられる。失敗した通話1件からは、どちら側が
     * 原因かは分からない。
     */
    fun probeNetwork() {
        note("回線を確認中…（最大12秒）")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val result = try {
                NetworkProbe.run(app)
            } catch (e: Throwable) {
                note("回線確認に失敗: " + e::class.simpleName + " " + e.message)
                return@launch
            }
            result.toString().lines().reversed().forEach { note(it) }
        }
    }

    /**
     * Yosegi v1 をこの端末で走らせ、結果をログに書く。
     *
     * 形式は Node 上の実測から選んだ。圧縮率はハードウェアに依らずそのまま移るが、
     * **時間は移らない**。JIT が温まっていない省電力コアは別の機械で、デスクトップの
     * マイクロ秒を Android の数字として報告するのは作り話になる。本物が出るのはここだけ。
     *
     * メインスレッドから外す。1秒ぶんの計算で、落としたフレームに汚染された測定値には
     * 意味が無い。
     */
    fun runWireBenchmark() {
        note("Yosegi 計測中…")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val report = try {
                YosegiBenchmark.run()
            } catch (e: Throwable) {
                // そのまま出す。ここで握り潰すと、何と言っていたか調べるために
                // もう一度端末まで往復することになる。
                note("Yosegi 計測に失敗: ${e::class.simpleName} ${e.message}")
                return@launch
            }
            // note() は先頭に積むので、古い順に渡すと上から下へ読める。
            report.toString().lines().reversed().forEach { note(it) }
        }
    }

    /**
     * 繋がっている相手。経緯は問わない。
     *
     * この画面が頼んだ接続の一覧ではなく、通信路側の状態を読む。両方が同時に広告と
     * 探索をするので、相手から張られることも同じくらい多い。自分の発信だけ数えると、
     * すでに話している相手に「接続」と出る。
     */
    fun isConnected(endpointId: String): Boolean =
        connected.containsKey(endpointId) ||
            transport.connectionState.value[endpointId] in CONNECTED_STATES

    /** 相手が張ったリンクを引き取り、送れるようにする。 */
    private fun peerFor(peer: DiscoveredPeer): AuthenticatedPeer =
        connected.getOrPut(peer.endpointId) {
            transport.trustForTesting(PeerLink(peer.endpointId, peer.tier))
        }

    /**
     * 使っている無線。分かる範囲で。
     *
     * Nearby 側の意味は「回線が無くても動く」こと。Wi-Fi の上で動いているリンクは
     * それを示さない（名前の違う LAN 経路）ので、オフラインを主張する前に見えている必要がある。
     */
    fun mediumOf(endpointId: String): String? {
        val nearby = transport as? NearbyDirectTransport ?: return null
        return when (nearby.bandwidth.value[endpointId]) {
            BandwidthInfo.Quality.HIGH -> "Wi-Fi（オフライン不可）"
            BandwidthInfo.Quality.MEDIUM -> "中速"
            BandwidthInfo.Quality.LOW -> "Bluetooth（オフライン可）"
            else -> null
        }
    }

    fun stateOf(endpointId: String): String =
        when (transport.connectionState.value[endpointId]) {
            DirectConnectionState.Discovered -> "発見"
            DirectConnectionState.Connecting -> "接続中"
            DirectConnectionState.Connected -> "接続済（未認証）"
            DirectConnectionState.Authenticated -> "接続済"
            DirectConnectionState.Lost -> "切断"
            null -> "—"
        }


    /**
     * この端末と Firestore で、暗号化の往復を実際に1回やる。
     *
     * 本番のメッセージ経路を先に切り替えないのは、暗号層が鍵・ルール・メッセージ経路に
     * 同時に触るから。間違いに気付くのが「読めないメッセージ」だと取り返しがつかない
     * （平文はもう無く、鍵は違う）。ここなら失敗しても1行で済む。Yosegi と同じ順序で、
     * 端末で測ってから採用する。
     *
     * 分かるのは、エンジンが開き、本物のルールを通して鍵を publish し、**自分だけを
     * 相手に**セッションを張り、暗号化して復号できること。自分とのセッションも本物の
     * Megolm セッションなので、通れば通信路とルールは正しい。2台で話せる証明にはならない。
     */
    fun runCryptoProbe() {
        note("E2EE 検証中…")
        viewModelScope.launch {
            val services = (app as? blog.nextlab.echo.RinowaApplication)?.services
            val transport = services?.crypto
            val me = services?.auth?.state?.value.let {
                (it as? AuthState.SignedIn)?.user?.uid
            }
            if (transport == null || me == null) {
                note("E2EE: サインインしていないので実行できません")
                return@launch
            }

            val user = UserId(me)
            val started = System.nanoTime()
            val engine = CryptoEngine.open(
                context = app,
                transport = transport,
                me = user,
                onFailure = { note("E2EE: エンジンを開けません — " + it) },
            )
            if (engine == null) return@launch
            val openedMs = (System.nanoTime() - started) / 1_000_000

            val published = engine.pump()
            val keys = engine.identityKeys()

            val conversation = ConversationId("probe")
            val plaintext = "E2EE probe " + System.currentTimeMillis()

            val encryptStart = System.nanoTime()
            val ciphertext = engine.encrypt(conversation, listOf(user), plaintext)
            val encryptUs = (System.nanoTime() - encryptStart) / 1_000

            if (ciphertext == null) {
                note("E2EE: 暗号化に失敗しました（鍵の配布まで到達していない可能性）")
                return@launch
            }

            engine.receive()

            val decryptStart = System.nanoTime()
            val recovered = engine.decrypt(conversation, user, ciphertext)
            val decryptUs = (System.nanoTime() - decryptStart) / 1_000

            // ------------------------------------------------------------------
            // 上の数字は最初の1通で、1回きりの処理（セッション確立）が支配的。
            // 鍵の publish、ワンタイム鍵の要求、部屋鍵の配布と、どれも Firestore の往復。
            //
            // これを「暗号化に46ms」と書くのは、フレーム単位の数字とメッセージ単位の
            // 平均を並べたのと同じ間違い（一度やった）。定常状態は別に測って両方出す。
            var steadyEncryptUs = 0L
            var steadySetupUs = 0L
            var steadySealUs = 0L
            var steadyDecryptUs = 0L
            var steadyOk = true
            repeat(STEADY_ROUNDS) { round ->
                val text = "steady $round"
                val t0 = System.nanoTime()
                val sealed = engine.encrypt(conversation, listOf(user), text) { setup, seal ->
                    steadySetupUs += setup
                    steadySealUs += seal
                }
                steadyEncryptUs += (System.nanoTime() - t0) / 1_000
                if (sealed == null) { steadyOk = false; return@repeat }
                val t1 = System.nanoTime()
                val opened = engine.decrypt(conversation, user, sealed)
                steadyDecryptUs += (System.nanoTime() - t1) / 1_000
                if (opened != text) steadyOk = false
            }

            // note() は先頭に積むので古い順に渡す。
            listOf(
                "E2EE probe on this device",
                "  device        ${engine.deviceId}",
                "  curve25519    ${keys["curve25519"]?.take(16) ?: "?"}…",
                "  open          $openedMs ms",
                "  requests sent $published",
                "  plaintext     ${plaintext.length} B",
                "  ciphertext    ${ciphertext.length} B  (x${ciphertext.length / plaintext.length})",
                "  --- 初回（鍵の配布を含む・往復あり）---",
                "  encrypt       $encryptUs us",
                "  decrypt       $decryptUs us",
                "  --- 定常（セッション確立後・$STEADY_ROUNDS 回平均）---",
                "  encrypt       ${steadyEncryptUs / STEADY_ROUNDS} us",
                "    準備        ${steadySetupUs / STEADY_ROUNDS} us",
                "    暗号化      ${steadySealUs / STEADY_ROUNDS} us  ← Megolm 本体",
                "  decrypt       ${steadyDecryptUs / STEADY_ROUNDS} us",
                if (recovered == plaintext && steadyOk) {
                    "  round trip    OK — 復号一致"
                } else if (recovered == plaintext) {
                    "  round trip    初回OK / 定常で不一致"
                } else {
                    "  round trip    FAILED — ${recovered ?: "復号できず"}"
                },
            ).reversed().forEach { note(it) }
        }
    }

    private fun note(line: String) {
        // 新しい順、件数に上限。ファイルではなく端末上のログ表示なので。
        log.add(0, line)
        if (log.size > MAX_LOG) log.removeAt(log.lastIndex)
    }

    override fun onCleared() {
        viewModelScope.launch {
            runCatching { nearby.shutdown() }
            runCatching { lan.shutdown() }
        }
    }

    private companion object {
        /** 遅い1回をならせる程度に多く、止まって見えない程度に少なく。 */
        const val STEADY_ROUNDS = 20

        const val MAX_LOG = 60

        /** ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT。 */
        const val ALREADY_CONNECTED = 8003

        val CONNECTED_STATES = setOf(
            DirectConnectionState.Connected,
            DirectConnectionState.Authenticated,
        )

        /**
         * 定数ではなく文字列で書く。Android 16 より前の SDK には無く、しかも上に
         * 書いたとおり Android 16 を名乗る端末すべてにあるわけでもない。
         */
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
