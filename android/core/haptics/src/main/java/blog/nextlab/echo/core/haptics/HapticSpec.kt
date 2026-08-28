package blog.nextlab.echo.core.haptics

/**
 * 触覚1種類の、各段階での定義。
 *
 * ここはプラットフォームに触らない。全部ただの値で、[HapticTokens] の数字を書き換えて
 * 建て直すだけで設計全体を調整できるようにしてある。
 */

/** 合成用のプリミティブ。`VibrationEffect.Composition.PRIMITIVE_*` に対応。 */
enum class HapticPrimitive {
    // API 30 から。
    Click,
    Tick,
    QuickRise,
    SlowRise,
    QuickFall,

    // API 31 で追加。
    LowTick,
    Thud,
    Spin,
    ;

    val requiresApi31: Boolean
        get() = this == LowTick || this == Thud || this == Spin
}

/** 既定効果。`VibrationEffect.EFFECT_*` に対応。 */
enum class HapticPredefined { Tick, Click, DoubleClick, HeavyClick }

/**
 * API 36 の `BasicEnvelopeBuilder` の制御点1つ。
 *
 * @param intensity 0..1 の振幅。利用者の強度設定で増減する。
 * @param sharpness 0..1 の「硬さ」。利用者設定では増減させない — 強さではなく
 *   その触覚の性格を運ぶため。
 * @param durationMs 前の点からここへ移るまでの時間。
 */
data class EnvelopePoint(
    val intensity: Float,
    val sharpness: Float,
    val durationMs: Long,
)

/** 段階4（API 36+）: 連続的に変化するエンベロープ。 */
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
 * 合成の1手。
 *
 * @param scale 0..1。利用者の強度設定で増減する。
 * @param delayMs この手を始める前の間。
 */
data class PrimitiveStep(
    val primitive: HapticPrimitive,
    val scale: Float,
    val delayMs: Int = 0,
)

/** 段階3／2（API 30+）: プリミティブの並び。 */
data class PrimitiveSpec(val steps: List<PrimitiveStep>) {
    /** API 31 以上でしか動かないなら true。 */
    val requiresApi31: Boolean get() = steps.any { it.primitive.requiresApi31 }
}

/** 段階0（API 26+）: 時間と振幅を明示。振幅は 0..255。 */
data class WaveformSpec(
    val timings: List<Long>,
    val amplitudes: List<Int>,
) {
    init {
        require(timings.size == amplitudes.size) { "timings and amplitudes must be the same length" }
    }
}

/** 段階-1（API 24+）: 時間だけ。振幅は指定できない。 */
data class LegacySpec(val timings: List<Long>)

/**
 * 1種類ぶんの完全な定義。
 *
 * @param minIntervalMs この時間内には再発火しない。触覚を安っぽくする一番の要因＝
 *   連射を防ぐ。
 */
data class HapticSpec(
    val envelope: EnvelopeSpec,
    /** 段階2。API 30 に存在するプリミティブだけを使うこと。 */
    val primitives: PrimitiveSpec,
    /** 段階3。任意の上位版で、API 31 のプリミティブを使ってよい。 */
    val primitivesApi31: PrimitiveSpec? = null,
    val predefined: HapticPredefined,
    val waveform: WaveformSpec,
    /**
     * 入と切しかないモーター向けのパターン。
     *
     * arrows We2 (F-52E) での実測: API 36 なのに振幅制御もプリミティブもエンベロープも
     * 無い。触覚を区別するために設計が頼っているもの（立ち上がり、減衰、柔らかさ）は
     * どれも中間の出力を必要とするのに、それが無い。11種類が4つの既定効果に潰れ、
     * 成功・警告・エラーが同じダブルクリックになる。成功したときと止められたときで
     * 同じ感触なのは、粗い感触より悪い。
     *
     * なのでそういう端末では**長さ**で区別する。残っている唯一の次元。パルスは
     * [waveform] のものより長い。偏心モーターは回り始めるのに20〜30msかかるので、
     * 振幅制御のある端末向けに書いた12msのパルスはここではまったく感じない。
     *
     * null は「既定効果で十分」という意味。既定効果はメーカーがその実機に合わせて
     * 調整したもので、紛らわしくない場面ではそちらが勝つ。
     */
    val onOff: WaveformSpec? = null,
    val legacy: LegacySpec,
    val minIntervalMs: Long = 0L,
    /**
     * 端末の対応とは別に、この触覚が使ってよい上限の段階。
     *
     * 一番上の段階が常に最良とは限らない。Pixel 10（API 37）での実測では、
     * エンベロープの制御点の最短が20msなので、2点のエンベロープは40msを切れない。
     * PRIMITIVE_TICK よりずっと長い。「瞬間」であること自体が意味の触覚にとっては、
     * 使える環境でもエンベロープは*間違った*道具なので、ここで上限を切る。
     * 時間的な形を持つ触覚はエンベロープのまま。
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
