package blog.nextlab.echo.ui.common

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import kotlin.math.pow
import kotlinx.coroutines.CancellationException

/**
 * 戻る操作を、まだ決まっていない間に見せて触らせる。
 *
 * Android 14 からの戻るジェスチャーは押下ではなく**取り消せるドラッグ**。指が下りて
 * いる間にシステムが裏を見せるので、最後にだけ反応するアプリでは、気が変わる余地の
 * ある時間に何も起きない。ここでは全画面が引きに応える（縮んで滑り、途中で離せば
 * 元どおり）。
 *
 * 画面ごとのハンドラにしないのは、最初にそうやって写真ビューアだけが対応した状態に
 * なったから。画面によって挙動が違うジェスチャーは、まったく動かないものより悪い
 * （規則を教えてから破る）。
 */
class BackPull internal constructor() {

    /** 何も起きていなければ 0、引き切って 1。 */
    var progress by mutableFloatStateOf(0f)
        internal set

    /**
     * 指がどちらの端から来たか。
     *
     * 中身は手が進む方向へ出ていく（右端からなら右から左、左端からなら左から右）。
     * 逆に動かしたり、常に同じ側へ動かしたりすると、画面がジェスチャーに逆らって見える。
     */
    var fromLeftEdge by mutableStateOf(true)
        internal set
}

/**
 * ジェスチャーを取り付けて、その状態を返す。
 *
 * @param onBack 引き切ったときに1回。取り消したときは呼ばない。
 */
@Composable
fun rememberBackPull(enabled: Boolean = true, onBack: () -> Unit): BackPull {
    val pull = remember { BackPull() }
    val haptics = LocalRinowaHaptics.current

    PredictiveBackHandler(enabled = enabled) { events ->
        var lastTick = 0f
        try {
            events.collect { event ->
                pull.progress = event.progress
                pull.fromLeftEdge = event.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT

                // はっきり意図的になるまで何も鳴らさない。
                //
                // **戻るのほとんどは軽く払う操作**で、1日に何十回もあり、どれも
                // 迷って決めているものではない。その全部に段階的な触覚を付けるのは
                // 情報ではなく税。最初の1/3は無音にして、速度を落として保持している
                // ところからだけ刻む。追加の情報に意味があるのはそこだけ。
                //
                // 間隔は時間ではなく距離。時間で刻むとモーターが回っているように感じ、
                // 距離で刻むとジェスチャーそのものに感じる。
                if (event.progress >= SILENT_UNTIL && event.progress - lastTick >= TICK_EVERY) {
                    lastTick = event.progress
                    haptics.performProgress(tokenFor(event.progress), strengthFor(event.progress))
                }
            }

            // 最後に自前の触覚は鳴らさない。
            //
            // ジェスチャーが確定した瞬間にシステムが1回鳴らす（アプリが聞いていても
            // いなくても）。そこに「戻るボタンの触覚」を足すと2回になる。ボタンには
            // 正しい（ボタンの裏には何も無い）が、頼んでいない2回は端末の不具合に感じる。
            pull.progress = 0f
            onBack()
        } catch (cancelled: CancellationException) {
            // 取り消された。何も起きない。押下ではなく予測型である意味がそこにある。
            pull.progress = 0f
            throw cancelled
        }
    }

    return pull
}

/** 引きの分だけ、出ていくものを動かす。 */
fun Modifier.backPull(pull: BackPull): Modifier = graphicsLayer {
    val p = pull.progress
    if (p <= 0f) return@graphicsLayer

    val direction = if (pull.fromLeftEdge) 1f else -1f
    scaleX = 1f - p * SHRINK
    scaleY = 1f - p * SHRINK
    translationX = direction * p * size.width * SLIDE
    alpha = 1f - p * FADE
}

/**
 * その時点でどれくらい強く刻むか。
 *
 * 進捗をそのまま渡す最初の版は感じ取れなかった。理由は数式ではなくモーター側で、
 * 全力の1/5を下回る刻みは無音と区別が付かず（前半が無音になる）、触覚は線形より
 * 対数に近いので等間隔の振幅は等しく「弱く」感じる。
 *
 * なので感じ取れる高さから始めて急に上げる。引きの終盤に変化の大半を置く
 * ＝そこが判断の場所でもある。
 *
 * 尺度は0ではなく [SILENT_UNTIL] から測る。後ろ2/3にしか存在しない刻みは、
 * その範囲を目一杯使うべきで、途中から始める必要はない。
 */
private fun strengthFor(progress: Float): Float {
    val past = ((progress - SILENT_UNTIL) / (1f - SILENT_UNTIL)).coerceIn(0f, 1f)
    return (FLOOR + (1f - FLOOR) * past.pow(CURVE)).coerceIn(0f, 1f)
}

/**
 * 半分を越えたら、大きい刻みではなく別の刻みにする。
 *
 * ポケットで温まった手の中では振幅だけでは弱い信号。効果の形を変えることで、
 * 「もう戻れない位置に来た」が音量ではなく情報として届く。
 */
private fun tokenFor(progress: Float): HapticToken =
    if (progress >= THRESHOLD_AT) HapticToken.Threshold else HapticToken.Selection

/**
 * ここまでは何も言わない。
 *
 * 1/3の位置。毎回のように使う短い払いは全部この下を通り、これまでどおりに感じる
 * （画面が変わるときのシステムの刻みだけで、その前には何も無い）。
 */
private const val SILENT_UNTIL = 0.34f

/**
 * 無音でない範囲でだいたい8回。
 *
 * 少ないと死んで感じ、多いとただの振動になる。振動は距離の情報を持たない。
 */
private const val TICK_EVERY = 0.08f

/** これを下回るとモーターは誰にも気付かれない。 */
private const val FLOOR = 0.35f

/** 1より大きい。最初はゆっくり、判断の場所で急になる。 */
private const val CURVE = 1.8f

/** ここから刻みの形が変わる。音量ではなく、引きが決意に変わったということ。 */
private const val THRESHOLD_AT = 0.72f

private const val SHRINK = 0.14f
private const val SLIDE = 0.10f
private const val FADE = 0.25f
