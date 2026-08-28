package blog.nextlab.echo.notifications

import android.content.Context
import android.os.Build
import blog.nextlab.echo.data.RinowaDb
import blog.nextlab.echo.data.renamedPreferences
import blog.nextlab.echo.core.model.UserId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * この端末の FCM トークンを、サインイン中のアカウントの下に登録する。
 *
 * `users/{uid}/devices/{deviceId}` は、Rinowa Direct が端末の公開鍵に使う予定の
 * コレクションと同じ（docs/DIRECT_ARCHITECTURE.md §5.2）。「このアカウントが使う端末」の
 * 一覧を2つにしない。無くした端末を消したら、通知の受信も信頼済みの立場も同時に消える
 * べきだから。2つあるとずれて、誰も消し忘れたほうが問題になる。
 */
object PushTokenRegistrar {

    private const val PREFS = "rinowa_push"

    /** アプリが Echo だった頃のファイル名。 */
    private const val FORMER_PREFS = "echo_push"

    /**
     * トークンのファイル。古い名前の中身を引き継ぐ。
     *
     * device id はここにある。失っても通知が止まるのではなく、同じ端末の登録が
     * 静かに2つになり、古いほうにも送られ続ける。
     */
    private fun prefs(context: Context) =
        context.renamedPreferences(PREFS, FORMER_PREFS)
    private const val KEY_PENDING_TOKEN = "pending_token"
    private const val KEY_DEVICE_ID = "device_id"

    /**
     * Firebase は好きなときにトークンを渡してくる（誰もサインインしていない時点でも）。
     * 登録先のアカウントができるまでここに置いておく。
     */
    fun rememberPendingToken(context: Context, token: String) {
        prefs(context)
            .edit()
            .putString(KEY_PENDING_TOKEN, token)
            .apply()
    }

    /**
     * このインストールを表す安定した id。
     *
     * ハードウェアから導かず、1回だけ乱数で作って持ち続ける。端末の識別子から作った id は
     * 入れ直しても同じで、他のアプリとも同じになる。まさに Rinowa が作るべきでない、
     * 消えない取っ手。
     */
    private fun deviceId(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    /** サインイン後と、サインイン済みで起動したときに呼ぶ。 */
    suspend fun register(context: Context, db: FirebaseFirestore, owner: UserId): Result<Unit> =
        runCatching {
            // トークンが無いと**通知が1つも届かない**のに、他はどこもおかしく見えない。
            // 次に保存済みのものを試すが、なぜ失敗したかは唯一の手がかり。
            val token = runCatching { FirebaseMessaging.getInstance().token.await() }
                .onFailure { android.util.Log.w("Rinowa/push", "token fetch failed", it) }
                .getOrNull()
                ?: prefs(context).getString(KEY_PENDING_TOKEN, null)
                ?: return@runCatching

            db.collection(RinowaDb.Users.COLLECTION)
                .document(owner.value)
                .collection(RinowaDb.Devices.COLLECTION)
                .document(deviceId(context))
                .set(
                    mapOf(
                        RinowaDb.Devices.FCM_TOKEN to token,
                        RinowaDb.Devices.PLATFORM to "android",
                        // 機種名ではなく分類。docs/ANALYTICS_SCHEMA.md は珍しい機種名が
                        // 個人を特定するとして収集しないと決めていて、端末について
                        // 保存するものにも同じ理由が当てはまる。
                        RinowaDb.Devices.OS_API_LEVEL to Build.VERSION.SDK_INT,
                        RinowaDb.Devices.UPDATED_AT to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                )
                .await()
        }

    /** サインアウト時にこの端末を消し、そのアカウントのメッセージを受け取らないようにする。 */
    suspend fun unregister(context: Context, db: FirebaseFirestore, owner: UserId) {
        runCatching {
            db.collection(RinowaDb.Users.COLLECTION)
                .document(owner.value)
                .collection(RinowaDb.Devices.COLLECTION)
                .document(deviceId(context))
                .delete()
                .await()
        }
    }
}
