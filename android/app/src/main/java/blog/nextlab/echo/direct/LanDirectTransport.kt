package blog.nextlab.echo.direct

import blog.nextlab.echo.bestEffort
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 同じ Wi-Fi 上の Rinowa Direct — プラットフォームをまたぐ段。
 *
 * 相手探しは Network Service Discovery（mDNS/Bonjour）、運ぶのは素の TCP ソケット。
 * どちらも Android 固有ではない。iPhone なら `NWBrowser` / `NWListener` で同じ2つを
 * 使う。この段があるのは、まさにそのため。
 *
 * Android どうしなら Nearby のほうが良い段（回線が要らず、確立も速い）。こちらの
 * 存在理由は別で、**iPhone への唯一の道**だから。Nearby の iOS SDK は Wi-Fi LAN しか
 * 持たず、iOS は背面のアプリを Bluetooth で Android に見つけさせない。
 *
 * Android どうしでも動くおかげで、いま試せる。iPhone が絡む前に、同じ引き出しの
 * 2台でこの経路を証明し、測り、直せる。
 *
 * 最初に必ずぶつかる失敗: 公共 Wi-Fi の多くと一部の家庭用ルータは**AP 隔離**をしていて、
 * 同じアクセスポイントの端末どうしの通信を止める。探索は何も見つけず、どの説明も
 * このファイルのバグのように聞こえるが、そうではない。ここから見た姿が
 * [DirectFailure.Unsupported] で、検証画面はそれを言葉で出す。
 */
class LanDirectTransport(context: Context) : DirectTransport {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val capabilities = TransportCapabilities(
        tier = DirectTier.Lan,
        // 共有された回線も回線には違いない。これはモバイルの電波が消えたら
        // 生き残らない段。
        worksOffline = false,
        crossPlatform = true,
    )

    private val _connectionState = MutableStateFlow<Map<String, DirectConnectionState>>(emptyMap())
    override val connectionState: StateFlow<Map<String, DirectConnectionState>> =
        _connectionState.asStateFlow()

    private val incoming = MutableSharedFlow<DirectPayload>(extraBufferCapacity = 32)

    /** 開いているソケット。相手に伝えた endpoint id を鍵にする。 */
    private val sockets = ConcurrentHashMap<String, Socket>()
    private val resolved = ConcurrentHashMap<String, NsdServiceInfo>()

    private var serverSocket: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var acceptJob: Job? = null

    private fun setState(endpointId: String, state: DirectConnectionState) {
        _connectionState.value = _connectionState.value + (endpointId to state)
    }

    override fun discoverPeers(
        label: String,
        // この段は定義上「共有された回線」なので、オフライン版を選ぶ余地が無い。
        // 2つの通信路が1つの interface を共有できるよう、受け取って無視する。
        preference: DirectPreference,
    ): Flow<DiscoveredPeer> = callbackFlow {
        // ポート0で OS に空きを聞き、それを広告する。決め打ちにすると端末上の
        // 他の何かと衝突し、理由の見えない失敗になる。
        val server = withContext(Dispatchers.IO) { ServerSocket(0) }
        serverSocket = server

        acceptJob = scope.launch {
            while (true) {
                // swallow-ok: accept() はサーバーソケットを閉じると例外を投げ、
                // 閉じることがこのループの止め方。例外そのものが停止の合図。
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val endpointId = socket.remoteSocketAddress.toString()
                sockets[endpointId] = socket
                setState(endpointId, DirectConnectionState.Connected)
                readLoop(endpointId, socket)
            }
        }

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                close(DirectException(DirectFailure.Unsupported))
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = registrationListener

        nsd.registerService(
            NsdServiceInfo().apply {
                // このラベルは mDNS で平文のまま広告されるので、名前・住所・
                // アカウント id にしてはいけない。Direct-2 では入れ替わるトークンに
                // 置き換える。docs/DIRECT_THREAT_MODEL.md T1。
                serviceName = "$SERVICE_PREFIX$label"
                serviceType = SERVICE_TYPE
                port = server.localPort
            },
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener,
        )

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith(SERVICE_PREFIX)) return
                // 自分の広告が自分に返ってくる。自分を相手として扱うと、検証画面が
                // 端末を自分自身に接続できてしまう。
                if (info.serviceName == "$SERVICE_PREFIX$label") return

