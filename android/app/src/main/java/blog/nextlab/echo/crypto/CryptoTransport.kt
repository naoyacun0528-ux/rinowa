package blog.nextlab.echo.crypto

import blog.nextlab.echo.core.model.UserId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * 鍵の郵便。
 *
 * 暗号エンジンは通信手段を持たない状態機械で、「これを送って、返事を教えろ」としか言わない。
 * どこへ送るかを決めるのはこのクラスだけ。暗号処理は一切せず、firestore.rules に
 * 書かれた規則に従う以上の判断もしない。
 *
 * 鍵の束（bundle）は JSON のまま保存してそのまま返す。束には**署名**が付いていて、
 * 署名は正確なバイト列にかかる。Firestore の項目に分解して組み直すと（順序が変わる、
 * 空白が消える）別のバイト列になり、相手には「形式が変」ではなく**署名が検証できない**
 * と見える。それは成りすましと区別が付かない。ここでの Firestore は封筒であって
 * スキーマではない。
 *
 * 返事の形は Matrix のホームサーバーに合わせる。他人の仕様で固定されているのが利点で、
 * ここに設計の余地は無い＝プロトコルを発明する機会も無い。翻訳係が意訳を始めたらバグ。
 */
class CryptoTransport(
    private val db: FirebaseFirestore,
    /**
     * どの受信箱イベントが1回通過済みか。[ToDeviceLedger] を参照。
     *
     * 保存先の無いビルドでは null になり、その場合は何も消さない。少し場所を使うだけで
     * 失うものは無く、この取引ではそちらが正しい向き。
     */
    private val ledger: ToDeviceLedger? = null,
) {

    /** 直前の [drainToDevice] が実際に読んだもの。[clearToDevice] を参照。 */
    private val drained = mutableListOf<com.google.firebase.firestore.DocumentReference>()

    /** [drained] のうち、前の回でも見たもの。消すのはこれだけ。 */
    private var deletable: Set<String> = emptySet()

    private fun cryptoDevices(user: UserId) =
        db.collection(USERS).document(user.value).collection(CRYPTO_DEVICES)

    private fun oneTimeKeys(user: UserId, deviceId: String) =
        cryptoDevices(user).document(deviceId).collection(ONE_TIME_KEYS)

    private fun toDevice(user: UserId) =
        db.collection(USERS).document(user.value).collection(TO_DEVICE)

    // ---------------------------------------------------------------- 鍵の公開

    /**
     * この端末の識別鍵とワンタイム鍵を公開する。
     *
     * エンジンは両方入った1つの塊で渡してくる。ここで分けるのは寿命が違うから
     * （識別鍵は置き換わり、ワンタイム鍵は取った者が1回で消す）。
     *
     * 返すのはエンジンが期待する返事＝未使用の残数。これで作り足す時期を決めている。
     */
    suspend fun uploadKeys(me: UserId, deviceId: String, body: String): String {
        val parsed = JSONObject(body)

        parsed.optJSONObject("device_keys")?.let { deviceKeys ->
            cryptoDevices(me).document(deviceId).set(
                mapOf(
                    FIELD_DEVICE_ID to deviceId,
                    FIELD_JSON to deviceKeys.toString(),
                    FIELD_UPDATED_AT to FieldValue.serverTimestamp(),
                ),
            ).await()
        }

        parsed.optJSONObject("one_time_keys")?.let { keys ->
            for (keyId in keys.keys()) {
                // ドキュメント id を鍵 id にする。同じ鍵を2回公開しても1つのままで、
                // 失敗後の再送で同じ鍵が別名で2つ出回ることもない。
                oneTimeKeys(me, deviceId).document(keyId.sanitisedForFirestore()).set(
                    mapOf(FIELD_JSON to keys.get(keyId).toString()),
                ).await()
            }
        }

        // ここの数を間違えると、エンジンが鍵を作りすぎたり足りなくなったりする。
        // 外からは見えないので、数え損ないは声に出す価値がある。
        val remaining = runCatching { oneTimeKeys(me, deviceId).get().await().size() }
            .onFailure { CryptoProblems.record("uploadKeys/count", it) }
            .getOrDefault(0)
        return JSONObject()
            .put("one_time_key_counts", JSONObject().put(SIGNED_CURVE, remaining))
            .toString()
    }

    /**
     * この端末のワンタイム鍵の在庫。
     *
     * エンジンは**サーバーに何個あるか**を自分では知らない。教えないかぎり、最後に
     * 公開したときの記憶を持ち続ける。0.19.0 で鍵の置き場所を端末ごとに移したとき、
     * 移行前からある端末はこの記憶のせいで新しい場所へ公開し直さず、**在庫0のまま
     * 誰からもセッションを張れない端末**になった。送れるのに、受け取れない。
     *
     * どこにも失敗が出ないのが厄介なところで、症状は相手側の「まだ開けません」だけ。
     */
    suspend fun oneTimeKeyCount(me: UserId, deviceId: String): Int =
        // swallow-ok: 数えられなければ「分からない」。呼び元は分からない回を飛ばす。
        // 適当な数を渡すと、エンジンは要らない鍵を作るか、作るべきときに作らない。
        runCatching { oneTimeKeys(me, deviceId).get().await().size() }
            .onFailure { CryptoProblems.record("oneTimeKeyCount", it) }
            .getOrDefault(-1)

    // ---------------------------------------------------------------- 鍵の問い合わせ

    /** 指定した人たちが公開しているものを、エンジンが期待する形で返す。 */
    suspend fun queryKeys(users: List<String>): String {
        val deviceKeys = JSONObject()
        for (matrixUser in users) {
            val user = CryptoIds.userFromMatrix(matrixUser) ?: continue
            val perDevice = JSONObject()
            // ここで誰かを飛ばすと、その人宛には誰も暗号化できず、メッセージが単に
            // 届かなくなる。黙って起きてはいけない。
            val snapshot = runCatching { cryptoDevices(user).get().await() }
                .onFailure { CryptoProblems.record("queryKeys/$matrixUser", it) }
                .getOrNull() ?: continue
            for (document in snapshot.documents) {
                val json = document.getString(FIELD_JSON) ?: continue
                perDevice.put(document.id, JSONObject(json))
            }
            if (perDevice.length() > 0) deviceKeys.put(matrixUser, perDevice)
        }
        return JSONObject()
            .put("device_keys", deviceKeys)
            // 空でも必ず入れる。エンジンはこのキーを読むので、無いと「失敗ゼロ」と
            // 「項目が無い」が区別できない。
            .put("failures", JSONObject())
            .toString()
    }

    // ---------------------------------------------------------------- 鍵の取得

    /**
     * 端末ごとにワンタイム鍵を1つ取り、取ったものを消す。
     *
     * トランザクションは必須。読んでから消す2段階にすると、2人が同じ鍵を読んでから
     * どちらかが消すことになり、**同じ鍵素材で2つのセッションが始まる**。それは
     * 前方秘匿性が防ぐためにある事故そのもの。
     *
     * 仲裁するサーバーが無いので、原子性は Firestore から借りる。無しでやると、
     * 問題になる日まではずっと動いているように見える。
     */
    suspend fun claimKeys(request: Map<String, Map<String, String>>): String {
        val claimed = JSONObject()

        for ((matrixUser, devices) in request) {
            val user = CryptoIds.userFromMatrix(matrixUser) ?: continue
            val perDevice = JSONObject()

            for (deviceId in devices.keys) {
                // 鍵が取れないとその端末とのセッションは張れない。在庫切れは普通の
                // ことなのでそう報告するが、要求そのものの失敗は別。
                val taken = runCatching { takeOneKey(user, deviceId) }
                    .onFailure { CryptoProblems.record("claimKeys/$matrixUser", it) }
                    .getOrNull() ?: continue
                perDevice.put(deviceId, JSONObject().put(taken.first, JSONObject(taken.second)))
            }
            if (perDevice.length() > 0) claimed.put(matrixUser, perDevice)
        }

        return JSONObject()
            .put("one_time_keys", claimed)
            .put("failures", JSONObject())
            .toString()
    }

    /**
     * 鍵 id と束を返す。**その端末の**在庫が尽きていれば null。
     *
     * 端末を指定するのが要。利用者ごとの1つの束から取っていたときは、2台持ちの
     * 相手に対して別の端末の鍵を渡していた。セッションは張れず、症状は
     * 「ときどき届かない」だった。
     */
    private suspend fun takeOneKey(user: UserId, deviceId: String): Pair<String, String>? {
        val candidates = oneTimeKeys(user, deviceId).limit(CLAIM_CANDIDATES).get().await()
        for (candidate in candidates.documents) {
            val reference = candidate.reference
            val result = runCatching {
                db.runTransaction { transaction ->
                    val fresh = transaction.get(reference)
                    // 一覧を取ってからここまでの間に誰かが取った。エラーではなく、
                    // 競合した鍵の見え方。次の候補を試す。
                    if (!fresh.exists()) return@runTransaction null
                    val json = fresh.getString(FIELD_JSON) ?: return@runTransaction null
                    transaction.delete(reference)
                    json
                }.await()
            }
                // 競合はトランザクション内で null になり、これは正常。例外は別で、
                // その端末とセッションが張れない＝メッセージが届かないということ。
                .onFailure { CryptoProblems.record("claimKeys/transaction", it) }
                .getOrNull()
            if (result != null) return candidate.id.restoredFromFirestore() to result
        }
        return null
    }

    // ---------------------------------------------------------------- to-device

    /**
     * エンジンが封をした封筒を、相手の受信箱に置く。
     *
     * 本文は複数人宛でまとめて来るが Firestore にその概念は無いので、1通ずつ配る。
     * ここにあるコードは、運んでいるものを読めない。
     */
    suspend fun sendToDevice(me: UserId, eventType: String, body: String): Int {
        val parsed = JSONObject(body)
        // 形が2通りあり、こちらが想定していたのは片方だけだった。
        //
        // Matrix の /sendToDevice は {"messages": {user: {device: content}}} を取り、
        // それを前提に解いていた。FFI は内側の map だけを渡してくる。読み違えると
        // 宛先0件・エラー無しで、部屋の鍵が誰にも届かない。いまは両方を受け、
        // どちらだったかを推測ではなくログに残す。
        val messages = parsed.optJSONObject("messages") ?: parsed
        android.util.Log.i(
            "Rinowa/crypto",
            "toDevice wrapped=" + parsed.has("messages") + " to=" + messages.keys().asSequence().joinToString(","),
        )
        var sent = 0

        for (matrixUser in messages.keys()) {
            val user = CryptoIds.userFromMatrix(matrixUser) ?: continue
            val perDevice = messages.optJSONObject(matrixUser) ?: continue
            for (deviceId in perDevice.keys()) {
                val payload = perDevice.get(deviceId).toString()
                runCatching {
                    toDevice(user).add(
                        mapOf(
                            FIELD_SENDER_ID to me.value,
                            FIELD_EVENT_TYPE to eventType,
                            FIELD_BODY to payload,
                            FIELD_CREATED_AT to FieldValue.serverTimestamp(),
                        ),
                    ).await()
                }
                    // 部屋の鍵の投函に失敗すると、相手はそのメッセージを永久に開けない。
                    // それ以外はどこもおかしく見えない。
                    .onFailure { CryptoProblems.record("sendToDevice/" + matrixUser, it) }
                    .onSuccess { reference ->
                        sent++
                        // サーバーから読み直す。
                        //
                        // 「書き込みは成功した」と「受信箱が空に見える」は、こちら側から
                        // 区別できなかった。その曖昧さがこの機能をずっと止めていた。
                        // 送信者は自分が書いたものだけ読める（firestore.rules）。
                        val present = runCatching {
                            reference.get(com.google.firebase.firestore.Source.SERVER)
                                .await().exists()
                        }
                            .onFailure {
                                CryptoProblems.record("sendToDevice/readback", it)
                            }
                            .getOrDefault(false)
                        android.util.Log.i(
                            "Rinowa/crypto",
                            "toDevice readback id=" + reference.id + " present=" + present,
                        )
                    }
            }
        }
        return sent
    }

    /**
     * この端末の受信箱を集めて空にする。
     *
     * 読んだ**あと**に消す。エンジンが取り込む前に消えたメッセージは、どこにも存在
     * しない部屋の鍵になり、それで開くはずだったメッセージは永久に読めない。
     * 同じものを2回読むのは取り返せるが、1回失うのは取り返せない。
     */
    suspend fun drainToDevice(me: UserId): String {
        val snapshot = runCatching {
            // 明示的にサーバーから。このコレクションを一度も見ていないローカル
            // キャッシュは「空」と答え、それは「鍵が送られていない」と同じに見える。
            toDevice(me).limit(DRAIN_LIMIT)
                .get(com.google.firebase.firestore.Source.SERVER).await()
        }
            .onFailure { CryptoProblems.record("drainToDevice", it) }
            .getOrNull()
            ?: return EMPTY_EVENTS

        android.util.Log.i("Rinowa/crypto", "mailbox docs=" + snapshot.size())
        val events = org.json.JSONArray()
        // 読んだものだけ消すために覚えておく。問い合わせ直して消すと、その間に届いて
        // まだ取り込んでいないイベントまで消えることがある。
        drained.clear()
        val present = mutableListOf<String>()
        for (document in snapshot.documents) {
            drained += document.reference
            present += document.id
            val sender = document.getString(FIELD_SENDER_ID) ?: continue
            val body = document.getString(FIELD_BODY) ?: continue
            val type = document.getString(FIELD_EVENT_TYPE) ?: continue
            events.put(
                JSONObject()
                    .put("sender", CryptoIds.matrixUser(UserId(sender)))
                    .put("type", type)
                    .put("content", JSONObject(body)),
            )
        }
        // エンジンが取り込んだあと、どれを消してよいか。
        //
        // 初めて見たイベントは、その後どうなろうと残す。取り込みと削除の間に
        // プロセスが死ぬと部屋の鍵ごと消え、開くはずだったメッセージが永久に読めなくなった。
        deletable = ledger?.sift(present)?.deletable ?: emptySet()
        if (present.isNotEmpty()) {
            android.util.Log.i(
                "Rinowa/crypto",
                "mailbox held=" + (present.size - deletable.size) + " deletable=" + deletable.size,
            )
        }

        return JSONObject().put("events", events).toString()
    }

    /**
     * 直前の drain が読んだもののうち、**前の回にも見ていたもの**だけを消す。
     *
     * ここで避けている失敗は2つあり、どちらもメッセージを永久に失う。
     *
     * 最初の版は問い合わせ直して見つかったものを消したので、drain と clear の間に
     * 届いたものが取り込まれずに消えた。次の版は取り込んだものだけを消したが、
     * その間にプロセスが消えると同じことになり（ネイティブのクラッシュで実際に起きた）、
     * 結果はどちらも「どこにも無い部屋の鍵」と「二度と開けないメッセージ」。
     *
     * なので2回見るまで消さない。[ToDeviceLedger] を参照。
     */
    suspend fun clearToDevice(me: UserId) {
        val removable = deletable
        val taken = drained.toList().filter { removable.contains(it.id) }
        drained.clear()
        deletable = emptySet()
        for (reference in taken) {
            // swallow-ok: エンジンはもうこのイベントを持っている。削除が失敗しても
            // 残るだけで、エンジンは無視する。失うよりはるかによい。
            runCatching { reference.delete().await() }
        }
    }

    companion object {
        const val USERS = "users"
        const val CRYPTO_DEVICES = "cryptoDevices"
        const val ONE_TIME_KEYS = "oneTimeKeys"
        const val TO_DEVICE = "toDevice"

        const val FIELD_DEVICE_ID = "deviceId"
        const val FIELD_JSON = "json"
        const val FIELD_UPDATED_AT = "updatedAt"
        const val FIELD_SENDER_ID = "senderId"
        const val FIELD_EVENT_TYPE = "eventType"
        const val FIELD_BODY = "body"
        const val FIELD_CREATED_AT = "createdAt"

        const val SIGNED_CURVE = "signed_curve25519"
        const val EMPTY_EVENTS = """{"events":[]}"""

        /** 1つでは足りない。競合した鍵で試行が終わらないように。 */
        const val CLAIM_CANDIDATES = 5L

        /** 1回分。受信箱は次の回でまた空にする。 */
        const val DRAIN_LIMIT = 100L
    }
}

/**
 * 鍵 id には `/` が入るが、Firestore のドキュメント id では使えない。
 *
 * base64 に現れない文字と入れ替える。可逆で、別々の鍵 id が同じドキュメントに
 * 衝突することもない。
 */
private fun String.sanitisedForFirestore(): String = replace('/', '~')

private fun String.restoredFromFirestore(): String = replace('~', '/')
