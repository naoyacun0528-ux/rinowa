package blog.nextlab.echo.ui.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.ui.auth.NoticeBanner
import blog.nextlab.echo.ui.auth.PrimaryButton
import blog.nextlab.echo.ui.auth.PrimaryButtonLabel
import blog.nextlab.echo.ui.auth.QuietButton
import blog.nextlab.echo.ui.auth.QuietButtonLabel
import blog.nextlab.echo.ui.auth.RinowaField

/**
 * 会話を、本人の Google ドライブに控える画面。
 *
 * **一度に一つのことだけ訊く。** 前の作りは1画面に全部載せていて、初めて開いた人は
 * 「保存します」「暗号化します」「忘れると開けません」「復旧手段はありません」
 * 「数字だけだと弱いです」「入るもの」「入らないもの」を読んでから、
 * ようやく最初の一文字を打つことになっていた。読む前に手が止まる。
 *
 * 順番も逆だった。暗証番号を決めさせてから Google の許可を求めていたので、
 * 許可しなかった人は、使われない暗証番号を考えたことになる。
 * **置き場所を先に決める。** 鍵はそのあと。
 *
 * 分けたぶん、それぞれの段で言うことが減る。減った代わりに
 * **言うべき場所で言う**——「忘れると二度と開けません」は概要ではなく、
 * 暗証番号を打つ画面に出る。そこでしか意味を持たない。
 */