                @Suppress("DEPRECATION")
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val endpointId = info.serviceName
                            resolved[endpointId] = info
                            setState(endpointId, DirectConnectionState.Discovered)
                            trySend(
                                DiscoveredPeer(
                                    endpointId = endpointId,
                                    advertisedLabel = info.serviceName.removePrefix(SERVICE_PREFIX),
                                    tier = DirectTier.Lan,
                                ),
                            )
                        }
                    },
                )
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                setState(info.serviceName, DirectConnectionState.Lost)
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(DirectException(DirectFailure.Unsupported))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = discoveryListener

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose { scope.launch { stopDiscovery() } }
    }

    override suspend fun stopDiscovery() {
        discovery?.let { d -> bestEffort("stop discovery") { nsd.stopServiceDiscovery(d) } }
        registration?.let { r -> bestEffort("unregister service") { nsd.unregisterService(r) } }
        discovery = null
        registration = null
        acceptJob?.cancel()
        bestEffort("close server socket") { serverSocket?.close() }
        serverSocket = null
    }

    override suspend fun connect(peer: DiscoveredPeer): Result<PeerLink> = runCatching {
        val info = resolved[peer.endpointId]
            ?: throw DirectException(DirectFailure.Unknown)

        setState(peer.endpointId, DirectConnectionState.Connecting)

        val socket = withContext(Dispatchers.IO) {
            Socket().apply {
                @Suppress("DEPRECATION")
                connect(InetSocketAddress(info.host, info.port), CONNECT_TIMEOUT_MS)
            }
        }
        sockets[peer.endpointId] = socket
        setState(peer.endpointId, DirectConnectionState.Connected)
        scope.launch { readLoop(peer.endpointId, socket) }

        PeerLink(peer.endpointId, DirectTier.Lan)
    }

    override fun trustForTesting(link: PeerLink): AuthenticatedPeer {
        setState(link.endpointId, DirectConnectionState.Authenticated)
        return AuthenticatedPeer(link.endpointId, link.tier)
    }

    override suspend fun send(peer: AuthenticatedPeer, payload: ByteArray): Result<Unit> =
        runCatching {
            val socket = sockets[peer.endpointId] ?: throw DirectException(DirectFailure.Unknown)
            withContext(Dispatchers.IO) {
                // 長さを前に付ける。TCP はストリームであってメッセージの列ではないので、
                // 枠が無いと立て続けに送った2つが1つになって届く。
                DataOutputStream(socket.getOutputStream()).run {
                    writeInt(payload.size)
                    write(payload)
                    flush()
                }
            }
        }

    private suspend fun readLoop(endpointId: String, socket: Socket) {
        withContext(Dispatchers.IO) {
            // swallow-ok: この直後の2行*が*処理そのもの。読み取りループの失敗は
            // 接続が切れたということで、その2行が記録するのがまさにそれ。
            runCatching {
                val input = DataInputStream(socket.getInputStream())
                while (!socket.isClosed) {
                    val size = input.readInt()
                    if (size !in 1..MAX_PAYLOAD_BYTES) break
                    val buffer = ByteArray(size)
                    input.readFully(buffer)
                    incoming.tryEmit(DirectPayload(endpointId, buffer))
                }
            }
            setState(endpointId, DirectConnectionState.Lost)
            sockets.remove(endpointId)
            bestEffort("close socket") { socket.close() }
        }
    }

    override fun receive(): Flow<DirectPayload> = incoming.asSharedFlow()

    override suspend fun disconnect(endpointId: String) {
        bestEffort("disconnect") { sockets.remove(endpointId)?.close() }
        setState(endpointId, DirectConnectionState.Lost)
    }

    override suspend fun shutdown() {
        stopDiscovery()
        sockets.values.forEach { socket -> bestEffort("close socket") { socket.close() } }
        sockets.clear()
        resolved.clear()
        _connectionState.value = emptyMap()
        scope.cancel()
    }

    private companion object {
        /** 両方の端末で一致している必要がある。いずれ iOS でも。 */
        const val SERVICE_TYPE = "_rinowadirect._tcp."
        const val SERVICE_PREFIX = "rinowa-"
        const val CONNECT_TIMEOUT_MS = 5_000

        /** これより大きな枠は Rinowa が送ったものではない。 */
        const val MAX_PAYLOAD_BYTES = 1 shl 20
    }
}
