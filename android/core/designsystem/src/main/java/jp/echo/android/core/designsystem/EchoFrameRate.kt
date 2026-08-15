package jp.echo.android.core.designsystem

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.preferredFrameRate

/**
 * Adaptive refresh rate.
 *
 * Panels run anywhere from 1 Hz on an idle always-on display to 144 Hz on a high-refresh
 * phone, and the rate changes underneath a running app. **Nothing in this codebase may
 * assume a rate.** A frame budget is always derived from what the panel is doing now.
 *
 * Two consequences worth stating, because both are easy to get wrong:
 *
 * - **Never hardcode 16.7 ms or 8.3 ms.** Use [RefreshRateInfo.frameBudgetMs].
 * - **Never drive anything off a frame count.** Animations must be time-based, so that a
 *   panel dropping to 1 Hz slows nothing down and a 144 Hz panel speeds nothing up.
 *   Compose's spring and tween specs already work this way; hand-rolled counters do not.
 */
@Immutable
data class RefreshRateInfo(
    val currentHz: Float,
    val supportedHz: List<Float>,
    /** True when the panel varies its rate continuously rather than switching modes. */
    val adaptive: Boolean,
) {
    /** Milliseconds available to render one frame at the rate the panel is running now. */
    val frameBudgetMs: Float get() = if (currentHz > 0f) 1000f / currentHz else 0f

    val maxHz: Float get() = supportedHz.maxOrNull() ?: currentHz
    val minHz: Float get() = supportedHz.minOrNull() ?: currentHz
}

object EchoRefreshRate {

    fun read(context: Context): RefreshRateInfo {
        val display = displayOf(context)
        val current = display?.refreshRate?.takeIf { it > 0f } ?: FALLBACK_HZ

        val supported = display
            ?.let { runCatching { it.supportedRefreshRates.toList() }.getOrNull() }
            ?.filter { it > 0f }
            ?.distinct()
            ?.sorted()
            ?: listOf(current)

        val adaptive = if (Build.VERSION.SDK_INT >= 36 && display != null) {
            runCatching { display.hasArrSupport() }.getOrDefault(false)
        } else {
            false
        }

        return RefreshRateInfo(
            currentHz = current,
            supportedHz = supported,
            adaptive = adaptive,
        )
    }

    private fun displayOf(context: Context): Display? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }
    }.getOrNull()

    /**
     * Only used when the platform will not say. Deliberately the low end rather than a
     * flattering number: code that reasons about the budget should err towards assuming
     * less time, not more.
     */
    private const val FALLBACK_HZ = 60f
}

@Composable
fun rememberRefreshRateInfo(): RefreshRateInfo {
    val context = LocalContext.current
    // Re-read on recomposition rather than caching for the process: the panel switches
    // modes while the app is running, and a stale value is worse than none.
    return remember(context) { EchoRefreshRate.read(context) }
}

/**
 * Asks for the highest rate the panel offers while [active] is true.
 *
 * Applied narrowly — to what is actually moving under a finger — rather than to the whole
 * app. Holding a 120 Hz panel at 120 Hz while the user reads a message costs battery and
 * buys nothing, and this app is not in the business of taking things from people quietly.
 */
fun Modifier.preferHighFrameRate(active: Boolean): Modifier =
    if (active) this.preferredFrameRate(FrameRateCategory.High) else this

/** Asks for a specific rate, for content that has a natural cadence of its own. */
fun Modifier.preferFrameRate(hz: Float): Modifier =
    if (hz > 0f) this.preferredFrameRate(hz) else this