@Composable
fun BackupScreen(
    state: BackupUiState,
    onConnectDrive: () -> Unit,
    onBackUp: (String) -> Unit,
    onRestore: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val haptics = LocalRinowaHaptics.current

    var step by remember { mutableStateOf(Step.Overview) }
    var secret by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    // 終わったら最初の画面へ戻す。**「保存しました」を暗証番号の画面で読ませない。**
    // 済んだ作業の画面に留まると、もう一度押すものがまだあるように見える。
    val finished = !state.busy && !state.failed && state.message != null
    LaunchedEffect(finished) {
        if (finished && step != Step.Overview) {
            secret = ""
            again = ""
            mismatch = false
            step = Step.Overview
        }
    }

    // 前の段へ。**一段ずつしか戻らない。** 打ち直しが一段分で済むように。
    fun back() {
        haptics.perform(HapticToken.Navigation)
        when (step) {
            Step.Overview -> onBack()
            Step.Choose -> { secret = ""; step = Step.Overview }
            Step.Confirm -> { again = ""; mismatch = false; step = Step.Choose }
            Step.RestorePin -> { secret = ""; step = Step.Overview }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .padding(top = 6.dp),
    ) {
        Header(title = step.title, onBack = { back() })

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    // 進むと左へ、戻ると右へ。**どちらへ動いたかが見えれば、
                    // 戻れることも見える。**
                    val forward = targetState.ordinal > initialState.ordinal
                    val side = if (forward) 1 else -1
                    (slideInHorizontally(tween(280)) { it * side / 5 } + fadeIn(tween(220)))
                        .togetherWith(
                            slideOutHorizontally(tween(280)) { -it * side / 5 } +
                                fadeOut(tween(160)),
                        )
                },
                label = "backupStep",
            ) { current ->
                when (current) {
                    Step.Overview -> Overview(
                        state = state,
                        onConnect = {
                            haptics.perform(HapticToken.SoftConfirm)
                            onConnectDrive()
                        },
                        onStartBackup = {
                            haptics.perform(HapticToken.Navigation)
                            secret = ""
                            step = Step.Choose
                        },
                        onStartRestore = {
                            haptics.perform(HapticToken.Navigation)
                            secret = ""
                            step = Step.RestorePin
                        },
                    )

                    Step.Choose -> ChooseSecret(
                        secret = secret,
                        onSecretChange = { secret = it },
                        onNext = {
                            haptics.perform(HapticToken.Navigation)
                            again = ""
                            mismatch = false
                            step = Step.Confirm
                        },
                    )

                    Step.Confirm -> ConfirmSecret(
                        again = again,
                        mismatch = mismatch,
                        busy = state.busy,
                        onAgainChange = { again = it; mismatch = false },
                        onDone = {
                            if (again == secret) {
                                haptics.perform(HapticToken.SoftConfirm)
                                onBackUp(secret)
                            } else {
                                // **打ち間違いは失敗ではない。** 消して、その場で言う。
                                haptics.perform(HapticToken.Warning)
                                mismatch = true
                                again = ""
                            }
                        },
                    )

                    Step.RestorePin -> RestoreSecret(
                        secret = secret,
                        busy = state.busy,
                        onSecretChange = { secret = it },
                        onDone = {
                            haptics.perform(HapticToken.SoftConfirm)
                            onRestore(secret)
                        },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            NoticeBanner(text = state.message, isError = state.failed)
            Spacer(Modifier.height(40.dp))
        }
    }
}

private enum class Step(val title: String) {
    Overview("バックアップ"),
    Choose("暗証番号を決める"),
    Confirm("もう一度入力"),
    RestorePin("バックアップから復元"),
}

// ------------------------------------------------------------------ それぞれの段

@Composable
private fun Overview(
    state: BackupUiState,
    onConnect: () -> Unit,
    onStartBackup: () -> Unit,
    onStartRestore: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Column {
        Text(
            text = "メッセージを、あなた自身の Google ドライブに保存します。",
            style = type.listName,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "保存する前に、この端末で暗号化します。",
            style = type.listPreview,
            color = colors.textSecondary,
        )

        Spacer(Modifier.height(26.dp))
        DriveRow(connected = state.connected)

        Spacer(Modifier.height(22.dp))
        if (!state.connected) {
            // **置き場所を先に決める。** 許可しない人に暗証番号を考えさせない。
            PrimaryButton(enabled = !state.busy, onClick = onConnect) { tint ->
                PrimaryButtonLabel(
                    text = if (state.busy) "確認しています…" else "Google ドライブに接続",
                    color = tint,
                )
            }
        } else {
            PrimaryButton(enabled = !state.busy, onClick = onStartBackup) { tint ->
                PrimaryButtonLabel(
                    text = if (state.busy) "処理中…" else "いますぐバックアップ",
                    color = tint,
                )
            }
            if (state.hasBackup) {
                Spacer(Modifier.height(10.dp))
                QuietButton(enabled = !state.busy, onClick = onStartRestore) { tint ->
                    QuietButtonLabel(text = "バックアップから復元", color = tint)
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Details()
    }
}

@Composable
private fun ChooseSecret(secret: String, onSecretChange: (String) -> Unit, onNext: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Column {
        Text(
            text = "暗証番号を忘れると、二度と開けません。",
            style = type.listName,
            color = colors.danger,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "復旧の手段はありません。作りません。",
            style = type.listPreview,
            color = colors.textSecondary,
        )

        Spacer(Modifier.height(22.dp))
        SecretField(
            value = secret,
            onValueChange = onSecretChange,
            placeholder = "暗証番号",
            imeAction = ImeAction.Next,
            onSubmit = { if (secret.length >= MIN_SECRET) onNext() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "4文字以上。長い合言葉のほうが、比べものにならないほど強くなります。",
            style = type.labelSmall,
            color = colors.textTertiary,
        )

        Spacer(Modifier.height(22.dp))
        PrimaryButton(enabled = secret.length >= MIN_SECRET, onClick = onNext) { tint ->
            PrimaryButtonLabel(text = "次へ", color = tint)
        }
    }
}

@Composable
private fun ConfirmSecret(
    again: String,
    mismatch: Boolean,
    busy: Boolean,
    onAgainChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Column {
        Text(
            text = "確認のため、同じものをもう一度入力してください。",
            style = type.listName,
            color = colors.textPrimary,
        )

        Spacer(Modifier.height(22.dp))
        SecretField(
            value = again,
            onValueChange = onAgainChange,
            placeholder = "暗証番号（確認）",
            imeAction = ImeAction.Done,
            onSubmit = { if (again.isNotEmpty()) onDone() },
        )
        if (mismatch) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "一致しません。もう一度入力してください。",
                style = type.labelSmall,
                color = colors.danger,
            )
        }

        Spacer(Modifier.height(22.dp))
        PrimaryButton(enabled = !busy && again.isNotEmpty(), onClick = onDone) { tint ->
            PrimaryButtonLabel(text = if (busy) "処理中…" else "バックアップを開始", color = tint)
        }
    }
}

@Composable
private fun RestoreSecret(
    secret: String,
    busy: Boolean,
    onSecretChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Column {
        Text(
            text = "バックアップを取ったときの暗証番号を入力してください。",
            style = type.listName,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "復元しても、相手にはもう一度届きません。" +
                "この端末が読めなかった分を、読めるようにするだけです。",
            style = type.listPreview,
            color = colors.textSecondary,
        )

        Spacer(Modifier.height(22.dp))
        SecretField(
            value = secret,
            onValueChange = onSecretChange,
            placeholder = "暗証番号",
            imeAction = ImeAction.Done,
            onSubmit = { if (secret.length >= MIN_SECRET) onDone() },
        )

        Spacer(Modifier.height(22.dp))
        PrimaryButton(enabled = !busy && secret.length >= MIN_SECRET, onClick = onDone) { tint ->
            PrimaryButtonLabel(text = if (busy) "処理中…" else "復元する", color = tint)
        }
    }
}

// ------------------------------------------------------------------ 部品

/**
 * 暗証番号の欄。**隠したままにしない。**
 *
 * 打ったものが一度も見えないと、決める欄と確認欄で同じ打ち間違いをすることがある。
 * その二つが揃うと、**開けないバックアップが黙って出来上がる**。
 * 目のボタンは飾りではなく、その組み合わせを潰すためにある。
 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onSubmit: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val haptics = LocalRinowaHaptics.current
    var visible by remember { mutableStateOf(false) }

    RinowaField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        keyboardOptions = KeyboardOptions(
            // NumberPassword にしない。合言葉も受け付ける欄なのに、数字の
            // キーパッドを出すと「数字を入れるもの」と黙って伝えてしまう。
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = { onSubmit() },
            onDone = { onSubmit() },
        ),
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailing = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable {
                        haptics.perform(HapticToken.Selection)
                        visible = !visible
                    },
            ) {
                Text(
                    text = if (visible) "隠す" else "表示",
                    style = RinowaTheme.type.labelSmall,
                    color = colors.textSecondary,
                )
            }
        },
    )
}

/** 置き場所が繋がっているかどうか。**文字だけでなく印でも示す。** */
@Composable
private fun DriveRow(connected: Boolean) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceSunken)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Google ドライブ", style = type.listName, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (connected) "接続済み" else "まだ接続していません",
                style = type.labelSmall,
                color = if (connected) colors.textSecondary else colors.textTertiary,
            )
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (connected) colors.success else colors.outline),
        )
    }
}

