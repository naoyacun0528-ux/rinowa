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
 * ## Which knob raises the pitch
 *
 * Only the envelope tier exposes frequency, as `sharpness` (0 = dull and low, 1 = crisp
 * and high). At the primitive tier the OS fixes the frequency per primitive, so the only
 * way to sound higher is to pick a lighter primitive:
 *
 *     TICK  >  CLICK  >  LOW_TICK / THUD          (higher ......... lower)
 *
 * Sharpness is deliberately *not* scaled by the user's intensity setting: it carries the
 * token's character, not its strength.
 *
 * Values mirror docs/HAPTIC_DESIGN.md. Keep both in sync when tuning.
 */
object HapticTokens {

    private val selection = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.72f,
            points = listOf(
                EnvelopePoint(0.25f, 0.72f, 8),
                EnvelopePoint(0.00f, 0.72f, 12),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.25f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(8), amplitudes = listOf(40)),
        legacy = LegacySpec(listOf(0, 8)),
        minIntervalMs = 40,
        // Must stay tiny. See HapticSpec.preferredMaxTier.
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    private val navigation = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.58f,
            points = listOf(
                EnvelopePoint(0.35f, 0.58f, 10),
                EnvelopePoint(0.00f, 0.55f, 18),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.45f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(10), amplitudes = listOf(60)),
        legacy = LegacySpec(listOf(0, 10)),
        minIntervalMs = 100,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    private val softConfirm = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.65f,
            points = listOf(
                EnvelopePoint(0.45f, 0.65f, 10),
                EnvelopePoint(0.00f, 0.65f, 20),
            ),
        ),
        // Was CLICK; TICK sits higher and reads lighter for a small confirmation.
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.55f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(12), amplitudes = listOf(80)),
        legacy = LegacySpec(listOf(0, 12)),
        minIntervalMs = 60,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /** Sharp attack, fast decay, no tail: the message left the finger. */
    private val send = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.90f,
            points = listOf(
                EnvelopePoint(0.70f, 0.93f, 6),
                EnvelopePoint(0.00f, 0.79f, 22),
            ),
        ),
        // Was CLICK, which carries a low body. TICK at a high scale keeps the punch
        // without the weight underneath it.
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.85f))),
        predefined = HapticPredefined.Click,
        waveform = WaveformSpec(timings = listOf(14), amplitudes = listOf(150)),
        legacy = LegacySpec(listOf(0, 16)),
        minIntervalMs = 120,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /**
     * The hardest, most definite token. It must be recognisable without looking.
     *
     * Kept on CLICK while everything around it moved to TICK: it is the one moment that
     * should feel solid, and it needs to stay distinguishable from [send], which now sits
     * right next to it in the same screen.
     */
    private val threshold = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.93f,
            points = listOf(
                EnvelopePoint(0.90f, 1.00f, 5),
                EnvelopePoint(0.00f, 0.86f, 16),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Click, 0.75f))),
        predefined = HapticPredefined.Click,
        waveform = WaveformSpec(timings = listOf(12), amplitudes = listOf(200)),
        legacy = LegacySpec(listOf(0, 18)),
        minIntervalMs = 80,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /** Deliberately a softer echo of [threshold] — same shape, less authority. */
    private val thresholdRelease = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.79f,
            points = listOf(
                EnvelopePoint(0.45f, 0.79f, 5),
                EnvelopePoint(0.00f, 0.72f, 14),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.40f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(10), amplitudes = listOf(90)),
        legacy = LegacySpec(listOf(0, 8)),
        minIntervalMs = 80,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /** Slight bloom: rise into a commit. */
    private val reaction = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.51f,
            points = listOf(
                EnvelopePoint(0.50f, 0.62f, 12),
                EnvelopePoint(0.75f, 0.79f, 10),
                EnvelopePoint(0.00f, 0.72f, 24),
            ),
        ),
        primitives = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.QuickRise, 0.40f),
                PrimitiveStep(HapticPrimitive.Tick, 0.65f),
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
            initialSharpness = 0.65f,
            points = listOf(
                EnvelopePoint(0.40f, 0.65f, 10),
                EnvelopePoint(0.00f, 0.65f, 12),
                EnvelopePoint(0.00f, 0.65f, 50),
                EnvelopePoint(0.70f, 0.79f, 10),
                EnvelopePoint(0.00f, 0.72f, 18),
            ),
        ),
        primitives = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.Tick, 0.50f),
                PrimitiveStep(HapticPrimitive.Tick, 0.80f, delayMs = 60),
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
            initialSharpness = 0.79f,
            points = listOf(
                EnvelopePoint(0.75f, 0.79f, 8),
                EnvelopePoint(0.00f, 0.72f, 10),
                EnvelopePoint(0.00f, 0.72f, 90),
                EnvelopePoint(0.45f, 0.65f, 10),
                EnvelopePoint(0.00f, 0.65f, 16),
            ),
        ),
        primitives = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.Tick, 0.75f),
                PrimitiveStep(HapticPrimitive.Tick, 0.50f, delayMs = 100),
            ),
        ),
        predefined = HapticPredefined.DoubleClick,
        waveform = WaveformSpec(timings = listOf(12, 100, 12), amplitudes = listOf(190, 0, 110)),
        legacy = LegacySpec(listOf(0, 18, 100, 12)),
        minIntervalMs = 150,
    )

    /**
     * Not "stronger" — congested. Three equal knocks that stop dead, at a lower sharpness
     * than everything around it so it reads as dull rather than crisp. It should feel
     * *blocked*.
     *
     * Its sharpness rose with the rest, but it stays below [success] on purpose: the gap
     * between them is the meaning.
     */
    private val error = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.58f,
            points = listOf(
                EnvelopePoint(0.80f, 0.58f, 8),
                EnvelopePoint(0.00f, 0.55f, 8),
                EnvelopePoint(0.00f, 0.55f, 42),
                EnvelopePoint(0.80f, 0.58f, 8),
                EnvelopePoint(0.00f, 0.55f, 8),
                EnvelopePoint(0.00f, 0.55f, 42),
                EnvelopePoint(0.60f, 0.51f, 10),
                EnvelopePoint(0.00f, 0.51f, 14),
            ),
        ),
        // Stays on CLICK: a dull knock is the point.
        primitives = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.Click, 0.80f),
                PrimitiveStep(HapticPrimitive.Click, 0.80f, delayMs = 50),
                PrimitiveStep(HapticPrimitive.Click, 0.55f, delayMs = 50),
            ),
        ),
        primitivesApi31 = PrimitiveSpec(
            listOf(
                PrimitiveStep(HapticPrimitive.Click, 0.80f),
                PrimitiveStep(HapticPrimitive.Click, 0.80f, delayMs = 50),
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

    /**
     * Low, heavy, with a tail. Must never be mistaken for [send] or [success].
     *
     * The only token that stays deliberately low. Everything else moved up, which makes
     * this one stand out more than it did before rather than less.
     */
    private val destructive = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.41f,
            points = listOf(
                EnvelopePoint(0.85f, 0.44f, 18),
                EnvelopePoint(0.00f, 0.41f, 45),
            ),
        ),
        // API 30 has neither THUD nor LOW_TICK, so weight is approximated by a hit that
        // immediately falls away.
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
