package blog.nextlab.echo.ui.lab

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
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.rememberRefreshRateInfo
import blog.nextlab.echo.core.haptics.HapticIntensity
import blog.nextlab.echo.core.haptics.HapticTier
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 触覚を実機で判断するための開発用画面。
 *
 * ここにあるものは机の上では評価できない。振動は触らないと分からない。
 * 「触る → どう嫌かを言う → HapticTokens.kt の数字を変える」という往復のために
 * ある。docs/HAPTIC_DESIGN.md の手順そのもの。
 *
 * 製品の機能ではない。この形のまま Prototype 0 より先へは出さない。
 */
@Composable
fun HapticLabScreen(onBack: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
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
                start = RinowaDimens.screenPadding,
                end = RinowaDimens.screenPadding,
                top = 12.dp,
                bottom = 32.dp,
            ),
        ) {
            item { SectionTitle("この端末の対応状況") }
            item {
                CapabilityCard(
                    rows = buildList {
                        add("API level" to capabilities.apiLevel.toString())
                        add("振動子" to if (capabilities.hasVibrator) "あり" else "なし")
                        add("振幅制御" to if (capabilities.hasAmplitudeControl) "あり" else "なし")
                        add(
                            "Envelope (API 36)" to
                                if (capabilities.supportsEnvelope) "対応" else "非対応",
                        )
                        // 意味のあるときだけ出す。この2つは端末が答えないと既定値に
                        // 落ちるので、「非対応」のすぐ下に「最大点数 16」と出ると、
                        // 端末が持っていない能力として読めてしまう。
                        if (capabilities.supportsEnvelope) {
                            add("Envelope 最大点数" to capabilities.envelopeMaxPoints.toString())
                            add(
                                "Envelope 点の長さ" to
                                    "${capabilities.envelopeMinPointMs}–" +
                                    "${capabilities.envelopeMaxPointMs} ms",
                            )
                        }
                        add(
                            "対応プリミティブ" to
                                capabilities.supportedPrimitives
                                    .joinToString(", ") { it.name }
                                    .ifEmpty { "なし" },
                        )
                        add("既定のTier" to tierLabel(capabilities.bestTier))
                    },
                )
            }

            item { SectionTitle("画面のリフレッシュレート") }
            item {
                val refresh = rememberRefreshRateInfo()
                CapabilityCard(
                    rows = listOf(
                        "現在" to "%.1f Hz".format(refresh.currentHz),
                        "1フレームの予算" to "%.2f ms".format(refresh.frameBudgetMs),
                        "対応レート" to refresh.supportedHz.joinToString(" / ") { "%.0f".format(it) },
                        "可変(ARR)" to if (refresh.adaptive) "対応" else "非対応（モード切替のみ）",
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "フレーム予算は固定値ではなく、常にこの実測レートから計算しています。" +
                        "アニメーションは時間基準なので、1Hz でも 144Hz でも速度は変わりません。",
                    style = type.labelSmall,
                    color = colors.textTertiary,
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
                // 振幅の調整には、中間の出力で駆動できるモーターが要る。T1 は
                // createPredefined() を使い、そもそも強さを取らない。どちらも無い端末では
                // つまみが動いても何も変わらないので、バグに見えるより先にそう言う。
                val intensityInert = !capabilities.hasAmplitudeControl ||
                    (forcedTier ?: capabilities.bestTier) == HapticTier.Predefined
                if (intensityInert) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (!capabilities.hasAmplitudeControl) {
                            "この端末は振幅を制御できないため、OFF 以外の強度差は出ません。"
                        } else {
                            "T1 は強さを指定できない方式なので、OFF 以外の強度差は出ません。"
                        },
                        style = type.labelSmall,
                        color = colors.textTertiary,
                    )
                }
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

            item {
                SectionTitle("トークン")
                Text(
                    text = "バッジは実際に使われる Tier。" +
                        "「制限」は端末がもっと上に対応していても、" +
                        "そのトークンの意味に合わないため意図的に下げていることを示します。",
                    style = type.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(HapticToken.entries, key = { it.name }) { token ->
                val resolved = if (forcedTier != null) forcedTier!! else haptics.tierFor(token)
                TokenRow(
                    token = token,
                    resolvedTier = resolved,
                    forced = forcedTier != null,
                    // 上限で止めている。端末はもっと上を出せるが、この触覚は出すべきでない。
                    capped = forcedTier == null && resolved != capabilities.bestTier,
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
    capped: Boolean,
    onFire: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

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
        TierBadge(resolvedTier, forced, capped)
    }
}

@Composable
private fun ComparisonRow(comparison: Comparison, onPlay: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

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
private fun TierBadge(tier: HapticTier, forced: Boolean, capped: Boolean = false) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (forced) colors.accentSoft else colors.outlineSoft)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (capped) "${tierShort(tier)} 制限" else tierShort(tier),
            style = type.labelSmall,
            color = when {
                forced -> colors.accent
                capped -> colors.textTertiary
                else -> colors.textSecondary
            },
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
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

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
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

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
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Text(
        text = text,
        style = type.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color = colors.textTertiary,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun LabTopBar(onBack: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current

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
                    .size(RinowaDimens.touchTarget)
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
    HapticToken.SoftConfirm -> "軽い確定（トグル・下書き保存）"
    HapticToken.Send -> "メッセージを送り出した（短く鋭く、余韻なし）"
    HapticToken.Threshold -> "引き返せない境界を超えた（返信スワイプ成立）"
    HapticToken.ThresholdRelease -> "境界より戻した（Threshold の弱い反響）"
    HapticToken.Reaction -> "リアクション確定（わずかに咲く）"
    HapticToken.ReadReceipt -> "送ったものが読まれた（指以外が起こす唯一の触覚・最も弱い）"
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
