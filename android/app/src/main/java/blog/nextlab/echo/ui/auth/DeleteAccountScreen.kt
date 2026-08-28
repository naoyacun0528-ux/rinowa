package blog.nextlab.echo.ui.auth

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.auth.AuthProvider
import blog.nextlab.echo.auth.AuthViewModel
import blog.nextlab.echo.auth.RinowaUser
import blog.nextlab.echo.ui.common.ScreenHeader
import blog.nextlab.echo.core.designsystem.RinowaConfirmDialog
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import kotlinx.coroutines.launch

/**
 * アカウントを完全に終わらせる。
 *
 * メニュー項目＋ダイアログではなく1ページにしてある。正直に書くと、この画面は
 * ほとんどが*文章*になるから（何が消えて、何が消えず、何をしても戻らない）。
 * ダイアログにはその余地が無く、それを書かないダイアログは落とし穴。
 *
 * ダイアログ自体は出るが、意識的な2つの操作の2つ目としてであって、唯一のものではない。
 */
@Composable
fun DeleteAccountScreen(
    viewModel: AuthViewModel,
    user: RinowaUser,
    onBack: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val activity = LocalActivity.current

    val busy = viewModel.form.busy
    val notice = viewModel.notice
    // Google のアカウントは Google 自身のシートで本人確認するので、打つものが無い。
    // パスワードのアカウントは入れ直す必要がある。
    val needsPassword = AuthProvider.Google !in user.providers
    val ready = !busy && (!needsPassword || viewModel.deletePassword.isNotEmpty())

    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        RinowaConfirmDialog(
            title = "本当に削除しますか",
            message = "アカウントと、Rinowa に保存されているあなたのデータが消えます。元に戻すことはできません。",
            confirmLabel = "削除する",
            destructive = true,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                val host = activity ?: return@RinowaConfirmDialog
                keyboard?.hide()
                scope.launch {
                    // 呼び出しのあとではなく前に鳴らす。成功すると結果が返る頃には
                    // サインアウトしていて、この画面はもう無い。
                    haptics.perform(HapticToken.Destructive)
                    if (!viewModel.deleteAccount(host)) haptics.perform(HapticToken.Error)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        ScreenHeader(title = "アカウントの削除", onBack = {
            keyboard?.hide()
            onBack()
        })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            WarningMark()
            Spacer(Modifier.height(18.dp))

            Text(
                text = "削除すると、次のものが失われます。",
                style = type.listName,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Bullet("アカウントとプロフィール")
            Bullet("あなたが作ったスタンプ")
            Bullet("会話への参加。あなたの端末にある履歴も開けなくなります")

            Spacer(Modifier.height(16.dp))
            Text(
                // 本当のことで、あとではなく先に知るべきことなので、はっきり書く。
                text = "相手の端末に既に届いたメッセージは、相手の手元に残ります。" +
                    "送ったものを取り消す機能ではありません。",
                style = type.listPreview,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "この操作は取り消せません。",
                style = type.listPreview,
                color = colors.danger,
            )

            if (needsPassword) {
                Spacer(Modifier.height(22.dp))
                Text(
                    text = "確認のためパスワードを入力してください。",
                    style = type.label,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                RinowaField(
                    value = viewModel.deletePassword,
                    onValueChange = viewModel::updateDeletePassword,
                    placeholder = "パスワード",
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            if (notice != null) {
                Spacer(Modifier.height(14.dp))
            }
            NoticeBanner(text = notice?.text(), isError = notice?.isError() == true)

            Spacer(Modifier.height(24.dp))

            PrimaryButton(
                enabled = ready,
                tint = colors.danger,
                onClick = {
                    haptics.perform(HapticToken.Warning)
                    confirming = true
                },
            ) { color ->
                PrimaryButtonLabel(if (busy) "削除しています" else "アカウントを削除", color)
            }

            Spacer(Modifier.height(4.dp))

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QuietButton(
                    enabled = !busy,
                    onClick = {
                        haptics.perform(HapticToken.Navigation)
                        onBack()
                    },
                ) { color -> QuietButtonLabel("やめる", color) }
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun Bullet(text: String) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(colors.textTertiary),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = type.listPreview, color = colors.textSecondary)
    }
}

@Composable
private fun WarningMark() {
    val colors = RinowaTheme.colors
    Box(
        modifier = Modifier
            .size(56.dp)
            .glassFace(shape = RoundedCornerShape(18.dp), elevation = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val stroke = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val w = size.width
            val h = size.height
            val bang = Path().apply {
                moveTo(w * 0.5f, h * 0.22f)
                lineTo(w * 0.5f, h * 0.58f)
            }
            drawPath(bang, colors.danger, style = stroke)
            drawCircle(colors.danger, radius = w * 0.055f, center = center.copy(y = h * 0.78f))
        }
    }
}
