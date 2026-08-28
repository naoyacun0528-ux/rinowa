package blog.nextlab.echo.core.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * アプリ全体で使う触覚の実体。
 *
 * 既定は [NoOpHaptics]。`@Preview` のコンポーザブルやテストが、本物の振動子に
 * 触りに行かないように。
 */
val LocalRinowaHaptics: ProvidableCompositionLocal<RinowaHaptics> =
    staticCompositionLocalOf { NoOpHaptics() }

/** `LocalRinowaHaptics.current` の短い書き方。 */
val haptics: RinowaHaptics
    @Composable
    @ReadOnlyComposable
    get() = LocalRinowaHaptics.current
