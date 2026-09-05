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
 *
 * 一度に鳴らすのは1件だけ。鳴っている間に別の通話が届いても、先に鳴っているほうを
 * 続ける（[ringingCallId] と [onStartCommand] を参照）。出せる場所が1つしかない以上、
 * どちらかは落ちる。落とす側を選べるなら、まだ誰も見ていないほうを落とす。
 */
class IncomingCallService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val stopper = Handler(Looper.getMainLooper())

    /** 通話が終わったことを見張る購読。[watchUntilAnswered] を参照。 */
    private var callWatch: com.google.firebase.firestore.ListenerRegistration? = null

    /**
     * いま鳴らしている通話。鳴っていなければ null。
     *
     * サービスは鳴っている間ずっと生きているので、その最中に `start()` がもう一度来る。
     * 同じ通話がもう一度届いたのか（FCM の再配信、発信側の二重 push）、別の人からの
     * 2件目なのかは、この id でしか見分けが付かない。扱いが正反対なので見分ける。
     *
     * 写真を引く側は別スレッドから読むので volatile。
     */
    @Volatile
    private var ringingCallId: String? = null

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
            // 通知が許可されていなければ、写真を付け直しても出ない。
            // 出ない理由は canPost がログに残す。
            if (IncomingCallNotifier.denied(this@IncomingCallService)) return@launch
            // 同じ確認をここにも書く。静的解析は関数をまたいでガードを追えないので、
            // notify を書いた場所そのものに無いと「無防備な呼び出し」に見える。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this@IncomingCallService,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return@launch
            }

            // 写真を引いている間に、鳴っている通話が入れ替わっていることがある（断った
            // 直後に次の着信が来ると、前のサービスが片付く前に次が始まる）。同じ通知 id を
            // 使うので、古いほうを出し直すと名前も応答ボタンの行き先も前の通話に戻る。
            // **鳴っている人と、応答が繋がる先が食い違う**のが一番悪い。
            if (ringingCallId != callId) return@launch

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
            // stopSelf() は「もう用は無い」と言うだけで、onDestroy がすぐ来るとは限らない。
            // その隙間に次の着信が届くと停止が取り消され、断ったはずの通話の音と見張りが
            // そのまま残る。頼まれた時点で鳴りやませる。
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        val callId = intent?.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID).orEmpty()
        val conversationId = intent?.getStringExtra(IncomingCallNotifier.EXTRA_CONVERSATION_ID).orEmpty()
        val callerName = intent?.getStringExtra(IncomingCallNotifier.EXTRA_CALLER_NAME) ?: "着信"
        val kindLabel = intent?.getStringExtra(IncomingCallNotifier.EXTRA_KIND_LABEL) ?: "着信"

        val ringing = ringingCallId
        if (!ringing.isNullOrEmpty()) {
            if (ringing == callId) {
                // 同じ通話がもう一度届いただけ。すでに鳴っているので何もしない。
                // ここから鳴らし直すと着信音が頭に戻り（誰も押していないのに切り替わる）、
                // 時間切れの30秒も数え直しになる。
                android.util.Log.i("Rinowa/calls", "the same call arrived again; already ringing")
                return START_NOT_STICKY
            }

            // 鳴っている最中に、別の人からの2件目。
            //
            // 出せる場所はこの端末に1つしかない（通知 id もサービスもこの画面も1つ）。
            // 黙って差し替えると、**応答ボタンの行き先が、押そうとしている人の指の下で
            // 別の人に変わる**。掛け直せる2件目より、間違った相手に出てしまう1件目の
            // ほうが取り返しがつかないので、先に鳴り始めたほうを守る。
            //
            // 2件を同時にちゃんと出すには通話中着信の画面が要る。まだ無い。
            android.util.Log.i("Rinowa/calls", "another call is already ringing; not taking over")
            return START_NOT_STICKY
        }
        ringingCallId = callId

        // 着信通知は写真なしで組み立て、すぐ出す。
        //
        // 先に写真を引くと Firestore を待つことになる。FCM が起こしたばかりの
        // プロセスではローカルストアを開くだけでも、鳴っている端末が許せる時間を超える。
        // 150ms では時間切れになり「会話がキャッシュに無い」と報告された。事実では
        // あるが誤解を招く（キャッシュにはあった、時間が足りなかった）。
        //
        // なので顔なしで先に出し、同じ通知にあとから顔を足す。着信は写真を待たない。
        // ここは必ず写真なし。**null を渡していることが読めるように、そう書く。**
        // 変数を挟んで ?.let を通すと「写真があれば付ける」ように見えるが、
        // 上のとおり必ず無い。あとから refinePhoto が同じ通知を差し替える。
        val notification = IncomingCallNotifier.build(
            this,
            callId,
            conversationId,
            callerName,
            kindLabel,
            null,
            null,
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
        //
        // 積む前に消すのは、前の通話の分がまだ残っていることがあるから（断った直後に
        // 次が来る道）。残ったまま積むと、25秒前に仕掛けられたほうが先に鳴って、
        // 始まったばかりの着信を5秒で黙らせる。
        stopper.removeCallbacksAndMessages(null)
        stopper.postDelayed(
            {
                stopEverything()
                stopSelf()
            },
            RING_TIMEOUT_MS,
        )
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
        // 見張れるかどうかを決める前に、前の購読を捨てる。前の通話のものが生きていると、
        // あちらが終わった知らせで stopSelf() が呼ばれ、いま鳴っている別の通話が黙る。
        callWatch?.remove()
        callWatch = null
        if (callId.isEmpty() || conversationId.isEmpty()) return
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
                    // 止めるのを onDestroy 任せにしない。掛けた人が切ってすぐ掛け直す
                    // のはよくあることで、その2件目が届く頃にまだ「1件目を鳴らして
                    // いる」ことになっていると、掛け直したほうが黙って捨てられる。
                    if (!ringing) {
                        stopEverything()
                        stopSelf()
                    }
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
        // 鳴らす前に、前のものを必ず止める。呼び出し側が二重に来ないよう見張ってはいるが、
        // 鳴らす側にも置く。ここを通らずに player を上書きすると、捨てたほうは
        // isLooping のまま誰の参照にも残らずに鳴り続け、止める手は強制終了しかない。
        stopSound()

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

    /**
     * 音と振動を止める。何度呼んでもよい。
     *
     * 先に持ち手を手放してから止めにかかる。stop() が投げたときに、解放済みのものを
     * 指したままの player が残らないように（次に来たものがそれを止めようとする）。
     *
     * 振動子はシステムのものを借りているだけなので解放は要らない。ただし止めるのは要る。
     * 同じ端末で vibrate() を呼び直せば前の波形は置き換わるが、着信音だけ鳴らす設定に
     * 変わっていると次の vibrate() が来ないので、前の振動が残る。
     */
    private fun stopSound() {
        val ringing = player
        player = null
        bestEffort("stop ringtone") { ringing?.stop() }
        bestEffort("release ringtone") { ringing?.release() }

        val buzzing = vibrator
        vibrator = null
        bestEffort("stop vibration") { buzzing?.cancel() }
    }

    /** 鳴らすのをやめ、鳴らし続けるための仕掛けも全部畳む。 */
    private fun stopEverything() {
        callWatch?.remove()
        callWatch = null
        stopper.removeCallbacksAndMessages(null)
        stopSound()
        ringingCallId = null
    }

    override fun onDestroy() {
        stopEverything()
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
