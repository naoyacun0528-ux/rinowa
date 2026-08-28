package blog.nextlab.echo.core.designsystem

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
 * 可変リフレッシュレート。
 *
 * パネルは、常時表示の待機中の1Hzから高リフレッシュ端末の144Hzまで動き、しかも
 * アプリが動いている最中に変わる。**このコードのどこも、レートを仮定してはいけない。**
 * フレームの予算は、いまパネルが何をしているかから毎回導く。
 *
 * 間違えやすいので明記する2点:
 *
 * - **16.7ms や 8.3ms を書かない。** [RefreshRateInfo.frameBudgetMs] を使う。
 * - **フレーム数で何かを動かさない。** アニメーションは時間で動かす。そうすれば
 *   1Hzに落ちても遅くならず、144Hzでも速くならない。Compose の spring と tween は
 *   もともとそうなっているが、手で数えるカウンタはそうならない。
 */
@Immutable
data class RefreshRateInfo(
    val currentHz: Float,
    val supportedHz: List<Float>,
    /** モードを切り替えるのではなく、連続的にレートを変えるパネルなら true。 */
    val adaptive: Boolean,
) {
    /** いまのレートで1フレームを描くのに使えるミリ秒。 */
    val frameBudgetMs: Float get() = if (currentHz > 0f) 1000f / currentHz else 0f

    val maxHz: Float get() = supportedHz.maxOrNull() ?: currentHz
    val minHz: Float get() = supportedHz.minOrNull() ?: currentHz
}

object RinowaRefreshRate {

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
     * プラットフォームが答えないときだけ使う。見栄えのする数字ではなくわざと低い側。
     * 予算を考えるコードは、時間が多いほうではなく少ないほうに寄せて間違えるべき。
     */
    private const val FALLBACK_HZ = 60f
}

@Composable
fun rememberRefreshRateInfo(): RefreshRateInfo {
    val context = LocalContext.current
    // プロセス単位でキャッシュせず、再コンポーズのたびに読み直す。パネルは動作中に
    // モードを変えるので、古い値は無いより悪い。
    return remember(context) { RinowaRefreshRate.read(context) }
}

/**
 * [active] の間だけ、パネルが出せる最高のレートを要求する。
 *
 * アプリ全体ではなく、実際に指の下で動いているものにだけ付ける。メッセージを
 * 読んでいる間 120Hz に張り付かせるのは電池を食うだけで何も買わないし、
 * このアプリは黙って何かを奪う側ではない。
 */
fun Modifier.preferHighFrameRate(active: Boolean): Modifier =
    if (active) this.preferredFrameRate(FrameRateCategory.High) else this

/** 自前の周期を持つ内容のために、特定のレートを要求する。 */
fun Modifier.preferFrameRate(hz: Float): Modifier =
    if (hz > 0f) this.preferredFrameRate(hz) else this
