package jp.echo.android.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.echo.android.core.designsystem.EchoDimens
import jp.echo.android.core.designsystem.EchoTheme
import jp.echo.android.core.haptics.HapticIntensity
import jp.echo.android.core.haptics.HapticTier
import jp.echo.android.core.haptics.HapticToken
import jp.echo.android.core.haptics.LocalEchoHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Development surface for judging haptics on a real device.
 *
 * Nothing here can be evaluated from a desk: a vibration has to be felt. This screen
 * exists so the feedback loop is "feel it -> say what is wrong -> change a number in
 * HapticTokens.kt", which is the workflow described in docs/HAPTIC_DESIGN.md.
 *
 * Not a product feature. It does not ship past Prototype 0 in this form.
 */
@Composable
fun HapticLabScreen(onBack: () -> Unit) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    val haptics = LocalEchoHaptics.current
    val scope = rememberCoroutineScope()
    val prefs by haptics.preferences.collectAsState()
    val capabilities = remember { haptics.capabilities }

    var forcedTier by remember { mutableStateOf<HapticTier?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        LabTopBar(onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = EchoDimens.screenPadding,
                end = EchoDimens.screenPadding,
                top = 12.dp,
                bottom = 32.dp,
            ),
        ) {
            item { SectionTitle("この端末の対応状況") }
            item {
                CapabilityCard(
                    rows = listOf(
                        "API level" to capabilities.apiLevel.toString(),
                        "振動子" to if (capabilities.hasVibrator) "あり" else "なし",
                        "振幅制御" to if (capabilities.hasAmplitudeControl) "あり" else "なし",
                        "Envelope (API 36)" to if (capabilities.supportsEnvelope) "対応" else "非対応",
                        "Envelope 最大点数" to capabilities.envelopeMaxPoints.toString(),
                        "Envelope 点の長さ" to
                            "${capabilities.envelopeMinPointMs}–${capabilities.envelopeMaxPointMs} ms",
                        "対応プリミティブ" to
                            capabilities.supportedPrimitives
                                .joinToString(", ") { it.name }
                                .ifEmpty { "なし" },
                        "既定のTier" to tierLabel(capabilities.bestTier),
                    ),
                )
            }

            item { SectionTitle("強度") }
            item {
                SegmentedRow(
                    options = HapticIntensity.entries.map { it to intensityLabel(it) },
                    selected = prefs.intensity,
                    onSelect = { intensity ->
                        haptics.setPreferences(
                            prefs.copy(intensity = intensity, enabled = intensity != HapticIntensity.Off),
                        )
                        if (intensity != HapticIntensity.Off) {
                            haptics.previewToken(HapticToken.SoftConfirm, forcedTier)
                        }
                    },
                )
            }

            item { SectionTitle("Tier を固定して比較") }
            item {
                TierSelector(
                    selected = forcedTier,
                    onSelect = { forcedTier = it },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "「自動」はこの端末で実際に使われる Tier。" +
                        "固定すると下位互換の感触を同じ端末で比較できます。",
                    style = type.labelSmall,
                    color = colors.textTertiary,
                )
            }

            item { SectionTitle("トークン") }
            items(HapticToken.entries, key = { it.name }) { token ->
                TokenRow(
                    token = token,
                    resolvedTier = if (forcedTier != null) forcedTier!! else haptics.tierFor(token),
                    forced = forcedTier != null,
                    onFire = { haptics.previewToken(token, forcedTier) },
                )
            }

            item { SectionTitle("対比で確かめる") }
            items(comparisons, key = { it.label }) { comparison ->
                ComparisonRow(
                    comparison = comparison,
                    onPlay = {
                        scope.launch {
                            haptics.previewToken(comparison.first, forcedTier)
                            delay(comparison.gapMs)
                            haptics.previewToken(comparison.second, forcedTier)
                        }
                    },
                )
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

private data class Comparison(
    val label: String,
    val question: String,
    val first: HapticToken,
    val second: HapticToken,
    val gapMs: Long = 700,
)

private val comparisons = listOf(
    Comparison(
        label = "Success → Error",
        question = "成功が上がり、失敗が詰まって聞こえるか",
        first = HapticToken.Success,
        second = HapticToken.Error,
    ),
    Comparison(
        label = "Send → Threshold",
        question = "送信と返信成立を取り違えないか",
        first = HapticToken.Send,
        second = HapticToken.Threshold,
    ),
    Comparison(
        label = "Send → Destructive",
        question = "削除が明確に重いと感じるか",
        first = HapticToken.Send,
        second = HapticToken.Destructive,
    ),
    Comparison(
        label = "Threshold → ThresholdRelease",
        question = "戻したときが弱い反響として感じられるか",
        first = HapticToken.Threshold,
        second = HapticToken.ThresholdRelease,
        gapMs = 450,
    ),
    Comparison(
        label = "Selection ×5",
        question = "連続で動かしても不快な連打にならないか",
        first = HapticToken.Selection,
        second = HapticToken.Selection,
        gapMs = 90,
    ),
)

@Composable
private fun TokenRow(
    token: HapticToken,
    resolvedTier: HapticTier,
    forced: Boolean,
    onFire: () -> Unit,
) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceSunken)
            .clickable(onClick = onFire)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = token.name,
                style = type.listName,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = tokenMeaning(token),
                style = type.labelSmall,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.width(10.dp))
        TierBadge(resolvedTier, forced)
    }
}

