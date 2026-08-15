package jp.echo.android

import android.app.Application
import jp.echo.android.core.analytics.Analytics
import jp.echo.android.core.analytics.AnalyticsUserProperty
import jp.echo.android.core.analytics.DebugAnalytics
import jp.echo.android.core.analytics.HapticTierId
import jp.echo.android.core.analytics.NoOpAnalytics
import jp.echo.android.core.haptics.AndroidHaptics
import jp.echo.android.core.haptics.EchoHaptics
import jp.echo.android.core.haptics.HapticTier

class EchoApplication : Application() {

    lateinit var haptics: EchoHaptics
        private set

    lateinit var analytics: Analytics
        private set

    override fun onCreate() {
        super.onCreate()

        haptics = AndroidHaptics(this)

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
