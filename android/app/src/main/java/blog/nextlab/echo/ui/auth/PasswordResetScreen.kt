package blog.nextlab.echo.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.auth.AuthViewModel
import blog.nextlab.echo.ui.common.ScreenHeader
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import kotlinx.coroutines.launch

/**
 * アドレスを聞いて、再設定のリンクを送る。
 *
 * 独立したページにしてある。独立した作業だから。パスワード欄の下のリンクだと、
 * 打った内容に対して何かするように見えたし、確認もしていないアドレスへメールが
 * 送られようとしていることが何も示されなかった。
 */
@Composable
fun PasswordResetScreen(viewModel: AuthViewModel, onBack: () -> Unit) {
    val colors = RinowaTheme.colors
    val haptics = LocalRinowaHaptics.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        ScreenHeader(title = "パスワードの再設定", onBack = {
            keyboard?.hide()
            onBack()
        })

        AnimatedContent(
            targetState = viewModel.resetSent,
            transitionSpec = {
                fadeIn(tween(RinowaMotion.DURATION_STANDARD)) togetherWith
                    fadeOut(tween(RinowaMotion.DURATION_QUICK))
            },
            label = "resetStage",
        ) { sent ->
            if (sent) {
                ResetSentBody(viewModel.resetEmail, onBack)
            } else {
                ResetRequestBody(
                    viewModel = viewModel,
                    onSend = {
                        keyboard?.hide()
                        scope.launch {
                            viewModel.sendPasswordReset()
                            if (viewModel.resetSent) {
                                haptics.perform(HapticToken.Success)
                            } else {
                                haptics.perform(HapticToken.Error)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ResetRequestBody(viewModel: AuthViewModel, onSend: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val notice = viewModel.notice
    val busy = viewModel.form.busy
    val canSend = !busy && viewModel.resetEmail.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "登録したメールアドレスを入力してください。\n再設定用のリンクをそのアドレスに送ります。",
            style = type.listPreview,
            color = colors.textSecondary,
        )

        Spacer(Modifier.height(24.dp))

        RinowaField(
            value = viewModel.resetEmail,
            onValueChange = viewModel::updateResetEmail,
            placeholder = "メールアドレス",
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (canSend) onSend() }),
        )

        if (notice != null) {
            Spacer(Modifier.height(14.dp))
        }
        NoticeBanner(text = notice?.text(), isError = notice?.isError() == true)

        Spacer(Modifier.height(20.dp))

        PrimaryButton(enabled = canSend, onClick = onSend) { color ->
            PrimaryButtonLabel(if (busy) "送信しています" else "再設定メールを送る", color)
        }

        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ResetSentBody(email: String, onBack: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        SentMark()
        Spacer(Modifier.height(22.dp))
        Text(
            text = "メールを送りました",
            style = type.screenTitle,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            // アカウントがあってもなくても本当になる書き方にしてある。
            // 「そのアドレスは登録されていません」と出すと、このフォームが
            // 誰が Rinowa を使っているか調べる道具になる。
            text = "$email 宛に、そのアドレスで登録されていれば再設定用のリンクが届きます。" +
                "\n届かない場合は迷惑メールもご確認ください。",
            style = type.listPreview,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        PrimaryButton(enabled = true, onClick = onBack) { color ->
            PrimaryButtonLabel("ログインに戻る", color)
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SentMark() {
    val colors = RinowaTheme.colors
    Box(
        modifier = Modifier
            .size(72.dp)
            .glassFace(shape = CircleShape, elevation = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(32.dp)) {
            val stroke = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val w = size.width
            val h = size.height
            val check = Path().apply {
                moveTo(w * 0.18f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.74f)
                lineTo(w * 0.82f, h * 0.26f)
            }
            drawPath(check, colors.success, style = stroke)
        }
    }
}
