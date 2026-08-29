package blog.nextlab.echo.crypto

import android.content.Context
import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.data.renamedPreferences
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.crypto.EncryptionSettings
import org.matrix.rustcomponents.sdk.crypto.EventEncryptionAlgorithm
import org.matrix.rustcomponents.sdk.crypto.OlmMachine
import org.matrix.rustcomponents.sdk.crypto.Request
import org.matrix.rustcomponents.sdk.crypto.RequestType
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement

/**
 * 暗号化本体と、それを回すループ。
 *
 * 暗号は一切自分で書かない。鍵もラチェットも署名も matrix-sdk-crypto（Olm/Megolm の
 * Rust 実装）のもの。ここにあるのは「何を送りたいか」を聞いて返事を渡すループと、
 * 本文を封じる／開く2つの呼び出しだけ。
 *
 * エンジンは通信しない状態機械で、次の順に回して初めて進む:
 *
 *     outgoingRequests() -> 送る -> markRequestAsSent(id, type, reply)
 *
 * キューを流さない端末は鍵を公開せず、誰もその端末宛に暗号化できない。症状は
 * 「この人からのメッセージが届かない」なので、[pump] は起動時だけでなく送受信のたびに回す。
 *
 * mutex があるのは、machine と store が1つで呼び出し側が複数だから（送信中に push が
 * 来るなど）。store は Rust 側が持つ SQLite で、同時に叩くのは扱いきれない。
 */
