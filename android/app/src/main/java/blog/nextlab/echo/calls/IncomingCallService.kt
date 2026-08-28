package blog.nextlab.echo.calls

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import blog.nextlab.echo.auth.AuthState
import blog.nextlab.echo.bestEffort
import blog.nextlab.echo.data.RinowaDb
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 端末を鳴らし、鳴らし続けるもの。
 *
 * 通知チャンネルの音は1回しか鳴らない。メッセージにはそれでよく、通話には足りない
 * （1回鳴って黙る端末は「鳴った」ではなく「ピッと言った」）。誰かが対応するまで
 * 鳴らし続けるには生きている何かが要り、Android ではフォアグラウンドサービス。
 *
 * アプリが開いていなくても動く必要もある。着信は push で、そのために起こされたばかりの
 * プロセスに届くので、レシーバから直接鳴らした MediaPlayer はレシーバが戻った時点で
 * 殺されうる。
 *
 * 種別は `mediaPlayback`。実際にやっているのは着信音の再生だから。`phoneCall` のほうが
 * 合っているように見えるが、Android 14 からは Telecom に登録された通話アプリである
 * 必要があり、Rinowa はまだそうではない（docs/CALLS_ARCHITECTURE.md の C-4）。
 * 拒否される種別を名乗るとサービスが立たず、つまり鳴らない。
 *
 * それでも拒否されうるので `startForeground` は保護する。断られたらこのプロセスが
 * 生きている間だけ鳴らす。質は落ちるが無音ではない。API のレベルは端末の能力ではない
 * （ACCESS_LOCAL_NETWORK の教訓はサービス種別にも当てはまる）。
 *
 * 応答・拒否・時間切れで止まる。発信側があきらめたあとも鳴っている端末は、鳴らない
 * 端末より悪い（人が走ってくる）。
 */
