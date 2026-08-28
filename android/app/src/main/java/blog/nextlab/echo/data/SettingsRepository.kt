package blog.nextlab.echo.data

import android.content.Context
import blog.nextlab.echo.core.haptics.HapticIntensity
import blog.nextlab.echo.core.haptics.HapticPreferences
import blog.nextlab.echo.model.UserId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * 端末ではなくアカウントに付いてくる設定。
 *
 * docs/SYNC_AND_BACKUP.md は触覚の設定をクラウド同期の対象に入れている。時間をかけて
 * 合わせた強さはその人にとってのアプリの手触りの一部で、機種変更で失うのは、
 * 手触りを売りにしている製品では本当の損失。
 *
 * 手元とサーバーの両方に書く。起動時に読むのは手元の複製（最初の振動を正しくするために
 * 通信の往復を待つのは、最初の振動を間違えるということ）。サーバー側は新しい端末が
 * 復元するためのもの。
 */
class SettingsRepository(
    context: Context,
    private val db: FirebaseFirestore?,
) {

    private val prefs = context.renamedPreferences(FILE, FORMER_FILE)

    fun localHaptics(): HapticPreferences = HapticPreferences(
        enabled = prefs.getBoolean(KEY_HAPTICS_ENABLED, true),
        // swallow-ok: このビルドが知らない値は、古いか新しい版が書いた強度の名前。
        // Normal に落とすのが意図した動作であって失敗ではない。
        intensity = runCatching {
            HapticIntensity.valueOf(
                prefs.getString(KEY_HAPTIC_INTENSITY, HapticIntensity.Normal.name)!!,
            )
        }
            // swallow-ok: 知らない名前は古いか新しい版が書いたもの。Normal は
            // 文書化された既定値であって、読み取りの失敗ではない。
            .getOrDefault(HapticIntensity.Normal),
    )

    fun localOptedOut(): Boolean = prefs.getBoolean(KEY_ANALYTICS_OPTED_OUT, false)

    /**
     * 通知に本文を出すか。既定は true。
     *
     * どのメッセンジャーもそうしていて、人もそう期待するから true。「メッセージが
     * 届きました」だけの通知は、意図的だと知るまで壊れて見える。代償は勝手に決めず
     * プライバシー画面に書く（入れている間、文面は Firebase Cloud Messaging を通る）。
     */
    fun localNotificationShowsBody(): Boolean =
        prefs.getBoolean(KEY_NOTIFICATION_SHOWS_BODY, true)

    /**
     * 写真を撮ったままの大きさでも送るか。
     *
     * 既定は off で、勝手に決めず尋ねる。オリジナルは数百KBに対して3〜15MB。
     * 入れると送信者の通信量を使い、共有の保管庫を埋める。それでもあるのは、
     * 受け取る側が本物のファイルを本当に欲しがることがあるから。
     * **費用を払うのは、入れた本人。**
     *
     * 触覚と違って端末内だけ。この端末の送り方の話なので、アカウントへ送ると
     * 1台の判断が別の1台を変えてしまう。
     */
    fun localSendsOriginals(): Boolean = prefs.getBoolean(KEY_SENDS_ORIGINALS, false)

    fun putLocal(
        haptics: HapticPreferences? = null,
        optedOut: Boolean? = null,
        notificationShowsBody: Boolean? = null,
        sendsOriginals: Boolean? = null,
    ) {
        prefs.edit().apply {
            haptics?.let {
                putBoolean(KEY_HAPTICS_ENABLED, it.enabled)
                putString(KEY_HAPTIC_INTENSITY, it.intensity.name)
            }
            optedOut?.let { putBoolean(KEY_ANALYTICS_OPTED_OUT, it) }
            notificationShowsBody?.let { putBoolean(KEY_NOTIFICATION_SHOWS_BODY, it) }
            sendsOriginals?.let { putBoolean(KEY_SENDS_ORIGINALS, it) }
        }.apply()
    }

    private fun document(owner: UserId) = db
        ?.collection(RinowaDb.Users.COLLECTION)
        ?.document(owner.value)
        ?.collection(RinowaDb.Users.SETTINGS)
        ?.document(RinowaDb.Users.SETTINGS_DOC)

    suspend fun push(owner: UserId): Result<Unit> = runCatching {
        val target = document(owner) ?: return@runCatching
        val haptics = localHaptics()
        target.set(
            mapOf(
                RinowaDb.Settings.HAPTICS_ENABLED to haptics.enabled,
                RinowaDb.Settings.HAPTIC_INTENSITY to haptics.intensity.name,
                RinowaDb.Settings.ANALYTICS_OPTED_OUT to localOptedOut(),
                // このアカウントについて push サーバーが読むので、他に変更が無くても
                // 送る必要がある。でないと、出さない設定にした人にサーバーが本文を出し続ける。
                RinowaDb.Settings.NOTIFICATION_SHOWS_BODY to localNotificationShowsBody(),
                RinowaDb.Settings.UPDATED_AT to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    /**
     * 設定をこの端末へ戻す。
     *
     * @return 何か戻したら true。false は、このアカウントが一度も設定を保存して
     *   いないという意味で、その場合は手元の既定値を残す（「設定が無い」で上書きしない）。
     */
    suspend fun pull(owner: UserId): Boolean = runCatching {
        val snapshot = document(owner)?.get()?.await() ?: return@runCatching false
        if (!snapshot.exists()) return@runCatching false

        val enabled = snapshot.getBoolean(RinowaDb.Settings.HAPTICS_ENABLED) ?: true
        val intensity = runCatching {
            HapticIntensity.valueOf(
                snapshot.getString(RinowaDb.Settings.HAPTIC_INTENSITY) ?: HapticIntensity.Normal.name,
            )
        }
            // swallow-ok: 知らない名前は古いか新しい版が書いたもの。Normal は
            // 文書化された既定値であって、読み取りの失敗ではない。
            .getOrDefault(HapticIntensity.Normal)

        putLocal(
            haptics = HapticPreferences(enabled = enabled, intensity = intensity),
            optedOut = snapshot.getBoolean(RinowaDb.Settings.ANALYTICS_OPTED_OUT) ?: false,
            notificationShowsBody =
                snapshot.getBoolean(RinowaDb.Settings.NOTIFICATION_SHOWS_BODY) ?: true,
        )
        true
    }
        // 黙って同期に失敗すると、合わせた触覚が新しい端末で静かに元に戻り、
        // 画面には理由が出ない。
        .onFailure { android.util.Log.w("Rinowa/settings", "pull failed", it) }
        .getOrDefault(false)

    private companion object {
        const val FILE = "rinowa_settings"

        /** アプリが Echo だった頃のファイル名。[renamedPreferences] を参照。 */
        const val FORMER_FILE = "echo_settings"
        const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        const val KEY_HAPTIC_INTENSITY = "haptic_intensity"
        const val KEY_ANALYTICS_OPTED_OUT = "analytics_opted_out"
        const val KEY_NOTIFICATION_SHOWS_BODY = "notification_shows_body"
        const val KEY_SENDS_ORIGINALS = "sends_originals"
    }
}
