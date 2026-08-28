package blog.nextlab.echo.calls

import blog.nextlab.echo.bestEffort
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import blog.nextlab.echo.MainActivity
import blog.nextlab.echo.R
import blog.nextlab.echo.notifications.RinowaMessagingService

/**
 * アプリが画面に無い間も通話を生かし、そう言う。
 *
 * フォアグラウンドサービスが無いと、アプリを離れた時点でプロセスは Android が
 * いつ止めてもよい場所に置かれる。しかもこのプロセスはマイクを握っている。
 * つまり選ぶのは「背面でも通話を続けるべきか」ではなく「他のものを見た瞬間に
 * 通話が切れるかどうか」で、電話と呼べるのは片方だけ。
 *
 * 通知は正直な部分。見えないアプリがマイクを握っているのは、人が疑って当然のもの。
 * この通知がそれを見えるようにする。プラットフォームに強いられた飾りではなく、
 * 「Rinowa が聞いています」という表明で、止めるボタンも付いている。
 * `CallStyle.forOngoingCall` は標準の電話アプリと同じ扱いで、経過時間も出る。
 * それは通話がまだ生きている一番安い証拠でもある。
 *
 * 種別は `microphone`、ビデオ通話ならさらに `camera`。実際に握っているものがそれ。
 * Android 14 は宣言した種別と実態が合わないフォアグラウンドサービスを拒否し、
 * 拒否されたサービスは背面で死ぬ通話になる。このクラスが防ぎたいのはまさにそれなので、
 * 拒否は握り潰さず記録して対処する。
 */
class OngoingCallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "通話中"
        val conversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, false) == true
        val startedAt = intent?.getLongExtra(EXTRA_STARTED_AT, System.currentTimeMillis())
            ?: System.currentTimeMillis()

        ensureChannel(this)
        val notification = build(this, peerName, conversationId, isVideo, startedAt)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (isVideo) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            android.util.Log.w("Rinowa", "ongoing call service refused: " + it.message)
            stopSelf()
        }

        return START_STICKY
    }

    companion object {
        const val NOTIFICATION_ID = 0x0EC1
        const val CHANNEL_ONGOING = "ongoing_call"

        const val ACTION_STOP = "blog.nextlab.echo.STOP_ONGOING_CALL"
        const val ACTION_HANG_UP = "blog.nextlab.echo.HANG_UP"

        const val EXTRA_PEER_NAME = "peerName"
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_IS_VIDEO = "isVideo"
        const val EXTRA_STARTED_AT = "startedAt"

        fun start(
            context: Context,
            peerName: String,
            conversationId: String,
            isVideo: Boolean,
            startedAt: Long,
        ) {
            val intent = Intent(context, OngoingCallService::class.java).apply {
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_IS_VIDEO, isVideo)
                putExtra(EXTRA_STARTED_AT, startedAt)
            }
            // この通知が無いと、耳に当てている最中の通話をプラットフォームが
            // 止めることがある。だから起動の失敗は書き留める価値がある。
            bestEffort("start the ongoing-call service") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            bestEffort("stop the ongoing-call service") {
                context.startService(
                    Intent(context, OngoingCallService::class.java).setAction(ACTION_STOP),
                )
            }
        }

        /**
         * 専用のチャンネルで、重要度は低。
         *
         * 通話中の表示は見えている必要があり、音を出してはいけない（音は通話そのもの）。
         * 着信のチャンネルと共有すると、通話に出るたびに端末が鳴る。
         */
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ONGOING) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "通話中", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "通話中であることの表示"
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    },
            )
        }

        private fun build(
            context: Context,
            peerName: String,
            conversationId: String,
            isVideo: Boolean,
            startedAt: Long,
        ): Notification {
            val open = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(RinowaMessagingService.EXTRA_CONVERSATION_ID, conversationId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val hangUp = PendingIntent.getActivity(
                context,
                2,
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION_HANG_UP
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(RinowaMessagingService.EXTRA_CONVERSATION_ID, conversationId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val peer = Person.Builder().setName(peerName).setImportant(true).build()
                Notification.Builder(context, CHANNEL_ONGOING)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setStyle(Notification.CallStyle.forOngoingCall(peer, hangUp))
                    .setContentIntent(open)
                    .setUsesChronometer(true)
                    .setWhen(startedAt)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .build()
            } else {
                NotificationCompat.Builder(context, CHANNEL_ONGOING)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(peerName)
                    .setContentText(if (isVideo) "ビデオ通話中" else "音声通話中")
                    .setContentIntent(open)
                    .setUsesChronometer(true)
                    .setWhen(startedAt)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .addAction(0, "終了", hangUp)
                    .build()
            }
        }
    }
}
