package blog.nextlab.echo.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/**
 * 動きの定義。
 *
 * 触覚はこれに合わせて鳴らすので、アニメーションの時間は画面ごとの判断にしない。
 * ここの時間を変えれば、対応する触覚の感じも一緒に変わる。その連動が狙い。
 */
object RinowaMotion {

    /**
     * 指に追従して、揺り戻さずに落ち着く動き。
     * 返信スワイプと吹き出しのドラッグで使う。
     */
    fun <T> followSpring(): SpringSpec<T> = spring(
        dampingRatio = 1f,
        stiffness = 1400f,
    )

    /** 決然と見えるべき確定。速く、ごくわずかに行き過ぎる。 */
    fun <T> commitSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.72f,
        stiffness = 900f,
    )

    /** 指の下に現れるもの。リアクションの選択、コンテキストのシート。 */
    fun <T> popSpring(): SpringSpec<T> = spring(
        dampingRatio = 0.68f,
        stiffness = 700f,
    )

    /** 大きい面が落ち着く動き。ゆっくり、跳ねない。 */
    fun <T> settleSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** フェードや色の変化の標準。 */
    val standardEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 画面から出ていくもの用。速く始まり、名残を残さない。 */
    val exitEasing: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    const val DURATION_INSTANT = 90
    const val DURATION_QUICK = 160
    const val DURATION_STANDARD = 260
    const val DURATION_SLOW = 420
}

/** 返信スワイプの寸法。ドラッグ中に触覚が鳴るのは閾値だけ。 */
object RinowaSwipe {
    /** 返信が成立する距離。 */
    val ThresholdDistance: Dp = Dp(72f)

    /** 閾値を越えると次第に重くなる。限界が見えるだけでなく感じられるように。 */
    val MaxDistance: Dp = Dp(104f)

    /** これ未満はスワイプではなくスクロールとして扱う。 */
    val StartSlop: Dp = Dp(10f)

    fun resist(rawPx: Float, thresholdPx: Float, maxPx: Float): Float {
        if (rawPx <= thresholdPx) return rawPx
        val overshoot = rawPx - thresholdPx
        val room = (maxPx - thresholdPx).coerceAtLeast(1f)
        // 漸近的に。指はいつでも動かせるが、吹き出しは追いつかなくなる。
        return thresholdPx + room * (1f - 1f / (1f + overshoot / room))
    }
}

internal fun offsetOf(x: Float, y: Float) = IntOffset(x.toInt(), y.toInt())
