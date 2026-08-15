package blog.nextlab.echo.ui

import androidx.compose.runtime.staticCompositionLocalOf
import blog.nextlab.echo.core.analytics.Analytics
import blog.nextlab.echo.core.analytics.NoOpAnalytics
import blog.nextlab.echo.data.LocalStickerStore

/**
 * Analytics is provided rather than referenced globally so that previews and tests get a
 * sink that records nothing.
 */
val LocalAnalytics = staticCompositionLocalOf<Analytics> { NoOpAnalytics() }

/**
 * The device's sticker store.
 *
 * No default: a store needs a Context, and silently handing out an empty one would turn
 * a wiring mistake into stickers that quietly never render.
 */
val LocalStickers = staticCompositionLocalOf<LocalStickerStore> {
    error("LocalStickers was not provided")
}
