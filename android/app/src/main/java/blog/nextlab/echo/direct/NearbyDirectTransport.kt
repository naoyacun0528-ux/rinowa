package blog.nextlab.echo.direct

import blog.nextlab.echo.bestEffort
import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

/**
 * Nearby Connections 上の Rinowa Direct — オフラインの段。
 *
 * Nearby は無線を自分で選び（見つけるのは BLE、運ぶのは Bluetooth か Wi-Fi）、
 * 回線をまったく必要としない。それがこの段の意味で、モバイル回線が使えないときにも動く。
 *
 * 両方が同時に広告と探索をする。どちらも「主」ではない（先に押したほうが繋ぎ、
 * もう一方はすでに聞いている）。片方が広告・片方が探索という設計だと、二人が
 * どちらの役かを決める必要があり、docs/DIRECT_ARCHITECTURE.md §2.2 はそういう指示を
 * 出すことを禁じている。`P2P_STAR` ではなく `P2P_CLUSTER` なのも同じ理由で、中心を作らない。
 *
 * 2026年後半から、Nearby Connections は Wi-Fi や Bluetooth を勝手に入れなくなった。
 * 切れていると探索は単に何も見つけないので、呼び出し側が気付いて頼む必要がある。
 * それが [DirectFailure.RadiosOff]。握り潰さず外へ出す。
 */
class NearbyDirectTransport(context: Context) : DirectTransport {

    private val client = Nearby.getConnectionsClient(context.applicationContext)

    override val capabilities = TransportCapabilities(
        tier = DirectTier.Nearby,
        worksOffline = true,
        // iOS SDK は Wi-Fi LAN しか持たず、それはもう一方の段の領分。
        crossPlatform = false,
    )

    private val _connectionState = MutableStateFlow<Map<String, DirectConnectionState>>(emptyMap())
    override val connectionState: StateFlow<Map<String, DirectConnectionState>> =
        _connectionState.asStateFlow()

    private val incoming = MutableSharedFlow<DirectPayload>(extraBufferCapacity = 32)

    /** こちらへ接続を求めてきて、受け入れ待ちの相手。 */
    private val pending = mutableMapOf<String, ConnectionInfo>()

    private fun setState(endpointId: String, state: DirectConnectionState) {
        _connectionState.value = _connectionState.value + (endpointId to state)
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            incoming.tryEmit(DirectPayload(endpointId, bytes))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // バイト列の payload は丸ごと届くので、報告する進捗がまだ無い。
            // 意味を持ち始めるのは Direct-2 の添付から。
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pending[endpointId] = info
            setState(endpointId, DirectConnectionState.Connecting)
            // どちらの側でもコードを出さずに受け入れる。Nearby は二人が読み上げて
            // 照合する数字を用意しているが、Rinowa は使わない。
            // docs/DIRECT_ARCHITECTURE.md §2.2 が毎回尋ねることを禁じているため。
            // 代わりの暗号的な確認は Direct-2。それまでこのリンクは接続済みだが
            // *信頼されていない*、それが PeerLink の意味。
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            pending.remove(endpointId)
            val code = resolution.status.statusCode
            val ok = code == ConnectionsStatusCodes.STATUS_OK

            setState(
                endpointId,
                if (ok) DirectConnectionState.Connected else DirectConnectionState.Lost,
            )

            connectContinuations.remove(endpointId)?.let { continuation ->
                continuation(
                    if (ok) {
                        Result.success(PeerLink(endpointId, DirectTier.Nearby))
                    } else {
                        // Nearby 自身の理由。「接続失敗」だけでは何も分からないし、
                        // 推測はもう2回誤診を生んでいる（AndroidManifest.xml を参照）。
                        Result.failure(
                            NearbyConnectFailure(
                                code,
                                ConnectionsStatusCodes.getStatusCodeString(code),
                            ),
                        )
                    },
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            setState(endpointId, DirectConnectionState.Lost)
        }

        /**
         * どの無線に落ち着いたか。Direct-1 が本当に答えたい問い。
         *
         * Nearby は媒体を自分で選ぶが名前は言わない。ただし達成した帯域は報告するので、
         * それが十分な代用になる（`HIGH` は Wi-Fi へ上げた、`LOW` は Bluetooth）。
         *
         * これが要るのは、両方が同じ Wi-Fi にいる状態で動くリンクは「回線が無くても
         * 動く」ことを何も示さないから。名前だけ Nearby の LAN 段でしかない。
         * この表示が無いと外からは区別できず、そうやって「オフラインでも動く」が
         * 事実でないまま信じられる。
         */
        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            _bandwidth.value = _bandwidth.value + (endpointId to info.quality)
        }
    }

    private val _bandwidth = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 相手ごとの [BandwidthInfo.Quality]。 */
    val bandwidth: StateFlow<Map<String, Int>> = _bandwidth.asStateFlow()

    /** Nearby が結果を返したら、止まっていた [connect] を再開する。 */
    private val connectContinuations =
        mutableMapOf<String, (Result<PeerLink>) -> Unit>()

