package blog.nextlab.echo.core.haptics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 触覚の強さ。振幅にだけかかり、長さには絶対にかけない。 */
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
    /** 振幅にかける倍率。切ってあるときは0。 */
    val effectiveScale: Float get() = if (enabled) intensity.scale else 0f
}

/**
 * セッションごとの回数。計測のまとめに流し込む。
 *
 * わざと集計する。触覚1回ごとにイベントを送ると、送信量が増えるうえ、
 * その人の操作の順序を復元できてしまう。docs/ANALYTICS_SCHEMA.md。
 */
data class HapticCounters(
    val perToken: Map<HapticToken, Int> = emptyMap(),
    val suppressed: Int = 0,
)

/**
 * アプリが触覚を出す唯一の道。
 *
 * 画面は意味を指定して [perform] を呼ぶ。このモジュールの外は `Vibrator` にも
 * `performHapticFeedback` にも触らない。
 */
interface RinowaHaptics {

    val capabilities: HapticCapabilities

    val preferences: StateFlow<HapticPreferences>

    fun setPreferences(preferences: HapticPreferences)

    /** 利用者の設定と種類ごとの連射制限を守って [token] を鳴らす。 */
    fun perform(token: HapticToken)

    /**
     * ジェスチャーに追従する強さで [token] を鳴らす。
     *
     * 指がまだ動かしている最中のもの（予測型の戻る、閾値へ向かうドラッグ）向け。
     * 固定の刻み1回では*どこまで来たか*を何も言わない。エンベロープは出力を連続的に
     * 変えられて、そこに費やす価値がある。感触がジェスチャーと一緒に上がるので、
     * 離す頃合いを目より先に手が知る。
     *
     * 連射制限はかけない。間隔を空けるのは呼び出し側の責任で、40回鳴るジェスチャーは
     * 呼び出し側のバグ。ここで飲み込むとそれが隠れる。触覚が切ってあるときと
     * アプリが背面のときは、やはり鳴らない。
     *
     * @param intensity 0..1に丸める。利用者の強度設定を掛けるので、控えめを選んだ人は
     *   控えめなまま上がっていく。
     */
    fun performProgress(token: HapticToken, intensity: Float)

    /**
     * 連射制限を無視して [token] を鳴らす。段階を指定することもできる。
     *
     * 触覚 Lab 専用。段階を続けて触り比べるのが目的なので。
     */
    fun previewToken(token: HapticToken, forceTier: HapticTier? = null)

    /**
     * 実験室だけが使う、素の1発。トークンを通さずに長さと強さを直に指定する。
     *
     * **これは触覚の設計ではなく、モーターの物差し。** 安いモーターは回り切るまでに
     * 20〜30ms かかるので、それより短い指示は強さをいくら上げても何も出ない。
     * 出る／出ないの境目が端末ごとに違い、**問い合わせて分かる API が無い**ので、
     * 実物を押して探すしかない。docs/HAPTIC_STRENGTH.md。
     *
     * 製品の画面からは呼ばない。段（tier）も上限も通さない生の指示なので、
     * ここを本番で使うと、端末ごとの違いを吸収する仕組みを全部迂回することになる。
     */
    fun previewPulse(durationMs: Long, amplitude: Int)

    /** この端末が [token] に実際に使う段階。 */
    fun tierFor(token: HapticToken): HapticTier

    /** アプリが背面にいる間、触覚は鳴らさない。 */
    fun setAppInForeground(inForeground: Boolean)

    fun cancel()

    fun counters(): HapticCounters

    fun resetCounters()
}

// 触るところごとに perform(HapticToken.X) と書くより、意味の名前のほうが読める。
fun RinowaHaptics.selection() = perform(HapticToken.Selection)
fun RinowaHaptics.navigation() = perform(HapticToken.Navigation)
fun RinowaHaptics.softConfirm() = perform(HapticToken.SoftConfirm)
fun RinowaHaptics.send() = perform(HapticToken.Send)
fun RinowaHaptics.threshold() = perform(HapticToken.Threshold)
fun RinowaHaptics.thresholdRelease() = perform(HapticToken.ThresholdRelease)
fun RinowaHaptics.reaction() = perform(HapticToken.Reaction)
fun RinowaHaptics.success() = perform(HapticToken.Success)
fun RinowaHaptics.warning() = perform(HapticToken.Warning)
fun RinowaHaptics.error() = perform(HapticToken.Error)
fun RinowaHaptics.destructive() = perform(HapticToken.Destructive)

/** `@Preview` と単体テスト用。 */
class NoOpHaptics : RinowaHaptics {
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
    override fun performProgress(token: HapticToken, intensity: Float) = Unit
    override fun previewToken(token: HapticToken, forceTier: HapticTier?) = Unit
    override fun previewPulse(durationMs: Long, amplitude: Int) = Unit
    override fun tierFor(token: HapticToken): HapticTier = HapticTier.None
    override fun setAppInForeground(inForeground: Boolean) = Unit
    override fun cancel() = Unit
    override fun counters(): HapticCounters = HapticCounters()
    override fun resetCounters() = Unit
}