class IncomingCallService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val stopper = Handler(Looper.getMainLooper())

    /** 通話が終わったことを見張る購読。[watchUntilAnswered] を参照。 */
    private var callWatch: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 鳴っている最中の通知に、発信者の顔を足す。
     *
     * 顔は push ではなく端末の中から引く。push に載せると、送信側が項目を正しく
     * 埋めているかに着信が依存する。必要なものは全部手元にある（会話に誰がいるか、
     * 相手のドキュメントにどの写真が最新か、ファイルは一覧を描いたときの複製）。
     * Firestore はどちらもキャッシュから答えるので、メインスレッドで数ミリ秒、
     * 通信は待たない。**着信は何も待ってはいけない。**
     *
     * どこかが欠ければ null。写真を設定していない人では普通のことで、その場合は
     * システムの仮アイコンが出る。
     *
     * 同じ id で通知を出し直すとその場で差し替わる（もう一度鳴らず、ボタンも生きたまま）。
     * 見つからなければ、鳴っているものをそのままにする。
     */
    private fun refinePhoto(
        callId: String,
        conversationId: String,
        callerName: String,
        kindLabel: String,
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val face = callerBitmap(conversationId) ?: return@launch
            val withFace = IncomingCallNotifier.build(
                this@IncomingCallService,
                callId,
                conversationId,
                callerName,
                kindLabel,
                android.graphics.drawable.Icon.createWithAdaptiveBitmap(face),
                face,
            )
            runCatching {
                androidx.core.app.NotificationManagerCompat.from(this@IncomingCallService)
                    .notify(IncomingCallNotifier.NOTIFICATION_ID, withFace)
            }
                // すでに出ている着信は、写真を付けられなかったからといって
                // 間違いになるわけではない。
                .onFailure {
                    android.util.Log.w("Rinowa/calls", "could not attach the caller photo", it)
                }
        }
    }

    private suspend fun callerBitmap(conversationId: String): android.graphics.Bitmap? {
        // どこであきらめたかを段ごとに言う。
        //
        // 最初の版は6箇所から null を返して仮アイコンを描いた。見た目は普通の通知で、
        // 探索が失敗したことは誰にも伝わらない。「写真が無い」と「理由があって
        // 出せていない」は別の状態で、動く価値があるのは後者だけ。ここに本文は無い
        // （会話 id とハッシュの先頭は経路情報であってメッセージではない）。
        fun give(reason: String): android.graphics.Bitmap? {
            android.util.Log.i("Rinowa/calls", "caller photo: " + reason)
            return null
        }

        if (conversationId.isEmpty()) return give("no conversation")
        val app = applicationContext as? blog.nextlab.echo.RinowaApplication
            ?: return give("no application")
        val services = app.services ?: return give("no services")
        val me = (services.auth.state.value as? AuthState.SignedIn)
            ?.user?.uid ?: return give("signed out")

        return runCatching {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val conversation = kotlinx.coroutines.withTimeoutOrNull(PHOTO_LOOKUP_MS) {
                db.collection(RinowaDb.Conversations.COLLECTION)
                    .document(conversationId)
                    .get(com.google.firebase.firestore.Source.CACHE)
                    .await()
            } ?: return give("conversation not in cache")

            @Suppress("UNCHECKED_CAST")
            val members = conversation.get(
                RinowaDb.Conversations.MEMBER_IDS,
            ) as? List<String> ?: return give("no members")
            val peer = members.firstOrNull { it != me } ?: return give("no peer")

            val profile = kotlinx.coroutines.withTimeoutOrNull(PHOTO_LOOKUP_MS) {
                db.collection(RinowaDb.Users.COLLECTION)
                    .document(peer)
                    .get(com.google.firebase.firestore.Source.CACHE)
                    .await()
            } ?: return give("peer profile not in cache")

            val hash = profile.getString(RinowaDb.Users.PHOTO_HASH)
                ?.takeIf { it.isNotEmpty() } ?: return give("peer has no photo")

            val file = java.io.File(java.io.File(filesDir, AVATAR_DIR), hash + ".webp")
            if (!file.exists()) return give("photo " + hash.take(8) + " not downloaded")

            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                ?: return give("photo will not decode")
        }
            // swallow-ok: どの枝も行き着く先は「写真なし」で、通知はすでにそう描ける。
            // 写真が見つからなかったせいで着信そのものが鳴らないほうがはるかに悪い。
            .getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val callId = intent?.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID).orEmpty()
        val conversationId = intent?.getStringExtra(IncomingCallNotifier.EXTRA_CONVERSATION_ID).orEmpty()
        val callerName = intent?.getStringExtra(IncomingCallNotifier.EXTRA_CALLER_NAME) ?: "着信"
        val kindLabel = intent?.getStringExtra(IncomingCallNotifier.EXTRA_KIND_LABEL) ?: "着信"

        // 着信通知は写真なしで組み立て、すぐ出す。
        //
        // 先に写真を引くと Firestore を待つことになる。FCM が起こしたばかりの
        // プロセスではローカルストアを開くだけでも、鳴っている端末が許せる時間を超える。
        // 150ms では時間切れになり「会話がキャッシュに無い」と報告された。事実では
        // あるが誤解を招く（キャッシュにはあった、時間が足りなかった）。
        //
        // なので顔なしで先に出し、同じ通知にあとから顔を足す。着信は写真を待たない。
        val callerFace: android.graphics.Bitmap? = null
        val notification = IncomingCallNotifier.build(
            this,
            callId,
            conversationId,
            callerName,
            kindLabel,
            callerFace?.let(android.graphics.drawable.Icon::createWithAdaptiveBitmap),
            callerFace,
        )

        refinePhoto(callId, conversationId, callerName, kindLabel)

        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    IncomingCallNotifier.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(IncomingCallNotifier.NOTIFICATION_ID, notification)
            }
        }.isSuccess

        if (!started) {
            // サービスは立たなくても、通知と音は出せる。種別を断られただけで着信が
            // 丸ごと消えるのが一番悪い。
            IncomingCallNotifier.showWithoutService(this, notification)
        }

        startRinging()
        watchUntilAnswered(callId, conversationId)

        // 保険。相手の端末が圏外に落ちるなどして「終わった」が書かれないことはある。
        // 上の購読が届けば、ここまで待たずに止まる。
        stopper.postDelayed({ stopSelf() }, RING_TIMEOUT_MS)
        return START_NOT_STICKY
    }

    /**
     * 発信側が切ったら鳴りやむ。
     *
     * これが無いと、掛けた人が切ったあとも相手の端末は 30 秒鳴り続けた。切った側の
     * 画面はもう終わっているので、鳴っている人は誰も居ない電話に出ることになる。
     *
     * 見るのは通話ドキュメント1件だけ。ringing でなくなった時点で用済み——終了でも、
     * 別の端末が応答した場合でも同じ。文書が消えていても止める（通話が片付けられた）。
     */
    private fun watchUntilAnswered(callId: String, conversationId: String) {
        if (callId.isEmpty() || conversationId.isEmpty()) return
        callWatch?.remove()
        // swallow-ok: 見張れなくても着信そのものは出す。上の時間切れが受け止める。
        callWatch = runCatching {
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection(RinowaDb.Conversations.COLLECTION)
                .document(conversationId)
                .collection(CALLS)
                .document(callId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) return@addSnapshotListener
                    val ringing = snapshot != null &&
                        snapshot.exists() &&
                        snapshot.getString(FIELD_STATE) == STATE_RINGING
                    if (!ringing) stopSelf()
                }
        }
            .onFailure { android.util.Log.w("Rinowa/calls", "could not watch the call", it) }
            .getOrNull()
    }

    /**
     * 着信音と振動。どちらも繰り返す。
     *
     * 同梱の音ではなく端末の着信音を使う。このアプリからの通話も通話として
     * 分かるべきで、人が通話だと知っている音は端末のもの。
     */
    private fun startRinging() {
        val audio = getSystemService(AUDIO_SERVICE) as? AudioManager

        // マナーモードや消音は事故ではなく判断。その上から鳴らすアプリは会議中に消される。
        val silent = audio?.ringerMode == AudioManager.RINGER_MODE_SILENT
        val vibrateOnly = audio?.ringerMode == AudioManager.RINGER_MODE_VIBRATE

        if (!silent && !vibrateOnly) {
            // 鳴らないまま届いた着信は、届かなかった着信と区別が付かない。
            player = runCatching {
                MediaPlayer().apply {
                    setDataSource(
                        this@IncomingCallService,
                        RingtoneManager.getActualDefaultRingtoneUri(
                            this@IncomingCallService,
                            RingtoneManager.TYPE_RINGTONE,
                        ) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    )
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            }
                .onFailure { android.util.Log.w("Rinowa/calls", "ringtone failed", it) }
                .getOrNull()
        }

        if (!silent) {
            vibrator = resolveVibrator()
            // 添字0から繰り返す。ブッ、休み、ブッ、休み、を鳴っている間ずっと。
            // ポケットの中で感じるのはここ。
            val pattern = longArrayOf(0, 800, 900)
            bestEffort("vibrate") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(pattern, 0)
                }
            }
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }

    override fun onDestroy() {
        callWatch?.remove()
        callWatch = null
        stopper.removeCallbacksAndMessages(null)
        bestEffort("stop ringtone") { player?.stop() }
        bestEffort("release ringtone") { player?.release() }
        player = null
        bestEffort("stop vibration") { vibrator?.cancel() }
        vibrator = null
        IncomingCallNotifier.dismiss(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "blog.nextlab.echo.STOP_RINGING"

        /**
         * 冷えたプロセスがローカルストアを開けるだけの時間。
         *
         * 誰もこれを待たない（着信はもう出ている）。通知を良くする試みをどこで
         * 打ち切るかであって、鳴らす時間の上限ではない。
         */
        private const val PHOTO_LOOKUP_MS = 3_000L

        /**
         * ProfilePhotos がファイルを置く場所。
         *
         * そのクラスを呼ばずに名前だけ書く。あちらは Firestore のハンドルとデコードの
         * キャッシュを持っていて、鳴っている最中にこのサービスが作るようなものではない。
         * 置き場所を変えるときはここが2箇所目。検索で両方に当たるよう名前を書き下す。
         */
        private const val AVATAR_DIR = "avatars"

        /** server/push.php の FCM ttl と同じ。 */
        private const val RING_TIMEOUT_MS = 30_000L

        // CallSignaling と同じ綴り。あちらを private にしたまま名前だけ合わせてある。
        private const val CALLS = "calls"
        private const val FIELD_STATE = "state"
        private const val STATE_RINGING = "ringing"

        fun start(
            context: Context,
            callId: String,
            conversationId: String,
            callerName: String,
            kindLabel: String = "着信",
        ) {
            val intent = Intent(context, IncomingCallService::class.java).apply {
                putExtra(IncomingCallNotifier.EXTRA_CALL_ID, callId)
                putExtra(IncomingCallNotifier.EXTRA_CONVERSATION_ID, conversationId)
                putExtra(IncomingCallNotifier.EXTRA_CALLER_NAME, callerName)
                putExtra(IncomingCallNotifier.EXTRA_KIND_LABEL, kindLabel)
            }
        // ここで失敗すると端末が鳴らない。振動子を解放するのと同じ書き方をしていたが、
        // まったく別のこと。
            bestEffort("start the ringing service") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            bestEffort("stop the ringing service") {
                context.startService(
                    Intent(context, IncomingCallService::class.java).setAction(ACTION_STOP),
                )
            }
            IncomingCallNotifier.dismiss(context)
        }
    }
}
