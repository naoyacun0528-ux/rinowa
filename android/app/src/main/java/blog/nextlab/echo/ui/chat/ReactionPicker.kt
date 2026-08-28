package blog.nextlab.echo.ui.chat

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import blog.nextlab.echo.core.model.MessageId
import blog.nextlab.echo.core.designsystem.BackdropState
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.FrostedBar
import blog.nextlab.echo.core.designsystem.preferHighFrameRate
import blog.nextlab.echo.core.model.ReactionPalette
import kotlin.math.roundToInt

/**
 * リアクション選択の座標計算。
 *
 * 測るのではなく計算する。選択が出る時点で指はもう下りていて、当たり判定はレイアウトを
 * 待てない。描画と「指の位置→添字」の対応の両方がここを読むので、2つがずれない。
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

    /** @return [pointer] の下のパレット添字。選択の外なら -1。 */
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
    val messageId: MessageId,
    val anchorBounds: Rect,
    val pillLeftPx: Float,
    val pillTopPx: Float,
    val highlightedIndex: Int,
    val alreadyReactedIndex: Int?,
    /**
     * 何も選ばずに指を離したら true。
     *
     * 離さずに滑らせて選ぶのが速い道だが、それは見つけにくい（「押したままで」と
     * 画面のどこにも書いていない）。なので離しても消さない。開いたまま留まり、
     * 押して選べるようになる。どちらでも動き、速いほうは見つけてもらうためにあって、
     * 必須ではない。
     */
    val latched: Boolean = false,
)

@Composable
fun ReactionPickerOverlay(
    state: ReactionPickerState,
    backdrop: BackdropState,
    onSelect: (Int) -> Unit,
    /** 自分のメッセージのときだけ、列の上に出る。他人のものでは null。 */
    onRetract: (() -> Unit)? = null,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val pill = RoundedCornerShape(percent = 50)

    /**
     * 取り消し。リアクションの上に置く。
     *
     * 同じ長押しに入れているのは、同じ問い（このメッセージをどうしたいか）だから。
     * 2つのジェスチャーに分けると、どちらがどちらかを覚える必要が出る。下ではなく上に
     * 置くのは、稀で重いほうだから。リアクションへ滑る指が通り抜ける場所に置かない。
     */
    if (onRetract != null) {
        // 自分の高さ＋間隔をピクセルで。最初の版は dp のつもりで素の `56f` を使っていて、
        // 2.6倍の画面では必要な距離の1/5しかなく、リアクションの上ではなく重なって出ていた。
        val liftPx = with(LocalDensity.current) { (RETRACT_HEIGHT + RETRACT_GAP).toPx() }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        state.pillLeftPx.roundToInt(),
                        (state.pillTopPx - liftPx).roundToInt(),
                    )
                }
                .height(RETRACT_HEIGHT)
                .shadow(10.dp, RoundedCornerShape(14.dp), clip = false)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceRaised)
                .clickable(onClick = onRetract)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "送信を取り消す", style = type.label, color = colors.danger)
        }
    }

    // 常にすりガラス。スレッドが止まっている間しか出ないので、フリック中に必要な
    // 費用は発生しない。
    FrostedBar(
        state = backdrop,
        tint = colors.barGlassTint,
        shape = pill,
        blurRadius = 22.dp,
        frostAmount = { 1f },
        invalidateOn = { state.highlightedIndex },
        modifier = Modifier
            // 指が選んでいる間だけ画面にあり、その間ずっと動いている。
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
    val colors = RinowaTheme.colors
    val scale by animateFloatAsState(
        targetValue = if (highlighted) 1.45f else 1f,
        animationSpec = RinowaMotion.popSpring(),
        label = "reactionScale",
    )
    val lift by animateFloatAsState(
        targetValue = if (highlighted) -10f else 0f,
        animationSpec = RinowaMotion.popSpring(),
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

private val RETRACT_HEIGHT: Dp = 42.dp

/** 取り消しと、その下のリアクションの間の余白。 */
private val RETRACT_GAP: Dp = 10.dp
