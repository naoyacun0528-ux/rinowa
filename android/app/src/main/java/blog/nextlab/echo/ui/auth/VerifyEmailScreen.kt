package blog.nextlab.echo.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.auth.AuthState
import blog.nextlab.echo.auth.AuthViewModel
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import androidx.compose.material3.Text
import kotlinx.coroutines.launch

/**
 * 登録と、中に入ることの間の関門。
 *
 * アドレスを一度も確認していないパスワードのアカウントは、打った本人のものだと
 * まだ示されていない。パスワードの再設定はそのアドレスへ届くので、持ち主は
 * ここを通るまで定まらない。Google のアカウントはこの画面に来ない
 * （Google がすでにアドレスを確かめている）。
 */
@Composable
fun VerifyEmailScreen(viewModel: AuthViewModel, pending: AuthState.NeedsVerification) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    val scope = rememberCoroutineScope()

    val form = viewModel.form
    val notice = viewModel.notice

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        EnvelopeMark()
        Spacer(Modifier.height(22.dp))

        Text(
            text = "メールを確認してください",
            style = type.screenTitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = buildString {
                append(pending.user.email ?: "登録したアドレス")
                append(" 宛に確認メールを送りました。\n")
                append("メール内のリンクを開いてから、下のボタンを押してください。")
            },
            style = type.listPreview,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        if (notice != null) {
            Spacer(Modifier.height(18.dp))
        }
        NoticeBanner(text = notice?.text(), isError = notice?.isError() == true)

        Spacer(Modifier.height(24.dp))

        PrimaryButton(
            enabled = !form.busy,
            onClick = {
                scope.launch {
                    viewModel.checkVerification()
                    // 関門を開けるのは状態が SignedIn に変わること。ここが言うのは、
                    // それが起きたかどうかだけ。
                    if (viewModel.state.value is AuthState.SignedIn) {
                        haptics.perform(HapticToken.Success)
                    } else {
                        haptics.perform(HapticToken.Warning)
                    }
                }
            },
        ) { color ->
            PrimaryButtonLabel(if (form.busy) "確認しています" else "確認しました", color)
        }

        Spacer(Modifier.height(4.dp))

        QuietButton(
            enabled = !form.busy,
            onClick = {
                scope.launch {
                    viewModel.resendVerification()
                    haptics.perform(HapticToken.SoftConfirm)
                }
            },
        ) { color -> QuietButtonLabel("確認メールを再送する", color) }

        Spacer(Modifier.weight(1f))

        QuietButton(
            enabled = !form.busy,
            onClick = {
                haptics.perform(HapticToken.Navigation)
                viewModel.signOut()
            },
        ) { color -> QuietButtonLabel("別のアカウントを使う", color) }
        Spacer(Modifier.height(16.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun EnvelopeMark() {
    val colors = RinowaTheme.colors
    Box(
        modifier = Modifier
            .size(72.dp)
            .glassFace(shape = CircleShape, elevation = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(32.dp)) {
            val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val w = size.width
            val h = size.height
            val body = Path().apply {
                moveTo(w * 0.10f, h * 0.26f)
                lineTo(w * 0.90f, h * 0.26f)
                lineTo(w * 0.90f, h * 0.74f)
                lineTo(w * 0.10f, h * 0.74f)
                close()
            }
            drawPath(body, colors.accent, style = stroke)
            val flap = Path().apply {
                moveTo(w * 0.10f, h * 0.30f)
                lineTo(w * 0.50f, h * 0.55f)
                lineTo(w * 0.90f, h * 0.30f)
            }
            drawPath(flap, colors.accent, style = stroke)
        }
    }
}
