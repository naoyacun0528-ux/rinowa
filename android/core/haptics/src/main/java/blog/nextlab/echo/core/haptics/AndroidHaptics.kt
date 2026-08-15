package blog.nextlab.echo.core.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android implementation of the haptic design system.
 *
 * Walks the tier ladder described in docs/HAPTIC_DESIGN.md, starting from whatever this
 * device actually supports and stepping down whenever an effect cannot be built or played.
 */
class AndroidHaptics(context: Context) : EchoHaptics {

    private val vibrator: Vibrator? = systemVibrator(context.applicationContext)
    private val powerManager: PowerManager? =
        context.applicationContext.getSystemService(PowerManager::class.java)

    override val capabilities: HapticCapabilities = detectCapabilities(vibrator)

    private val _preferences = MutableStateFlow(HapticPreferences())
    override val preferences: StateFlow<HapticPreferences> = _preferences.asStateFlow()

    private val lastFiredAt = ConcurrentHashMap<HapticToken, Long>()
    private val tokenCounts = ConcurrentHashMap<HapticToken, AtomicInteger>()
    private val suppressedCount = AtomicInteger(0)

    @Volatile
    private var inForeground: Boolean = true

    /**
     * Set when a tier throws at runtime. Some devices advertise support and then reject
     * the effect; when that happens we stop trying that tier for the rest of the session
     * rather than failing silently on every touch.
     */
    @Volatile
    private var degradedTo: HapticTier? = null

    override fun setPreferences(preferences: HapticPreferences) {
        _preferences.value = preferences
    }

    override fun setAppInForeground(inForeground: Boolean) {
        this.inForeground = inForeground
    }

    override fun perform(token: HapticToken) {
        if (isSuppressed(token, respectThrottle = true)) {
            suppressedCount.incrementAndGet()
            return
        }
        lastFiredAt[token] = SystemClock.uptimeMillis()
        tokenCounts.getOrPut(token) { AtomicInteger(0) }.incrementAndGet()
        emit(token, tierFor(token), _preferences.value.effectiveScale)
    }

    override fun previewToken(token: HapticToken, forceTier: HapticTier?) {
        if (vibrator == null || capabilities.bestTier == HapticTier.None) return
        // The Lab intentionally bypasses the throttle and the foreground check, but still
        // respects an explicit "off", because silently vibrating after the user turned
        // haptics off would be a bug the user cannot see.
        val prefs = _preferences.value
        if (!prefs.enabled) return
        val scale = prefs.intensity.scale.coerceAtLeast(HapticIntensity.Subtle.scale)
        emit(token, forceTier ?: tierFor(token), scale)
    }

    override fun tierFor(token: HapticToken): HapticTier {
        val spec = HapticTokens[token]
        val available = degradedTo ?: capabilities.bestTier
        // Larger ordinal == lower tier, so this takes whichever is lower: what the device
        // can do, or what this token should be allowed to do.
        var tier = if (spec.preferredMaxTier.ordinal > available.ordinal) {
            spec.preferredMaxTier
        } else {
            available
        }

        if (tier == HapticTier.PrimitiveRich) {
            val rich = spec.primitivesApi31 ?: spec.primitives
            if (!rich.steps.all { it.primitive in capabilities.supportedPrimitives }) {
                tier = HapticTier.Primitive
            }
        }
        if (tier == HapticTier.Primitive &&
            !spec.primitives.steps.all { it.primitive in capabilities.supportedPrimitives }
        ) {
            tier = HapticTier.Predefined
        }
        if (tier == HapticTier.Predefined && spec.predefined !in capabilities.supportedPredefined) {
            tier = if (capabilities.apiLevel >= Build.VERSION_CODES.O) {
                HapticTier.Waveform
            } else {
                HapticTier.Legacy
            }
        }
        return tier
    }

    override fun cancel() {
        runCatching { vibrator?.cancel() }
    }

    override fun counters(): HapticCounters = HapticCounters(
        perToken = tokenCounts.mapValues { it.value.get() },
        suppressed = suppressedCount.get(),
    )

    override fun resetCounters() {
        tokenCounts.clear()
        suppressedCount.set(0)
    }

    // ---------------------------------------------------------------- suppression

