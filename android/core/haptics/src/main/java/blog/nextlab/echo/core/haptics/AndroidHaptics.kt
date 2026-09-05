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
 * 触覚設計の Android 実装。
 *
 * docs/HAPTIC_DESIGN.md の段階の梯子を、この端末が実際に対応している段から始めて
 * 下りていく。効果を組めない、または再生できないたびに1段下げる。
 */
class AndroidHaptics(context: Context) : RinowaHaptics {

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
     * 実行時にその段が例外を投げたら入る。対応していると名乗っておいて効果を
     * 拒否する端末があるので、そうなったらそのセッションの間はその段を試さない。
     * 毎回黙って失敗し続けるよりよい。
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

    override fun performProgress(token: HapticToken, intensity: Float) {
        // 連射制限はかけない（interface を参照）が、黙る理由が他にあるならそれは効く。
        if (isSuppressed(token, respectThrottle = false)) {
            suppressedCount.incrementAndGet()
            return
        }
        tokenCounts.getOrPut(token) { AtomicInteger(0) }.incrementAndGet()
        emit(token, tierFor(token), _preferences.value.effectiveScale * intensity.coerceIn(0f, 1f))
    }

    override fun previewToken(token: HapticToken, forceTier: HapticTier?) {
        if (vibrator == null || capabilities.bestTier == HapticTier.None) return
        // Lab は連射制限も前面判定も意図的に飛ばすが、明示的な「切」は尊重する。
        // 切ったあとに黙って振動するのは、利用者からは見えないバグ。
        val prefs = _preferences.value
        if (!prefs.enabled) return
        val scale = prefs.intensity.scale.coerceAtLeast(HapticIntensity.Subtle.scale)
        emit(token, forceTier ?: tierFor(token), scale)
    }

    override fun previewPulse(durationMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        val prefs = _preferences.value
        if (!prefs.enabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 強度のつまみを掛けない。**ここは物差しなので、目盛りのほうを動かさない。**
        // 掛けてしまうと、短くて出ないのか弱くて出ないのかが分からなくなる。
        val level = if (capabilities.hasAmplitudeControl) {
            amplitude.coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        // swallow-ok: 物差しが振れなかっただけ。実験室の画面が1回反応しない以外に
        // 起きることは無く、次に押せばまた試せる。
        runCatching {
            dispatch(v, VibrationEffect.createWaveform(longArrayOf(durationMs), intArrayOf(level), -1))
        }
    }
    override fun tierFor(token: HapticToken): HapticTier {
        val spec = HapticTokens[token]
        val available = degradedTo ?: capabilities.bestTier
        // ordinal が大きいほど下の段なので、端末ができることと、この触覚に許した
        // 上限の、低いほうを取る。
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
        // 入切のパターンを持つ触覚は、モーターが強さを変えられないときは既定効果より
        // そちらを選ぶ。その状況では複数の触覚が同じ既定効果に潰れ、区別できるのが
        // 長さだけになるため。HapticSpec.onOff を参照。
        if (tier == HapticTier.Predefined &&
            !capabilities.hasAmplitudeControl &&
            spec.onOff != null &&
            capabilities.apiLevel >= Build.VERSION_CODES.O
        ) {
            tier = HapticTier.Waveform
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

    // ---------------------------------------------------------------- 抑制

    private fun isSuppressed(token: HapticToken, respectThrottle: Boolean): Boolean {
        if (vibrator == null || capabilities.bestTier == HapticTier.None) return true
        if (!inForeground) return true

        val prefs = _preferences.value
        if (!prefs.enabled || prefs.intensity == HapticIntensity.Off) return true

        // バッテリーセーバー。端末側の期待に逆らわず合わせる。
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

    // ---------------------------------------------------------------- 発火

    private fun emit(token: HapticToken, startTier: HapticTier, scale: Float) {
        val spec = HapticTokens[token]
        var tier: HapticTier? = startTier

        while (tier != null && tier != HapticTier.None) {
            val current = tier
            val played = runCatching { play(spec, current, scale) }.getOrElse { false }
            if (played) return
            // 対応を名乗ったのに動かなかった段。このセッションではもう試さない。
            degradedTo = current.next
            tier = current.next
        }
    }

    /** @return 実際に効果を送れたら true。 */
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
                // createPredefined() には強さの引数が無いので、この段では利用者の
                // 強度設定を反映できない。黙って違えるのではなく、そう書いておく。
                val effect = runCatching {
                    VibrationEffect.createPredefined(spec.predefined.platformId)
                }.getOrNull() ?: return false
                dispatch(v, effect)
                true
            }

            HapticTier.Waveform -> {
                if (api < Build.VERSION_CODES.O) return false
                // モーターに中間が無ければ入切のパターン、あれば振幅の形のほう。
                // どちらも波形で、違うのは数字だけ。
                val shape = if (capabilities.hasAmplitudeControl) {
                    spec.waveform
                } else {
                    spec.onOff ?: spec.waveform
                }
                val effect = buildWaveform(shape, scale) ?: return false
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
                // USAGE_TOUCH にすると OS がタッチのフィードバックとして扱い、
                // 利用者の触覚設定とサイレントが尊重される。付けないと、端末によっては
                // 通知の振動として分類され、鳴るべきでないときに鳴る。
            v.vibrate(effect, TOUCH_ATTRIBUTES)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, LEGACY_AUDIO_ATTRIBUTES)
        }
    }

    // ---------------------------------------------------------------- 組み立て

    @RequiresApi(36)
    private fun buildEnvelope(spec: EnvelopeSpec, scale: Float): VibrationEffect? = runCatching {
        var points = spec.points

        if (points.size > capabilities.envelopeMaxPoints) {
            points = points.take(capabilities.envelopeMaxPoints)
        }

        // 各区間を端末が受け付ける範囲に丸め、合計の上限より前で止める。
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

        // 切り詰めで解放部分が落ちていることがある。0 に戻らないエンベロープは
        // そのまま拒否される。
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
