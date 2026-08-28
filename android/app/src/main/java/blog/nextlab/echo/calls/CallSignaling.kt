package blog.nextlab.echo.calls

import blog.nextlab.echo.bestEffort
import blog.nextlab.echo.model.ConversationId
import blog.nextlab.echo.model.UserId
import blog.nextlab.echo.data.snapshotFlow
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

/**
 * 2台の端末に、どう話すかを合意させる。
 *
 * WebRTC は、接続ができる*前*に offer と answer と少数のネットワーク候補を両端で
 * やり取りする必要がある。signaling とはそれだけのことで、Firestore がすでにできる
 * （両者が読めるドキュメントと、変化で発火するリスナー）。**新しいサーバーは要らない。**
 *
 * 各自が書けるのは自分の分だけ。`signals/{uid}` で、ルールは自分の名前のドキュメントに
 * しか書かせない。無いと、会話にいる誰かが他人の枠に自分の offer を入れて**通話を
 * 乗っ取れる**。リアクションの「自分のキーしか触れない」と同じ規則で、理由も同じ。
 *
 * 通話が終わったら signal は消す。SDP と ICE 候補はネットワークアドレスの記述で、
 * 終わったあと取っておく理由が無い。残るのは通話の記録（あったこと、長さ）だけで、
 * それが当事者2人に見えるもの。docs/CALLS_ARCHITECTURE.md §3.1。
 */
class CallSignaling(private val db: FirebaseFirestore) {

    private fun calls(conversationId: ConversationId) =
        db.collection("conversations").document(conversationId.value).collection("calls")

    private fun signals(conversationId: ConversationId, callId: CallId) =
        calls(conversationId).document(callId.value).collection("signals")

    // -----------------------------------------------------------------------------------
    // 通話そのもの
    // -----------------------------------------------------------------------------------

    suspend fun place(
        conversationId: ConversationId,
        caller: UserId,
        kind: CallKind,
    ): Result<CallId> = runCatching {
        val document = calls(conversationId).document()
        document.set(
            mapOf(
                FIELD_CALLER to caller.value,
                FIELD_KIND to kind.name.lowercase(),
                FIELD_STATE to CallState.Ringing.wire,
                FIELD_STARTED_AT to FieldValue.serverTimestamp(),
            ),
        ).await()
        CallId(document.id)
    }

    suspend fun setState(
        conversationId: ConversationId,
        callId: CallId,
        state: CallState,
        endReason: CallEndReason? = null,
    ): Result<Unit> = runCatching {
        val update = buildMap<String, Any> {
            put(FIELD_STATE, state.wire)
            if (state == CallState.Ended) {
                put(FIELD_ENDED_AT, FieldValue.serverTimestamp())
                endReason?.let { put(FIELD_END_REASON, it.name.lowercase()) }
            }
        }
        calls(conversationId).document(callId.value).update(update).await()
    }

    /** 1件の通話を見る。両端はこれで状態機械を回す。 */
    fun observe(conversationId: ConversationId, callId: CallId): Flow<CallRecord?> =
        calls(conversationId).document(callId.value).snapshotFlow { snapshot, error ->
            if (error != null) return@snapshotFlow
            trySend(snapshot?.toCallRecord(conversationId))
        }

    /**
     * この会話に着信があるかを見る。
     *
     * 状態でクエリを絞らないのは、`whereEqualTo("state","ringing").orderBy("startedAt")`
     * には複合インデックスが要り、無いと Firestore は空を返すのではなく**リスナーごと
     * 失敗する**から。最初の版がまさにそれで、しかも失敗はエラー分岐で握り潰されていた。
     * フローは何も流さず、応答しても何も起きない。索引が無いのと誰もかけてこないのが
     * 同じに見えた。
     *
     * 1項目の並べ替えだけなら自動の単一索引で足りるので、状態の判定はクライアント側に
     * 移した。数件を絞るのは何でもないし、誰かが忘れた配備作業でクエリが壊れることも
     * なくなる。
     *
     * @param onError 握り潰さず外へ出す。上記のとおり。
     */
    fun observeIncoming(
        conversationId: ConversationId,
        me: UserId,
        onError: (String) -> Unit = {},
    ): Flow<CallRecord?> = calls(conversationId)
        .orderBy(FIELD_STARTED_AT, Query.Direction.DESCENDING)
        .limit(5)
        .snapshotFlow { snapshot, error ->
            if (error != null) {
                onError("${error.code}: ${error.message.orEmpty()}")
                return@snapshotFlow
            }
            val record = snapshot?.documents
                ?.mapNotNull { it.toCallRecord(conversationId) }
                ?.firstOrNull {
                    it.state == CallState.Ringing &&
                        it.isIncomingFor(me) &&
                        // ずっと前に始まって終了を書かれなかった通話が、起動のたびに
                        // 永久に鳴らないように。
                        System.currentTimeMillis() - it.startedAtMs < RINGING_WINDOW_MS
                }
            trySend(record)
        }

    /**
     * id で1件取る。
     *
     * 応答は通知から起きるが、そのために起こされたばかりのプロセスにはまだリスナーが
     * 無い。温まるのを待ったせいで、発信側が鳴らし続けている間、応答側は関係のない
     * 会話を見ていた。
     */
    suspend fun fetchCall(conversationId: ConversationId, callId: CallId): CallRecord? =
        runCatching {
            calls(conversationId).document(callId.value).get().await().toCallRecord(conversationId)
        }
            // ここで null になると応答できない通話になり、画面には何も出ない。
            // 理由を1回握り潰しただけで、まる1セッション溶かした。
            .onFailure { android.util.Log.w("Rinowa/calls", "fetchCall failed", it) }
            .getOrNull()