class CryptoEngine private constructor(
    private val machine: OlmMachine,
    private val transport: CryptoTransport,
    private val me: UserId,
) {

    private val lock = Mutex()

    /** 端末一覧を最近確認した部屋。prepareRoom を参照。 */
    private val preparedRooms = mutableMapOf<String, Long>()

    /**
     * 一度 Megolm セッションを捨て直した部屋。
     *
     * プロセスごと・部屋ごとに1回まで。配る相手が本当にいない場合に繰り返すと、
     * メッセージのたびにセッションを作り直すことになる。
     */
    private val recovered = mutableSetOf<String>()

    val deviceId: String get() = machine.deviceId()

    /** この端末の公開識別子。「この端末を確認」画面用。 */
    fun identityKeys(): Map<String, String> = machine.identityKeys()

    /**
     * 相手の端末を、指紋つきで並べる。
     *
     * **利用者が「相手が本人か」を確かめる唯一の手段。** サーバーが公開鍵を配っていて、
     * 検証する第三者はいない。だから鍵そのものを見せて、別の経路（対面・電話）で
     * 突き合わせてもらう。LINE も同じ形（技術白書 v2.2）。
     *
     * 消した端末の登録は残る。**それも隠さず出す。** 見えない古い端末に鍵が配られる
     * ほうが、一覧が長いことより悪い。
     */
    suspend fun devicesOf(user: UserId): List<DeviceFingerprint> = lock.withLock {
        withContext(Dispatchers.IO) {
            val matrixUser = CryptoIds.matrixUser(user)

            // **先に取りに行く。** これが無いと、画面は最後に送ったときの記録を出す。
            //
            // 端末一覧が更新されるのは prepareRoom（＝送るとき）だけだった。確認画面を
            // 開いただけでは何も問い合わせず、実機で「今日登録したはずの端末が出ない」
            // という形で出た。**確認の画面が古い事実を見せるのは、無いより悪い。**
            runCatching {
                machine.receiveSyncChanges(
                    events = EMPTY_EVENTS,
                    deviceChanges = org.matrix.rustcomponents.sdk.crypto.DeviceLists(
                        changed = listOf(matrixUser),
                        left = emptyList(),
                    ),
                    keyCounts = emptyMap(),
                    unusedFallbackKeys = null,
                    nextBatchToken = "",
                    decryptionSettings = DEFAULT_DECRYPTION,
                )
                machine.updateTrackedUsers(listOf(matrixUser))
            }.onFailure { CryptoProblems.record("devicesOf/refresh", it) }
            pumpLocked()

            // swallow-ok: 端末をまだ公開していない相手では例外になる。答えは「0台」で
            // 正しく、画面はそう出す。ここで投げると画面が開かなくなる。
            runCatching { machine.getUserDevices(matrixUser, 10u) }
                .onFailure { CryptoProblems.record("devicesOf", it) }
                .getOrDefault(emptyList())
                .map { device ->
                    DeviceFingerprint(
                        deviceId = device.deviceId,
                        // 鍵は "ed25519:DEVICEID" のような綴りで入っている。
                        ed25519 = device.keys.entries
                            .firstOrNull { it.key.startsWith("ed25519") }?.value.orEmpty(),
                        locallyTrusted = device.locallyTrusted,
                        crossSigned = device.crossSigningTrusted,
                        // 単位は FFI の資料に書かれていない。**推測せず実機で確かめた** —
                        // 1000倍すると 1月15日 のような無関係な日付になり、そのままだと
                        // 実際の登録日と一致した。よってミリ秒。
                        firstSeenMs = device.firstTimeSeenTs.toLong(),
                    )
                }
                .sortedByDescending { it.firstSeenMs }
        }
    }

    /**
     * 「この端末は確かに本人のものだ」と印を付ける。
     *
     * **この端末の中だけの記録。** サーバーにも相手にも送らない。指紋を読み合わせた
     * のは利用者本人なので、その判断を持つのも本人の端末でよい。
     *
     * 取り消せる。読み合わせを間違えることはあるし、あとで疑わしくなることもある。
     */
    suspend fun setVerified(user: UserId, deviceId: String, verified: Boolean) = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                machine.setLocalTrust(
                    CryptoIds.matrixUser(user),
                    deviceId,
                    if (verified) {
                        uniffi.matrix_sdk_crypto.LocalTrust.VERIFIED
                    } else {
                        uniffi.matrix_sdk_crypto.LocalTrust.UNSET
                    },
                )
            }.onFailure { CryptoProblems.record("setVerified", it) }
            Unit
        }
    }

    /** この端末の指紋。相手に読み上げてもらうのはこれ。 */
    fun myFingerprint(): DeviceFingerprint = DeviceFingerprint(
        deviceId = machine.deviceId(),
        ed25519 = machine.identityKeys()["ed25519"].orEmpty(),
        locallyTrusted = true,
        crossSigned = false,
        firstSeenMs = 0L,
    )

    /** 送るものが無くなるまで回す。回数に上限を置く（失敗が続くと lock を握って回り続けるため）。 */
    suspend fun pump(): Int = lock.withLock { withContext(Dispatchers.IO) { pumpLocked() } }

    private suspend fun pumpLocked(): Int {
        var handled = 0
        repeat(MAX_PUMP_ROUNDS) {
            // ここで空リストになると「やることが無い」と見分けがつかず、鍵が公開されない。
            val requests = runCatching { machine.outgoingRequests() }
                .onFailure { CryptoProblems.record("outgoingRequests", it) }
                .getOrDefault(emptyList())
            if (requests.isEmpty()) return handled
            for (request in requests) {
                if (dispatch(request)) handled++
            }
        }
        return handled
    }

    /** 要求を1つ送り、返事を1つ入れる。エンジンが受け取ったかを返す。 */
    private suspend fun dispatch(request: Request): Boolean = runCatching {
        when (request) {
            is Request.KeysUpload -> {
                val reply = transport.uploadKeys(me, machine.deviceId(), request.body)
                machine.markRequestAsSent(request.requestId, RequestType.KEYS_UPLOAD, reply)
            }

            is Request.KeysQuery -> {
                val reply = transport.queryKeys(request.users)
                machine.markRequestAsSent(request.requestId, RequestType.KEYS_QUERY, reply)
            }

            is Request.KeysClaim -> {
                val reply = transport.claimKeys(request.oneTimeKeys)
                machine.markRequestAsSent(request.requestId, RequestType.KEYS_CLAIM, reply)
            }

            is Request.ToDevice -> {
                val delivered = transport.sendToDevice(me, request.eventType, request.body)
                android.util.Log.i("Rinowa/crypto", "toDevice sent type=" + request.eventType + " count=" + delivered)
                machine.markRequestAsSent(request.requestId, RequestType.TO_DEVICE, EMPTY_JSON)
            }

            // 署名アップロードはクロス署名の機能で、まだ使っていない。無視ではなく
            // 返事をする（放置するとキューに残り、後ろが全部詰まる）。
            is Request.SignatureUpload -> machine.markRequestAsSent(
                request.requestId, RequestType.SIGNATURE_UPLOAD, EMPTY_FAILURES,
            )

            // 鍵のバックアップはサーバーではなく Google ドライブ。上と同じ理由。
            is Request.KeysBackup -> machine.markRequestAsSent(
                request.requestId, RequestType.KEYS_BACKUP, EMPTY_JSON,
            )

            is Request.RoomMessage -> machine.markRequestAsSent(
                request.requestId, RequestType.ROOM_MESSAGE, EMPTY_EVENT_ID,
            )
        }
        true
    }
        .onFailure { CryptoProblems.record("dispatch/${request::class.java.simpleName}", it) }
        .getOrDefault(false)

    /**
     * この端末宛に届いていたものを取り込む。
     *
     * 部屋の鍵はここを通って入る。つまり、これを走らせていないというだけで
     * メッセージが開けない。だから復号の前に呼ぶ（定期実行ではなく）。
     */
    /** 最後に在庫を数えた時刻。数えないあいだは空を返す。 */
    private var countedAtMs = 0L

    /**
     * エンジンに渡す在庫の数。
     *
     * 毎回数えると受信のたびに Firestore の集計が1回増える。エンジンが要るのは
     * 「減ってきたら作り足す」の判断だけなので、間隔を空けても足りる。起動直後は
     * [countedAtMs] が 0 なので必ず1回数える。移行後の端末はそこで気づく。
     */
    private suspend fun keyCounts(): Map<String, Int> {
        val now = System.currentTimeMillis()
        if (now - countedAtMs < COUNT_INTERVAL_MS) return emptyMap()
        val count = transport.oneTimeKeyCount(me, machine.deviceId())
        // 数えられなかった回は伝えない。推測をエンジンの前提にしない。
        if (count < 0) return emptyMap()
        countedAtMs = now
        return mapOf(CryptoTransport.SIGNED_CURVE to count)
    }

    suspend fun receive(): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            val events = transport.drainToDevice(me)
            android.util.Log.i("Rinowa/crypto", "drained me=" + CryptoIds.matrixUser(me) + " bytes=" + events.length)

            // 在庫の数を伝える。受信箱が空でもここは通す。
            //
            // 空だからと早く帰っていると、**鍵を使い切った端末は永久に気づけない**。
            // 誰もセッションを張れないので to-device も届かず、受信箱は空のまま。
            // 「何も来ていない」と「もう誰も来られない」が同じ見た目になる。
            val counts = keyCounts()
            if (events == EMPTY_EVENTS && counts.isEmpty()) return@withContext

            runCatching {
                machine.receiveSyncChanges(
                    events = events,
                    deviceChanges = org.matrix.rustcomponents.sdk.crypto.DeviceLists(
                        changed = emptyList(),
                        left = emptyList(),
                    ),
                    keyCounts = counts,
                    unusedFallbackKeys = null,
                    nextBatchToken = "",
                    decryptionSettings = DEFAULT_DECRYPTION,
                )
            }
                // 取り込みの失敗は静かな結果ではない。鍵が来たのに入らなかったということで、
                // それで開くはずだったメッセージが理由も出ずに閉じたままになる。
                .onFailure { CryptoProblems.record("receiveSyncChanges", it) }
                .onSuccess {
                    // エンジンが受け取ったあと、しかも前の回で既に見たものだけ消す。
                    // CryptoTransport.clearToDevice を参照。
                    if (events != EMPTY_EVENTS) transport.clearToDevice(me)
                }
            pumpLocked()
        }
    }

    /**
     * 本文を1つ暗号化する。
     *
     * セッションの無い端末とはここで確立する（getMissingSessions と shareRoomKey）。
     * どちらも新しい要求を作るので、間でクランクを回す。
     */
    suspend fun encrypt(
        conversation: ConversationId,
        members: List<UserId>,
        plaintext: String,
        /**
         * 準備と封じ込めにかかった時間をマイクロ秒で報告する。
         *
         * 別々に測る。前者は通信と帳簿（本来は繰り返さない）、後者は実際の暗号処理で、
         * 1つの数字にすると遅い送信の原因が分からない。
         */
        onTiming: ((setupUs: Long, sealUs: Long) -> Unit)? = null,
    ): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val room = CryptoIds.matrixRoom(conversation)
                val users = members.map(CryptoIds::matrixUser)

                val setupStart = System.nanoTime()
                prepareRoom(room, users)
                val setupUs = (System.nanoTime() - setupStart) / 1_000

                val sealStart = System.nanoTime()
                val sealed =
                    machine.encrypt(room, EVENT_TYPE, JSONObject().put("body", plaintext).toString())
                val sealUs = (System.nanoTime() - sealStart) / 1_000

                onTiming?.invoke(setupUs, sealUs)
                sealed
            }
                .onFailure { CryptoProblems.record("encrypt", it) }
                .getOrNull()
        }
    }

    /**
     * 送る前に、参加者の端末が全部開けるようにしておく。
     *
     * 全手順（利用者を追跡・キューを流す・セッションの無い端末に要求・もう一度流す・
     * 部屋の鍵を配る）は、参加者や端末が変わった部屋のためにある。ほとんどの送信では
     * 何も変わっていないので、[SESSION_FRESH_MS] の間は前半を飛ばす。
     *
     * [shareRoomKey] は毎回呼ぶ。Megolm セッションを入れ替える時期を決めているのが
     * これで、飛ばすと鍵の更新が黙って止まる（速度ではなく安全側の設定）。
     *
     * 代償として、途中で入った端末は窓が切れるまで気付かれず、その間の分を読めない。
     * 本物のクライアントはサーバーからの端末一覧変更通知で回避するが、ここにサーバーは無い。
     */
    private suspend fun prepareRoom(room: String, users: List<String>) {
        val signature = room + "|" + users.sorted().joinToString(",")
        val now = System.currentTimeMillis()
        val lastSeen = preparedRooms[signature]

        if (lastSeen == null || now - lastSeen > SESSION_FRESH_MS) {
            // 「この人たちの端末は変わったかもしれない」とエンジンに伝える。
            //
            // Megolm は部屋の鍵を渡した相手を覚えていて、二度は渡さない。Matrix では
            // ホームサーバーが「端末一覧が変わった」と伝えてその記憶が無効になるが、
            // ここにサーバーは無い。結果、相手がまだ鍵を publish していない時点で作った
            // セッションが「配り済み」のまま固定される。
            //
            // 症状は静かで正確だった。shareRoomKey は要求0件を返し、相手1人・端末1台と
            // 報告し、送信側は自分のメッセージを問題なく読め、受信側はずっと 🔒。
            // この件数を出力していたから見つかった。
            runCatching {
                machine.receiveSyncChanges(
                    events = EMPTY_EVENTS,
                    deviceChanges = org.matrix.rustcomponents.sdk.crypto.DeviceLists(
                        changed = users,
                        left = emptyList(),
                    ),
                    keyCounts = emptyMap(),
                    unusedFallbackKeys = null,
                    nextBatchToken = "",
                    decryptionSettings = DEFAULT_DECRYPTION,
                )
            }.onFailure { CryptoProblems.record("invalidateDevices", it) }

            machine.updateTrackedUsers(users)
            pumpLocked()
            machine.getMissingSessions(users)?.let { dispatch(it) }
            pumpLocked()
            preparedRooms[signature] = now
        }

        val shared = machine.shareRoomKey(room, users, defaultEncryptionSettings())
        shared.forEach { dispatch(it) }

        // 「誰にも配らず暗号化した」は「成功した」と見分けがつかない。
        //
        // 送信側は常に自分のメッセージを読めるので、相手0台に配った鍵でも、この端末では
        // 完璧に動いて他の端末では永久に読めないスレッドができる。例外も出ない。
        // なので数えて表に出す。
        val others = users.count { it != CryptoIds.matrixUser(me) }
        val devices = users.filter { it != CryptoIds.matrixUser(me) }.sumOf { user ->
            // swallow-ok: これは診断用の数え上げ。端末を publish していない利用者では
            // 空ではなく例外になるが、どちらでも正しい答えは0。結果は下の条件が報告する。
            runCatching { machine.getUserDevices(user, 10u).size }.getOrDefault(0)
        }
        android.util.Log.i(
            "Rinowa/crypto",
            "shareRoomKey room=$room others=$others devices=$devices requests=${shared.size}",
        )
        if (others > 0 && devices == 0) {
            CryptoProblems.record(
                "shareRoomKey",
                IllegalStateException("相手の端末が1台も見つかりません（鍵を配れていません）"),
            )
            return
        }

        // 配る相手はいるのに送るものが0件、という形になったらセッションを捨てて作り直す。
        // Megolm セッションの入れ替えは鍵配布1回分の費用で済むが、放置するとその相手への
        // メッセージが全部読めない。
        if (shared.isEmpty() && devices > 0 && !recovered.contains(room)) {
            recovered += room
            android.util.Log.i("Rinowa/crypto", "discarding stale room key for $room")
            runCatching { machine.discardRoomKey(room) }
                .onFailure { CryptoProblems.record("discardRoomKey", it) }
            val again = machine.shareRoomKey(room, users, defaultEncryptionSettings())
            again.forEach { dispatch(it) }
            android.util.Log.i("Rinowa/crypto", "reshared requests=${again.size}")
            if (again.isEmpty()) {
                CryptoProblems.record(
                    "shareRoomKey",
                    IllegalStateException("鍵を配り直しても要求が0件（相手は読めません）"),
                )
            }
        }
    }

    /** 鍵がまだ来ていなければ null。呼び出し側はエラーではなく「待っています」を出す。 */
    suspend fun decrypt(
        conversation: ConversationId,
        sender: UserId,
        ciphertext: String,
    ): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val event = JSONObject()
                    .put("type", "m.room.encrypted")
                    .put("sender", CryptoIds.matrixUser(sender))
                    .put("event_id", SYNTHETIC_EVENT_ID)
                    .put("origin_server_ts", System.currentTimeMillis())
                    .put("content", JSONObject(ciphertext))
                    .toString()

                val decrypted = machine.decryptRoomEvent(
                    event = event,
                    roomId = CryptoIds.matrixRoom(conversation),
                    handleVerificationEvents = false,
                    strictShields = false,
                    decryptionSettings = DEFAULT_DECRYPTION,
                )
                JSONObject(decrypted.clearEvent).optJSONObject("content")?.optString("body")
            }
                // 鍵が飛んでいる最中は普通に起きるが、本当の故障と理由なしには区別できない。
                // どちらにせよ記録する。
                .onFailure { CryptoProblems.record("decrypt", it) }
                .getOrNull()
        }
    }

    private fun defaultEncryptionSettings() = EncryptionSettings(
        algorithm = EventEncryptionAlgorithm.MEGOLM_V1_AES_SHA2,
        // 更新周期は Matrix の既定値のまま。ここを自分で決めるのは、やらないと決めた
        // 「独自の暗号設計」に当たる。
        rotationPeriod = ROTATION_PERIOD_MS,
        rotationPeriodMsgs = ROTATION_PERIOD_MESSAGES,
        historyVisibility =
            org.matrix.rustcomponents.sdk.crypto.HistoryVisibility.JOINED,
        // 端末確認の画面がまだ無いので、未確認の端末を拒否すると全部拒否になる。
        // 確立できていない信頼を主張せず、いま確認していることだけを正直に書く。
        onlyAllowTrustedDevices = false,
        errorOnVerifiedUserProblem = false,
    )

    companion object {
        /** 在庫を数え直す間隔。5分。減り方はゆっくりで、1通ごとに1つ減るだけ。 */
        private const val COUNT_INTERVAL_MS = 5 * 60_000L

        private const val EVENT_TYPE = "m.room.message"
        private const val EMPTY_JSON = "{}"
        private const val EMPTY_FAILURES = """{"failures":{}}"""
        private val EMPTY_EVENT_ID = JSONObject().put("event_id", "\$local").toString()
        private const val EMPTY_EVENTS = """{"events":[]}"""
        private const val SYNTHETIC_EVENT_ID = "\$rinowa"

        /** 1週間で新しいセッションに。Matrix の既定値。 */
        private const val ROTATION_PERIOD_MS = 604_800_000UL

        /** または100通。早いほう。Matrix の既定値。 */
        private const val ROTATION_PERIOD_MESSAGES = 100UL

        private const val MAX_PUMP_ROUNDS = 8

        /**
         * 部屋の端末一覧を「確認済み」とみなす時間。
         *
         * 間違ったときの代償が「誰かが読めない」なので短く、連投のたびに払わない程度に長く。
         */
        private const val SESSION_FRESH_MS = 30_000L

        private val DEFAULT_DECRYPTION =
            DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)

        /**
         * この端末の暗号ストアを開く（無ければ作る）。
         *
         * device id は最初に1回だけ作って持ち続ける。公開する鍵に焼き込まれる識別子なので、
         * 起動のたびに変えると新しい端末が次々現れるように見え、どれも前の分を読めない。
         * 入れ直したときに新しくなるのは意図どおり（ストアが消えた＝前の秘密鍵はもう無い）。
         *
         * 失敗を null で返さず報告するのは、最初の版が例外を握り潰して
         * 「暗号エンジンを開けませんでした」だけを出したから。ネイティブライブラリが無いのと
         * ファイルが壊れているのとでは対処が別なのに、null では区別できない。
         */
        suspend fun open(
            context: Context,
            transport: CryptoTransport,
            me: UserId,
            onFailure: (String) -> Unit = {},
        ): CryptoEngine? = withContext(Dispatchers.IO) {
            runCatching {
                // アプリの名前が変わったとき、設定ファイルも改名した。**ここだけ
                // 移行を書き忘れていて**、端末 id が新しく作り直された。鍵の保管庫は
                // ディレクトリ名が利用者 id なのでそのまま残り、中身は古い端末の
                // ものだった。結果、保管庫が開かず全部が「まだ開けません」になる。
                val prefs = context.renamedPreferences(PREFS, FORMER_PREFS)
                val store = File(context.filesDir, "crypto/${me.value}").apply { mkdirs() }

                val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: newDeviceId().also {
                    prefs.edit().putString(KEY_DEVICE_ID, it).apply()
                }

                val machine = runCatching {
                    open(me, deviceId, store)
                }.getOrElse { failure ->
                    // **保管庫のほうが正しい。**
                    //
                    // 秘密鍵を持っているのは保管庫で、設定ファイルはただの覚え書き。
                    // 食い違ったとき、覚え書きを信じて作り直すと、この端末が過去に
                    // 受け取ったものを二度と開けなくなる。保管庫が名乗る id を採用して、
                    // 覚え書きのほうを直す。
                    //
                    // 上の移行が入ったので、この道を通るのは移行より前に一度でも
                    // 起動してしまった端末だけ。それでも消さない——同じ壊れ方は、
                    // 設定ファイルを触るたびに作れてしまう。
                    val recovered = deviceIdIn(failure.message)
                        ?: throw failure
                    android.util.Log.w(
                        TAG,
                        "device id が食い違っていた。保管庫の $recovered を採用する",
                    )
                    prefs.edit().putString(KEY_DEVICE_ID, recovered).commit()
                    open(me, recovered, store)
                }
                CryptoEngine(machine, transport, me)
            }.onFailure {
                CryptoProblems.record("open", it)
                onFailure(it::class.java.name + ": " + (it.message ?: "(no message)"))
            }.getOrNull()
        }

        private fun open(me: UserId, deviceId: String, store: File): OlmMachine =
            OlmMachine(
                userId = CryptoIds.matrixUser(me),
                deviceId = deviceId,
                path = store.absolutePath,
                passphrase = null,
            )

        /**
         * 食い違いの知らせから、保管庫が名乗る端末 id を取り出す。
         *
         *     the account in the store doesn't match the account in the constructor:
         *     expected @uid:lowan.local:AAAAAAAAAA, got @uid:lowan.local:BBBBBBBBBB
         *
         * 文面に頼るのは弱い。だが**保管庫は自分の端末 id を問い合わせる口を
         * 持っていない**（開かないと何も答えない）ので、開けなかったときに
         * 分かるのはここだけ。文面が変わったら復旧に失敗するが、そのときも
         * 壊れ方は今と同じで、悪くはならない。
         */
        private fun deviceIdIn(message: String?): String? {
            val text = message ?: return null
            if (!text.contains("doesn't match the account")) return null
            // **欲しいのは expected のほう。** got は今こちらが渡した値で、
            // expected が保管庫の中に入っている本物。最初これを逆に読んで、
            // 渡した値をそのまま採用し直すという、何もしない修復を書いた。
            // 実機のログに prefs=JTTAJOOOVT と got JTTAJOOOVT が並んで気づいた。
            val owner = text.substringAfter("expected ", "").substringBefore(",")
            return owner.trim().substringAfterLast(':')
                .takeIf { it.length == DEVICE_ID_LENGTH && it.all(Char::isLetterOrDigit) }
        }

        private const val PREFS = "rinowa_crypto"

        /** アプリが Echo だった頃のファイル名。[renamedPreferences] を参照。 */
        private const val FORMER_PREFS = "echo_crypto"

        private const val KEY_DEVICE_ID = "deviceId"
        private const val DEVICE_ID_LENGTH = 10
        private const val TAG = "Rinowa/crypto"

        /** 大文字10文字。Matrix の device id の慣例に合わせる。 */
        private fun newDeviceId(): String {
            val random = SecureRandom()
            return (1..DEVICE_ID_LENGTH).map { ('A' + random.nextInt(26)) }.joinToString("")
        }
    }
}
