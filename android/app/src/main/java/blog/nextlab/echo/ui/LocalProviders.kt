package blog.nextlab.echo.ui

import androidx.compose.runtime.staticCompositionLocalOf
import blog.nextlab.echo.core.analytics.Analytics
import blog.nextlab.echo.core.analytics.NoOpAnalytics
import blog.nextlab.echo.data.LocalStickerStore

/**
 * 計測はグローバルに参照せず、上から渡す。プレビューとテストが、何も記録しない
 * 出口を受け取れるように。
 */
val LocalAnalytics = staticCompositionLocalOf<Analytics> { NoOpAnalytics() }

/**
 * 端末のスタンプ置き場。
 *
 * 既定値は用意しない。置き場には Context が要り、空のものを黙って配ると、
 * 配線の間違いが「スタンプが静かに出ない」に化ける。
 */
/**
 * 進行中の通話。無ければ null。
 *
 * 全画面より上に置くのは、通話が始まった会話より長く生きるから（スレッドを離れても
 * 切れてはいけない）。Firebase の無いビルドでは null で、その場合は通話ボタンが
 * 「あるのに壊れている」のではなく単に無い。
 */
val LocalCalls = staticCompositionLocalOf<blog.nextlab.echo.calls.CallController?> { null }

val LocalStickers = staticCompositionLocalOf<LocalStickerStore> {
    error("LocalStickers was not provided")
}

/**
 * 小さな浮き窓（ピクチャーインピクチャー）に描かれている間 true。
 *
 * 引数ではなく composition local にしてある。気にするのは木の中で1つ
 * （通話のオーバーレイ）だけで、Activity からそこまで真偽値を通すと、1箇所に
 * 伝えるために十数個の署名に触ることになる。
 *
 * 実際の意味: **窓は数センチで、触れない。** 操作もラベルも装飾も全部落とす。
 * 残るのは相手の顔。
 */
val LocalInPictureInPicture = staticCompositionLocalOf { false }