    override fun discoverPeers(
        label: String,
        preference: DirectPreference,
    ): Flow<DiscoveredPeer> = callbackFlow {
        myLabel = label
        offlineOnly = preference == DirectPreference.OfflineCapable

        val discovery = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                setState(endpointId, DirectConnectionState.Discovered)
                trySend(DiscoveredPeer(endpointId, info.endpointName, DirectTier.Nearby))
            }

            override fun onEndpointLost(endpointId: String) {
                setState(endpointId, DirectConnectionState.Lost)
            }
        }

        val started = runCatching {
            client.startAdvertising(
                label,
                SERVICE_ID,
                lifecycleCallback,
                AdvertisingOptions.Builder()
                    .setStrategy(STRATEGY)
                    // lowPower にすると Nearby は Bluetooth に留まり Wi-Fi を使わない。
                    // これが、回線が無くてもリンクが生き残る理由。遅くなるが、それは
                    // あとで気付く事故ではなく意図した取引。
                    .setLowPower(offlineOnly)
                    // 速さが欲しいときでも、そのために利用者の Wi-Fi を切らせない。
                    // スタンプを送るために家の回線を落とすメッセンジャーは残らない。
                    .setDisruptiveUpgrade(false)
                    .build(),
            ).await()

            client.startDiscovery(
                SERVICE_ID,
                discovery,
                DiscoveryOptions.Builder()
                    .setStrategy(STRATEGY)
                    .setLowPower(offlineOnly)
                    .build(),
            ).await()
        }

        started.exceptionOrNull()?.let { error ->
            // 推測ではなく本当の状態。Nearby は断った理由（すでに広告中、権限が無い、
            // Bluetooth が切、非対応）をそのまま返す。それを1文にまとめたせいで、
            // この通信路の最初の診断は完全に見当違いの方向へ行った。
            val code = (error as? ApiException)?.statusCode
            close(NearbyStartFailure(code, ConnectionsStatusCodes.getStatusCodeString(code ?: -1)))
            return@callbackFlow
        }

        awaitClose {
            bestEffort("stop discovery") { client.stopDiscovery() }
            bestEffort("stop advertising") { client.stopAdvertising() }
        }
    }

    override suspend fun stopDiscovery() {
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAdvertising() }
    }

    override suspend fun connect(peer: DiscoveredPeer): Result<PeerLink> =
        suspendCancellableCoroutine { continuation ->
            var resumed = false
            connectContinuations[peer.endpointId] = { result ->
                if (!resumed) {
                    resumed = true
                    continuation.resume(result)
                }
            }
            setState(peer.endpointId, DirectConnectionState.Connecting)

            // 第1引数は**この端末**の名前で、相手に見せるもの。相手の名前ではない。
            // 相手の名前を渡していたので、両方が相手のラベルで自己紹介していた。
            client.requestConnection(myLabel, peer.endpointId, lifecycleCallback)
                .addOnFailureListener { error ->
                    connectContinuations.remove(peer.endpointId)
                    if (!resumed) {
                        resumed = true
                        val code = (error as? ApiException)?.statusCode
                        continuation.resume(
                            Result.failure(
                                NearbyConnectFailure(
                                    code,
                                    ConnectionsStatusCodes.getStatusCodeString(code ?: -1),
                                ),
                            ),
                        )
                    }
                }

            continuation.invokeOnCancellation { connectContinuations.remove(peer.endpointId) }
        }

    override fun trustForTesting(link: PeerLink): AuthenticatedPeer {
        setState(link.endpointId, DirectConnectionState.Authenticated)
        return AuthenticatedPeer(link.endpointId, link.tier)
    }

    override suspend fun send(peer: AuthenticatedPeer, payload: ByteArray): Result<Unit> =
        runCatching {
            client.sendPayload(peer.endpointId, Payload.fromBytes(payload)).await()
        }

    override fun receive(): Flow<DirectPayload> = incoming.asSharedFlow()

    override suspend fun disconnect(endpointId: String) {
        bestEffort("disconnect endpoint") { client.disconnectFromEndpoint(endpointId) }
        setState(endpointId, DirectConnectionState.Lost)
    }

    override suspend fun shutdown() {
        bestEffort("stop all endpoints") { client.stopAllEndpoints() }
        stopDiscovery()
        _connectionState.value = emptyMap()
    }

    /** 探索を始めるときに入る。connect() が正しく自己紹介できるように。 */
    private var myLabel: String = android.os.Build.MODEL

    /** 呼び出し側の [DirectPreference] から入る。discoverPeers を参照。 */
    private var offlineOnly: Boolean = false

    /** Nearby 自身の理由をそのまま画面へ運ぶ。 */
    class NearbyConnectFailure(
        val statusCode: Int?,
        val statusName: String,
    ) : Exception("Nearby connect failed: $statusName (${statusCode ?: "no code"})")

    /** Nearby 自身の理由をそのまま画面へ運ぶ。 */
    class NearbyStartFailure(
        val statusCode: Int?,
        val statusName: String,
    ) : Exception("Nearby start failed: $statusName (${statusCode ?: "no code"})")

    private companion object {
        /**
         * このアプリの広告の名前空間。両方がこの文字列を使っているときだけ互いに
         * 見えるので、他所とぶつからないこと。
         */
        const val SERVICE_ID = "blog.nextlab.echo.direct.v1"

        /** 中心を作らない。どちらの端末から始めてもよい。 */
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }
}
