package jp.echo.android.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.echo.android.core.designsystem.BackdropState
import jp.echo.android.core.designsystem.EchoMotion
import jp.echo.android.core.designsystem.EchoTheme
import jp.echo.android.core.designsystem.FrostedBar
import jp.echo.android.core.designsystem.preferHighFrameRate
import jp.echo.android.model.ReactionPalette
import kotlin.math.roundToInt

/**
 * Geometry for the reaction picker.
 *
 * Deliberately computed rather than measured: the finger is already down when the picker
 * appears, so hit-testing cannot wait for a layout pass. Both the drawing and the
 * pointer-to-index mapping read from here, which keeps them from drifting apart.
 */
object ReactionPickerMetrics {
    val itemSize: Dp = 40.dp
    val itemGap: Dp = 2.dp
    val innerPadding: Dp = 8.dp
    val height: Dp = 56.dp
    val gapAboveAnchor: Dp = 10.dp
    val screenMargin: Dp = 12.dp

    val count: Int get() = ReactionPalette.emoji.size

    fun width(): Dp = innerPadding * 2 + itemSize * count + itemGap * (count - 1)

    fun leftPx(anchorCenterX: Float, widthPx: Float, screenWidthPx: Float, marginPx: Float): Float {
        val max = (screenWidthPx - widthPx - marginPx).coerceAtLeast(marginPx)
        return (anchorCenterX - widthPx / 2f).coerceIn(marginPx, max)
    }

    /** @return palette index under [pointer], or -1 when the finger is off the picker. */
    fun indexFor(
        pointer: Offset,
        pillLeftPx: Float,
        pillTopPx: Float,
        pillHeightPx: Float,
        itemSizePx: Float,
        itemGapPx: Float,
        innerPaddingPx: Float,
        toleranceAbovePx: Float,
        toleranceBelowPx: Float,
    ): Int {
        if (pointer.y < pillTopPx - toleranceAbovePx) return -1
        if (pointer.y > pillTopPx + pillHeightPx + toleranceBelowPx) return -1

        val local = pointer.x - pillLeftPx - innerPaddingPx
        if (local < 0f) return -1
        val step = itemSizePx + itemGapPx
        val index = (local / step).toInt()
        return if (index in 0 until count) index else -1
    }
}

@Immutable
data class ReactionPickerState(
    val messageId: Long,
    val anchorBounds: Rect,
    val pillLeftPx: Float,
    val pillTopPx: Float,
    val highlightedIndex: Int,
    val alreadyReactedIndex: Int?,
    /**
     * True once the finger has lifted without choosing anything.
     *
     * Sliding to a reaction without lifting is the fast path, but it is not discoverable
     * — nothing on screen says "keep holding". So lifting does not dismiss: the picker
     * latches open and becomes tappable. Both ways work, and the quick one is there to
     * be found rather than required.
     */
    val latched: Boolean = false,
)

@Composable
fun ReactionPickerOverlay(
    state: ReactionPickerState,
    backdrop: BackdropState,
    onSelect: (Int) -> Unit,
) {
    val colors = EchoTheme.colors
    val pill = RoundedCornerShape(percent = 50)

    // Always frosted. It only ever appears while the thread is still, so the capture
    // costs nothing that a fling would have needed.
    FrostedBar(
        state = backdrop,
        tint = colors.barGlassTint,
        shape = pill,
        blurRadius = 22.dp,
        frostAmount = { 1f },
        invalidateOn = { state.highlightedIndex },
        modifier = Modifier
            // On screen only while the finger is choosing, and animating the whole time.
            .preferHighFrameRate(true)
            .offset { IntOffset(state.pillLeftPx.roundToInt(), state.pillTopPx.roundToInt()) }
            .height(ReactionPickerMetrics.height)
            .width(ReactionPickerMetrics.width())
            .shadow(14.dp, pill, clip = false),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = ReactionPickerMetrics.innerPadding),
        ) {
            ReactionPalette.emoji.forEachIndexed { index, emoji ->
                if (index > 0) Spacer(Modifier.width(ReactionPickerMetrics.itemGap))
                ReactionPickerItem(
                    emoji = emoji,
                    highlighted = index == state.highlightedIndex,
                    alreadyChosen = index == state.alreadyReactedIndex,
                    onClick = if (state.latched) {
                        { onSelect(index) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun ReactionPickerItem(
    emoji: String,
    highlighted: Boolean,
    alreadyChosen: Boolean,
    onClick: (() -> Unit)?,
) {
    val colors = EchoTheme.colors
    val scale by animateFloatAsState(
        targetValue = if (highlighted) 1.45f else 1f,
        animationSpec = EchoMotion.popSpring(),
        label = "reactionScale",
    )
    val lift by animateFloatAsState(
        targetValue = if (highlighted) -10f else 0f,
        animationSpec = EchoMotion.popSpring(),
        label = "reactionLift",
    )

    Box(
        modifier = Modifier
            .size(ReactionPickerMetrics.itemSize)
            .offset { IntOffset(0, lift.roundToInt()) }
            .clip(RoundedCornerShape(percent = 50))
            .then(if (alreadyChosen) Modifier.background(colors.accentSoft) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = 24.sp, modifier = Modifier.scale(scale))
    }
}
