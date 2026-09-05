package blog.nextlab.echo.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal class PickerGeometry(
    val screenWidthPx: Float,
    val pillWidthPx: Float,
    val pillHeightPx: Float,
    val marginPx: Float,
    val gapPx: Float,
    val itemSizePx: Float,
    val itemGapPx: Float,
    val innerPaddingPx: Float,
    val toleranceAbovePx: Float,
    val toleranceBelowPx: Float,
    val topLimitPx: Float,
    /** 滑らせがリアクションの選択とみなされるまでの距離。 */
    val engageSlopPx: Float,
)

@Composable
internal fun rememberPickerGeometry(): PickerGeometry {
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return remember(density, screenWidthDp) {
        with(density) {
            PickerGeometry(
                screenWidthPx = screenWidthDp.dp.toPx(),
                pillWidthPx = ReactionPickerMetrics.width().toPx(),
                pillHeightPx = ReactionPickerMetrics.height.toPx(),
                marginPx = ReactionPickerMetrics.screenMargin.toPx(),
                gapPx = ReactionPickerMetrics.gapAboveAnchor.toPx(),
                itemSizePx = ReactionPickerMetrics.itemSize.toPx(),
                itemGapPx = ReactionPickerMetrics.itemGap.toPx(),
                innerPaddingPx = ReactionPickerMetrics.innerPadding.toPx(),
                toleranceAbovePx = 70.dp.toPx(),
                // 150.dp では、指が乗っている吹き出しまで覆ってしまった。
                toleranceBelowPx = 96.dp.toPx(),
                topLimitPx = 76.dp.toPx(),
                engageSlopPx = 18.dp.toPx(),
            )
        }
    }
}
