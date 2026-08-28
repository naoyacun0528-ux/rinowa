package blog.nextlab.echo.calls

import blog.nextlab.echo.bestEffort
import android.content.Context
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * WebRTC の接続1本。アプリの他の場所が WebRTC のコールバックを見なくて済むように包む。
 *
 * 通信の暗号化は DTLS-SRTP で常に有効。切る設定も、うっかり落ちる平文モードも無い。
 * 「独自の暗号を設計しない」という方針に合うのはそのため。
 *
 * ICE は次の順に可能性を試す:
 *
 *  1. host — 端末のローカルアドレス。同じ Wi-Fi ならこれだけで繋がり、サーバーは一切要らない。
 *  2. STUN — 外から自分がどう見えるかを公開サーバーに聞く。家庭用ルータならたいてい足りる。
 *  3. TURN — 中継。どうしても届かないとき用で、日本のモバイル回線では5本に1本ほど必要。
 *     docs/CALLS_ARCHITECTURE.md §5。
 *
 * [iceServers] を定数にせず引数にしているのは、3 が運用上の判断で変わるから。
 */
class WebRtcSession(
    private val context: Context,
    private val iceServers: List<PeerConnection.IceServer>,
    private val onLocalCandidate: (IceCandidate) -> Unit,
    private val onConnected: () -> Unit,
    private val onClosed: (failed: Boolean) -> Unit,
    /** 相手のカメラ映像が来たら呼ぶ。音声通話では呼ばれない。 */
    private val onRemoteVideo: (org.webrtc.VideoTrack?) -> Unit = {},
    /**
     * 相手がいなくなった。
     *
     * @param settled WebRTC が完全にあきらめたら true。戻る可能性が残っていれば false
     *   （短い回線切り替えで起き、これで通話を終わらせてはいけない）。
     */
    private val onPeerGone: (settled: Boolean) -> Unit = {},
) {

    /**
     * 描画側と共有する GL コンテキスト。
     *
     * factory と [org.webrtc.SurfaceViewRenderer] が同じコンテキストにいないと、
     * 一方がデコードしたフレームを他方が描けない。症状はエラーの出ない黒い矩形なので、
     * 隠さずに公開して共有する。
     */
    val eglBase: EglBase = EglBase.create()

    private val factory: PeerConnectionFactory
    private var connection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    /**
     * 相手の説明（remote description）より先に届いた候補。
     *
     * WebRTC はどのセッションのものか分かるまで候補を受け付けず、Firestore は
     * 到着順を保証しない。捨てると、一番速く届いた経路＝一番繋がりやすい経路を
     * 黙って落とすことになる。
     */
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    /** 「届かなかった」と「切られた」を区別するため。 */
    private var everConnected = false

    private val encoderFactory: DefaultVideoEncoderFactory

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        // ハードウェアエンコーダがあれば使い、VP8 と H.264 high profile を有効にする。
        // 端末でソフトウェアエンコードすると CPU が飽和し、熱で絞られ、フレームが落ちて
        // 電池も減る。docs/CALLS_ARCHITECTURE.md §5.5。
        encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /**
     * この端末が実際にエンコードできるもの。
     *
     * 報告するだけで、まだ判断には使わない。「対応する中で最良のコーデックを使う」は
     * 半分の規則で、残り半分は「相手も対応していること」と「ハードウェアであること」。
     * どちらもバージョン番号からは分からないので、まず実機が何と言うかを集める。
     */
    fun supportedVideoCodecs(): List<String> =
        // swallow-ok: この一覧は調査用で、何かを決めるのには使っていない。空リストは
        // 「集まらなかった」と読め、実際そういう意味になる。
        runCatching { encoderFactory.supportedCodecs.map { it.name } }.getOrDefault(emptyList())

    fun start(): Boolean {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // 全部集めてから送ると、通話のたびに数秒の無音が増える。trickle は
            // 見つかった候補から順に送る。
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        connection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                noteCandidate(candidate.sdp, local = true)
                onLocalCandidate(candidate)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        everConnected = true
                        onConnected()
                    }

                    // 相手がいなくなった。WebRTC が自分で気付くのが重要で、「終了」の
                    // 書き込みは失敗も遅延もするし、アプリを強制終了されれば起きない。
                    // 実体は「メディアが止まったこと」で、Firestore はその早い通知にすぎない。
                    PeerConnection.PeerConnectionState.DISCONNECTED -> onPeerGone(false)

                    // 繋がる前の FAILED は経路の問題、繋がったあとの FAILED は切断。
                    // 相手が赤いボタンを押しただけで「中継がありません」と言うのはおかしい。
                    PeerConnection.PeerConnectionState.FAILED ->
                        if (everConnected) onPeerGone(true) else onClosed(true)

                    PeerConnection.PeerConnectionState.CLOSED -> onClosed(false)
                    else -> Unit
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                // 相手のカメラ映像。そのまま渡す。描画は画面側の持ち物で、ここで
                // レンダラを持つとセッションが画面の寿命に縛られる。
                (receiver?.track() as? org.webrtc.VideoTrack)?.let(onRemoteVideo)
            }
        }) ?: return false

        // Echo cancellation、ノイズ抑制、ゲイン調整はネイティブの音声処理で既定で有効。
        // どれかを切るのは「音を悪くする」という判断になる。
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("rinowa_audio", audioSource).also {
            connection?.addTrack(it, listOf("rinowa_stream"))
        }
        return true
    }

    suspend fun createOffer(): String = negotiate { observer ->
        connection?.createOffer(observer, MediaConstraints())
    }

    suspend fun createAnswer(): String = negotiate { observer ->
        connection?.createAnswer(observer, MediaConstraints())
    }

    private suspend fun negotiate(request: (SdpObserver) -> Unit): String =
        suspendCoroutine { continuation ->
            request(object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection?.setLocalDescription(SimpleSdpObserver(), description)
                    continuation.resume(description.description)
                }

                override fun onCreateFailure(reason: String?) {
                    // そのまま出す。「通話を開始できませんでした」には情報が無く、
                    // 本当の理由がまだ残っているのはこの層。
                    continuation.resumeWithException(IllegalStateException("createSdp: $reason"))
                }
            })
        }

    fun setRemoteDescription(type: String, sdp: String) {
        val kind = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        connection?.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    remoteDescriptionSet = true
                    synchronized(pendingCandidates) {
                        pendingCandidates.forEach { connection?.addIceCandidate(it) }
                        pendingCandidates.clear()
                    }
                }
            },
            SessionDescription(kind, sdp),
        )
    }

    /**
     * 各端末がどの種類の経路を出せたか。
     *
     * ICE 候補には役に立つ3種類があり、どれが出たかで通話が繋がった／繋がらなかった
     * 理由がそのまま分かる:
     *
     *  - host — ローカルアドレス。常に出る。同じ回線でのみ繋がる。
     *  - srflx — STUN から見えた自分。STUN が効いた印で、家庭用ルータならたいてい足りる。
     *  - relay — TURN が受け入れた。両端がキャリアグレード NAT の内側にいるときの唯一の手。
     *
     * これが無いと失敗は「繋がりませんでした」で終わる。あれば、中継に届かなかったのか、
     * 届いたが使われなかったのか、片側だけ届いたのかが分かる。直し方はそれぞれ別。
     */
    private val localKinds = linkedSetOf<String>()
    private val remoteKinds = linkedSetOf<String>()

    private fun noteCandidate(sdp: String, local: Boolean) {
        // "candidate:... typ host ..." の typ の次の語。
        val kind = sdp.substringAfter(" typ ", "").substringBefore(' ').ifEmpty { "?" }
        if (local) localKinds.add(kind) else remoteKinds.add(kind)
    }

    /** 人に見せてもログに出しても安全な1行。候補の種類だけで、アドレスは含まない。 */
    fun diagnosis(): String =
        "自分=${localKinds.joinToString("/").ifEmpty { "なし" }} " +
            "相手=${remoteKinds.joinToString("/").ifEmpty { "なし" }}"

    val sawRelay: Boolean get() = "relay" in localKinds

    fun addRemoteCandidate(data: IceCandidateData) {
        noteCandidate(data.candidate, local = false)
        val candidate = IceCandidate(data.sdpMid, data.sdpMLineIndex, data.candidate)
        if (remoteDescriptionSet) {
            connection?.addIceCandidate(candidate)
        } else {
            synchronized(pendingCandidates) { pendingCandidates.add(candidate) }
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    // -----------------------------------------------------------------------------------
    // 映像
    // -----------------------------------------------------------------------------------

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: org.webrtc.VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    /** 自分の映像。[startVideo] が成功するまで null。 */
    var localVideo: org.webrtc.VideoTrack? = null
        private set

    var usingFrontCamera: Boolean = true
        private set

    /**
     * カメラを開いて通話に足す。
     *
     * @return 使えるカメラが無ければ false。その場合は**音声のまま続ける**。カメラが
     *   開けなかったビデオ通話は電話になればよく、エラー画面にする話ではない。
     */
    fun startVideo(front: Boolean = true): Boolean = runCatching {
        val enumerator = if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            @Suppress("DEPRECATION")
            org.webrtc.Camera1Enumerator(true)
        }

        val name = pickCamera(enumerator, front) ?: return@runCatching false
        usingFrontCamera = front

        val capturer = enumerator.createCapturer(name, null) ?: return@runCatching false
        val helper = SurfaceTextureHelper.create("RinowaCapture", eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)

        capturer.initialize(helper, context, source.capturerObserver)
        // 上限は 720p/30fps。回線が細ければ WebRTC の帯域推定が自分で解像度を落とす。
        // 見え方に効くのはコーデックよりこの適応のほう。
        capturer.startCapture(1280, 720, 30)

        val track = factory.createVideoTrack("rinowa_video", source)
        connection?.addTrack(track, listOf("rinowa_stream"))

        videoCapturer = capturer
        videoSource = source
        surfaceHelper = helper
        localVideo = track
        true
    }
        // false を返すと「カメラを開けませんでした。音声のみで続けます」だけが出て、
        // 十数種類あるカメラの失敗のどれかは分からない。
        .onFailure { android.util.Log.w("Rinowa/calls", "startVideo failed", it) }
        .getOrDefault(false)

    /** まず内カメラ。ビデオ通話はたいてい顔を写す。 */
    private fun pickCamera(enumerator: CameraEnumerator, front: Boolean): String? {
        val names = enumerator.deviceNames
        val wanted = names.firstOrNull {
            if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
        }
        return wanted ?: names.firstOrNull()
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { usingFrontCamera = isFront }
            override fun onCameraSwitchError(error: String?) = Unit
        })
    }

    /**
     * 通話を保ったままカメラを切る。
     *
     * トラックを無効にするだけでなく取り込み自体を止める。そうしないと端末の
     * カメラ表示が点いたままになり、画面が「切」と言っているのにカメラは動いている。
     */
    fun setCameraEnabled(enabled: Boolean) {
        localVideo?.setEnabled(enabled)
        // 報告する。カメラのボタンを押した結果がここで決まるので、黙って失敗すると
        // 何もしないボタンになる。
        bestEffort(if (enabled) "start capture" else "stop capture") {
            if (enabled) videoCapturer?.startCapture(1280, 720, 30) else videoCapturer?.stopCapture()
        }
    }

    /**
     * 全部片付ける。呼び出し元のスレッドではやらない。
     *
     * `stopCapture()` はカメラのスレッドが止まるまで、`dispose()` はネイティブ側が
     * 終わるまでブロックする。ボタン（＝メインスレッド）から呼ぶと、良くて数百ミリ秒、
     * カメラが開いていれば数秒。Android の答えは「Rinowa が応答していません」で、
     * 実際にビデオ通話の終了で出た。
     *
     * なので参照だけ先に取って null にし、遅い解放は別スレッドでやる。画面はタップの
     * フレームで閉じ、カメラは自分の速度で手を離す。
     */
    fun close() {
        // 先に取って空にする。片付けスレッドが dispose() の中にいる間に、
        // 同じものを WebRTC へ渡し直せないように。
        val capturer = videoCapturer
        val helper = surfaceHelper
        val vSource = videoSource
        val aTrack = audioTrack
        val aSource = audioSource
        val pc = connection

        videoCapturer = null
        surfaceHelper = null
        videoSource = null
        localVideo = null
        audioTrack = null
        audioSource = null
        connection = null

        Thread({
            // 順番が要る。フレームの生成を止め、消費側を解放し、接続、それらを所有する
            // factory、最後に GL コンテキスト。
            bestEffort("stop capture") { capturer?.stopCapture() }
            bestEffort("dispose capturer") { capturer?.dispose() }
            bestEffort("dispose surface helper") { helper?.dispose() }
            bestEffort("dispose video source") { vSource?.dispose() }
            bestEffort("dispose audio track") { aTrack?.dispose() }
            bestEffort("dispose audio source") { aSource?.dispose() }
            bestEffort("close peer connection") { pc?.close() }
            bestEffort("dispose peer connection") { pc?.dispose() }
            bestEffort("dispose factory") { factory.dispose() }
            bestEffort("release egl") { eglBase.release() }
        }, "rinowa-call-teardown").start()
    }
}

/** WebRTC の observer はメソッドが4つあるが、たいてい要るのは1つ。 */
private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(reason: String?) = Unit
    override fun onSetFailure(reason: String?) = Unit
}
