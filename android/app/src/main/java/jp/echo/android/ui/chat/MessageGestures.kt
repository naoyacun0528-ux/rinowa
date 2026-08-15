package jp.echo.android.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import jp.echo.android.core.designsystem.EchoSwipe
import kotlin.math.abs
import kotlin.math.max

/**
 * Callbacks for [messageGestures].
 *
 * Threshold crossings are reported as discrete enter/exit events rather than as a boolean
 * on every frame, because the haptic must fire exactly once per crossing. Recomputing
 * "am I past the line" per frame is how swipe haptics end up buzzing continuously.
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
 * Reply-swipe and long-press-to-react, resolved by a single state machine.
 *
 * They have to share one gesture: two independent detectors would race, and the common
 * failure is a long press that also nudges the bubble sideways, or a swipe that fires the
 * reaction picker mid-drag.
 *
 * Resolution rules, in order:
 *  - vertical movement first  -> abandon, and consume nothing so the list scrolls
 *  - rightward movement first -> reply swipe
 *  - leftward movement first  -> abandon (reserved; left swipe has no meaning yet)
 *  - no movement until the long-press timeout -> reaction picker
 *  - released before any of the above -> tap
 *
 * ## This modifier must go on a node the drag does not move
 *
 * Pointer positions are reported relative to the node that owns the `pointerInput`. If
 * that node is the one being translated by the drag, every pixel the bubble travels is
 * subtracted from the next reported position, and the system settles at
 * `offset = fingerTravel - offset`, i.e. the bubble tracks at exactly half finger speed
 * while oscillating around that equilibrium each frame.
 *
 * That was measured, not guessed: a 410 px swipe over 1200 ms crossed a 189 px threshold
 * at 1089 ms, by which point the finger had travelled 372 px — half of which is 186 px.
 * Visually it reads as the bubble flickering between two positions, and the oscillation
 * re-crosses the threshold, firing the threshold haptic more than once.
 *
 * So: attach this to the stationary container and translate a **child** of it.
 */
fun Modifier.messageGestures(
    key: Any?,
    enabled: Boolean,
    thresholdPx: Float,
    /** Hysteresis: once past, the drag must fall back to here before it counts as released. */
    releaseThresholdPx: Float,
    maxPx: Float,
    handlers: MessageGestureHandlers,
): Modifier = if (!enabled) this else pointerInput(key, thresholdPx, releaseThresholdPx, maxPx) {
    val slop = viewConfiguration.touchSlop
    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var mode = GestureMode.Undecided

        // ---- phase 1: work out what the finger is doing -------------------------
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
                            // Vertical intent. Consume nothing; the list owns this gesture.
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

        // ---- phase 2: run it ----------------------------------------------------
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

                    // Subtract the slop so the bubble starts exactly under the finger
                    // instead of jumping by the slop distance on the first frame.
                    val raw = (change.position.x - down.position.x - slop).coerceAtLeast(0f)
                    offset = EchoSwipe.resist(raw, thresholdPx, maxPx)
                    maxOffset = max(maxOffset, offset)

                    // Hysteresis, so a finger resting on the line cannot chatter the
                    // threshold haptic on and off.
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
