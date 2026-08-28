package blog.nextlab.echo.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * ピクチャーインピクチャーの窓のボタン。
 *
 * 浮き窓はタッチを受け取らない。触るのはシステムに「広げてくれ」と言うことで、
 * 指の下のものを押すことにはならない。だから操作はシステムが `RemoteAction` の
 * 一覧から描き、押すと PendingIntent が飛ぶ。その着地点がここ。
 *
 * Activity の intent ではなくブロードキャストなのは、これらがアプリを前面に
 * 戻しては**いけない**から。浮き窓から消音する意味は、いま見ているものを離れずに
 * それをすること。終了だけは例外で Activity が扱う（通話を終えるなら画面も
 * 一緒に畳む必要がある）。
 *
 * 通話が無いときはどの操作も何もしない。終わった通話の古い PendingIntent は、
 * 畳んだセッションに手を伸ばすのではなく、何もしないべき。
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_MUTE -> CallPresence.toggleMute?.invoke()
            ACTION_TOGGLE_CAMERA -> CallPresence.toggleCamera?.invoke()
            ACTION_END -> CallPresence.hangUp?.invoke()
        }
    }

    companion object {
        const val ACTION_TOGGLE_MUTE = "blog.nextlab.echo.PIP_TOGGLE_MUTE"
        const val ACTION_TOGGLE_CAMERA = "blog.nextlab.echo.PIP_TOGGLE_CAMERA"
        const val ACTION_END = "blog.nextlab.echo.PIP_END"
    }
}
