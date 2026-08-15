package jp.echo.android.core.haptics

/**
 * Data model for a token's feel at every fallback tier.
 *
 * Nothing here talks to the platform. These are plain values so that the whole
 * design can be tuned by editing numbers in [HapticTokens] and rebuilding.
 */

/** Composition primitives, mapped to `VibrationEffect.Composition.PRIMITIVE_*`. */
enum class HapticPrimitive {
    // Available since API 30.
    Click,
    Tick,
    QuickRise,
    SlowRise,
    QuickFall,

    // Added in API 31.
    LowTick,
    Thud,
    Spin,
    ;

    val requiresApi31: Boolean
        get() = this == LowTick || this == Thud || this == Spin
}

/** Predefined effects, mapped to `VibrationEffect.EFFECT_*`. */
enum class HapticPredefined { Tick, Click, DoubleClick, HeavyClick }

/**
 * One control point of an API 36 `BasicEnvelopeBuilder` effect.
 *
 * @param intensity 0..1, normalised amplitude. Scaled by the user's intensity setting.
 * @param sharpness 0..1, normalised "crispness". Deliberately NOT scaled by the user
 *   setting — sharpness carries the token's character, not its strength.
 * @param durationMs time taken to travel from the previous point to this one.
 */
data class EnvelopePoint(
    val intensity: Float,
    val sharpness: Float,
    val durationMs: Long,
)

/** Tier 4 (API 36+): a continuously varying envelope. */
data class EnvelopeSpec(
    val initialSharpness: Float,
    val points: List<EnvelopePoint>,
) {
    init {
        require(points.isNotEmpty()) { "envelope needs at least one point" }
        require(points.last().intensity == 0f) {
            "an envelope must end at zero intensity, otherwise the effect is rejected"
        }
    }
}

/**
 * One step of a composition.
 *
 * @param scale 0..1, scaled by the user's intensity setting.
 * @param delayMs pause before this step starts.
 */
data class PrimitiveStep(
    val primitive: HapticPrimitive,
    val scale: Float,
    val delayMs: Int = 0,
)

/** Tier 3 / Tier 2 (API 30+): a sequence of primitives. */
data class PrimitiveSpec(val steps: List<PrimitiveStep>) {
    /** True when this spec can only run on API 31+. */
    val requiresApi31: Boolean get() = steps.any { it.primitive.requiresApi31 }
}

/** Tier 0 (API 26+): explicit timings and amplitudes. Amplitudes are 0..255. */
data class WaveformSpec(
    val timings: List<Long>,
    val amplitudes: List<Int>,
) {
    init {
        require(timings.size == amplitudes.size) { "timings and amplitudes must be the same length" }
    }
}

/** Tier -1 (API 24+): duration only, no amplitude control. */
data class LegacySpec(val timings: List<Long>)

/**
 * The complete definition of one token.
 *
 * @param minIntervalMs the token will not re-fire within this window. Guards against the
 *   single biggest way to make haptics feel cheap: firing them in a burst.
 */
data class HapticSpec(
    val envelope: EnvelopeSpec,
    /** Tier 2. Must only use primitives that exist on API 30. */
    val primitives: PrimitiveSpec,
    /** Tier 3. Optional richer variant, free to use the API 31 primitives. */
    val primitivesApi31: PrimitiveSpec? = null,
    val predefined: HapticPredefined,
    val waveform: WaveformSpec,
    val legacy: LegacySpec,
    val minIntervalMs: Long = 0L,
    /**
     * The richest tier this token is *allowed* to use, independent of what the device
     * supports.
     *
     * The highest tier is not automatically the best one. Measured on a Pixel 10
     * (API 37), the envelope engine reports a minimum control-point duration of 20 ms,
     * so a two-point envelope cannot be shorter than 40 ms — far longer than a
     * PRIMITIVE_TICK. For tokens whose whole meaning is "instantaneous", the envelope
     * is therefore the *wrong* tool even where it is available, and this caps them at
     * the primitive tier. Tokens with a shape over time keep the envelope.
     */
    val preferredMaxTier: HapticTier = HapticTier.Envelope,
) {
    init {
        require(!primitives.requiresApi31) {
            "HapticSpec.primitives is the API 30 tier and must not use LowTick/Thud/Spin. " +
                "Put those in primitivesApi31 instead."
        }
    }
}