    // -----------------------------------------------------------------------------------
    // SDP と ICE
    // -----------------------------------------------------------------------------------

    suspend fun putDescription(
        conversationId: ConversationId,
        callId: CallId,
        me: UserId,
        type: String,
        sdp: String,
    ): Result<Unit> = runCatching {
        signals(conversationId, callId).document(me.value)
            .set(
                mapOf(FIELD_SDP_TYPE to type, FIELD_SDP to sdp),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .await()
    }

    /**
     * ネットワーク候補を1つ足す。
     *
     * 置き換えではなく追加。候補は見つかった順に少しずつ届き、相手は全部が要る。
     * 上書きすると、唯一つながったはずの経路を黙って捨てることになる。
     */
    suspend fun addCandidate(
        conversationId: ConversationId,
        callId: CallId,
        me: UserId,
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int,
    ): Result<Unit> = runCatching {
        signals(conversationId, callId).document(me.value)
            .set(
                mapOf(
                    FIELD_CANDIDATES to FieldValue.arrayUnion(
                        mapOf(
                            "candidate" to candidate,
                            "sdpMid" to sdpMid,
                            "sdpMLineIndex" to sdpMLineIndex.toLong(),
                        ),
                    ),
                ),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .await()
    }

    /**
     * こちらのカメラが入っているかを相手に伝える。
     *
     * 各自が持っている `signals/{uid}` に書く。通話中の全員がすでに読めるので、
     * 新しいルールもドキュメントも要らない。共有の通話ドキュメントに項目を足す案だと
     * 「自分の欄しか変えられない」というルールをもう一度書くことになり、得るものが無い。
     *
     * 必要なのは、**カメラを切っても相手側が暗くならない**から。WebRTC は送るのを
     * やめるだけで、最後にデコードしたフレームが残る。伝えないと、相手には固まった
     * 映像しか見えず、切ったのか通話が死んだのか分からない。
     */
    suspend fun putCameraState(
        conversationId: ConversationId,
        callId: CallId,
        me: UserId,
        cameraOn: Boolean,
    ): Result<Unit> = runCatching {
        signals(conversationId, callId).document(me.value)
            .set(
                mapOf(FIELD_CAMERA_ON to cameraOn),
                com.google.firebase.firestore.SetOptions.merge(),
            )
            .await()
    }

    /** 相手がこれまでに出したもの全部。相手が足すたびに更新される。 */
    fun observePeerSignal(
        conversationId: ConversationId,
        callId: CallId,
        peer: UserId,
    ): Flow<PeerSignal?> = signals(conversationId, callId).document(peer.value)
        .snapshotFlow { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) {
                trySend(null)
                return@snapshotFlow
            }
            @Suppress("UNCHECKED_CAST")
            val raw = (snapshot.get(FIELD_CANDIDATES) as? List<Map<String, Any?>>).orEmpty()
            trySend(
                PeerSignal(
                    sdpType = snapshot.getString(FIELD_SDP_TYPE),
                    sdp = snapshot.getString(FIELD_SDP),
                    cameraOn = snapshot.getBoolean(FIELD_CAMERA_ON),
                    candidates = raw.mapNotNull { entry ->
                        val candidate = entry["candidate"] as? String ?: return@mapNotNull null
                        IceCandidateData(
                            candidate = candidate,
                            sdpMid = entry["sdpMid"] as? String ?: "",
                            sdpMLineIndex = (entry["sdpMLineIndex"] as? Number)?.toInt() ?: 0,
                        )
                    },
                ),
            )
        }

    /** ネットワークアドレスが、使った通話より長く残る理由は無い。 */
    suspend fun clearSignals(conversationId: ConversationId, callId: CallId, me: UserId) {
        bestEffort("clear signals") { signals(conversationId, callId).document(me.value).delete().await() }
    }

    private companion object {
        /** これより古ければ、もう誰も待っていない通話。 */
        const val RINGING_WINDOW_MS = 60_000L

        const val FIELD_CALLER = "callerId"
        const val FIELD_KIND = "kind"
        const val FIELD_STATE = "state"
        const val FIELD_STARTED_AT = "startedAt"
        const val FIELD_ENDED_AT = "endedAt"
        const val FIELD_END_REASON = "endReason"
        const val FIELD_SDP_TYPE = "sdpType"
        const val FIELD_SDP = "sdp"
        const val FIELD_CANDIDATES = "candidates"
        const val FIELD_CAMERA_ON = "cameraOn"
    }
}

data class IceCandidateData(val candidate: String, val sdpMid: String, val sdpMLineIndex: Int)

data class PeerSignal(
    val sdpType: String?,
    val sdp: String?,
    val candidates: List<IceCandidateData>,
    /** 相手が言うまで null。無ければ「入っている」とみなす（以前と同じ）。 */
    val cameraOn: Boolean? = null,
)

private val CallState.wire: String get() = name.lowercase()

private fun DocumentSnapshot.toCallRecord(conversationId: ConversationId): CallRecord? {
    if (!exists()) return null
    val caller = getString("callerId") ?: return null
    val state = CallState.entries.firstOrNull { it.wire == getString("state") } ?: return null
    return CallRecord(
        id = CallId(id),
        conversationId = conversationId,
        callerId = UserId(caller),
        kind = if (getString("kind") == "video") CallKind.Video else CallKind.Audio,
        state = state,
        // ローカルの書き込みからサーバーの確定までの一瞬だけ null になる。
        startedAtMs = getTimestamp("startedAt")?.toDate()?.time ?: System.currentTimeMillis(),
        endedAtMs = getTimestamp("endedAt")?.toDate()?.time,
        endReason = CallEndReason.entries.firstOrNull { it.name.lowercase() == getString("endReason") },
    )
}
