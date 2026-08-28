package blog.nextlab.echo.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 指の下でふくらんで光る、浮いた面。
 *
 * **これはガラスではない。** ガラスは半透明で、裏にあるものが柔らかく見える。これには
 * それが無い。影で紙から浮かせた不透明な板で、上の縁が光り、触れたところが明るくなる。
 * 名前は目標として残してあるが、ぼかしが無いことに戸惑わないよう先に書いておく。
 *
 * 0.3.2 でむしろガラスから遠ざかった。半透明をやめる必要があったため（半透明の面だと、
 * 影のシルエットの多角形が透けて八角形に見えた）。影と透明は重ねられない。
 *
 * 本物のすりガラスには、板ごとに裏を写してぼかす必要がある。Android では API 31 の
 * `RenderEffect` を使い、板ごとに背景を描き直すかウィンドウ全体をぼかすことになる。
 * どちらも面ごとに実測できる GPU 時間を食うので、リストに近づける前にスクロールの
 * 予算と突き合わせる必要がある。それは後の段階の話で、このファイルの調整ではない。
 *
 * 屈折はわざと入れていない。ガラスらしさを一番売るのは屈折だが、API 33 と AGSL の
 * シェーダが要り、そして決定的なことに、板の裏に曲げる価値のあるものが要る。
 * このアプリの背景はほぼ均一な温かい紙色なので、屈折させてもクリーム色の上に
 * クリーム色が出るだけ。高くついて何も見えない。
 *
 * 明るい背景でガラスに見えるのはもっと単純で安いもの。上の縁が光を拾い、面が
 * 完全には平らでなく、柔らかい影で紙から浮き、そして一番効くのが、触れたことに
 * 応えること。押すとふくらみ、**指のある場所が**光る（全体ではなく）。
 *
 * シェーダを使わないので minSdk 24 から同じように動き、GPU 時間はミリ秒未満。
 * 屈折の処理なら数ミリ秒かかる。
 */
enum class GlassTone {
    /** 大きい面。会話のカードやパネル。 */
    Panel,

    /** 小さい丸い操作。入力欄のボタン。 */
    Control,
}

/**
 * 押したときの反応が無い、見た目だけのガラス。
 *
 * ガラスではあるが触れてふくらむべきでない面のため（入力欄は焦点が当たるだけで
 * 応えない、他の操作の裏にあるパネルには自分の押下が無い）。[GlassSurface] は
 * これに反応を足したもの。
 */
@Composable
fun Modifier.glassFace(
    shape: Shape = RoundedCornerShape(RinowaDimens.glassCorner),
    elevation: Dp = 3.dp,
): Modifier {
    val colors = RinowaTheme.colors
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = colors.glassShadow,
            spotColor = colors.glassShadow,
        )
        .clip(shape)
        .background(Brush.verticalGradient(listOf(colors.glassFaceHigh, colors.glassFace)))
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(colors.glassEdge, colors.glassEdgeLow)),
            shape = shape,
        )
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(RinowaDimens.glassCorner),
    tone: GlassTone = GlassTone.Panel,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = RinowaTheme.colors

    var pressPoint by remember { mutableStateOf(Offset.Unspecified) }
    var pressed by remember { mutableStateOf(false) }

    // 縮むのではなくふくらむ。縮む操作は押しのけられたように読め、
    // ふくらむ操作は応えたように読める。
    val swell by animateFloatAsState(
        targetValue = if (pressed) tone.pressedScale else 1f,
        animationSpec = RinowaMotion.popSpring(),
        label = "glassSwell",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = RinowaMotion.commitSpring(),
        label = "glassGlow",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = swell
                scaleY = swell
            }
            .glassFace(shape, tone.elevation)
            // 縁のあと。面と一緒に縁も光るように。
            .drawBehind {
                if (glow <= 0f || pressPoint == Offset.Unspecified) return@drawBehind
                // 指のある場所を光らせる。全体を明るくすると状態の切り替えに見え、
                // 局所的に光ると材質が接触に反応したように見える。
                val reach = size.maxDimension * (0.45f + 0.35f * glow)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.glassGlow.copy(alpha = 0.34f * glow),
                            colors.glassGlow.copy(alpha = 0.10f * glow),
                            Color.Transparent,
                        ),
                        center = pressPoint,
                        radius = reach,
                    ),
                    radius = reach,
                    center = pressPoint,
                )
            }
            .then(
                if (enabled && (onClick != null || onLongClick != null)) {
                    Modifier.pointerInput(onClick, onLongClick) {
                        detectTapGestures(
                            onPress = { offset ->
                                pressPoint = offset
                                pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = { onClick?.invoke() },
                            onLongPress = { onLongClick?.invoke() },
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = contentAlignment,
        content = content,
    )
}

private val GlassTone.pressedScale: Float
    get() = when (this) {
        // カードは大きいので、割合が小さくても実際の移動量は大きい。
        GlassTone.Panel -> 1.018f
        GlassTone.Control -> 1.075f
    }

private val GlassTone.elevation: Dp
    get() = when (this) {
        GlassTone.Panel -> 5.dp
        GlassTone.Control -> 3.dp
    }