    private fun isSuppressed(token: HapticToken, respectThrottle: Boolean): Boolean {
        if (vibrator == null || capabilities.bestTier == HapticTier.None) return true
        if (!inForeground) return true

        val prefs = _preferences.value
        if (!prefs.enabled || prefs.intensity == HapticIntensity.Off) return true

        // Battery saver: match the device's own expectation rather than fighting it.
        if (powerManager?.isPowerSaveMode == true) return true

        if (respectThrottle) {
            val minInterval = HapticTokens[token].minIntervalMs
            if (minInterval > 0) {
                val last = lastFiredAt[token] ?: 0L
                if (SystemClock.uptimeMillis() - last < minInterval) return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- emission

    private fun emit(token: HapticToken, startTier: HapticTier, scale: Float) {
        val spec = HapticTokens[token]
        var tier: HapticTier? = startTier

        while (tier != null && tier != HapticTier.None) {
            val current = tier
            val played = runCatching { play(spec, current, scale) }.getOrElse { false }
            if (played) return
            // This tier claimed support but did not work. Do not try it again this session.
            degradedTo = current.next
            tier = current.next
        }
    }

    /** @return true when an effect was actually dispatched. */
    private fun play(spec: HapticSpec, tier: HapticTier, scale: Float): Boolean {
        val v = vibrator ?: return false
        val api = Build.VERSION.SDK_INT

        return when (tier) {
            HapticTier.Envelope -> {
                if (api < 36 || !capabilities.supportsEnvelope) return false
                val effect = buildEnvelope(spec.envelope, scale) ?: return false
                dispatch(v, effect)
                true
            }

            HapticTier.PrimitiveRich -> {
                if (api < Build.VERSION_CODES.S) return false
                val primitiveSpec = spec.primitivesApi31 ?: spec.primitives
                if (!primitiveSpec.steps.all { it.primitive in capabilities.supportedPrimitives }) {
                    return false
                }
                val effect = buildComposition(primitiveSpec, scale) ?: return false
                dispatch(v, effect)
                true
            }

            HapticTier.Primitive -> {
                if (api < Build.VERSION_CODES.R) return false
                if (!spec.primitives.steps.all { it.primitive in capabilities.supportedPrimitives }) {
                    return false
                }
                val effect = buildComposition(spec.primitives, scale) ?: return false
                dispatch(v, effect)
                true
            }

            HapticTier.Predefined -> {
                if (api < Build.VERSION_CODES.Q) return false
                // createPredefined() has no scale parameter, so the user's intensity
                // setting cannot be honoured at this tier. Documented, not silently wrong.
                val effect = runCatching {
                    VibrationEffect.createPredefined(spec.predefined.platformId)
                }.getOrNull() ?: return false
                dispatch(v, effect)
                true
            }

            HapticTier.Waveform -> {
                if (api < Build.VERSION_CODES.O) return false
                val effect = buildWaveform(spec.waveform, scale) ?: return false
                dispatch(v, effect)
                true
            }

            HapticTier.Legacy -> {
                @Suppress("DEPRECATION")
                v.vibrate(spec.legacy.timings.toLongArray(), -1)
                true
            }

            HapticTier.None -> false
        }
    }

    private fun dispatch(v: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // USAGE_TOUCH makes the OS treat this as touch feedback, so the user's own
            // haptics setting and Do Not Disturb are respected. Without it, some devices
            // classify our vibrations as notifications and play them when they should not.
            v.vibrate(effect, TOUCH_ATTRIBUTES)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, LEGACY_AUDIO_ATTRIBUTES)
        }
    }

    // ---------------------------------------------------------------- builders

    @RequiresApi(36)
    private fun buildEnvelope(spec: EnvelopeSpec, scale: Float): VibrationEffect? = runCatching {
        var points = spec.points

        if (points.size > capabilities.envelopeMaxPoints) {
            points = points.take(capabilities.envelopeMaxPoints)
        }

        // Clamp each segment to what the device accepts, and stop before the total limit.
        val clamped = ArrayList<EnvelopePoint>(points.size)
        var total = 0L
        for (point in points) {
            val duration = point.durationMs
                .coerceIn(capabilities.envelopeMinPointMs, capabilities.envelopeMaxPointMs)
            if (total + duration > capabilities.envelopeMaxTotalMs) break
            total += duration
            clamped += point.copy(durationMs = duration)
        }
        if (clamped.isEmpty()) return@runCatching null

        // Truncation may have removed the release, and an envelope that does not return to
        // zero is rejected outright.
        if (clamped.last().intensity != 0f) {
            clamped[clamped.lastIndex] = clamped.last().copy(intensity = 0f)
        }

        val builder = VibrationEffect.BasicEnvelopeBuilder()
        builder.setInitialSharpness(spec.initialSharpness.coerceIn(0f, 1f))
        clamped.forEach { point ->
            builder.addControlPoint(
                (point.intensity * scale).coerceIn(0f, 1f),
                point.sharpness.coerceIn(0f, 1f),
                point.durationMs,
            )
        }
        builder.build()
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildComposition(spec: PrimitiveSpec, scale: Float): VibrationEffect? = runCatching {
        val composition = VibrationEffect.startComposition()
        spec.steps.forEach { step ->
            composition.addPrimitive(
                step.primitive.platformId,
                (step.scale * scale).coerceIn(0f, 1f),
                step.delayMs,
            )
        }
        composition.compose()
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildWaveform(spec: WaveformSpec, scale: Float): VibrationEffect? = runCatching {
        val timings = spec.timings.toLongArray()
        val amplitudes = IntArray(spec.amplitudes.size) { index ->
            val raw = spec.amplitudes[index]
            when {
                raw <= 0 -> 0
                capabilities.hasAmplitudeControl -> (raw * scale).toInt().coerceIn(1, 255)
                else -> VibrationEffect.DEFAULT_AMPLITUDE
            }
        }
        VibrationEffect.createWaveform(timings, amplitudes, -1)
    }.getOrNull()

    private companion object {
        @get:RequiresApi(Build.VERSION_CODES.R)
        val TOUCH_ATTRIBUTES: VibrationAttributes by lazy {
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
        }

        val LEGACY_AUDIO_ATTRIBUTES: AudioAttributes by lazy {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
    }
}
