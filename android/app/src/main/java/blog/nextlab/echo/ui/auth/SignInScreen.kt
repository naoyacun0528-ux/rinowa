package blog.nextlab.echo.ui.auth

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.auth.AuthMode
import blog.nextlab.echo.auth.AuthViewModel
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import kotlinx.coroutines.launch
// play-services-base は自身を com.google.android.gms.base として宣言しているので、
// R クラスがあるのは com.google.android.gms ではなくそちら。
import com.google.android.gms.base.R as GmsResources

/**
 * サインアウト側の画面。フォームと、その奥のパスワード再設定。
 *
 * 再設定はフォーム上の操作ではなく別画面にしてある。パスワード欄の横のリンクだと
 * 「いま打ったものに何かする」と読めるが、実際にやるのはアドレスを聞いてメールを
 * 送ること。別の作業なので、別のページを持つに値する。
 */
@Composable
fun SignInScreen(viewModel: AuthViewModel) {
    var showReset by remember { mutableStateOf(false) }

    fun leaveReset() {
        showReset = false
        viewModel.leavePasswordReset()
    }

    BackHandler(enabled = showReset) { leaveReset() }

    AnimatedContent(
        targetState = showReset,
        transitionSpec = {
            val duration = RinowaMotion.DURATION_STANDARD
            val forward = targetState
            val direction = if (forward) 1 else -1
            (
                slideInHorizontally(
                    animationSpec = tween(duration, easing = RinowaMotion.standardEasing),
                ) { width -> direction * width / 3 } + fadeIn(tween(duration))
                ) togetherWith (
                slideOutHorizontally(
                    animationSpec = tween(duration, easing = RinowaMotion.exitEasing),
                ) { width -> -direction * width / 6 } + fadeOut(tween(RinowaMotion.DURATION_QUICK))
                )
        },
        label = "signInPage",
    ) { reset ->
        if (reset) {
            PasswordResetScreen(viewModel, onBack = ::leaveReset)
        } else {
            SignInForm(
                viewModel = viewModel,
                onForgotPassword = {
                    viewModel.beginPasswordReset()
                    showReset = true
                },
            )
        }
    }
}

