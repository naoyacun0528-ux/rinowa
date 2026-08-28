package blog.nextlab.echo.calls

import blog.nextlab.echo.bestEffort
import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import blog.nextlab.echo.model.CallOutcome
import blog.nextlab.echo.model.ConversationId
import blog.nextlab.echo.model.MessageContent
import blog.nextlab.echo.model.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 通話1件の状態機械。[WebRtcSession] と [CallSignaling] を歩調を合わせて動かす。
 * 画面は [state] を読んでボタンを押すだけで、SDP のことは知らない。
 *
 * offer を作るのは常に発信側。両方が offer を出すと衝突（glare）して、追加の仕組み
 * なしには復帰できない。着信側のマイクは応答するまで開けない（拒否した通話が
 * すでに音を拾っていたことになるため）。
 */
class CallController(
    private val context: Context,
    private val signaling: CallSignaling,
    private val scope: CoroutineScope,
    private val me: UserId,
    /** 相手の端末を鳴らす。push の無いビルドでは null。 */
    private val push: (suspend (ConversationId, CallId, CallKind) -> Unit)? = null,
    /** 通話後にスレッドへ1行残す。発信側の端末でだけ呼ばれる。 */
    /**
     * スレッドに残す1行。第2引数は**読めるべき全員**。
     *
     * ここを渡し忘れていた。既定は「送信者だけ」なので、通話の記録は発信した端末に
     * しか復号できない形で暗号化され、相手の画面には最初から出なかった。書き込みは
     * 成功しているので、どこにも失敗が出ない。
     */
    private val recordCall: ((ConversationId, List<UserId>, MessageContent.Call) -> Unit)? = null,
    /**
     * signaling ドキュメントに書く値を封をする。
     *
     * WebRTC は通話相手を SDP の DTLS フィンガープリントで判断する。これを平文で
     * Firestore に置くと、書き換えられる者は自分のフィンガープリントを入れて中継でき、
     * 両端は「正しく暗号化された通話」を攻撃者と結ぶ。ICE 候補も両端の IP アドレス
     * そのもの。どちらも会話の鍵で封をする。封ができなければ通話を失敗させる。
     */
    private val seal: (suspend (ConversationId, List<UserId>, String) -> String?)? = null,
    private val open: (suspend (ConversationId, UserId, String) -> String?)? = null,
) {

    var state by mutableStateOf<CallState?>(null)
        private set

    var active by mutableStateOf<CallRecord?>(null)
        private set

    var muted by mutableStateOf(false)
        private set

    var speakerOn by mutableStateOf(false)
        private set

    /**
     * ICE の一覧が STUN だけで返ってきたら false。
     *
     * その構成は同じ回線の2台では動き、別のキャリア同士では失敗する。黙っていると
     * アプリが壊れているように見えるので表に出す。
     */
    var relayAvailable by mutableStateOf(true)
        private set

    /** 相手のカメラ映像。音声通話では null。 */
    var remoteVideo by mutableStateOf<org.webrtc.VideoTrack?>(null)
        private set

    /** 自分のカメラ映像（自画面用）。 */
    var localVideo by mutableStateOf<org.webrtc.VideoTrack?>(null)
        private set

    var cameraOn by mutableStateOf(false)
        private set

    var usingFrontCamera by mutableStateOf(true)
        private set

    /** 相手のカメラが入っているか。初期値 true（まだ何も言っていない状態を「切った」と描かない）。 */
    var peerCameraOn by mutableStateOf(true)
        private set

    /**
     * 通話中の通知に出す相手の名前。
     *
     * このコントローラは画面より上で保持されるので、会話を離れても通話が続く代わりに
     * 誰との会話かを知らない。UI が入れる。入らなくても通知は出る（名前が無いだけ）。
     */
    var peerLabel: String = ""

    /** 描画側がデコーダと共有する GL コンテキスト。 */
    val eglBase: org.webrtc.EglBase? get() = session?.eglBase

    /** この端末がハードウェアで扱える映像コーデック。集めているだけで、まだ何もしない。 */
    var videoCodecs by mutableStateOf<List<String>>(emptyList())
        private set

    /** 失敗の内容をそのまま。黙って失敗すると、失敗した本人にも報告できない。 */
    var failure by mutableStateOf<String?>(null)
        private set

    private var ringTimeout: Job? = null
    private var peerGoneGrace: Job? = null
    private var session: WebRtcSession? = null
    private var watchers = mutableListOf<Job>()
    private var peer: UserId? = null

    /** すでに開いて WebRTC に渡した暗号文。 */
    private val appliedCandidates = mutableSetOf<String>()

    // -----------------------------------------------------------------------------------

    fun place(conversationId: ConversationId, peerId: UserId, kind: CallKind = CallKind.Audio) {
        if (active != null) return
        peer = peerId
        failure = null
        state = CallState.Ringing
        scope.launch {
            val callId = signaling.place(conversationId, me, kind).getOrElse {
                fail("発信できませんでした: ${it.message.orEmpty()}")
                return@launch
            }
            active = CallRecord(
                id = callId,
                conversationId = conversationId,
                callerId = me,
                kind = kind,
                state = CallState.Ringing,
                startedAtMs = System.currentTimeMillis(),
            )
            // 接続を組む前に鳴らす。候補を集めている間に相手が鳴っていてほしい。
            push?.invoke(conversationId, callId, kind)

            if (!openSession()) return@launch

            val offer = runCatching { session!!.createOffer() }.getOrElse {
                fail("接続の準備に失敗: ${it.message.orEmpty()}")
                return@launch
            }
            val sealedOffer = sealFor(conversationId, peerId, offer) ?: run {
                fail("通話を暗号化できませんでした")
                return@launch
            }
            signaling.putDescription(conversationId, callId, me, "offer", sealedOffer)
            watch(conversationId, callId, peerId)

            // 呼び出しには上限を置く。相手の端末も同じ時間で止まるので、その先は
            // 誰も鳴らしていない音を聞くことになる（上限が無くて「ずっと呼び出し中」になった）。
            ringTimeout = scope.launch {
                kotlinx.coroutines.delay(RING_TIMEOUT_MS)
                if (state == CallState.Ringing) {
                    failure = "応答がありませんでした"
                    signaling.setState(conversationId, callId, CallState.Ended, CallEndReason.Missed)
                    pendingOutcome = CallOutcome.Missed
                    teardown()
                }
            }
        }
    }

    fun accept(record: CallRecord) {
        if (active != null) return
        peer = record.callerId
        failure = null
        active = record
        state = CallState.Connecting
        scope.launch {
            if (!openSession()) return@launch
            signaling.setState(record.conversationId, record.id, CallState.Connecting)
            watch(record.conversationId, record.id, record.callerId)
        }
    }

    fun decline(record: CallRecord) {
        scope.launch {
            signaling.setState(record.conversationId, record.id, CallState.Ended, CallEndReason.Declined)
        }
    }

    fun hangUp(reason: CallEndReason = CallEndReason.Hangup) {
        val record = active ?: return
        // 誰も出ないうちに切ったものは「通話した」ではない。
        pendingOutcome = if (connectedAtMs != null) CallOutcome.Completed else CallOutcome.Missed
        scope.launch {
            signaling.setState(record.conversationId, record.id, CallState.Ended, reason)
            signaling.clearSignals(record.conversationId, record.id, me)
        }
        teardown()
    }

    fun toggleMute() {
        muted = !muted
        session?.setMicrophoneEnabled(!muted)
        // 小窓のボタンにも今の状態を出すため。
        CallPresence.publish(muted = muted, cameraOn = cameraOn)
    }

    fun toggleCamera() {
        cameraOn = !cameraOn
        session?.setCameraEnabled(cameraOn)
        CallPresence.publish(muted = muted, cameraOn = cameraOn)
        val record = active ?: return
        // カメラを切っても相手の画面は暗くならず、最後のフレームが残る。伝えないと
        // 「カメラを切った」のか「通話が死んだ」のか区別できない。
        scope.launch {
            signaling.putCameraState(record.conversationId, record.id, me, cameraOn)
                .onFailure { failure = "カメラの状態を相手に伝えられませんでした" }
        }
    }

    fun switchCamera() {
        session?.switchCamera()
        usingFrontCamera = session?.usingFrontCamera ?: true
    }

    fun toggleSpeaker() {
        speakerOn = !speakerOn
        audioManager().isSpeakerphoneOn = speakerOn
    }

    // -----------------------------------------------------------------------------------

    private suspend fun openSession(): Boolean {
        // 埋め込まず取りに行く。TURN の資格情報は期限切れになるし、提供元も変わる。
        // ここで失敗しても発信は止めず STUN に落とす。
        val servers = IceServers.current()

        // 中継が無いと別回線同士の通話は繋がらない。「アプリが壊れている」と
        // 「中継がまだ用意できていない」の違いは表示する価値がある。
        relayAvailable = IceServers.hasRelay(servers)

        val created = WebRtcSession(
            context = context,
            iceServers = servers,
            onLocalCandidate = { candidate ->
                val record = active ?: return@WebRtcSession
                scope.launch {
                    // 候補はこの端末のアドレスそのもの。封をしないと、通話のたびに
                    // 二人の居場所をサーバーに教えることになる。
                    val peer = peer ?: return@launch
                    val sealed = sealFor(record.conversationId, peer, candidate.sdp)
                        ?: return@launch
                    signaling.addCandidate(
                        record.conversationId, record.id, me,
                        sealed, candidate.sdpMid.orEmpty(), candidate.sdpMLineIndex,
                    )
                }
            },
            onConnected = {
                ringTimeout?.cancel()
                ringTimeout = null
                // 回線切り替えから復帰した。切断ではなかった。
                peerGoneGrace?.cancel()
                peerGoneGrace = null
                state = CallState.Active
                // 最初の1回だけ。再接続は同じ会話なので、測り直すと短く出る。
                if (connectedAtMs == null) connectedAtMs = System.currentTimeMillis()
                val record = active ?: return@WebRtcSession
                scope.launch { signaling.setState(record.conversationId, record.id, CallState.Active) }
            },
            onRemoteVideo = { track -> remoteVideo = track },
            onPeerGone = { settled ->
                if (settled) {
                    endBecausePeerLeft()
                } else {
                    // DISCONNECTED は Wi-Fi からモバイルへ切り替わったときにも起きて、
                    // 数秒で戻る。すぐ終わらせると直るはずの通話を切ってしまうので待つ。
                    peerGoneGrace?.cancel()
                    peerGoneGrace = scope.launch {
                        kotlinx.coroutines.delay(PEER_GONE_GRACE_MS)
                        endBecausePeerLeft()
                    }
                }
            },
            onClosed = { failed ->
                if (failed) {
                    // 「失敗した」ではなく3通りのどれか。中継候補が出たかどうかで
                    // 「中継に届かない」「中継はあるが相手側に無い」「両方あるのに繋がらない」が
                    // 分かれ、直し方も別。
                    val detail = session?.diagnosis().orEmpty()
                    val cause = when {
                        session?.sawRelay != true -> "中継サーバーに繋がりませんでした"
                        else -> "中継はあったのに経路が成立しませんでした"
                    }
                    fail("$cause — $detail")
                } else {
                    teardown()
                }
            },
        )
        session = created

        if (!created.start()) {
            fail("音声の初期化に失敗しました")
            return false
        }
        videoCodecs = created.supportedVideoCodecs()

        // ビデオ通話は最初からカメラを開き、音声通話は開かない。途中から入れるのは
        // 再ネゴシエーションになるので、種別は発信時に決める。
        if (active?.kind == CallKind.Video) {
            if (created.startVideo(front = true)) {
                localVideo = created.localVideo
                cameraOn = true
                usingFrontCamera = created.usingFrontCamera
                active?.let { r ->
                    scope.launch { signaling.putCameraState(r.conversationId, r.id, me, true) }
                }
            } else {
                // カメラが使えない。話したい二人は話したいままなので音声で続ける。
                failure = "カメラを開けませんでした。音声のみで続けます"
            }
        }

        // ビデオ通話はスピーカー、音声通話は受話口。
        audioManager().mode = AudioManager.MODE_IN_COMMUNICATION
        speakerOn = active?.kind == CallKind.Video
        audioManager().isSpeakerphoneOn = speakerOn

        // onConnected ではなくここから。ICE 交渉中の数秒こそ、他のアプリに切り替えられ、
        // Android にプロセスを殺されやすい時間帯。
        CallPresence.videoActive = active?.kind == CallKind.Video
        CallPresence.hangUp = { hangUp() }
        CallPresence.toggleMute = { toggleMute() }
        CallPresence.toggleCamera = { toggleCamera() }
        CallPresence.publish(muted = muted, cameraOn = cameraOn)

        active?.let { record ->
            OngoingCallService.start(
                context = context,
                peerName = peerLabel.ifBlank { "通話中" },
                conversationId = record.conversationId.value,
                isVideo = record.kind == CallKind.Video,
                startedAt = System.currentTimeMillis(),
            )
        }
        return true
    }

    /** 1つ封をする。できなければ null を返し、呼び出し側が通話を失敗させる（平文では送らない）。 */
    private suspend fun sealFor(
        conversationId: ConversationId,
        peerId: UserId,
        value: String,
    ): String? {
        val sealer = seal ?: return value
        return sealer(conversationId, listOf(me, peerId), value)
    }

    /**
     * 1つ開く。開けなければ null。
     *
     * SDP や candidate の頭で始まる値は封をされていない＝この変更より前のビルドから来たもの。
     * 使わずに拒否する（使えば、防ぎたかった場合を許すことになる）。
     */
    private suspend fun openFrom(
        conversationId: ConversationId,
        peerId: UserId,
        value: String,
    ): String? {
        val opener = open ?: return value
        if (value.startsWith("v=0") || value.startsWith("candidate:")) {
            android.util.Log.w("Rinowa/calls", "refusing an unsealed signal")
            return null
        }
        return opener(conversationId, peerId, value)
    }

    private fun watch(conversationId: ConversationId, callId: CallId, peerId: UserId) {
        watchers += scope.launch {
            signaling.observePeerSignal(conversationId, callId, peerId).collect { signal ->
                val current = session ?: return@collect
                signal ?: return@collect

                if (signal.sdp != null && signal.sdpType != null && !appliedRemote) {
                    val opened = openFrom(conversationId, peerId, signal.sdp) ?: run {
                        fail("通話の暗号を解けませんでした")
                        return@collect
                    }
                    appliedRemote = true
                    current.setRemoteDescription(signal.sdpType, opened)
                    // offer を受け取った側が answer を返す。発信側はこの時点で送るものが無い。
                    if (signal.sdpType == "offer") {
                        val answer = runCatching { current.createAnswer() }.getOrElse {
                            fail("応答の生成に失敗: ${it.message.orEmpty()}")
                            return@collect
                        }
                        val sealedAnswer = sealFor(conversationId, peerId, answer) ?: run {
                            fail("通話を暗号化できませんでした")
                            return@collect
                        }
                        signaling.putDescription(conversationId, callId, me, "answer", sealedAnswer)
                    }
                }
                signal.cameraOn?.let { peerCameraOn = it }
                signal.candidates.forEach { candidate ->
                    // 配列は相手がアドレスを見つけるたびに伸び、そのたびに全件が再配信される。
                    // 弾かないと同じものを何度も復号することになり、Megolm 的にも再生扱いになる。
                    if (!appliedCandidates.add(candidate.candidate)) return@forEach

                    val opened = openFrom(conversationId, peerId, candidate.candidate)
                    // 開けない候補は捨てるだけ（数が多く、1つ通れば足りる）。offer は
                    // 通らないと困るので、そちらは失敗として扱う。
                        ?: return@forEach
                    current.addRemoteCandidate(candidate.copy(candidate = opened))
                }
            }
        }

        watchers += scope.launch {
            signaling.observe(conversationId, callId).collect { record ->
                record ?: return@collect
                // 片付けたあとに配信が1件届くことがある（リスナーは止めても、飛んでいる途中のもの）。
                // 弾かないと active が書き戻されて、終わった通話の画面が復活する。
                if (active?.id != callId) return@collect
                active = record
                when (record.state) {
                    CallState.Connecting -> if (state == CallState.Ringing) {
                        // 相手が出た。この先の問題は接続の問題であって不在ではないので、
                        // 応答なしのタイマーはここで止める。
                        ringTimeout?.cancel()
                        ringTimeout = null
                        state = CallState.Connecting
                    }
                    CallState.Ended -> {
                        if (record.endReason == CallEndReason.Declined) failure = "相手が応答しませんでした"
                        pendingOutcome = when {
                            connectedAtMs != null -> CallOutcome.Completed
                            record.endReason == CallEndReason.Declined -> CallOutcome.Declined
                            record.endReason == CallEndReason.Busy -> CallOutcome.Declined
                            record.endReason == CallEndReason.Failed -> CallOutcome.Failed
                            else -> CallOutcome.Missed
                        }
                        teardown()
                    }
                    else -> Unit
                }
            }
        }
    }

    private var appliedRemote = false

    /**
     * 音が流れ始めた時刻。流れなければ null。
     *
     * 発信した時刻ではない。呼び出し時間を含めると、相手が電話を探していた時間まで
     * 通話時間になる。
     */
    private var connectedAtMs: Long? = null

    /** スレッドに残す結果。通話が終わった時点で決まる。 */
    private var pendingOutcome: CallOutcome? = null

    /** push の ttl と着信サービスと同じ値。3つが揃っている必要がある。 */
    private val RING_TIMEOUT_MS = 35_000L

    /** Wi-Fi とモバイルの切り替えに耐え、かつ固まって見えない長さ。 */
    private val PEER_GONE_GRACE_MS = 6_000L

    /**
     * 相手がいなくなった（切り方は問わない）。
     *
     * signaling 側でも終了にする。アプリが殺されるなどして切断が届かなかった通話が、
     * あとで開いた人にはずっと通話中に見えてしまうため。
     */
    private fun endBecausePeerLeft() {
        if (active == null) return
        pendingOutcome = if (connectedAtMs != null) CallOutcome.Completed else CallOutcome.Failed
        val record = active
        if (record != null) {
            scope.launch {
                signaling.setState(
                    record.conversationId, record.id, CallState.Ended, CallEndReason.Hangup,
                )
                signaling.clearSignals(record.conversationId, record.id, me)
            }
        }
        teardown()
    }

    private fun fail(message: String) {
        // teardown より先に読む。診断はセッションの中にあり、teardown で捨てられる。
        failure = message
        pendingOutcome = if (connectedAtMs != null) CallOutcome.Completed else CallOutcome.Failed
        val record = active
        if (record != null) {
            scope.launch {
                signaling.setState(record.conversationId, record.id, CallState.Ended, CallEndReason.Failed)
            }
        }
        teardown()
    }

    private fun teardown() {
        // 通話の終わり方は全部ここを通る。だからサービスの停止も記録の書き込みも
        // 1箇所で済む。終わったのに通知が残ると「マイクがまだ開いている」と言うことになる。
        leaveRecordInThread()

        OngoingCallService.stop(context)
        CallPresence.clear()

        ringTimeout?.cancel()
        ringTimeout = null
        peerGoneGrace?.cancel()
        peerGoneGrace = null
        watchers.forEach { it.cancel() }
        watchers.clear()
        session?.close()
        session = null
        appliedRemote = false
        muted = false
        speakerOn = false
        connectedAtMs = null
        peerCameraOn = true
        remoteVideo = null
        localVideo = null
        cameraOn = false
        peer = null
        appliedCandidates.clear()
        active = null
        state = null
        // 黙って失敗すると、通話後も端末が通話用の音声設定のまま残る。
        bestEffort("restore audio mode") {
            audioManager().mode = AudioManager.MODE_NORMAL
            audioManager().isSpeakerphoneOn = false
        }
    }

    /**
     * 会話に残る1行を書く。
     *
     * 通話の終わり方は6通りあり、全部 [teardown] を通る。各所で書くと二重に書く機会が
     * 6回できる。書くのは発信側だけ（両方が書くと必ず2件になり、各自が測った通話時間で
     * 食い違う）。ネットワークの書き込みを待たずに投げっぱなしにする — 終わった通話が
     * マイクを離すのを遅らせない。
     */
    private fun leaveRecordInThread() {
        val record = active ?: return
        val outcome = pendingOutcome ?: return
        pendingOutcome = null
        if (record.callerId != me) return

        val seconds = connectedAtMs
            ?.let { ((System.currentTimeMillis() - it) / 1000).toInt() }
            ?.coerceIn(0, MAX_CALL_SECONDS)
            ?: 0

        recordCall?.invoke(
            record.conversationId,
            listOfNotNull(me, peer),
            MessageContent.Call(
                video = record.kind == CallKind.Video,
                outcome = outcome,
                // 時間を書くのは繋がった通話だけ。誰も出なかった呼び出しの会話時間は0秒。
                seconds = if (outcome == CallOutcome.Completed) seconds else 0,
            ),
        )
    }

    private fun audioManager() =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun dismissFailure() { failure = null }
}

/**
 * 1日。
 *
 * firestore.rules の上限と同じ。ここで丸めておけば、時計が飛んだとき（時差や NTP 補正）
 * 書き込みが失敗して行ごと消えるのではなく、変な数字が出るだけで済む。
 */
private const val MAX_CALL_SECONDS = 86_400
