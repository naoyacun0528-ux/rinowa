package blog.nextlab.echo.calls

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * この回線だけで、通話が外へ出られるかを調べる。
 *
 * 1台で足りる理由: 通話が繋がるかを決める要素のほとんどは**回線ごとの性質**で、
 * 相手がいなくても測れる。ICE 候補を集めるのはこの端末と STUN/TURN サーバーの
 * やり取りで、結果はこの回線がどんな種類のアドレスを出してくれるかを示す。
 *
 * それによって「試したが駄目だった」が「この回線は host と srflx を出す、
 * あの回線は host だけ」に変わる。別の時間・別の場所で、一人で集められる2つの事実。
 *
 * 読み方:
 *
 * | 出たもの | 意味 |
 * |---|---|
 * | host だけ | STUN が答えていない。UDP が丸ごと塞がれている |
 * | + srflx | STUN が効いた。NAT の行儀がよければこちら側へ届く |
 * | + relay | TURN の中継に届く。こちら側へは常に届く |
 *
 * 対称 NAT の判定がここで一番役に立つ。**別々の** STUN サーバー2つに問い合わせ、
 * 同じ外部ポートを報告するならルータはソケットごとにポートを割り当てていて STUN だけで
 * 足りる。違うポートを報告するなら宛先ごとに割り当てる「対称」で、反射アドレスは
 * 第三者には使えない。**中継が要るのはその場合で、それが端末1台で分かる。**
 *
 * 分からないこと: *相手側*が協力するかどうか。どちらもこれを通っても、両方が対称なら
 * 組めない。両方で走らせて比べる。
 */
object NetworkProbe {

    data class Result(
        val host: Int,
        val serverReflexive: Int,
        val relay: Int,
        val hasIpv6: Boolean,
        /** 反射アドレスが2つ未満しか見えなかったときは null。 */
        val symmetricNat: Boolean?,
        val reflexivePorts: List<Int>,
        val error: String? = null,
    ) {
        val verdict: String
            get() = when {
                error != null -> "判定できませんでした: $error"
                relay > 0 && symmetricNat == true ->
                    "対称NAT。中継が要りますが、中継は使えます → 繋がります"
                relay > 0 ->
                    "良好。中継も使えます → まず繋がります"
                symmetricNat == true ->
                    "対称NAT で中継なし → 相手が同じ条件だと繋がりません"
                serverReflexive > 0 && hasIpv6 ->
                    "良好。IPv6 もあるので直接繋がる可能性が高い"
                serverReflexive > 0 ->
                    "良好。相手も同様なら繋がります"
                else ->
                    "STUN に届いていません。この回線は UDP を塞いでいる可能性"
            }

        override fun toString(): String = buildString {
            appendLine("この回線の判定")
            appendLine("  $verdict")
            appendLine("  host $host / srflx $serverReflexive / relay $relay")
            appendLine("  IPv6 ${if (hasIpv6) "あり" else "なし"}")
            append(
                "  外から見えるポート " + when {
                    reflexivePorts.isEmpty() -> "取得できず"
                    reflexivePorts.distinct().size == 1 -> "${reflexivePorts.first()}（一定 = 素直なNAT）"
                    else -> "${reflexivePorts.joinToString(",")}（バラバラ = 対称NAT）"
                },
            )
        }
    }

    suspend fun run(context: Context): Result {
        val servers = IceServers.current()
        val eglBase = EglBase.create()

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        val candidates = mutableListOf<String>()
        var gatheringDone = false

        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val connection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                synchronized(candidates) { candidates.add(candidate.sdp) }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringDone = true
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceCandidatesRemoved(list: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        })

        if (connection == null) {
            factory.dispose()
            eglBase.release()
            return Result(0, 0, 0, false, null, emptyList(), error = "PeerConnection を作れません")
        }

        // offer を集める意味を持たせる一番安い方法がデータチャネル。マイクは開かない。
        // これは回線についての問いであって通話ではない。
        connection.createDataChannel("probe", DataChannel.Init())
        connection.createOffer(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection.setLocalDescription(NoopSdpObserver, description)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(reason: String?) = Unit
                override fun onSetFailure(reason: String?) = Unit
            },
            MediaConstraints(),
        )

        // 中継の候補は一番遅く出る。時間で打ち切らず収集の完了を待つから、
        // 中継についての答えが信用できる。
        withTimeoutOrNull(12_000) {
            while (!gatheringDone) delay(200)
        }

        val found = synchronized(candidates) { candidates.toList() }
        connection.close()
        connection.dispose()
        factory.dispose()
        eglBase.release()

        val ports = found.filter { it.contains(" typ srflx") }.mapNotNull { externalPort(it) }

        return Result(
            host = found.count { it.contains(" typ host") },
            serverReflexive = found.count { it.contains(" typ srflx") },
            relay = found.count { it.contains(" typ relay") },
            // IPv6 には NAT が無いので、両方が IPv6 の家なら中継なしで届くことが多い。
            // 日本の光回線ではよくあるのに見落としやすい。
            hasIpv6 = found.any { it.contains(":") && it.contains(" typ host") && it.count { c -> c == ':' } > 2 },
            symmetricNat = if (ports.size >= 2) ports.distinct().size > 1 else null,
            reflexivePorts = ports,
        )
    }

    /** `candidate:... <priority> <ip> <port> typ srflx ...` の typ の1つ前。 */
    private fun externalPort(sdp: String): Int? {
        val parts = sdp.trim().split(" ")
        val typIndex = parts.indexOf("typ")
        if (typIndex < 2) return null
        return parts[typIndex - 1].toIntOrNull()
    }

    private object NoopSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(reason: String?) = Unit
        override fun onSetFailure(reason: String?) = Unit
    }
}
