package blog.nextlab.echo.ui.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.analytics.FeedbackCategory
import blog.nextlab.echo.ui.common.ScreenHeader
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.GlassSurface
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.data.FeedbackItem
import blog.nextlab.echo.ui.auth.RinowaField
import blog.nextlab.echo.ui.auth.NoticeBanner
import blog.nextlab.echo.ui.auth.PrimaryButton
import blog.nextlab.echo.ui.auth.PrimaryButtonLabel
import blog.nextlab.echo.ui.auth.QuietButton
import blog.nextlab.echo.ui.auth.QuietButtonLabel

private val categories = listOf(
    FeedbackCategory.Bug to "不具合",
    FeedbackCategory.Feature to "ほしい機能",
    FeedbackCategory.Ui to "画面・操作",
    FeedbackCategory.Haptic to "触覚",
    FeedbackCategory.Other to "その他",
)

/**
 * フィードバックと、他の人が求めていること。
 *
 * 一覧はサインインしている全員から見える。意図的にそうしている。「利用者の声を
 * 開発の中心に置く」はこの企画の原則の1つで、私書箱に消えていくフィードバックは、
 * 誰にも信じる理由を与えない。他の人の要望が見え、そこに1票足せることが、
 * これをフォームではなく会話にする。
 */
@Composable
fun FeedbackScreen(viewModel: FeedbackViewModel, onBack: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    val keyboard = LocalSoftwareKeyboardController.current

    var composing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(FeedbackCategory.Feature) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        ScreenHeader(
            title = "フィードバック",
            onBack = {
                keyboard?.hide()
                onBack()
            },
        ) {
            QuietButton(
                enabled = !viewModel.submitting,
                onClick = {
                    haptics.perform(HapticToken.Selection)
                    composing = !composing
                },
            ) { color ->
                QuietButtonLabel(if (composing) "やめる" else "書く", color)
            }
        }

        AnimatedVisibility(visible = composing) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    categories.forEach { (value, label) ->
                        CategoryChip(
                            label = label,
                            selected = category == value,
                            onClick = {
                                haptics.perform(HapticToken.Selection)
                                category = value
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                RinowaField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    placeholder = "ひとことで言うと",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(),
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp)
                        .glassFace(shape = RoundedCornerShape(16.dp), elevation = 2.dp)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (body.isEmpty()) {
                        Text("くわしく（任意）", style = type.composer, color = colors.textTertiary)
                    }
                    BasicTextField(
                        value = body,
                        onValueChange = { body = it.take(4000) },
                        textStyle = type.composer.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (viewModel.error != null) {
                    Spacer(Modifier.height(12.dp))
                }
                NoticeBanner(text = viewModel.error, isError = true)

                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    enabled = !viewModel.submitting && title.isNotBlank(),
                    onClick = {
                        keyboard?.hide()
                        viewModel.submit(title, body, category) { ok ->
                            if (ok) {
                                haptics.perform(HapticToken.Success)
                                title = ""
                                body = ""
                                composing = false
                            } else {
                                haptics.perform(HapticToken.Error)
                            }
                        }
                    },
                ) { color ->
                    PrimaryButtonLabel(
                        if (viewModel.submitting) "送っています" else "送る",
                        color,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    // 誰も開かない規約ではなく、ここに書く。
                    text = "ここに書いた内容は開発者が読みます。メッセージの本文とは別の扱いです。",
                    style = type.labelSmall,
                    color = colors.textTertiary,
                )
                Spacer(Modifier.height(14.dp))
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
        ) {
            items(viewModel.items, key = { it.id }) { item ->
                FeedbackRow(
                    item = item,
                    onVote = {
                        haptics.perform(HapticToken.SoftConfirm)
                        viewModel.toggleVote(item)
                    },
                    onWithdraw = {
                        haptics.perform(HapticToken.Destructive)
                        viewModel.withdraw(item)
                    },
                )
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun FeedbackRow(item: FeedbackItem, onVote: () -> Unit, onWithdraw: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = RinowaDimens.glassCardMargin,
                vertical = RinowaDimens.glassCardGap / 2,
            ),
        onClick = onVote,
        onLongClick = if (item.mine) onWithdraw else null,
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(RinowaDimens.listItemPadding),
        ) {
            VoteBadge(count = item.voteCount, voted = item.votedByMe)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = categories.firstOrNull { it.first == item.category }?.second ?: "その他",
                    style = type.labelSmall,
                    color = colors.accent,
                )
                Spacer(Modifier.height(3.dp))
                Text(text = item.title, style = type.listName, color = colors.textPrimary)
                if (item.body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.body,
                        style = type.listPreview,
                        color = colors.textSecondary,
                        maxLines = 4,
                    )
                }
                if (item.mine) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "あなたの投稿 ・ 長押しで取り下げ",
                        style = type.labelSmall,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoteBadge(count: Int, voted: Boolean) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (voted) colors.accentSoft else colors.surfaceSunken)
            .padding(vertical = 8.dp),
    ) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.15f, h * 0.65f)
                lineTo(w * 0.5f, h * 0.25f)
                lineTo(w * 0.85f, h * 0.65f)
            }
            drawPath(path, if (voted) colors.accent else colors.textSecondary, style = stroke)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = count.toString(),
            style = type.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (voted) colors.accent else colors.textSecondary,
        )
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.accentSoft else colors.surfaceSunken)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.labelSmall,
            color = if (selected) colors.accent else colors.textSecondary,
        )
    }
}
