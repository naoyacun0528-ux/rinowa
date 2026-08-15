package jp.echo.android.core.haptics

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  THE TUNING TABLE                                                        │
 * │                                                                          │
 * │  This is the single file to edit after feeling something on a device.    │
 * │  Nothing else in the app contains haptic magic numbers.                  │
 * │                                                                          │
 * │  Workflow:  feel it on the Pixel  ->  say what is wrong  ->  change a    │
 * │             number here  ->  rebuild.                                    │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * Values mirror docs/HAPTIC_DESIGN.md. Keep both in sync when tuning.
 */
object HapticTokens {

    private val selection = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.60f,
            points = listOf(
                EnvelopePoint(0.25f, 0.60f, 8),
                EnvelopePoint(0.00f, 0.60f, 12),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.30f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(8), amplitudes = listOf(40)),
        legacy = LegacySpec(listOf(0, 8)),
        minIntervalMs = 40,
        // Must stay tiny. See HapticSpec.preferredMaxTier.
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    private val navigation = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.40f,
            points = listOf(
                EnvelopePoint(0.35f, 0.40f, 10),
                EnvelopePoint(0.00f, 0.35f, 18),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.50f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(10), amplitudes = listOf(60)),
        legacy = LegacySpec(listOf(0, 10)),
        minIntervalMs = 100,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    private val softConfirm = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.50f,
            points = listOf(
                EnvelopePoint(0.45f, 0.50f, 10),
                EnvelopePoint(0.00f, 0.50f, 20),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Click, 0.35f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(12), amplitudes = listOf(80)),
        legacy = LegacySpec(listOf(0, 12)),
        minIntervalMs = 60,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /** Sharp attack, fast decay, no tail: the message left the finger. */
    private val send = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.85f,
            points = listOf(
                EnvelopePoint(0.70f, 0.90f, 6),
                EnvelopePoint(0.00f, 0.70f, 22),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Click, 0.65f))),
        predefined = HapticPredefined.Click,
        waveform = WaveformSpec(timings = listOf(14), amplitudes = listOf(150)),
        legacy = LegacySpec(listOf(0, 16)),
        minIntervalMs = 120,
        // A sharp attack with no tail. The envelope floor would stretch this past the
        // point where it still reads as "gone".
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /** The hardest, most definite token. It must be recognisable without looking. */
    private val threshold = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.90f,
            points = listOf(
                EnvelopePoint(0.90f, 1.00f, 5),
                EnvelopePoint(0.00f, 0.80f, 16),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Click, 0.85f))),
        predefined = HapticPredefined.Click,
        waveform = WaveformSpec(timings = listOf(12), amplitudes = listOf(200)),
        legacy = LegacySpec(listOf(0, 18)),
        minIntervalMs = 80,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /** Deliberately a softer echo of [threshold] — same shape, less authority. */
    private val thresholdRelease = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.70f,
            points = listOf(
                EnvelopePoint(0.45f, 0.70f, 5),
                EnvelopePoint(0.00f, 0.60f, 14),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.55f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(10), amplitudes = listOf(90)),
        legacy = LegacySpec(listOf(0, 8)),
        minIntervalMs = 80,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /** Slight bloom: rise into a commit. */
    private val reaction = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.30f,
            points = listOf(
                EnvelopePoint(0.50f, 0.45f, 12),
                EnvelopePoint(0.75f, 0.70f, 10),
                EnvelopePoint(0.00f, 0.60f, 24),
            ),
        ),
        primitives = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.QuickRise, 0.40f),
                PrimitiveStep(HapticPrimitive.Click, 0.55f),
            ),
        ),
        predefined = HapticPredefined.Click,
        waveform = WaveformSpec(timings = listOf(8, 16), amplitudes = listOf(100, 200)),
        legacy = LegacySpec(listOf(0, 24)),
        minIntervalMs = 60,
    )

    /** Rising pair. The contrast with [error] is what carries the meaning. */
    private val success = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.50f,
            points = listOf(
                EnvelopePoint(0.40f, 0.50f, 10),
                EnvelopePoint(0.00f, 0.50f, 12),
                EnvelopePoint(0.00f, 0.50f, 50),
                EnvelopePoint(0.70f, 0.70f, 10),
                EnvelopePoint(0.00f, 0.60f, 18),
            ),
        ),
        primitives = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.Click, 0.45f),
                PrimitiveStep(HapticPrimitive.Click, 0.75f, delayMs = 60),
            ),
        ),
        predefined = HapticPredefined.DoubleClick,
        waveform = WaveformSpec(timings = listOf(12, 60, 14), amplitudes = listOf(110, 0, 190)),
        legacy = LegacySpec(listOf(0, 14, 60, 18)),
        minIntervalMs = 150,
    )

    /** Falling pair. */
    private val warning = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.70f,
            points = listOf(
                EnvelopePoint(0.75f, 0.70f, 8),
                EnvelopePoint(0.00f, 0.60f, 10),
                EnvelopePoint(0.00f, 0.60f, 90),
                EnvelopePoint(0.45f, 0.50f, 10),
                EnvelopePoint(0.00f, 0.50f, 16),
            ),
        ),
        primitives = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.Click, 0.70f),
                PrimitiveStep(HapticPrimitive.Click, 0.45f, delayMs = 100),
            ),
        ),
        predefined = HapticPredefined.DoubleClick,
        waveform = WaveformSpec(timings = listOf(12, 100, 12), amplitudes = listOf(190, 0, 110)),
        legacy = LegacySpec(listOf(0, 18, 100, 12)),
        minIntervalMs = 150,
    )

    /**
     * Not "stronger" — congested. Three equal knocks that stop dead, with a low
     * sharpness so it reads as dull rather than crisp. It should feel *blocked*.
     */
    private val error = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.40f,
            points = listOf(
                EnvelopePoint(0.80f, 0.40f, 8),
                EnvelopePoint(0.00f, 0.35f, 8),
                EnvelopePoint(0.00f, 0.35f, 42),
                EnvelopePoint(0.80f, 0.40f, 8),
                EnvelopePoint(0.00f, 0.35f, 8),
                EnvelopePoint(0.00f, 0.35f, 42),
                EnvelopePoint(0.60f, 0.30f, 10),
                EnvelopePoint(0.00f, 0.30f, 14),
            ),
        ),
        primitives = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.Click, 0.85f),
                PrimitiveStep(HapticPrimitive.Click, 0.85f, delayMs = 50),
                PrimitiveStep(HapticPrimitive.Click, 0.60f, delayMs = 50),
            ),
        ),
        // LowTick lands duller than Click, which is exactly what "blocked" wants.
        primitivesApi31 = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.Click, 0.85f),
                PrimitiveStep(HapticPrimitive.Click, 0.85f, delayMs = 50),
                PrimitiveStep(HapticPrimitive.LowTick, 1.00f, delayMs = 50),
            ),
        ),
        predefined = HapticPredefined.DoubleClick,
        waveform = WaveformSpec(
            timings = listOf(14, 46, 14, 46, 18),
            amplitudes = listOf(200, 0, 200, 0, 140),
        ),
        legacy = LegacySpec(listOf(0, 18, 46, 18, 46, 22)),
        minIntervalMs = 200,
    )

    /** Low, heavy, with a tail. Must never be mistaken for [send] or [success]. */
    private val destructive = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.15f,
            points = listOf(
                EnvelopePoint(0.85f, 0.20f, 18),
                EnvelopePoint(0.00f, 0.15f, 45),
            ),
        ),
        // API 30 has neither THUD nor LOW_TICK, so weight is approximated by a hit
        // that immediately falls away.
        primitives = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.Click, 0.90f),
                PrimitiveStep(HapticPrimitive.QuickFall, 1.00f),
            ),
        ),
        primitivesApi31 = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Thud, 0.90f))),
        predefined = HapticPredefined.HeavyClick,
        waveform = WaveformSpec(timings = listOf(35), amplitudes = listOf(200)),
        legacy = LegacySpec(listOf(0, 40)),
        minIntervalMs = 200,
    )

    private val table: Map<HapticToken, HapticSpec> = mapOf(
        HapticToken.Selection to selection,
        HapticToken.Navigation to navigation,
        HapticToken.SoftConfirm to softConfirm,
        HapticToken.Send to send,
        HapticToken.Threshold to threshold,
        HapticToken.ThresholdRelease to thresholdRelease,
        HapticToken.Reaction to reaction,
        HapticToken.Success to success,
        HapticToken.Warning to warning,
        HapticToken.Error to error,
        HapticToken.Destructive to destructive,
    )

    operator fun get(token: HapticToken): HapticSpec =
        table.getValue(token)
}
