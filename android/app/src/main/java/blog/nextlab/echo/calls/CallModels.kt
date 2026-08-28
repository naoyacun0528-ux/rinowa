package blog.nextlab.echo.calls

import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.core.model.UserId

@JvmInline
value class CallId(val value: String)

enum class CallKind { Audio, Video }

/**
 * 通話がいまどこにいるか。
 *
 * 「繋がったか否か」にまとめず、状態を明示したままにする。[Ringing] と [Active] の
 * 間には ICE 交渉の数秒があり、そこが電話で一番不安な時間だから。その間に何も
 * 言わない画面は、人に切ってかけ直させ、それこそが失敗を確実にする。
 */
enum class CallState {
    /** 発信済み。まだ出ていない。 */
    Ringing,

    /** 出た。両端はまだ互いへの経路を探している。 */
    Connecting,

    /** 音声・映像が流れている。 */
    Active,

    /** 終わった。理由は問わない。どれかは [CallRecord.endReason]。 */
    Ended,
}

enum class CallEndReason {
    /** 誰かが赤いボタンを押した。 */
    Hangup,

    /** 相手が断った。 */
    Declined,

    /** 誰も出なかった。 */
    Missed,

    /** ICE が経路を見つけられなかったか、切れて戻らなかった。 */
    Failed,

    /** 相手はすでに通話中だった。 */
    Busy,
}

/**
 * 両端から見た通話1件。
 *
 * これは signaling であって内容ではない。通話があったこと、誰と誰か、どれだけ続いたかを
 * 言う。**メッセージの本文と違い、こちらからは見える**。docs/CALLS_ARCHITECTURE.md §3.1 に
 * そう書いてあり、誰かが偶然気付くのに任せていない。
 */
data class CallRecord(
    val id: CallId,
    val conversationId: ConversationId,
    val callerId: UserId,
    val kind: CallKind,
    val state: CallState,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val endReason: CallEndReason? = null,
) {
    fun isIncomingFor(me: UserId): Boolean = callerId != me

    /** 実際に話していた秒数。スレッドに残す記録用。 */
    val durationSeconds: Long?
        get() = endedAtMs?.let { ((it - startedAtMs) / 1000).coerceAtLeast(0) }
}
