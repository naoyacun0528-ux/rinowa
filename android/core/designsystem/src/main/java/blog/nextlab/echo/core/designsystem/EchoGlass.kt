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
 * A raised surface that swells and lights up under the finger.
 *
 * ## What this is, and what it is not
 *
 * **This is not glass.** Glass is translucent: you see what is behind it, softened. This
 * has none of that. It is an opaque pane, lifted off the page by a shadow, with a lit top
 * edge and a bloom where a finger touches it. The name is aspirational, kept because the
 * intent is to grow into the real thing — but nobody should read this file expecting
 * frosted glass and be confused about why there is no blur.
 *
 * It became *less* glass-like in 0.3.2, when translucency had to go: a partly transparent
 * fill let the elevation shadow's tessellated silhouette read through as an octagon.
 * Shadow and transparency do not stack.
 *
 * Real frosted glass needs the backdrop sampled and blurred behind each pane. On Android
 * that means API 31's `RenderEffect`, and either re-rendering the background into every
 * pane or a window-level blur — which costs real GPU time per surface and would need
 * measuring against the scroll budget before it goes anywhere near a list. That is a
 * later phase, not a tweak to this file.
 *
 * ## No refraction, on purpose
 *
 * Refraction is what usually sells "glass", but it needs API 33, an AGSL shader, and —
 * decisively — something worth bending behind the pane. This app's background is
 * near-uniform warm paper, so refracting it produces cream on cream. It would cost a
 * great deal and show nothing.
 *
 * What actually reads as glass on a light background is simpler and cheaper: an edge that
 * catches light along its top, a face that is not perfectly flat, a soft shadow that lifts
 * it off the page, and — the part that matters most — a surface that answers being
 * touched. Pressing swells it and lights it **where the finger is**, not uniformly.
 *
 * Because none of that needs a shader, it works identically from minSdk 24 upward, and
 * costs GPU time measured in fractions of a millisecond rather than the several a
 * refraction pass would take.
 */
enum class GlassTone {
    /** Large surfaces: conversation cards, panels. */
    Panel,

    /** Small round controls: composer buttons. */
    Control,
}

/**
 * The look of glass without the press behaviour.
 *
 * For surfaces that are glass but should not swell when touched — a text field focuses
 * rather than responds, and a panel behind other controls has no press of its own.
 * [GlassSurface] builds on this and adds the reaction.
 */
@Composable
fun Modifier.glassFace(
    shape: Shape = RoundedCornerShape(EchoDimens.glassCorner),
    elevation: Dp = 3.dp,
): Modifier {
    val colors = EchoTheme.colors
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
    shape: Shape = RoundedCornerShape(EchoDimens.glassCorner),
    tone: GlassTone = GlassTone.Panel,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = EchoTheme.colors

    var pressPoint by remember { mutableStateOf(Offset.Unspecified) }
    var pressed by remember { mutableStateOf(false) }

    // Swelling, not shrinking. A control that shrinks reads as being pushed away; one
    // that inflates reads as responding.
    val swell by animateFloatAsState(
        targetValue = if (pressed) tone.pressedScale else 1f,
        animationSpec = EchoMotion.popSpring(),
        label = "glassSwell",
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = EchoMotion.commitSpring(),
        label = "glassGlow",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = swell
                scaleY = swell
            }
            .glassFace(shape, tone.elevation)
            // After the edge, so the rim lights up along with the face.
            .drawBehind {
                if (glow <= 0f || pressPoint == Offset.Unspecified) return@drawBehind
                // Light where the finger is. A uniform brighten would look like a state
                // change; a local bloom looks like a material reacting to contact.
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
        // Cards are large, so a small proportion is a large absolute movement.
        GlassTone.Panel -> 1.018f
        GlassTone.Control -> 1.075f
    }

private val GlassTone.elevation: Dp
    get() = when (this) {
        GlassTone.Panel -> 5.dp
        GlassTone.Control -> 3.dp
    }
