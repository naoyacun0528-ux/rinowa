package jp.echo.android.core.designsystem

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
 * Frosted bars: surfaces that show a blurred version of whatever scrolls underneath them.
 *
 * This is the one place in the app where real translucency earns its cost. The top bar
 * and the composer are not read — they hold a name and a set of controls — and messages
 * pass behind them continuously. There is genuinely something back there worth showing,
 * softened.
 *
 * Nothing else gets this treatment. Reading surfaces stay opaque.
 *
 * ## How it works, and what it costs
 *
 * The scrolling content is recorded into a [GraphicsLayer] as it draws. Each bar then
 * draws that same layer again, offset so the region behind it lines up, inside a layer
 * carrying a blur. The backdrop is therefore rasterised twice per frame in the bars' area.
 *
 * That cost is real and lands on the GPU, which is why it goes on two fixed bars and not
 * on anything inside a list. **Measure before and after.**
 *
 * Blur needs `RenderEffect`, so API 31+. Below that the bars fall back to a more opaque
 * tint — plain rather than broken.
 */
@Stable
class BackdropState {
    internal var layer: GraphicsLayer? = null
    internal var sourceOriginInRoot: Offset = Offset.Zero
}

@Composable
fun rememberBackdropState(): BackdropState = remember { BackdropState() }

/** True when this device can actually blur; below API 31 the bars use a tint instead. */
val backdropBlurSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Marks this content as the thing the frosted bars show through themselves.
 *
 * Apply to the scrolling region, not to the whole screen: recording the bars into their
 * own backdrop would feed them their own output.
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
                // Straight to the screen. Measured on a Pixel 10, routing the thread
                // through an offscreen layer costs ~3–4 ms per frame no matter how small
                // the blur is — the capture is the expense, not the blur. So it is only
                // paid when it buys something, which is not during a fling.
                drawContent()
            }
        }
}

/**
 * A bar whose background is the blurred backdrop.
 *
 * @param invalidateOn invoked while drawing so the bar repaints when what is behind it
 *   moves. Compose cannot see that the recorded layer changed, so the dependency is
 *   stated explicitly — read the scroll position and anything else that alters the
 *   content underneath. It is a lambda rather than a value on purpose: reading scroll
 *   position at the call site would recompose the whole screen on every frame of a
 *   fling, whereas reading it here invalidates only this bar's drawing.
 */
@Composable
fun FrostedBar(
    state: BackdropState,
    tint: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    blurRadius: Dp = 18.dp,
    /**
     * 0 = sharp, 1 = fully frosted.
     *
     * A lambda, so pulling focus does not recompose the caller on every frame of the
     * transition — only this bar's layer is rebuilt.
     *
     * Animating the *radius* rather than crossfading matters: at radius 0 a captured
     * backdrop is pixel-identical to the content showing straight through, so the
     * transition has no seam. Fading a blurred copy in over a sharp one would show both
     * at once and read as a glitch.
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
                        // BlurEffect rejects a zero radius, and at that point there is
                        // nothing to blur anyway.
                        renderEffect = if (radius > 0.5f) {
                            BlurEffect(radius, radius, TileMode.Clamp)
                        } else {
                            null
                        }
                    }
                    .drawBehind {
                        // Stated dependency: see the parameter's documentation.
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
                    // Below API 31 nothing can be blurred at all, so the tint has to do
                    // the whole job and needs to be heavier. Above it, the tint stays the
                    // same whether or not the blur is currently running, so switching the
                    // blur off during a fling changes sharpness, not opacity.
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
