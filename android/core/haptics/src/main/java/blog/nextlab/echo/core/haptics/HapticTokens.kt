package blog.nextlab.echo.core.haptics

/**
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │  調整表                                                                   │
 * │                                                                          │
 * │  実機で触って直すときに開くのはこのファイルだけ。触覚の数値は他に無い。    │
 * │                                                                          │
 * │  手順: Pixel で触る → どう嫌かを言う → ここの数字を変える → 建て直す     │
 * └──────────────────────────────────────────────────────────────────────────┘
 *
 * 音程を上げるつまみは、エンベロープ段の `sharpness` だけ（0＝鈍く低い、1＝硬く高い）。
 * プリミティブ段では周波数が OS 側で決まっているので、高く鳴らす唯一の方法は
 * 軽いプリミティブを選ぶこと:
 *
 *     TICK  >  CLICK  >  LOW_TICK / THUD          (高い ......... 低い)
 *
 * sharpness は利用者の強度設定で増減させない。強さではなく、その触覚の性格を表すため。
 *
 * 値は docs/HAPTIC_DESIGN.md と対応。調整したら両方直す。
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
        // 必ず小さいままにする。HapticSpec.preferredMaxTier を参照。
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
        // 以前は CLICK。TICK のほうが高く、小さな確認としては軽く読める。
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.55f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(12), amplitudes = listOf(80)),
        legacy = LegacySpec(listOf(0, 12)),
        minIntervalMs = 60,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /** 立ち上がりが鋭く、すぐ減衰して尾を引かない。メッセージが指から離れた感じ。 */
    private val send = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.90f,
            points = listOf(
                EnvelopePoint(0.70f, 0.93f, 6),
                EnvelopePoint(0.00f, 0.79f, 22),
            ),
        ),
        // 以前は CLICK。あれは低い胴を持つ。TICK を強めに出すと、下に重さを
        // 残さずに打感だけが残る。
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.85f))),
        predefined = HapticPredefined.Click,
        waveform = WaveformSpec(timings = listOf(14), amplitudes = listOf(150)),
        legacy = LegacySpec(listOf(0, 16)),
        minIntervalMs = 120,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /**
     * 一番硬く、一番はっきりした触覚。見なくても分かる必要がある。
     *
     * 周りが TICK に移った中でこれだけ CLICK のまま。ここは確かに感じるべき瞬間で、
     * しかも同じ画面で隣に並ぶ [send] と区別が付き続ける必要がある。
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

    /** [threshold] のわざと弱い反響。形は同じで、権威だけ落とす。 */
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

    /**
     * 送ったものが読まれた。
     *
     * アプリの中で一番小さい [selection] よりさらに弱くする。これは指に応えている
     * わけではなく勝手に来るので、気付かなくても構わないものであるべきで、
     * 気付けと迫るものであってはいけない。
     *
     * エンベロープ段より下に抑える。エンベロープの最小制御点20msでは [selection] より
     * 長くなり、自分の操作より長く続く通知は、それより大事なものとして読まれる。
     * そうではない。
     */
    private val readReceipt = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.42f,
            points = listOf(
                EnvelopePoint(0.26f, 0.45f, 8),
                EnvelopePoint(0.00f, 0.40f, 20),
            ),
        ),
        primitives = PrimitiveSpec(listOf(PrimitiveStep(HapticPrimitive.Tick, 0.22f))),
        predefined = HapticPredefined.Tick,
        waveform = WaveformSpec(timings = listOf(10), amplitudes = listOf(45)),
        onOff = WaveformSpec(timings = listOf(22), amplitudes = listOf(255)),
        legacy = LegacySpec(listOf(0, 10)),
        // 何人もいる会話では、既読がまとめて来ると振動もまとめて来る。多くて毎秒1回。
        minIntervalMs = 1000,
        preferredMaxTier = HapticTier.PrimitiveRich,
    )

    /** わずかに膨らませる。確定へ向かう立ち上がり。 */
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

    /** 上がる2連。[error] との対比が意味を運ぶ。 */
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
        // 上がる形を、強さが使えないので長さで表す。短い打、間、長い打。
        // 間を詰めて、2つで1つの上向きの動作に読めるようにする。
        onOff = WaveformSpec(timings = listOf(28, 45, 44), amplitudes = listOf(255, 0, 255)),
        legacy = LegacySpec(listOf(0, 14, 60, 18)),
        minIntervalMs = 150,
    )

    /** 下がる2連。 */
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
        // 成功の鏡。長い打、広い間、短い打。間を広げるのは、同じ対を逆再生しただけに
        // 聞こえないようにするため。
        onOff = WaveformSpec(timings = listOf(44, 95, 26), amplitudes = listOf(255, 0, 255)),
        legacy = LegacySpec(listOf(0, 18, 100, 12)),
        minIntervalMs = 150,
    )

    /**
     * 「強い」ではなく詰まっている感じ。等しい打を3回で止め、sharpness は周りより
     * 低くして硬さではなく鈍さに読ませる。**塞がっている**と感じるべき。
     *
     * 全体が上がったのに合わせて上げたが、[success] より下に置いてある。
     * その差が意味そのもの。
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
        // CLICK のまま。鈍い打であることが要点。
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
        // 成功と警告が2回のところ、こちらは3回。しかも最後が長すぎる。強いのではなく
        // 詰まっている＝**止まった**と読ませたい。音量が1段しかないモーターでは、
        // 長く続けるしか言い方が無い。
        onOff = WaveformSpec(
            timings = listOf(26, 38, 26, 38, 70),
            amplitudes = listOf(255, 0, 255, 0, 255),
        ),
        legacy = LegacySpec(listOf(0, 18, 46, 18, 46, 22)),
        minIntervalMs = 200,
    )

    /**
     * 低く、重く、尾を引く。[send] や [success] と取り違えられてはいけない。
     *
     * 意図的に低いままの唯一の触覚。他が上がったぶん、以前より目立つようになった。
     */
    private val destructive = HapticSpec(
        envelope = EnvelopeSpec(
            initialSharpness = 0.41f,
            points = listOf(
                EnvelopePoint(0.85f, 0.44f, 18),
                EnvelopePoint(0.00f, 0.41f, 45),
            ),
        ),
        // API 30 には THUD も LOW_TICK も無いので、当たってすぐ落ちる形で重さを代用する。
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
        HapticToken.ReadReceipt to readReceipt,
        HapticToken.Success to success,
        HapticToken.Warning to warning,
        HapticToken.Error to error,
        HapticToken.Destructive to destructive,
    )

    operator fun get(token: HapticToken): HapticSpec =
        table.getValue(token)
}
