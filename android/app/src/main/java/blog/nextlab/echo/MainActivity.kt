package blog.nextlab.echo

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import blog.nextlab.echo.calls.CallActionReceiver
import blog.nextlab.echo.calls.CallPresence
import blog.nextlab.echo.calls.OngoingCallService
import blog.nextlab.echo.notifications.RinowaMessagingService
import blog.nextlab.echo.ui.RinowaApp
import blog.nextlab.echo.ui.LocalInPictureInPicture

class MainActivity : ComponentActivity() {

    private val app: RinowaApplication get() = application as RinowaApplication

    /**
     * 通知から開かれたときの、その会話。
     *
     * ただのフィールドではなく Compose の state。起動時にも [onNewIntent] からも入り、
     * 2度目も画面が反応する必要がある。1回読んだら消す（一覧に戻った瞬間に
     * チャットへ跳ね返らないように）。
     */
    private var pendingConversationId by mutableStateOf<String?>(null)

    /**
     * いま小さな浮き窓かどうか。
     *
     * その大きさでは通話画面をまったく別の簡素なものにする必要がある
     * （[LocalInPictureInPicture]）。
     */
    private var inPictureInPicture by mutableStateOf(false)

    /**
     * この端末がそもそも PiP を持っているか。
     *
     * **API のレベルでは答えられない。** PiP は Android 8 からあるが任意機能で、
     * 積んでいない端末では `enterPictureInPictureMode` が例外か無反応になる。
     * パッケージマネージャに聞くのが唯一正直な確認で、この読み違えはもう3回やっている。
     */
    private val supportsPip: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 通知が1つも出る前にチャンネルを作る。Rinowa が音を出したあとではなく、
        // 先に Android の設定側で調整できるように。許可そのものは Compose 側から求める
        // （RinowaApp を参照）。
        RinowaMessagingService.ensureChannel(this)
        OngoingCallService.ensureChannel(this)
        pendingConversationId = intent.conversationId()
        handleHangUp(intent)

        // 浮き窓から消音したら浮き窓のボタンも変わる必要があり、通話を終えたら
        // 窓自体が消える必要がある。
        CallPresence.onChanged = {
            if (CallPresence.videoActive) refreshPipActions() else leavePipIfCallEnded()
        }

