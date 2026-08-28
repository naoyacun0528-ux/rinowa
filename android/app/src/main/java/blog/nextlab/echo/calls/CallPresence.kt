package blog.nextlab.echo.calls

/**
 * 通話のうち、Compose の外から触れる必要がある部分。
 *
 * このアプリでは普段やらないシングルトンにしてある理由は2つ。コンポジションの外に
 * いて通話を操作する必要があるものがあるから:
 *
 * - `Activity.onUserLeaveHint()` — ホームを押した瞬間で、ピクチャーインピクチャーに
 *   入る唯一の確実な合図。
 * - PiP の操作ボタン。これは**ブロードキャスト**として来る。浮き窓はタッチを
 *   受け取らず、ボタンはシステムが描き、押すと PendingIntent が飛ぶ。その経路に
 *   コンポーザブルは1つも無い。
 *
 * [CallController] はコンポジションの中、全画面より上で作られる。会話を離れても
 * 通話が続くようにするため。Activity のコールバックも BroadcastReceiver も、
 * コンポジションの中に手を伸ばして問い合わせることはできない。
 *
 * 代案は、コントローラを `RinowaApplication` に上げる（誰も必要としない形で通話が
 * UI より長生きする）か、Activity も持つ ViewModel を経由させる（同じシングルトンに
 * 儀式が増えるだけ）。ここは項目が数個、書くのは1箇所、読むのは2箇所。
 *
 * **会話に関するものはここに置かない** — 相手も、id も、文字も1つも。浮き窓が
 * できる必要があることだけ。
 */
object CallPresence {

    /** ビデオ通話のセッションが開いている間 true。PiP に入るかを Activity が判断する。 */
    @Volatile
    var videoActive: Boolean = false

    @Volatile
    var muted: Boolean = false

    @Volatile
    var cameraOn: Boolean = false

    /**
     * いまの通話を終わらせる。
     *
     * 通話中はコントローラが入れるので、通知の「終了」と浮き窓の終了ボタンに
     * 呼ぶ先ができる。片付けで空にするので、終わったあとに古いインテントが届いても
     * 何も起きない（畳んだセッションに手を伸ばさない）。
     */
    @Volatile
    var hangUp: (() -> Unit)? = null

    @Volatile
    var toggleMute: (() -> Unit)? = null

    @Volatile
    var toggleCamera: (() -> Unit)? = null

    /**
     * [muted] か [cameraOn] が変わったときに呼ばれる。
     *
     * Activity はこれで PiP の操作一覧を作り直す。無いとボタンは作られたときの絵柄の
     * ままなので、浮き窓から消音してもマイクは生きているように見える。
     * 自分が変えた状態について、その操作が嘘をつくことになる。
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun publish(muted: Boolean, cameraOn: Boolean) {
        this.muted = muted
        this.cameraOn = cameraOn
        onChanged?.invoke()
    }

    fun clear() {
        videoActive = false
        muted = false
        cameraOn = false
        hangUp = null
        toggleMute = null
        toggleCamera = null
        onChanged?.invoke()
    }
}
