package blog.nextlab.echo.ui.auth

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.auth.AuthProvider
import blog.nextlab.echo.auth.RinowaUser
import blog.nextlab.echo.ui.common.ScreenHeader
import blog.nextlab.echo.core.designsystem.RinowaConfirmDialog
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.data.UserRepository
import blog.nextlab.echo.ui.common.Avatar

/**
 * いま誰としてサインインしているかと、そこから出る方法。
 *
 * わざと薄い。設定画面ではなく、サインアウトに手が届くようにするためのもの。
 * 実際に必要だし、サインインの流れを2回試す唯一の方法でもある。
 */
@Composable
fun AccountScreen(
    user: RinowaUser,
    inviteCode: String?,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onOpenFeedback: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenDirectLab: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    var confirmingSignOut by remember { mutableStateOf(false) }

    if (confirmingSignOut) {
        RinowaConfirmDialog(
            title = "ログアウトしますか",
            message = "この端末から出るだけで、アカウントは残ります。同じアカウントでいつでも戻れます。",
            confirmLabel = "ログアウト",
            onDismiss = {
                haptics.perform(HapticToken.Selection)
                confirmingSignOut = false
            },
            onConfirm = {
                confirmingSignOut = false
                // 取り消せるので SoftConfirm。Destructive は削除の画面のために取ってある。
                // ここで使うと2つが同じ重さに感じられる。
                haptics.perform(HapticToken.SoftConfirm)
                onSignOut()
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "アカウント", onBack = onBack)

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RinowaDimens.glassCardMargin)
                .glassFace(shape = RoundedCornerShape(RinowaDimens.glassCorner))
                .padding(RinowaDimens.listItemPadding),
        ) {
            Avatar(
                title = user.displayName ?: user.email ?: "?",
                seed = user.uid.hashCode(),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = user.displayName ?: "名前未設定",
                    style = type.listName,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = user.email ?: "アドレス未登録",
                    style = type.listPreview,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = user.providers.joinToString(" ・ ") { provider ->
                        when (provider) {
                            AuthProvider.Google -> "Google"
                            AuthProvider.Password -> "メールアドレス"
                        }
                    },
                    style = type.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }

        if (inviteCode != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RinowaDimens.glassCardMargin)
                    .glassFace(shape = RoundedCornerShape(RinowaDimens.glassCorner))
                    .padding(RinowaDimens.listItemPadding),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = "招待コード", style = type.labelSmall, color = colors.textTertiary)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = UserRepository.format(inviteCode),
                        style = type.listName,
                        color = colors.textPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        LinkRow("プロフィールを編集") {
            haptics.perform(HapticToken.Navigation)
            onOpenProfile()
        }
        LinkRow("フィードバックを送る・見る") {
            haptics.perform(HapticToken.Navigation)
            onOpenFeedback()
        }
        LinkRow("プライバシーと計測") {
            haptics.perform(HapticToken.Navigation)
            onOpenPrivacy()
        }
        LinkRow("バックアップ") {
            haptics.perform(HapticToken.Navigation)
            onOpenBackup()
        }
        // Direct-1 の開発用。Rinowa Direct が自分で経路を選ぶようになり、誰も押さなく
        // なった時点でここから外す。
        LinkRow("Rinowa Direct（検証中）") {
            haptics.perform(HapticToken.Navigation)
            onOpenDirectLab()
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            QuietButton(
                enabled = true,
                onClick = {
                    haptics.perform(HapticToken.Selection)
                    confirmingSignOut = true
                },
                modifier = Modifier.align(Alignment.Center),
            ) { color -> QuietButtonLabel("ログアウト", color) }
        }

        Spacer(Modifier.height(4.dp))

        // サインアウトから離して、危険の色で、直接実行せずページへ進ませる。
        // どれも、2つが混同されないようにするためにある。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            QuietButton(
                enabled = true,
                onClick = {
                    haptics.perform(HapticToken.Navigation)
                    onDeleteAccount()
                },
                modifier = Modifier.align(Alignment.Center),
            ) { _ -> QuietButtonLabel("アカウントを削除", colors.danger) }
        }

        Spacer(Modifier.height(20.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = RinowaDimens.screenPadding, vertical = 15.dp),
    ) {
        Text(text = label, style = type.label, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Canvas(Modifier.size(16.dp)) {
            val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.38f, h * 0.20f)
                lineTo(w * 0.68f, h * 0.5f)
                lineTo(w * 0.38f, h * 0.80f)
            }
            drawPath(path, colors.textTertiary, style = stroke)
        }
    }
}