@Composable
private fun ComparisonRow(comparison: Comparison, onPlay: () -> Unit) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceSunken)
            .clickable(onClick = onPlay)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = comparison.label, style = type.listName, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(text = comparison.question, style = type.labelSmall, color = colors.textSecondary)
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(14.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.14f)
                    lineTo(size.width * 0.82f, size.height * 0.5f)
                    lineTo(size.width * 0.28f, size.height * 0.86f)
                    close()
                }
                drawPath(path, colors.accent)
            }
        }
    }
}

@Composable
private fun TierBadge(tier: HapticTier, forced: Boolean) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (forced) colors.accentSoft else colors.outlineSoft)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = tierShort(tier),
            style = type.labelSmall,
            color = if (forced) colors.accent else colors.textSecondary,
        )
    }
}

@Composable
private fun TierSelector(selected: HapticTier?, onSelect: (HapticTier?) -> Unit) {
    val options: List<Pair<HapticTier?, String>> =
        listOf<Pair<HapticTier?, String>>(null to "自動") +
            HapticTier.entries.filter { it != HapticTier.None }.map { it to tierShort(it) }

    SegmentedRow(options = options, selected = selected, onSelect = onSelect)
}

@Composable
private fun <T> SegmentedRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) colors.accent else colors.surfaceSunken)
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = type.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isSelected) colors.onAccent else colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(rows: List<Pair<String, String>>) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceSunken)
            .padding(14.dp),
    ) {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = label,
                    style = type.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.width(140.dp),
                )
                Text(
                    text = value,
                    style = type.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    Text(
        text = text,
        style = type.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = colors.textTertiary,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun LabTopBar(onBack: () -> Unit) {
    val colors = EchoTheme.colors
    val type = EchoTheme.type
    val haptics = LocalEchoHaptics.current

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(EchoDimens.touchTarget)
                    .clip(CircleShape)
                    .clickable {
                        haptics.perform(HapticToken.Navigation)
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val stroke = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                    val path = Path().apply {
                        moveTo(size.width * 0.62f, size.height * 0.16f)
                        lineTo(size.width * 0.30f, size.height * 0.5f)
                        lineTo(size.width * 0.62f, size.height * 0.84f)
                    }
                    drawPath(path, colors.textPrimary, style = stroke)
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(text = "Haptic Lab", style = type.screenTitle, color = colors.textPrimary)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineSoft),
        )
    }
}

private fun tokenMeaning(token: HapticToken): String = when (token) {
    HapticToken.Selection -> "選択対象が変わった（極小・連打防止あり）"
    HapticToken.Navigation -> "画面が変わった"
    HapticToken.SoftConfirm -> "軽い確定（トグル・既読）"
    HapticToken.Send -> "メッセージを送り出した（短く鋭く、余韻なし）"
    HapticToken.Threshold -> "引き返せない境界を超えた（返信スワイプ成立）"
    HapticToken.ThresholdRelease -> "境界より戻した（Threshold の弱い反響）"
    HapticToken.Reaction -> "リアクション確定（わずかに咲く）"
    HapticToken.Success -> "成功（上がる2連）"
    HapticToken.Warning -> "注意（下がる2連）"
    HapticToken.Error -> "失敗（詰まった3連・鈍い）"
    HapticToken.Destructive -> "破壊的操作の確定（低く重く余韻あり）"
}

private fun tierShort(tier: HapticTier): String = when (tier) {
    HapticTier.Envelope -> "T4"
    HapticTier.PrimitiveRich -> "T3"
    HapticTier.Primitive -> "T2"
    HapticTier.Predefined -> "T1"
    HapticTier.Waveform -> "T0"
    HapticTier.Legacy -> "T-1"
    HapticTier.None -> "—"
}

private fun tierLabel(tier: HapticTier): String = when (tier) {
    HapticTier.Envelope -> "T4 Envelope (API 36)"
    HapticTier.PrimitiveRich -> "T3 Primitive rich (API 31)"
    HapticTier.Primitive -> "T2 Primitive (API 30)"
    HapticTier.Predefined -> "T1 Predefined (API 29)"
    HapticTier.Waveform -> "T0 Waveform (API 26)"
    HapticTier.Legacy -> "T-1 Legacy (API 24)"
    HapticTier.None -> "利用不可"
}

private fun intensityLabel(intensity: HapticIntensity): String = when (intensity) {
    HapticIntensity.Off -> "OFF"
    HapticIntensity.Subtle -> "弱"
    HapticIntensity.Normal -> "標準"
    HapticIntensity.Strong -> "強"
}
