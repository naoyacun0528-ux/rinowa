package blog.nextlab.echo.core.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app-wide haptics instance.
 *
 * Defaults to [NoOpHaptics] so that `@Preview` composables and tests never try to reach a
 * real vibrator.
 */
val LocalEchoHaptics: ProvidableCompositionLocal<EchoHaptics> =
    staticCompositionLocalOf { NoOpHaptics() }

/** Shorthand for `LocalEchoHaptics.current`. */
val haptics: EchoHaptics
    @Composable
    @ReadOnlyComposable
    get() = LocalEchoHaptics.current
