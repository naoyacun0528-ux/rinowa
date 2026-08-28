package blog.nextlab.echo.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics

/**
 * 会話を、本人の Google ドライブに控える画面。
 *
 * 脚注ではなく画面の真ん中で言う必要があることが2つ:
 *
 * 1. **暗証番号を忘れるとバックアップは開けない。** 誰にも。こちらにも。端末を出る前に
 *    暗号化していることの帰結で、E2EE のメッセージのバックアップとして成り立つ唯一の形。
 *    LINE も WhatsApp も同じことを言っていて、それでも履歴を失う人がいる。だから
 *    小さくではなく、はっきり言う。
 * 2. **数字だけの短い暗証番号は、合言葉より弱い。** 欄はどちらも受け付ける。6桁を
 *    「安全です」と言うのは耳当たりのよい嘘。計算は BackupCipher にある。
 *
 * ボタンを飾らないのは、この画面が製品紹介であってはいけないから。ここに来る人は、
 * 用心しているか、すでに何かが起きたかのどちらか。
 */
@Composable
fun BackupScreen(
    state: BackupUiState,
    onBackUp: (String) -> Unit,
    onRestore: (String) -> Unit,
    onBack: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current

    var secret by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .padding(top = 6.dp),
    ) {
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
                ) {
                    haptics.perform(HapticToken.Navigation)
                    onBack()
                },
            )
            Spacer(Modifier.fillMaxWidth(0.06f))
            Text(text = "バックアップ", style = type.screenTitle, color = colors.textPrimary)
        }

        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = "メッセージを、あなた自身の Google ドライブに保存します。",
                style = type.listName,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "保存する前に、この端末で暗号化します。" +
                    "Google からも、開発者からも、中身は読めません。",
                style = type.listPreview,
                color = colors.textSecondary,
            )

            Spacer(Modifier.height(22.dp))
            Text(
                text = "暗証番号を忘れると、二度と開けません。",
                style = type.listName,
                color = colors.danger,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "復旧の手段はありません。作りません。" +
                    "作れば、それは私たちが中身を読める設計だという意味になります。",
                style = type.listPreview,
                color = colors.textSecondary,
            )

            Spacer(Modifier.height(20.dp))
            TextField(
                value = secret,
                onValueChange = { secret = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    // NumberPassword にしない。合言葉も受け付ける欄なのに、数字の
                    // キーパッドを出すと「数字を入れるもの」と黙って伝えてしまう。
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                label = { Text("暗証番号（数字4桁以上、または長い合言葉）") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceSunken,
                    unfocusedContainerColor = colors.surfaceSunken,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "数字だけの短い番号は、時間をかければ破られます。" +
                    "長い合言葉のほうが、比べものにならないほど強くなります。",
                style = type.labelSmall,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(20.dp))
            Action(
                label = if (state.busy) "処理中…" else "いますぐバックアップ",
                enabled = !state.busy && secret.length >= MIN_SECRET,
            ) {
                haptics.perform(HapticToken.SoftConfirm)
                onBackUp(secret)
            }

            Spacer(Modifier.height(10.dp))
            Action(
                label = "ドライブから復元",
                enabled = !state.busy && secret.length >= MIN_SECRET && state.hasBackup,
            ) {
                haptics.perform(HapticToken.SoftConfirm)
                onRestore(secret)
            }

            state.message?.let { message ->
                Spacer(Modifier.height(18.dp))
                Text(
                    text = message,
                    style = type.listPreview,
                    color = if (state.failed) colors.danger else colors.textSecondary,
                )
            }

            Spacer(Modifier.height(26.dp))
            Text(
                text = "入るもの: メッセージ本文と、写真や動画の取り出しに必要な鍵。",
                style = type.labelSmall,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "入らないもの: 写真や動画そのもの。保管庫から取り直せるので、" +
                    "あなたのドライブを埋めません。",
                style = type.labelSmall,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "復元しても、相手にはもう一度届きません。" +
                    "この端末が読めなかった分を、読めるようにするだけです。",
                style = type.labelSmall,
                color = colors.textTertiary,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** 別の場所で進んでいる作業について、画面が出すもの。 */
class BackupUiState(
    val busy: Boolean = false,
    val hasBackup: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

@Composable
private fun Action(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Text(
        text = label,
        style = type.listName,
        color = if (enabled) colors.textPrimary else colors.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) colors.surfaceSunken else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/**
 * 4桁。それより短いのは、誰かが選んだ長さとは言えない。
 *
 * 推奨ではなく下限。正直な助言は欄の横の文にあり、そこには合言葉を使えと書いてある。
 */
private const val MIN_SECRET = 4
