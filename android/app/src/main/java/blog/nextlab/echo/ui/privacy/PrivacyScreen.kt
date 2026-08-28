package blog.nextlab.echo.ui.privacy

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.analytics.Analytics
import blog.nextlab.echo.core.analytics.AnalyticsEvent
import blog.nextlab.echo.ui.common.ScreenHeader
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.data.SettingsRepository

/**
 * Rinowa が何を集めているかを、はっきり言う画面。止めるスイッチ付き。
 *
 * 下の一覧は宣伝文ではなく、docs/ANALYTICS_SCHEMA.md を読んでいない人向けに
 * 言い直したもの。スキーマにこの画面が触れていないものが増えたら、どちらかが間違い。
 */
@Composable
fun PrivacyScreen(
    analytics: Analytics,
    settings: SettingsRepository?,
    onNotificationBodyChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current

    var optedOut by remember { mutableStateOf(analytics.optedOut) }
    var showsBody by remember {
        mutableStateOf(settings?.localNotificationShowsBody() ?: true)
    }
    var sendsOriginals by remember {
        mutableStateOf(settings?.localSendsOriginals() ?: false)
    }

    LaunchedEffect(Unit) { analytics.log(AnalyticsEvent.PrivacyScreenOpened) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "プライバシー", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = "メッセージの本文は、届けるためだけに使われます。",
                style = type.listName,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "本文を読めるのはその会話の参加者だけです。" +
                    "管理者が本文を読む経路は、サーバーの権限設定そのものに存在しません。",
                style = type.listPreview,
                color = colors.textSecondary,
            )

            Spacer(Modifier.height(26.dp))
            SectionTitle("送っていないもの")
            Line("メッセージの本文・下書き", allowed = false)
            Line("会話ID・メッセージID・ユーザーID", allowed = false)
            Line("表示名・メールアドレス・連絡先", allowed = false)
            Line("選んだ絵文字そのもの", allowed = false)
            Line("スタンプのID", allowed = false)
            Line("広告ID・位置情報", allowed = false)

            Spacer(Modifier.height(22.dp))
            SectionTitle("送っているもの")
            Line("画面ごとの操作回数と滞在時間", allowed = true)
            Line("メッセージの文字数（500以上はまとめて）", allowed = true)
            Line("送信の成否と所要時間", allowed = true)
            Line("触覚の種類ごとの回数（セッション単位の合計）", allowed = true)
            Line("端末のAPIレベルと触覚の能力段階", allowed = true)

            Spacer(Modifier.height(10.dp))
            Text(
                // 型で保証していることを、1文で説明する。
                text = "本文を送れないのは、方針としてそうしているだけでなく、" +
                    "計測部品に文字列を渡す口が存在しないためです。",
                style = type.labelSmall,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(28.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .glassFace(shape = RoundedCornerShape(16.dp), elevation = 2.dp)
                    .clickable {
                        haptics.perform(HapticToken.SoftConfirm)
                        optedOut = !optedOut
                        analytics.setOptedOut(optedOut)
                    }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "利用状況の送信を止める",
                        style = type.label.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "止めても、メッセージの送受信はそのまま使えます。",
                        style = type.labelSmall,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Switch(on = optedOut)
            }

            Spacer(Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .glassFace(shape = RoundedCornerShape(16.dp), elevation = 2.dp)
                    .clickable {
                        haptics.perform(HapticToken.SoftConfirm)
                        showsBody = !showsBody
                        onNotificationBodyChanged(showsBody)
                    }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "通知にメッセージの内容を表示する",
                        style = type.label.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        // 勝手に決めず、書いて示す。メッセージの本文が Rinowa 自身の
                        // 経路から出ていく唯一の場所。
                        text = if (showsBody) {
                            "オンのあいだ、通知の文面が Firebase Cloud Messaging を通ります。"
                        } else {
                            "「メッセージが届きました」とだけ表示されます。"
                        },
                        style = type.labelSmall,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Switch(on = showsBody)
            }

            Spacer(Modifier.height(14.dp))

            // 撮ったままのファイルを送る設定。
            //
            // ピッカーの中ではなくここに置く。1枚を見ながら決める選択ではなく、
            // この端末が何を送るかという継続的な判断だから。既定は切で、代償も書く
            // （オリジナルは圧縮版の10〜50倍で、送信側がその通信量を払う）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .glassFace(shape = RoundedCornerShape(16.dp), elevation = 2.dp)
                    .clickable {
                        haptics.perform(HapticToken.SoftConfirm)
                        sendsOriginals = !sendsOriginals
                        settings?.putLocal(sendsOriginals = sendsOriginals)
                    }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "写真をオリジナルでも送る",
                        style = type.label.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (sendsOriginals) {
                            "圧縮版に加えて、撮ったままのファイルも送ります。" +
                                "相手は保存するときにどちらか選べます。通信量は10〜50倍です。"
                        } else {
                            "圧縮版だけを送ります。相手が保存できるのは、この圧縮版です。"
                        },
                        style = type.labelSmall,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Switch(on = sendsOriginals)
            }

            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = RinowaTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
        color = RinowaTheme.colors.textPrimary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Line(text: String, allowed: Boolean) {
    val colors = RinowaTheme.colors
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Canvas(Modifier.size(14.dp).padding(top = 3.dp)) {
            val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val w = size.width
            val h = size.height
            val path = if (allowed) {
                Path().apply {
                    moveTo(w * 0.12f, h * 0.55f)
                    lineTo(w * 0.40f, h * 0.82f)
                    lineTo(w * 0.88f, h * 0.20f)
                }
            } else {
                Path().apply {
                    moveTo(w * 0.18f, h * 0.18f)
                    lineTo(w * 0.82f, h * 0.82f)
                    moveTo(w * 0.82f, h * 0.18f)
                    lineTo(w * 0.18f, h * 0.82f)
                }
            }
            drawPath(path, if (allowed) colors.success else colors.textTertiary, style = stroke)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = RinowaTheme.type.listPreview,
            color = if (allowed) colors.textSecondary else colors.textTertiary,
        )
    }
}

@Composable
private fun Switch(on: Boolean) {
    val colors = RinowaTheme.colors
    val progress by animateFloatAsState(if (on) 1f else 0f, label = "switch")
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(lerp(colors.surfaceSunken, colors.accent, progress)),
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .offset(x = (progress * TRAVEL_DP).dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** 軌道の幅から、つまみとその余白を引いたもの。 */
private const val TRAVEL_DP = 20f
