package blog.nextlab.echo

import android.app.Application
import blog.nextlab.echo.core.analytics.Analytics
import blog.nextlab.echo.core.analytics.AnalyticsUserProperty
import blog.nextlab.echo.core.analytics.DebugAnalytics
import blog.nextlab.echo.core.analytics.HapticTierId
import blog.nextlab.echo.core.analytics.NoOpAnalytics
import blog.nextlab.echo.core.haptics.AndroidHaptics
import blog.nextlab.echo.core.haptics.EchoHaptics
import blog.nextlab.echo.core.haptics.HapticTier
import blog.nextlab.echo.data.LocalStickerStore

class EchoApplication : Application() {

    lateinit var haptics: EchoHaptics
        private set

    lateinit var analytics: Analytics
        private set

    lateinit var stickers: LocalStickerStore
        private set

    override fun onCreate() {
        super.onCreate()

        haptics = AndroidHaptics(this)

        // Materialising the bundled pack is a handful of small files and is done up front
        // so the first sticker never renders as a placeholder. If the pack grows enough
        // to be felt at startup, move it off the main thread rather than making the
        // store nullable.
        stickers = LocalStickerStore(this).apply { installBuiltIns() }

        // Prototype 0 sends nothing anywhere. Debug builds print events to logcat so the
        // schema can be watched while using the app; release builds drop them entirely.
        // Firebase arrives in Prototype 1 — see docs/ROADMAP.md.
        analytics = if (BuildConfig.DEBUG) DebugAnalytics() else NoOpAnalytics()

        analytics.setUserProperty(
            AnalyticsUserProperty.OsApiLevel(android.os.Build.VERSION.SDK_INT),
        )
        analytics.setUserProperty(
            AnalyticsUserProperty.AppVersionCode(BuildConfig.VERSION_CODE),
        )
        analytics.setUserProperty(
            AnalyticsUserProperty.HapticTierProperty(haptics.capabilities.bestTier.toAnalyticsId()),
        )
    }
}

private fun HapticTier.toAnalyticsId(): HapticTierId = when (this) {
    HapticTier.Envelope -> HapticTierId.Envelope
    HapticTier.PrimitiveRich -> HapticTierId.PrimitiveRich
    HapticTier.Primitive -> HapticTierId.Primitive
    HapticTier.Predefined -> HapticTierId.Predefined
    HapticTier.Waveform -> HapticTierId.Waveform
    HapticTier.Legacy -> HapticTierId.Legacy
    HapticTier.None -> HapticTierId.None
}