        setContent {
            CompositionLocalProvider(LocalInPictureInPicture provides inPictureInPicture) {
                RinowaApp(
                    haptics = app.haptics,
                    analytics = app.analytics,
                    stickers = app.stickers,
                    services = app.services,
                    appScope = app.appScope,
                    openConversationId = pendingConversationId,
                    onConversationOpened = { pendingConversationId = null },
                )
            }
        }
    }

    /**
     * Rinowa が動いている最中にタップされた通知。
     *
     * 通知の PendingIntent は FLAG_ACTIVITY_SINGLE_TOP を持つので、Android は
     * 2つ目の activity を作らずここへ配る。この override が無いと、前に開いていた
     * 画面のまま前面に出てくる（実際そうなった）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingConversationId = intent.conversationId()
        handleHangUp(intent)
    }

    /**
     * 通話中の通知で「終了」が押された。
     *
     * ブロードキャストではなく Activity の intent で来る。通話の終了は裏の仕事ではなく、
     * 画面が映しているセッションを畳む操作で、画面も一緒に変わる必要があるから。
     * 何も出ていなければ何もしない（古い通知の操作が、すでに終わった通話に触らないように）。
     */
    private fun handleHangUp(intent: Intent?) {
        if (intent?.action != OngoingCallService.ACTION_HANG_UP) return
        CallPresence.hangUp?.invoke()
        // 消費する。消さないと、構成変更で intent が再生されて*次の*通話が
        // 始まった瞬間に終わる。
        intent.action = null
    }

    /**
     * ホームを押された、またはジェスチャーでアプリから出た。
     *
     * ビデオ通話を消さずに隅へ縮める場面。ビデオだけ。音声通話の浮き窓は黒い矩形に
     * なるだけで、通話中の通知がすでにその役をしている。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!CallPresence.videoActive || !supportsPip || isInPictureInPictureMode) return
        runCatching {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(pipParams())
            }
        }
    }

    /**
     * 浮き窓の形とボタン。
     *
     * ボタンを自前で描かないのは、**PiP の窓がタッチを受け取らない**から。触ると
     * システムに「広げてくれ」と伝わるだけで、指の下のものは押されない。自分で描いた
     * ものは効かない。触って出てくるのはシステム側の操作列で、それをこの `RemoteAction`
     * の一覧から作る。LINE を含め他のアプリもこの方法で浮き窓に消音ボタンを置いている。
     *
     * 保証される上限は3つで、通話に要るのもちょうど3つ（マイク、カメラ、終了）。
     *
     * 各ボタンは「押したらどうなるか」ではなく「いまどうなっているか」を出す。
     * だから [CallPresence.onChanged] が要る。切り替えたあとに作り直さないと、
     * 作られたときの絵柄のまま嘘をつき始める。
     */
    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            // 縦。相手が持っている端末が縦だから。縦の映像を 16:9 の窓に入れると
            // 黒帯2本と細い人になる。
            .setAspectRatio(Rational(9, 16))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && CallPresence.videoActive) {
            builder.setActions(
                listOf(
                    remoteAction(
                        iconRes = if (CallPresence.muted) R.drawable.ic_call_mic_off else R.drawable.ic_call_mic,
                        title = if (CallPresence.muted) "ミュート解除" else "ミュート",
                        action = CallActionReceiver.ACTION_TOGGLE_MUTE,
                        requestCode = 11,
                    ),
                    remoteAction(
                        iconRes = if (CallPresence.cameraOn) R.drawable.ic_call_video else R.drawable.ic_call_video_off,
                        title = if (CallPresence.cameraOn) "カメラを切る" else "カメラを入れる",
                        action = CallActionReceiver.ACTION_TOGGLE_CAMERA,
                        requestCode = 12,
                    ),
                    remoteAction(
                        iconRes = R.drawable.ic_call_end,
                        title = "終了",
                        action = CallActionReceiver.ACTION_END,
                        requestCode = 13,
                    ),
                ),
            )
        }
        return builder.build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun remoteAction(
        iconRes: Int,
        title: String,
        action: String,
        requestCode: Int,
    ): RemoteAction {
        val intent = Intent(this, CallActionReceiver::class.java)
            .setAction(action)
            // パッケージを明示する。暗黙のブロードキャストは Android 8 以降届かず、
            // ボタンが何もしないものになる。
            .setPackage(packageName)
        val pending = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return RemoteAction(Icon.createWithResource(this, iconRes), title, title, pending)
    }

    /** 直前の操作の結果に合わせて、操作列の絵柄を作り直す。 */
    private fun refreshPipActions() {
        if (!supportsPip || !isInPictureInPictureMode) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /**
     * 映していた通話が終わったら浮き窓を閉じる。
     *
     * 浮き窓の「終了」で通話は終わる（相手側も切れる）のに、窓だけが通話の無いアプリを
     * 映したまま残っていた。**Android には PiP から出る API が無い。** 出る方法は
     * 利用者が窓を広げるか activity が終わるかの2つだけなので、終わらせる。
     *
     * これは回避策ではなく正しい結末でもある。浮き窓から通話を終えた人は別のことを
     * 見ていて、Rinowa は消えてその場を空けるべき。全画面から終えたときにアプリごと
     * 閉じないよう、PiP のときだけに限定する。
     */
    private fun leavePipIfCallEnded() {
        if (!supportsPip || !isInPictureInPictureMode) return
        runCatching { finish() }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        // PiP に入った瞬間が操作列の生まれる最初の機会なので、ここでも作る。
        if (isInPictureInPictureMode) refreshPipActions()
    }

    override fun onDestroy() {
        // 破棄された Activity をシングルトンから触れる状態にしておかない。
        if (CallPresence.onChanged != null) CallPresence.onChanged = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        app.haptics.setAppInForeground(true)
    }

    override fun onPause() {
        // アプリが前面にないとき触覚は鳴らさない。浮き窓の通話は前面ではない
        // （別のことに使っている端末を震わせるのは間違い）。
        app.haptics.setAppInForeground(false)
        super.onPause()
    }
}

private fun Intent?.conversationId(): String? =
    this?.getStringExtra(RinowaMessagingService.EXTRA_CONVERSATION_ID)?.takeIf { it.isNotBlank() }
