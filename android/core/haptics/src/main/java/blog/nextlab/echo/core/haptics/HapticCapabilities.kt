package blog.nextlab.echo.core.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 段階の梯子。上ほど豊か。
 *
 * 天井は API 36 のエンベロープで、そこから minSdk 24 まで段階的に落とす。
 * docs/HAPTIC_DESIGN.md。
 */
enum class HapticTier {
    /** T4 — API 36+、`VibrationEffect.BasicEnvelopeBuilder`。 */
    Envelope,

    /** T3 — API 31+、LOW_TICK / THUD / SPIN を含む合成。 */
    PrimitiveRich,

    /** T2 — API 30、最初からあるプリミティブだけの合成。 */
    Primitive,

    /** T1 — API 29、`createPredefined`。 */
    Predefined,

    /** T0 — API 26、波形を明示。 */
    Waveform,

    /** T-1 — API 24、長さだけ。 */
    Legacy,

    /** 振動子が無いか、振動が使えない。 */
    None,
    ;

    /** 1つ下の段。梯子の底なら `null`。 */
    val next: HapticTier?
        get() = entries.getOrNull(ordinal + 1)
}

/**
 * この端末が実際にできること。
 *
 * API のレベルだけでは足りない。API 36 で動いていて、エンベロープに対応しない
 * 振動子を積んだ端末はありうる。なのでどの段も、本当の能力の問い合わせで判定する。
 */
data class HapticCapabilities(
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val supportsEnvelope: Boolean,
    val envelopeMaxPoints: Int,
    val envelopeMinPointMs: Long,
    val envelopeMaxPointMs: Long,
    val envelopeMaxTotalMs: Long,
    val supportedPrimitives: Set<HapticPrimitive>,
    val supportedPredefined: Set<HapticPredefined>,
    val bestTier: HapticTier,
    val apiLevel: Int,
) {
    companion object {
        /** 能力の問い合わせに答えない端末のための、無難な既定値。 */
        internal const val DEFAULT_ENVELOPE_MAX_POINTS = 16
        internal const val DEFAULT_ENVELOPE_MIN_POINT_MS = 1L
        internal const val DEFAULT_ENVELOPE_MAX_POINT_MS = 1_000L
        internal const val DEFAULT_ENVELOPE_MAX_TOTAL_MS = 3_000L
    }
}

internal fun systemVibrator(context: Context): Vibrator? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}.getOrNull()

internal fun detectCapabilities(vibrator: Vibrator?): HapticCapabilities {
    val api = Build.VERSION.SDK_INT

    if (vibrator == null || !runCatching { vibrator.hasVibrator() }.getOrDefault(false)) {
        return HapticCapabilities(
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
            apiLevel = api,
        )
    }

    val hasAmplitude = runCatching { vibrator.hasAmplitudeControl() }.getOrDefault(false)

    var supportsEnvelope = false
    var maxPoints = HapticCapabilities.DEFAULT_ENVELOPE_MAX_POINTS
    var minPointMs = HapticCapabilities.DEFAULT_ENVELOPE_MIN_POINT_MS
    var maxPointMs = HapticCapabilities.DEFAULT_ENVELOPE_MAX_POINT_MS
    var maxTotalMs = HapticCapabilities.DEFAULT_ENVELOPE_MAX_TOTAL_MS

    if (api >= 36) {
        supportsEnvelope = runCatching { vibrator.areEnvelopeEffectsSupported() }.getOrDefault(false)
        if (supportsEnvelope) {
            runCatching { vibrator.envelopeEffectInfo }.getOrNull()?.let { info ->
                if (info.maxSize > 0) maxPoints = info.maxSize
                if (info.minControlPointDurationMillis > 0) minPointMs = info.minControlPointDurationMillis
                if (info.maxControlPointDurationMillis > 0) maxPointMs = info.maxControlPointDurationMillis
                if (info.maxDurationMillis > 0) maxTotalMs = info.maxDurationMillis
            }
        }
    }

    val primitives = if (api >= 30) {
        val candidates = HapticPrimitive.entries.filter { api >= 31 || !it.requiresApi31 }
        val ids = candidates.map { it.platformId }.toIntArray()
        val results = runCatching { vibrator.arePrimitivesSupported(*ids) }.getOrNull()
        if (results == null || results.size != candidates.size) {
            emptySet()
        } else {
            candidates.filterIndexed { index, _ -> results[index] }.toSet()
        }
    } else {
        emptySet()
    }

    val predefined = when {
        api >= 30 -> {
            val candidates = HapticPredefined.entries
            val ids = candidates.map { it.platformId }.toIntArray()
            val results = runCatching { vibrator.areEffectsSupported(*ids) }.getOrNull()
            if (results == null || results.size != candidates.size) {
                // 対応が不明でも createPredefined() は動く。内部で勝手に落ちるだけ。
                candidates.toSet()
            } else {
                candidates.filterIndexed { index, _ ->
                    results[index] != Vibrator.VIBRATION_EFFECT_SUPPORT_NO
                }.toSet()
            }
        }
        // API 29 には createPredefined() はあるが、対応を尋ねる方法が無い。
        api >= 29 -> HapticPredefined.entries.toSet()
        else -> emptySet()
    }

    val hasBasePrimitives = HapticPrimitive.Click in primitives && HapticPrimitive.Tick in primitives

    val tier = when {
        api >= 36 && supportsEnvelope -> HapticTier.Envelope
        api >= 31 && hasBasePrimitives -> HapticTier.PrimitiveRich
        api >= 30 && hasBasePrimitives -> HapticTier.Primitive
        api >= 29 && predefined.isNotEmpty() -> HapticTier.Predefined
        api >= 26 -> HapticTier.Waveform
        else -> HapticTier.Legacy
    }

    return HapticCapabilities(
        hasVibrator = true,
        hasAmplitudeControl = hasAmplitude,
        supportsEnvelope = supportsEnvelope,
        envelopeMaxPoints = maxPoints,
        envelopeMinPointMs = minPointMs,
        envelopeMaxPointMs = maxPointMs,
        envelopeMaxTotalMs = maxTotalMs,
        supportedPrimitives = primitives,
        supportedPredefined = predefined,
        bestTier = tier,
        apiLevel = api,
    )
}

internal val HapticPrimitive.platformId: Int
    get() = when (this) {
        HapticPrimitive.Click -> VibrationEffect.Composition.PRIMITIVE_CLICK
        HapticPrimitive.Tick -> VibrationEffect.Composition.PRIMITIVE_TICK
        HapticPrimitive.QuickRise -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
        HapticPrimitive.SlowRise -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        HapticPrimitive.QuickFall -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        HapticPrimitive.LowTick -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        HapticPrimitive.Thud -> VibrationEffect.Composition.PRIMITIVE_THUD
        HapticPrimitive.Spin -> VibrationEffect.Composition.PRIMITIVE_SPIN
    }

internal val HapticPredefined.platformId: Int
    get() = when (this) {
        HapticPredefined.Tick -> VibrationEffect.EFFECT_TICK
        HapticPredefined.Click -> VibrationEffect.EFFECT_CLICK
        HapticPredefined.DoubleClick -> VibrationEffect.EFFECT_DOUBLE_CLICK
        HapticPredefined.HeavyClick -> VibrationEffect.EFFECT_HEAVY_CLICK
    }
