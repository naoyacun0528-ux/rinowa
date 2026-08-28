package blog.nextlab.echo.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.ui.common.ScreenHeader
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.data.ProfilePhotos
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.ui.auth.RinowaField
import blog.nextlab.echo.ui.auth.NoticeBanner
import blog.nextlab.echo.ui.auth.PrimaryButton
import blog.nextlab.echo.ui.auth.PrimaryButtonLabel
import blog.nextlab.echo.ui.auth.QuietButton
import blog.nextlab.echo.ui.auth.QuietButtonLabel
import blog.nextlab.echo.ui.common.Avatar
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 自分のプロフィール。名前、一行、写真。
 *
 * 写真は Android のフォトピッカーから選ぶ。選んだ1枚だけが返ってきて、
 * **権限はまったく要らない** — Rinowa が写真ライブラリを読めるようになることは無い。
 * アイコンを設定するために保存領域へのアクセスを求めるのは、1つ借りるために
 * 家全体の鍵を受け取るようなもの。
 */
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    photos: ProfilePhotos?,
    me: UserId,
    onBack: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    val keyboard = LocalSoftwareKeyboardController.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) haptics.perform(HapticToken.SoftConfirm)
        viewModel.pickPhoto(uri)
    }

    // 選ぶことと保存することの間に切り取りが入る。重ねる小窓ではなく全画面にする。
    // 円をどこに置くか決めるには、写真全体が見えている必要がある。
    val cropping = viewModel.cropping
    if (cropping != null && photos != null) {
        PhotoCropScreen(
            source = cropping,
            photos = photos,
            onCancel = viewModel::cancelCrop,
            onCropped = viewModel::applyCrop,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        ScreenHeader(title = "プロフィール", onBack = {
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

            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .clickable {
                            haptics.perform(HapticToken.Selection)
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val pending = viewModel.pendingPhoto
                    // 置き場の revision を読むことで、描いたあとに届いた写真も出る。
                    // ProfilePhotos.revision を参照。
                    val stored = photos?.let {
                        it.revision
                        it.photo(me, viewModel.photoHash)
                    }

                    when {
                        pending != null -> Image(
                            bitmap = pending.asImageBitmap(),
                            contentDescription = "選んだプロフィール画像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )

                        stored != null -> Image(
                            bitmap = stored,
                            contentDescription = "現在のプロフィール画像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )

                        else -> Avatar(
                            title = viewModel.name.ifEmpty { "?" },
                            seed = me.value.hashCode(),
                            size = 112.dp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row {
                    QuietButton(
                        enabled = !viewModel.saving,
                        onClick = {
                            haptics.perform(HapticToken.Selection)
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                    ) { color -> QuietButtonLabel("写真を選ぶ", color) }

                    if (viewModel.photoHash != null || viewModel.pendingPhoto != null) {
                        QuietButton(
                            enabled = !viewModel.saving,
                            onClick = {
                                haptics.perform(HapticToken.SoftConfirm)
                                viewModel.removePhoto()
                            },
                        ) { _ -> QuietButtonLabel("削除", colors.danger) }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                // 安心できる部分で、しかも本当のことなので、ここに書く。
                text = "写真は端末の中で正方形に切り取って縮小してから保存します。" +
                    "元の写真と撮影場所などの情報は送られません。",
                style = type.labelSmall,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(24.dp))
            Text("名前", style = type.label, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            RinowaField(
                value = viewModel.name,
                onValueChange = viewModel::updateName,
                placeholder = "表示される名前",
                enabled = !viewModel.saving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(),
            )

            Spacer(Modifier.height(18.dp))
            Text("ひとこと", style = type.label, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 84.dp)
                    .glassFace(shape = RoundedCornerShape(16.dp), elevation = 2.dp)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (viewModel.statusMessage.isEmpty()) {
                    Text("（任意）", style = type.composer, color = colors.textTertiary)
                }
                BasicTextField(
                    value = viewModel.statusMessage,
                    onValueChange = viewModel::updateStatusMessage,
                    enabled = !viewModel.saving,
                    textStyle = type.composer.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (viewModel.error != null) Spacer(Modifier.height(14.dp))
            NoticeBanner(text = viewModel.error, isError = true)

            Spacer(Modifier.height(22.dp))
            PrimaryButton(
                enabled = !viewModel.saving && viewModel.name.isNotBlank() && viewModel.dirty,
                onClick = {
                    keyboard?.hide()
                    viewModel.save {
                        haptics.perform(HapticToken.Success)
                        onBack()
                    }
                },
            ) { color ->
                PrimaryButtonLabel(if (viewModel.saving) "保存しています" else "保存", color)
            }

            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
