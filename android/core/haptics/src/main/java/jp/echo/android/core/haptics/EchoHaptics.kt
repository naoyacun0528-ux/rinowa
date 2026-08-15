package jp.echo.android.core.haptics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How strongly haptics are played. Scales amplitude only — never duration. */
enum class HapticIntensity(val scale: Float) {
    Off(0.0f),
    Subtle(0.6f),
    Normal(1.0f),
    Strong(1.35f),
}

data class HapticPreferences(
    val enabled: Boolean = true,
    val intensity: HapticIntensity = HapticIntensity.Normal,
) {
    /** The multiplier applied to amplitudes, or 0 when haptics are off. */
    val effectiveScale: Float get() = if (enabled) intensity.scale else 0f
}

/**
 * Per-session counts, drained into the analytics summary.
 *
 * Aggregated deliberately: sending one analytics event per haptic would both flood the
 * pipeline and make an individual user's interaction sequence reconstructible.
 * See docs/ANALYTICS_SCHEMA.md.
 */
data class HapticCounters(
    val perToken: Map<HapticToken, Int> = emptyMap(),
    val suppressed: Int = 0,
)

/**
 * The one way the app produces haptics.
 *
 * Screens call [perform] with a meaning. Nothing outside this module touches
 * `Vibrator` or `performHapticFeedback`.
 */
interface EchoHaptics {

    val capabilities: HapticCapabilities

    val preferences: StateFlow<HapticPreferences>

    fun setPreferences(preferences: HapticPreferences)

    /** Plays [token], honouring the user's settings and the per-token throttle. */
    fun perform(token: HapticToken)

    /**
     * Plays [token] ignoring the throttle, optionally forcing a specific tier.
     *
     * Only for the Haptic Lab, where the point is to feel tiers back to back.
     */
    fun previewToken(token: HapticToken, forceTier: HapticTier? = null)

    /** Which tier this device will actually use for [token]. */
    fun tierFor(token: HapticToken): HapticTier

    /** Haptics never fire while the app is in the background. */
    fun setAppInForeground(inForeground: Boolean)

    fun cancel()

    fun counters(): HapticCounters

    fun resetCounters()
}

// Semantic call sites read better than perform(HapticToken.X) at every touch point.
fun EchoHaptics.selection() = perform(HapticToken.Selection)
fun EchoHaptics.navigation() = perform(HapticToken.Navigation)
fun EchoHaptics.softConfirm() = perform(HapticToken.SoftConfirm)
fun EchoHaptics.send() = perform(HapticToken.Send)
fun EchoHaptics.threshold() = perform(HapticToken.Threshold)
fun EchoHaptics.thresholdRelease() = perform(HapticToken.ThresholdRelease)
fun EchoHaptics.reaction() = perform(HapticToken.Reaction)
fun EchoHaptics.success() = perform(HapticToken.Success)
fun EchoHaptics.warning() = perform(HapticToken.Warning)
fun EchoHaptics.error() = perform(HapticToken.Error)
fun EchoHaptics.destructive() = perform(HapticToken.Destructive)

/** Used by `@Preview`s and unit tests. */
class NoOpHaptics : EchoHaptics {
    override val capabilities = HapticCapabilities(
        hasVibrator = false,
        hasAmplitudeControl = false,
        supportsEnvelope = false,
        envelopeMaxPoints = 0,
        envelopeMinPointMs = 0,
        envelopeMaxPointMs = 0,
        envelopeMaxTotalMs = 0,
        supportedPrimitives = emptySet(),
        supportedPredefined = emptySet(),
        bestTier = HapticTier.None,
        apiLevel = 0,
    )

    private val _preferences = MutableStateFlow(HapticPreferences())
    override val preferences: StateFlow<HapticPreferences> = _preferences.asStateFlow()

    override fun setPreferences(preferences: HapticPreferences) {
        _preferences.value = preferences
    }

    override fun perform(token: HapticToken) = Unit
    override fun previewToken(token: HapticToken, forceTier: HapticTier?) = Unit
    override fun tierFor(token: HapticToken): HapticTier = HapticTier.None
    override fun setAppInForeground(inForeground: Boolean) = Unit
    override fun cancel() = Unit
    override fun counters(): HapticCounters = HapticCounters()
    override fun resetCounters() = Unit
}
