package blog.nextlab.echo.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import blog.nextlab.echo.ui.common.ScreenHeader
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.data.UserRepository
import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.ui.auth.RinowaField
import blog.nextlab.echo.ui.auth.NoticeBanner
import blog.nextlab.echo.ui.auth.PrimaryButton
import blog.nextlab.echo.ui.auth.PrimaryButtonLabel

/**
 * 会話を始める。
 *
 * 検索欄ではなくコードにしている理由。メールアドレスや電話番号を打って、その人が
 * 使っているかどうかが見えるメッセンジャーは、構造として「この人はここにいますか」に
 * 誰にでも答えるサービスを作っている。その答えは、答えることに同意していない人についてのもの。
 *
 * 本人が渡すと決めたコードなら逆になる。持ち主が明かすまで、何も見つからない。
 * firestore.rules は利用者のコレクションもコードのコレクションも一覧させないので、
 * 試すクライアントがあっても集合を歩けない。
 */
@Composable
fun NewConversationScreen(
    viewModel: ChatListViewModel,
    onOpened: (ConversationId) -> Unit,
    onBack: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current

    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    val myCode = viewModel.inviteCode

    fun start() {
        if (busy) return
        keyboard?.hide()
        busy = true
        notice = null
        viewModel.startDirect(code) { outcome ->
            busy = false
            when (outcome) {
                is StartChatOutcome.Opened -> {
                    haptics.perform(HapticToken.Success)
                    onOpened(outcome.id)
                }

                StartChatOutcome.NotFound -> {
                    haptics.perform(HapticToken.Error)
                    notice = "そのコードの相手が見つかりませんでした。"
                }

                StartChatOutcome.Yourself -> {
                    haptics.perform(HapticToken.Warning)
                    notice = "自分のコードです。相手のコードを入れてください。"
                }

                StartChatOutcome.Failed -> {
                    haptics.perform(HapticToken.Error)
                    notice = "会話をはじめられませんでした。通信を確認してください。"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        ScreenHeader(title = "会話をはじめる", onBack = {
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

            Text(text = "あなたの招待コード", style = type.label, color = colors.textSecondary)
            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .glassFace(shape = RoundedCornerShape(16.dp), elevation = 2.dp)
                    .padding(start = 18.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            ) {
                Text(
                    text = myCode?.let(UserRepository::format) ?: "……",
                    style = type.screenTitle.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                    ),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (myCode != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                haptics.perform(HapticToken.SoftConfirm)
                                clipboard.setText(AnnotatedString(UserRepository.format(myCode)))
                                copied = true
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = if (copied) "コピーしました" else "コピー",
                            style = type.label,
                            color = colors.accent,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "このコードを渡した相手だけが、あなたを見つけられます。",
                style = type.labelSmall,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(32.dp))

            Text(text = "相手の招待コード", style = type.label, color = colors.textSecondary)
            Spacer(Modifier.height(10.dp))

            RinowaField(
                value = code,
                onValueChange = { code = it.uppercase().take(12); notice = null },
                placeholder = "ABCD-EFGH",
                enabled = !busy,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { start() }),
            )

            if (notice != null) {
                Spacer(Modifier.height(14.dp))
            }
            NoticeBanner(text = notice, isError = true)

            Spacer(Modifier.height(20.dp))

            PrimaryButton(
                enabled = !busy &&
                    UserRepository.normalise(code).length == UserRepository.CODE_LENGTH,
                onClick = ::start,
            ) { color ->
                PrimaryButtonLabel(if (busy) "探しています" else "この相手と話す", color)
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
