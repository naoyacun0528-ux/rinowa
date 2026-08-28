package blog.nextlab.echo.core.designsystem

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * すりガラスのバー。下を流れているものをぼかして映す面。
 *
 * 本物の半透明が費用に見合う唯一の場所。上のバーと入力欄は読むものではなく
 * （名前と操作が乗っているだけ）、その裏をメッセージが流れ続ける。裏に、
 * ぼかして見せる価値のあるものが本当にある。
 *
 * 他ではやらない。読む面は不透明のまま。
 *
 * 仕組みと費用: 流れる内容を描くときに [GraphicsLayer] へ記録し、各バーがその同じ
 * レイヤを、自分の裏が合う位置にずらして、ぼかしを載せたレイヤの中で描き直す。
 * つまりバーの範囲だけ1フレームに2回ラスタライズされる。その費用は本物で GPU に乗る。
 * だから固定の2本のバーだけに使い、リストの中では使わない。**必ず前後で測る。**
 *
 * ぼかしには `RenderEffect` が要るので API 31 以上。それ未満では、より不透明な色で
 * 代用する（壊れるのではなく素朴になる）。
 */
@Stable
class BackdropState {
    internal var layer: GraphicsLayer? = null
    internal var sourceOriginInRoot: Offset = Offset.Zero
}

@Composable
fun rememberBackdropState(): BackdropState = remember { BackdropState() }

/** この端末が本当にぼかせるなら true。API 31 未満のバーは色で代用する。 */
val backdropBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * すりガラスのバーが透かして見せる対象として、この内容に印を付ける。
 *
 * 画面全体ではなく、流れる領域に付ける。バー自身を記録すると、バーが自分の出力を
 * 食べることになる。
 */
@Composable
fun Modifier.backdropSource(state: BackdropState, capture: Boolean = true): Modifier {
    val layer = rememberGraphicsLayer()
    SideEffect { state.layer = layer }

    return this
        .onGloballyPositioned { state.sourceOriginInRoot = it.positionInRoot() }
        .drawWithContent {
            if (capture) {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            } else {
                // 画面へ直接描く。Pixel 10 での実測では、スレッドを一度オフスクリーンの
                // レイヤに通すだけでぼかしの大きさに関係なく1フレーム3〜4ms かかる
                // （高いのは記録であってぼかしではない）。なので見返りのあるときだけ払い、
                // フリック中は払わない。
                drawContent()
            }
        }
}

/**
 * 背景がぼけた裏側になっているバー。
 *
 * @param invalidateOn 描画中に呼ぶ。裏のものが動いたときにバーを描き直させるため。
 *   Compose は記録済みレイヤが変わったことを知れないので、依存を明示する
 *   （スクロール位置など、裏の内容を変えるものを読む）。値ではなくラムダなのは、
 *   呼び出し側でスクロール位置を読むとフリックの1フレームごとに画面全体が
 *   再コンポーズされるから。ここで読めば、無効化されるのはこのバーの描画だけ。
 */
@Composable
fun FrostedBar(
    state: BackdropState,
    tint: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    blurRadius: Dp = 18.dp,
    /**
     * 0＝くっきり、1＝完全にすりガラス。
     *
     * ラムダにしてあるのは、焦点の出し入れで呼び出し側が毎フレーム再コンポーズ
     * されないため。作り直されるのはこのバーのレイヤだけ。
     *
     * クロスフェードではなく*半径*を動かすのが要点。半径0のとき、記録した裏側は
     * そのまま透けて見えるものとピクセル単位で同じなので、切り替えに継ぎ目が出ない。
     * ぼけた複製をくっきりした上に重ねてフェードすると、両方が同時に見えて不具合に見える。
     */
    frostAmount: () -> Float = { 1f },
    invalidateOn: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val blurPx = with(density) { blurRadius.toPx() }
    var originInRoot by remember { mutableStateOf(Offset.Zero) }

    Box(modifier) {
        if (backdropBlurSupported) {
            Box(
                Modifier
                    .matchParentSize()
                    .onGloballyPositioned { originInRoot = it.positionInRoot() }
                    .graphicsLayer {
                        clip = true
                        this.shape = shape
                        val radius = blurPx * frostAmount().coerceIn(0f, 1f)
                        // BlurEffect は半径0を受け付けないし、そこではぼかす対象も無い。
                        renderEffect = if (radius > 0.5f) {
                            BlurEffect(radius, radius, TileMode.Clamp)
                        } else {
                            null
                        }
                    }
                    .drawBehind {
                        // 明示した依存。引数の説明を参照。
                        invalidateOn()
                        frostAmount()
                        val layer = state.layer ?: return@drawBehind
                        translate(
                            left = state.sourceOriginInRoot.x - originInRoot.x,
                            top = state.sourceOriginInRoot.y - originInRoot.y,
                        ) {
                            drawLayer(layer)
                        }
                    },
            )
        }

        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    // API 31 未満では何もぼかせないので、色だけで全部を担う必要があり
                    // 濃くする。31 以上では、ぼかしが動いているかどうかに関わらず色は同じ。
                    // だからフリック中にぼかしを止めても変わるのは鮮明さで、不透明度ではない。
                    if (backdropBlurSupported) {
                        tint
                    } else {
                        tint.copy(alpha = (tint.alpha * 2.4f).coerceAtMost(1f))
                    },
                ),
        )

        content()
    }
}
