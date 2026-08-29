package blog.nextlab.echo.calls

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import blog.nextlab.echo.R
import blog.nextlab.echo.bestEffort

/**
 * 端末を、電話らしく鳴らす。
 *
 * メッセンジャーの通話は、「通話」と書いたメッセージではなく電話アプリのように
 * 振る舞う必要がある。具体的には、ロック画面の上に出て、通知ではなく着信音の
 * チャンネルを使い、応答と拒否が親指の位置にあり、対応した瞬間に止まる。
 *
 * Android 側で要るのは2つ:
 *
 * 1. **全画面インテント。** 通知を画面に変えるもの。`USE_FULL_SCREEN_INTENT` が要り、
 *    Android 14 からは通話やアラームらしいアプリにだけ自動で与えられ、**断られうる**。
 *    断られてもヘッドアップの帯として同じ2つのボタン付きで出る（質は落ちるが壊れてはいない）。
 *    仮定せず実行時に確認する。理由は他と同じで、**API のレベルは端末の能力ではない**。
 *
 * 2. **`Notification.CallStyle`**（Android 12+）。標準の電話アプリと他の通話アプリが
 *    使っている見た目で、これを使うから通話らしく見える。12未満では2つの操作が付いた
 *    普通の通知になる（そのバージョンで出せるのはそれ）。
 */
object IncomingCallNotifier {

    const val CHANNEL_CALLS = "calls"
    const val NOTIFICATION_ID = 0x0EC0

    const val ACTION_ANSWER = "blog.nextlab.echo.ANSWER_CALL"
    const val ACTION_DECLINE = "blog.nextlab.echo.DECLINE_CALL"

    const val EXTRA_CALL_ID = "callId"
    const val EXTRA_CONVERSATION_ID = "conversationId"
    const val EXTRA_CALLER_NAME = "callerName"
    const val EXTRA_KIND_LABEL = "kindLabel"

    private const val TAG = "Rinowa/calls"

    /**
     * 着信音のチャンネル。
     *
     * メッセージとはわざと分け、端末の着信音と `USAGE_NOTIFICATION_RINGTONE` を使う。
     * 同じチャンネルにすると、メッセージ通知を黙らせた人は電話も黙らせたことになり、
     * 誰かが連絡できなくなるまでそれに気付かない。
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_CALLS) != null) return

        val channel = NotificationChannel(
            CHANNEL_CALLS,
            "通話",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "着信"
            // チャンネル側では音も振動も出さない。IncomingCallService が着信音を繰り返し
            // 鳴らし、繰り返し振動させる。チャンネルが自分の1回きりの版を重ねると、
            // 少しずれた着信音が2つ鳴る。
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 通知そのもの。着信サービスがフォアグラウンド通知として使う。 */
    fun build(
        context: Context,
        callId: String,
        conversationId: String,
        callerName: String,
        kindLabel: String = "着信",
        /**
         * 発信者の写真。端末がすでに持っていれば。
         *
         * 無いとシステムは名前の頭文字を丸に入れて描く（知らない番号と同じ仮アイコン）。
         * ロック画面で鳴っている端末では、顔が「誰から」の答えのほとんどで、しかも
         * アプリはすでにその画像を持っている（会話一覧に出ている）。
         *
         * まだ取得していなければ null。写真を設定していない人では普通のことで、
         * その場合は仮アイコンが正しい。
         */
        callerPhoto: android.graphics.drawable.Icon? = null,
        /** 同じ画像を、ビットマップを取る S 未満のビルダー用に。 */
        callerPhotoBitmap: android.graphics.Bitmap? = null,
    ): Notification {
        ensureChannel(context)

        val fullScreen = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            Intent(context, IncomingCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_KIND_LABEL, kindLabel)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val answer = actionIntent(context, ACTION_ANSWER, callId, conversationId, callerName)
        val decline = actionIntent(context, ACTION_DECLINE, callId, conversationId, callerName)

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val caller = Person.Builder()
                .setName(callerName)
                .setIcon(callerPhoto)
                .setImportant(true)
                .build()
            Notification.Builder(context, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_notification)
                .setStyle(
                    Notification.CallStyle.forIncomingCall(caller, decline, answer)
                        .setVerificationText(kindLabel),
                )
                .setFullScreenIntent(fullScreen, true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(callerName)
                .setContentText(kindLabel)
                .setLargeIcon(callerPhotoBitmap)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreen, true)
                .setOngoing(true)
                .addAction(0, "拒否", decline)
                .addAction(0, "応答", answer)
                .build()
        }

        return notification
    }

    /** フォアグラウンドサービスを断られたときだけ。IncomingCallService を参照。 */
    fun showWithoutService(context: Context, notification: Notification) {
        // サービスを断られたときは、これ自体が着信音になる。失敗すると端末は
        // まったく鳴らないので、黙って終わってはいけない。
        if (denied(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        bestEffort("post the ring") {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 通知が断られているか。
     *
     * Android 13 から通知は許可制になった。**断られていても notify は例外を投げない。**
     * 何も起きないだけで、runCatching では捕まらない。着信では、それが
     * 「電話が鳴らないのに誰も気付かない」という形で出る。
     *
     * 鳴らないのは同じだが、**理由がログに残る**。「アプリが壊れている」と
     * 「通知を切っている」を、あとから区別できるようにする。
     *
     * 呼ぶ側にも同じ確認が並んでいるのは重複ではない。**静的解析は関数をまたいで
     * ガードを追えない**ので、notify を書いた場所そのものに確認が要る。
     * こちらは理由を残すため、あちらは検査に見せるため。
     */
    fun denied(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            android.util.Log.w(TAG, "通知が許可されていないので着信を出せない")
        }
        return !granted
    }

    fun dismiss(context: Context) {
        bestEffort("dismiss the ring") {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    /**
     * この端末が本当に通知を画面に変えるか。
     *
     * Android 14 からは方針として通話・アラームアプリに与えられ、取り上げることもできる。
     * 仮定せず尋ねるのは、Rinowa Direct で `ACCESS_LOCAL_NETWORK` から学んだのと同じこと。
     * SDK のレベルは API の話で、この端末が何をするかの話ではない。
     */
    fun canShowFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    /** 画面がロックされているか。キーガードの上に出すかどうかを決める。 */
    fun isLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun actionIntent(
        context: Context,
        action: String,
        callId: String,
        conversationId: String,
        callerName: String,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        (action + callId).hashCode(),
        Intent(context, IncomingCallActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_CALLER_NAME, callerName)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