@Composable
private fun SignInForm(viewModel: AuthViewModel, onForgotPassword: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // Credential Manager は画面を出すので、Application ではなく Activity が要る。
    val activity = LocalActivity.current

    val form = viewModel.form
    val notice = viewModel.notice
    val signingUp = form.mode == AuthMode.SignUp
    var revealPassword by remember { mutableStateOf(false) }

    fun submit() {
        keyboard?.hide()
        scope.launch {
            haptics.perform(HapticToken.SoftConfirm)
            if (!viewModel.submit()) haptics.perform(HapticToken.Error)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        RinowaMark()
        Spacer(Modifier.height(18.dp))
        Text(
            text = "RINOWA",
            style = type.screenTitle.copy(
                fontSize = type.screenTitle.fontSize * 1.5f,
                fontWeight = FontWeight.Bold,
            ),
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        SwapContent(target = signingUp, forward = signingUp) { isSignUp ->
            Text(
                text = if (isSignUp) "アカウントを作成します" else "おかえりなさい",
                style = type.listPreview,
                color = colors.textSecondary,
            )
        }

        Spacer(Modifier.height(32.dp))

        GoogleButton(
            signingUp = signingUp,
            enabled = !form.busy && activity != null,
            onClick = {
                val host = activity ?: return@GoogleButton
                keyboard?.hide()
                scope.launch {
                    if (!viewModel.signInWithGoogle(host)) haptics.perform(HapticToken.Error)
                }
            },
        )

        Spacer(Modifier.height(8.dp))
        Text(
            // ボタン自身には言えないことを補う。Google サインインは両方の入口で、
            // どちらになるかはアカウント次第。
            text = "アカウントが無ければ作成され、あればログインします。",
            style = type.labelSmall,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(18.dp))
        OrDivider()
        Spacer(Modifier.height(18.dp))

        RinowaField(
            value = form.email,
            onValueChange = viewModel::setEmail,
            placeholder = "メールアドレス",
            enabled = !form.busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(),
        )

        Spacer(Modifier.height(10.dp))

        RinowaField(
            value = form.password,
            onValueChange = viewModel::setPassword,
            placeholder = if (signingUp) "パスワード（6文字以上）" else "パスワード",
            enabled = !form.busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (form.canSubmit) submit() }),
            visualTransformation = if (revealPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailing = {
                RevealToggle(revealed = revealPassword) {
                    haptics.perform(HapticToken.Selection)
                    revealPassword = !revealPassword
                }
            },
        )

        if (notice != null) {
            Spacer(Modifier.height(14.dp))
        }
        NoticeBanner(text = notice?.text(), isError = notice?.isError() == true)

        Spacer(Modifier.height(20.dp))

        PrimaryButton(enabled = form.canSubmit, onClick = ::submit) { color ->
            val label = when {
                form.busy -> "しばらくお待ちください"
                signingUp -> "登録する"
                else -> "ログイン"
            }
            SwapContent(target = label, forward = signingUp) { PrimaryButtonLabel(it, color) }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietButton(
                enabled = !form.busy,
                onClick = {
                    haptics.perform(HapticToken.Selection)
                    viewModel.setMode(if (signingUp) AuthMode.SignIn else AuthMode.SignUp)
                },
            ) { color ->
                SwapContent(target = signingUp, forward = signingUp) { isSignUp ->
                    QuietButtonLabel(
                        text = if (isSignUp) "ログインに戻る" else "新規登録",
                        color = color,
                    )
                }
            }

            // 新規登録には忘れるパスワードがまだ無い。逃げ道は、意味のある側にだけ置く。
            AnimatedVisibility(
                visible = !signingUp,
                enter = fadeIn(tween(RinowaMotion.DURATION_QUICK)) + expandHorizontally(),
                exit = fadeOut(tween(RinowaMotion.DURATION_INSTANT)) + shrinkHorizontally(),
            ) {
                QuietButton(
                    enabled = !form.busy,
                    onClick = {
                        haptics.perform(HapticToken.Navigation)
                        onForgotPassword()
                    },
                ) { color -> QuietButtonLabel("パスワードを忘れた", color) }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = "メッセージの本文は配信のためだけに使われます。",
            style = type.labelSmall,
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** 3本の波紋の印を見出しの大きさで。チャット一覧のバーにあるものと同じ形。 */
@Composable
internal fun RinowaMark() {
    val colors = RinowaTheme.colors
    Box(
        modifier = Modifier
            .size(72.dp)
            .glassFace(shape = CircleShape, elevation = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            val stroke = Stroke(
                width = 2.6.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.30f, h * 0.34f)
                quadraticTo(w * 0.14f, h * 0.5f, w * 0.30f, h * 0.66f)
                moveTo(w * 0.52f, h * 0.20f)
                quadraticTo(w * 0.30f, h * 0.5f, w * 0.52f, h * 0.80f)
                moveTo(w * 0.74f, h * 0.10f)
                quadraticTo(w * 0.46f, h * 0.5f, w * 0.74f, h * 0.90f)
            }
            drawPath(path, colors.accent, style = stroke)
        }
    }
}

@Composable
private fun GoogleButton(signingUp: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .glassFace(shape = RoundedCornerShape(16.dp), elevation = 2.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        GoogleMark()
        Spacer(Modifier.width(12.dp))
        // どちらのラベルでも呼ぶ先は同じ。Google の身元が初めてなら Firebase が
        // アカウントを作るので「ログイン」でも登録になる。驚かせずに済むよう、
        // ボタンの下の説明にそう書いてある。
        SwapContent(target = signingUp, forward = signingUp) { isSignUp ->
            Text(
                text = if (isSignUp) "Google で新規登録" else "Google でログイン",
                style = type.label.copy(fontWeight = FontWeight.Medium),
                color = if (enabled) colors.textPrimary else colors.textTertiary,
            )
        }
    }
}

/**
 * Google の「G」。描き起こしではなく本家の素材。
 *
 * `googleg_standard_color_18` は play-services-base に入っていて、それは
 * Credential Manager のためにすでにクラスパスにある。使えば、印は Google の
 * ブランド規約が求めるものそのものになり、依存を更新すれば追随し、ファイルは
 * すでにビルドに入っているのでアプリの大きさも増えない。
 *
 * 最初は4本の弧で自分で描いていた。それと分かる出来ではあったが、それでも間違い。
 * 他人の商標を記憶で再現するというのは、そういうこと。
 */
@Composable
private fun GoogleMark() {
    Image(
        painter = painterResource(GmsResources.drawable.googleg_standard_color_18),
        // 装飾。ボタンの文字がすでに何をするか言っている。
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun OrDivider() {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.outline),
        )
        Text(
            text = "または",
            style = type.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.outline),
        )
    }
}

@Composable
private fun RevealToggle(revealed: Boolean, onClick: () -> Unit) {
    val colors = RinowaTheme.colors
    Box(
        modifier = Modifier
            .size(RinowaDimens.touchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val stroke = Stroke(
                width = 1.9.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            val w = size.width
            val h = size.height
            val eye = Path().apply {
                moveTo(w * 0.08f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.14f, w * 0.92f, h * 0.5f)
                quadraticTo(w * 0.5f, h * 0.86f, w * 0.08f, h * 0.5f)
                close()
            }
            drawPath(eye, colors.textSecondary, style = stroke)
            drawCircle(colors.textSecondary, radius = w * 0.15f, center = center)
            if (!revealed) {
                val slash = Path().apply {
                    moveTo(w * 0.16f, h * 0.84f)
                    lineTo(w * 0.84f, h * 0.16f)
                }
                drawPath(slash, colors.textSecondary, style = stroke)
            }
        }
    }
}
