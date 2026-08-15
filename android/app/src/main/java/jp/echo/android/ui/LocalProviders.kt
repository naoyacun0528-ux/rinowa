package jp.echo.android.ui

import androidx.compose.runtime.staticCompositionLocalOf
import jp.echo.android.core.analytics.Analytics
import jp.echo.android.core.analytics.NoOpAnalytics

/**
 * Analytics is provided rather than referenced globally so that previews and tests get a
 * sink that records nothing.
 */
val LocalAnalytics = staticCompositionLocalOf<Analytics> { NoOpAnalytics() }