/**
 * 中身の話。**畳んでおく。**
 *
 * 消さないのは、消すと「何が入るか分からないもの」を預けさせることになるから。
 * 開いたままにしないのは、初めて来た人がここで止まるから。
 */
@Composable
private fun Details() {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    var open by remember { mutableStateOf(false) }

    Column {
        Text(
            text = if (open) "何が保存されるか  −" else "何が保存されるか  ＋",
            style = type.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.perform(HapticToken.Selection)
                open = !open
            },
        )
        if (open) {
            Spacer(Modifier.height(10.dp))
            Line("入るもの: メッセージ本文と、写真や動画の取り出しに必要な鍵。")
            Line(
                "入らないもの: 写真や動画そのもの。保管庫から取り直せるので、" +
                    "あなたのドライブを埋めません。",
            )
            Line("Google からも、開発者からも、中身は読めません。")
        }
    }
}

@Composable
private fun Line(text: String) {
    Text(
        text = text,
        style = RinowaTheme.type.labelSmall,
        color = RinowaTheme.colors.textTertiary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, bottom = 6.dp),
    ) {
        Text(
            text = "戻る",
            style = type.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBack,
            ),
        )
        Spacer(Modifier.width(18.dp))
        // 段が変わると題名も変わる。**今どこにいるかは題名が持つ。**
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(140))) },
            label = "backupTitle",
        ) { shown ->
            Text(text = shown, style = type.screenTitle, color = colors.textPrimary)
        }
    }
}

/** 別の場所で進んでいる作業について、画面が出すもの。 */
class BackupUiState(
    val busy: Boolean = false,
    val connected: Boolean = false,
    val hasBackup: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

/**
 * 4文字。それより短いのは、誰かが選んだ長さとは言えない。
 *
 * 推奨ではなく下限。正直な助言は欄の下の文にあり、そこには合言葉を使えと書いてある。
 */
private const val MIN_SECRET = 4
