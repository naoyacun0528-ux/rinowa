package blog.nextlab.echo.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import blog.nextlab.echo.core.designsystem.RinowaSwipe
import kotlin.math.abs
import kotlin.math.max

/**
 * [messageGestures] のコールバック。
 *
 * 閾値の出入りは毎フレームの真偽値ではなく、入った・出たという個別の出来事として
 * 報告する。触覚は1回の横断につきちょうど1回鳴る必要があり、毎フレーム
 * 「いま線の向こうか」を計算する作りにすると、スワイプ中ずっと振動し続ける。
 */
class MessageGestureHandlers(
    val onSwipeStart: () -> Unit = {},
    val onSwipeUpdate: (offsetPx: Float) -> Unit = {},
    val onThresholdEnter: () -> Unit = {},
    val onThresholdExit: () -> Unit = {},
    val onSwipeFinish: (
        committed: Boolean,
        maxDragRatio: Float,
        passedThreshold: Boolean,
        durationMs: Long,
    ) -> Unit = { _, _, _, _ -> },
    val onLongPressStart: (localPosition: Offset, holdMs: Long) -> Unit = { _, _ -> },
    val onLongPressMove: (localPosition: Offset) -> Unit = {},
    val onLongPressFinish: () -> Unit = {},
    val onTap: () -> Unit = {},
)

private enum class GestureMode { Undecided, Swipe, LongPress, Tap, Abandoned }

/**
 * 返信スワイプと長押しリアクションを、1つの状態機械で解決する。
 *
 * 1つのジェスチャーにまとめる必要がある。別々の検出器にすると競合し、よくある
 * 失敗は「長押ししたのに吹き出しが横にずれる」か「ドラッグの途中でリアクションが出る」。
 *
 * 解決の順序:
 *  - 先に縦へ動いた   -> 何も消費せず手を引く（一覧がスクロールする）
 *  - 先に右へ動いた   -> 返信スワイプ
 *  - 先に左へ動いた   -> 手を引く（左スワイプはまだ意味を持たせていない）
 *  - 長押しの時間まで動かない -> リアクションの選択
 *  - その前に離した   -> タップ
 *
 * この modifier は、ドラッグで動かない要素に付けること。
 *
 * 指の位置は `pointerInput` を持つ要素からの相対で報告される。その要素自体が
 * ドラッグで動いていると、吹き出しが進んだぶんが次の報告位置から引かれ、
 * `offset = 指の移動 - offset` で釣り合う。つまり吹き出しは指の半分の速さで付いていき、
 * その釣り合いの周りで毎フレーム揺れる。
 *
 * 実測した数字: 1200ms で 410px スワイプすると、189px の閾値を 1089ms に越え、
 * その時点で指は 372px 進んでいた（その半分が 186px）。見た目には吹き出しが2つの
 * 位置でちらつき、揺れが閾値を何度も跨いで触覚が複数回鳴る。
 *
 * したがって、これは動かない入れ物に付け、**その子**を動かす。
 */
fun Modifier.messageGestures(
    key: Any?,
    enabled: Boolean,
    thresholdPx: Float,
    /** 履歴（ヒステリシス）。一度越えたら、ここまで戻らないと解除にならない。 */
    releaseThresholdPx: Float,
    maxPx: Float,
    handlers: MessageGestureHandlers,
): Modifier = if (!enabled) this else pointerInput(key, thresholdPx, releaseThresholdPx, maxPx) {
    val slop = viewConfiguration.touchSlop
    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var mode = GestureMode.Undecided

        // ---- 第1段: 指が何をしているかを見極める --------------------------------
        try {
            withTimeout(longPressTimeoutMs) {
                while (mode == GestureMode.Undecided) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        mode = GestureMode.Abandoned
                        return@withTimeout
                    }
                    if (!change.pressed) {
                        mode = GestureMode.Tap
                        return@withTimeout
                    }
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    when {
                        abs(dy) > slop && abs(dy) >= abs(dx) -> {
                            // 縦の意図。何も消費しない。このジェスチャーは一覧のもの。
                            mode = GestureMode.Abandoned
                            return@withTimeout
                        }
                        dx > slop -> mode = GestureMode.Swipe
                        dx < -slop -> {
                            mode = GestureMode.Abandoned
                            return@withTimeout
                        }
                    }
                }
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            if (mode == GestureMode.Undecided) mode = GestureMode.LongPress
        }

        // ---- 第2段: 実行する ----------------------------------------------------
        when (mode) {
            GestureMode.Tap -> handlers.onTap()

            GestureMode.Swipe -> {
                handlers.onSwipeStart()
                var offset = 0f
                var maxOffset = 0f
                var pastThreshold = false
                var everPastThreshold = false

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break

                    // slop を引く。最初のフレームで slop のぶん飛ばず、指の真下から始まる。
                    val raw = (change.position.x - down.position.x - slop).coerceAtLeast(0f)
                    offset = RinowaSwipe.resist(raw, thresholdPx, maxPx)
                    maxOffset = max(maxOffset, offset)

                    // ヒステリシス。線の上に置いた指で閾値の触覚が細かく鳴り続けないように。
                    val nowPast = if (pastThreshold) {
                        offset >= releaseThresholdPx
                    } else {
                        offset >= thresholdPx
                    }
                    if (nowPast != pastThreshold) {
                        pastThreshold = nowPast
                        if (nowPast) {
                            everPastThreshold = true
                            handlers.onThresholdEnter()
                        } else {
                            handlers.onThresholdExit()
                        }
                    }
                    handlers.onSwipeUpdate(offset)
                    change.consume()
                }

                val durationMs = currentEvent.changes.firstOrNull()?.uptimeMillis?.minus(down.uptimeMillis)
                    ?: 0L
                handlers.onSwipeFinish(
                    pastThreshold,
                    if (thresholdPx > 0f) (maxOffset / thresholdPx) else 0f,
                    everPastThreshold,
                    durationMs.coerceAtLeast(0L),
                )
            }

            GestureMode.LongPress -> {
                handlers.onLongPressStart(down.position, longPressTimeoutMs)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    handlers.onLongPressMove(change.position)
                    change.consume()
                }
                handlers.onLongPressFinish()
            }

            GestureMode.Undecided, GestureMode.Abandoned -> Unit
        }
    }
}
