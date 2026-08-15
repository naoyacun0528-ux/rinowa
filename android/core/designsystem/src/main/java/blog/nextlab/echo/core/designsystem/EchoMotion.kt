package blog.nextlab.echo.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/**
 * Motion tokens.
 *
 * Haptics are timed against these, so animation timing is not a per-screen decision.
 * If a duration changes here, the feel of the matching haptic changes with it — that
 * coupling is the point.
 */
object EchoMotion {

    /**
     * Interactive movement that follows the finger, and settles without wobble.
     * Used for reply swipe and bubble drag.
     */
    fun <T> followSpring(): SpringSpec<T> = spring(
        dampingRatio = 1f,
        stiffness = 1400f,
    )

    /** A commit that should look decisive: fast, with the faintest overshoot. */
    fun <T> commitSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.72f,
        stiffness = 900f,
    )

    /** Something appearing under the finger: reaction picker, context sheet. */
    fun <T> popSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.68f,
        stiffness = 700f,
    )

    /** Large surfaces settling. Slower, no bounce. */
    fun <T> settleSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Standard easing for fades and colour changes. */
    val standardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For things leaving the screen: start fast, no lingering. */
    val exitEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    const val DURATION_INSTANT = 90
    const val DURATION_QUICK = 160
    const val DURATION_STANDARD = 260
    const val DURATION_SLOW = 420
}

/** Reply-swipe geometry. The threshold is the only place a haptic fires during the drag. */
object EchoSwipe {
    /** Distance at which the reply becomes valid. */
    val ThresholdDistance: Dp = Dp(72f)

    /** Movement is progressively resisted past the threshold so the limit is felt, not just seen. */
    val MaxDistance: Dp = Dp(104f)

    /** Below this the gesture is treated as a scroll, not a swipe. */
    val StartSlop: Dp = Dp(10f)

    fun resist(rawPx: Float, thresholdPx: Float, maxPx: Float): Float {
        if (rawPx <= thresholdPx) return rawPx
        val overshoot = rawPx - thresholdPx
        val room = (maxPx - thresholdPx).coerceAtLeast(1f)
        // Asymptotic: the finger can always move, but the bubble stops keeping up.
        return thresholdPx + room * (1f - 1f / (1f + overshoot / room))
    }
}

internal fun offsetOf(x: Float, y: Float) = IntOffset(x.toInt(), y.toInt())
