package blog.nextlab.echo.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import blog.nextlab.echo.MainActivity
import blog.nextlab.echo.R
import blog.nextlab.echo.auth.AuthState
import blog.nextlab.echo.calls.IncomingCallService
import blog.nextlab.echo.model.ConversationId
import blog.nextlab.echo.model.UserId
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch

/**
 * push を受けて画面に出す。
 *
 * サーバーが送るのは `data` の payload だけで、`notification` は送らない。
 * `notification` にすると、アプリが背面にいる間は Android が自分で通知を描く。
 * 便利そうに見えて判断を奪う（本文をロック画面に出すかどうかという受け取る側の設定が、
 * サーバー側で適用されるか、まったく効かないかになる）。ここで組み立てれば、
 * その選択は表示する端末で守られる。
 *
 * push が届いた時点でアプリが動いているという意味でもある。だから本文を復号してから
 * 表示できる。`notification` payload では絶対にできない（本文が読める形で届く必要がある）。
 */
class RinowaMessagingService : FirebaseMessagingService() {

    /**
     * 新しいトークン。初回か、Firebase が入れ替えたとき。
     *
     * 手元に保存し、誰かがサインインしたら [PushTokenRegistrar] が送る。
     * このコールバックはサインイン前にも来るし、誰の下にも無いトークンは誰にも届かない。
     */
    override fun onNewToken(token: String) {
        PushTokenRegistrar.rememberPendingToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        // 受信を記録するのは、記録が無いこと自体が通知の問いの多くに対する答えだから。
        // 「何も出なかった」は「push が届かなかった」と「届いたが何も出なかった」に
        // 分かれ、ここで直せるのは片方だけ。種類と会話は経路情報で、本文は書かない。
        android.util.Log.i("Rinowa/push", "received type=" + data["type"].orEmpty())
        val conversationId = data["conversationId"] ?: return

        // 通話はメッセージではないので、メッセージとして出さない。ロック画面の上で、
        // 応答と拒否を付けて鳴らす（IncomingCallNotifier を参照）。
        if (data["type"] == "call") {
            val callId = data["callId"].orEmpty()
            if (callId.isNotEmpty()) {
                // 「ビデオ通話」か「音声通話」（RinowaApp を参照）。カメラが入ることを
                // 知らないままビデオ通話に出ないように出す。
                val kindLabel = data["body"]?.takeIf { it.isNotBlank() } ?: "着信"
                IncomingCallService.start(
                    context = applicationContext,
                    callId = callId,
                    conversationId = conversationId,
                    callerName = data["senderName"]?.takeIf { it.isNotBlank() }
                        ?: data["title"].orEmpty().ifEmpty { "着信" },
                    kindLabel = kindLabel,
                )
            }
            return
        }

        // すでに読んでいる会話。届くのを見ていたメッセージについて知らせるのは雑音で、
        // 目を向けている当人の手の中の端末を震わせることになる。ActiveConversation を参照。
        if (ActiveConversation.isOpen(conversationId)) return

        val title = data["title"].orEmpty().ifEmpty { getString(R.string.app_name) }
        val pushed = data["body"].orEmpty()

        // 本文を push から読まず取りに行く理由。
        //
        // **push には意図的に本文が入っていない**（ChatViewModel を参照）。自前の PHP と
        // Google を通るので、載せると、アプリを閉じている間に届くメッセージ（＝ほとんど）の
        // 平文がその経路を通ることになる。
        //
        // なので Signal と同じ形にする。push は肩を叩くだけで、**この端末がメッセージを
        // 取りに行き、自分の鍵で開く**。本文はロック画面まで届くが、間の誰にも届かない。
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val opened = openLatest(conversationId)
            show(
                applicationContext,
                conversationId,
                title,
                opened ?: pushed.ifEmpty { "新しいメッセージ" },
            )
        }
    }

    /** 端末が読めなければ null。呼び出し側は本文なしで通知を出す。 */
    private suspend fun openLatest(conversationId: String): String? {
        val app = applicationContext as? blog.nextlab.echo.RinowaApplication ?: return null
        val services = app.services ?: return null
        val me = (services.auth.state.value as? AuthState.SignedIn)
            ?.user?.uid ?: return null

        return services.messages.newestBody(
            ConversationId(conversationId),
            UserId(me),
        )
    }

    companion object {

        const val CHANNEL_MESSAGES = "messages"
        const val EXTRA_CONVERSATION_ID = "conversationId"

        /** Rinowa のアクセント色。ステータスバーの印がシステム色ではなく Rinowa の色になる。 */
        private const val NOTIFICATION_TINT = 0xFFD2560F.toInt()

        /**
         * 最初の通知を待たず、先に作る。
         *
         * メッセージが届くまで存在しないチャンネルは、Android の設定であらかじめ
         * 調整できない。静かにしたい人は、一度音が鳴ったあとでしかそう言えなくなる。
         */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_MESSAGES) != null) return

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES,
                    "メッセージ",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "友達から届いたメッセージ"
                    enableVibration(true)
                },
            )
        }

        /**
         * その会話の通知を消す。
         *
         * 会話を開いている＝もう読んでいるので、通知が残っていると「まだ読んでいない何かが
         * ある」と言い続けることになる。開いた時点と、開いたまま次が届いた時点で呼ぶ。
         */
        fun dismiss(context: Context, conversationId: String) {
            // swallow-ok: 消えなかった通知は残るだけで、消し方を報告しても誰も直せない。
            runCatching {
                NotificationManagerCompat.from(context).cancel(conversationId.hashCode())
            }
        }

        fun show(context: Context, conversationId: String, title: String, body: String) {
            ensureChannel(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            val pending = PendingIntent.getActivity(
                context,
                conversationId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                // ランチャーのアイコンではなく、この用途に描いた白抜きの印。
                // Android は小さいアイコンのアルファしか使わないので、不透明なアプリ
                // アイコンはのっぺりした塊になる。res/drawable/ic_notification.xml を参照。
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(NOTIFICATION_TINT)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()

            // 1会話につき1つ。同じ人からの5通は5行に積まず、置き換わる。
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(conversationId.hashCode(), notification)
            }
        }
    }
}
