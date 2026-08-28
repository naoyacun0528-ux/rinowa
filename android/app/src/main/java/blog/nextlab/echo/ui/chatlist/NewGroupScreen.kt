package blog.nextlab.echo.ui.chatlist

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.ui.common.ScreenHeader
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.model.ConversationId
import blog.nextlab.echo.model.UserId
import blog.nextlab.echo.ui.auth.RinowaField
import blog.nextlab.echo.ui.auth.NoticeBanner
import blog.nextlab.echo.ui.auth.PrimaryButton
import blog.nextlab.echo.ui.auth.PrimaryButtonLabel
import blog.nextlab.echo.ui.common.Avatar

/**
 * グループを作る。
 *
 * 候補に出るのは、このアカウントがすでに会話したことのある人
 * （[ChatListViewModel.contacts]）。先に追加する友達一覧は別に無い。Rinowa では、
 * 話したことがあることが知っていること。一覧に居ない人へは招待コードで1回届けば、
 * 次からは居る。
 */
@Composable
fun NewGroupScreen(
    viewModel: ChatListViewModel,
    onCreated: (ConversationId) -> Unit,
    onBack: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    val keyboard = LocalSoftwareKeyboardController.current

    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableListOf<UserId>().toMutableStateList() }

    val contacts = viewModel.contacts

    fun create() {
        if (busy) return
        keyboard?.hide()
        busy = true
        notice = null
        viewModel.createGroup(title.trim(), selected.toList()) { outcome ->
            busy = false
            when (outcome) {
                is StartChatOutcome.Opened -> {
                    haptics.perform(HapticToken.Success)
                    onCreated(outcome.id)
                }

                else -> {
                    haptics.perform(HapticToken.Error)
                    notice = "作成できませんでした。通信を確認してください。"
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
        ScreenHeader(title = "グループを作る", onBack = {
            keyboard?.hide()
            onBack()
        })

        Column(Modifier.padding(horizontal = 24.dp)) {
            RinowaField(
                value = title,
                onValueChange = { title = it.take(60) },
                placeholder = "グループ名（例: 家族）",
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (selected.isEmpty()) {
                    "追加する人を選んでください"
                } else {
                    "${selected.size}人を追加します"
                },
                style = type.label,
                color = colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (contacts.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "まだ誰とも話していません",
                    style = type.listName,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "先に招待コードで1対1の会話をはじめると、その相手をグループに入れられます。",
                    style = type.listPreview,
                    color = colors.textSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(contacts, key = { it.id.value }) { profile ->
                    val isSelected = profile.id in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = RinowaDimens.glassCardMargin,
                                vertical = RinowaDimens.glassCardGap / 2,
                            )
                            .glassFace(shape = RoundedCornerShape(RinowaDimens.glassCorner))
                            .clickable {
                                haptics.perform(HapticToken.Selection)
                                if (isSelected) selected.remove(profile.id)
                                else selected.add(profile.id)
                            }
                            .padding(RinowaDimens.listItemPadding),
                    ) {
                        Avatar(title = profile.displayName, seed = profile.avatarSeed)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = profile.displayName,
                            style = type.listName,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        SelectionMark(selected = isSelected)
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 24.dp)) {
            if (notice != null) Spacer(Modifier.height(12.dp))
            NoticeBanner(text = notice, isError = true)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                enabled = !busy && selected.isNotEmpty() && title.isNotBlank(),
                onClick = ::create,
            ) { color ->
                PrimaryButtonLabel(if (busy) "作成しています" else "グループを作る", color)
            }
            Spacer(Modifier.height(20.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean) {
    val colors = RinowaTheme.colors
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else colors.surfaceSunken),
        contentAlignment = Alignment.Center,
    ) {
        if (!selected) return@Box
        Canvas(Modifier.size(14.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.16f, h * 0.54f)
                lineTo(w * 0.42f, h * 0.78f)
                lineTo(w * 0.84f, h * 0.24f)
            }
            drawPath(path, colors.onAccent, style = stroke)
        }
    }
}
