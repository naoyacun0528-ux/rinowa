package blog.nextlab.echo.ui.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import blog.nextlab.echo.media.PhotoSharing
import blog.nextlab.echo.model.Message
import blog.nextlab.echo.model.MessageContent

/**
 * スレッドから写真ビューアを開くところ。
 *
 * 何を渡すかがビューアの仕事の半分なので、チャット画面の中に置くと本文が読めなく
 * なっていた。ここに出す。
 *
 * 並びは Firestore に問い合わせ直さず、画面がすでに持っているものから作る。だから
 * ビューアの左右送りは、いまスクロールしてきた並びそのものになる。
 */
@Composable
internal fun ChatPhotoViewer(
    opened: Message,
    messages: List<Message>,
    viewModel: ChatViewModel,
    context: Context,
    onClose: () -> Unit,
) {
    val photos = remember(messages) {
        messages.filter {
            it.content is MessageContent.Image || it.content is MessageContent.Video
        }
    }
    val start = remember(photos, opened.id) {
        photos.indexOfFirst { it.id == opened.id }.coerceAtLeast(0)
    }
    if (photos.isEmpty()) return

    PhotoViewer(
        photos = photos,
        startIndex = start,
        bitmapFor = { photo ->
            viewModel.cachedMedia(photo.mediaId)
                ?: null.also { viewModel.requestMedia(photo.mediaId, photo.mediaKey) }
        },
        revision = viewModel.mediaRevision,
        onDismiss = onClose,
        // 消せるのは自分のものだけ。相手の写真を相手の端末から引き上げる機能は作らない。
        // 列には両方の写真が混ざるので1枚ごとに判定する。
        onDelete = { target ->
            viewModel.retract(target.id)
            onClose()
        },
        onShare = { bitmap, photo ->
            PhotoSharing.share(context, bitmap, photo.mediaId.value.take(12))
        },
        // 触覚と画面の文言はビューアが持つ。1つの結果から1箇所で言うため。
        onSave = { bitmap, photo ->
            PhotoSharing.saveToGallery(context, bitmap, photo.mediaId.value.take(12))
        },
        onSaveVideo = { video ->
            val file = viewModel.wholeVideo(video)
            file != null && PhotoSharing.saveVideoToGallery(
                context,
                file,
                "rinowa-" + video.mediaId.value.take(12),
            )
        },
        onShareVideo = { video ->
            val file = viewModel.wholeVideo(video)
            file != null && PhotoSharing.shareVideo(
                context,
                file,
                "rinowa-" + video.mediaId.value.take(12),
            )
        },
        playerFor = { video, modifier, onFinished ->
            val store = viewModel.mediaStore
            if (store != null) {
                InlineVideo(
                    video = video,
                    store = store,
                    local = viewModel.localVideo(video.mediaId),
                    modifier = modifier,
                    onFinished = onFinished,
                )
            }
        },
        onSaveOriginal = { photo ->
            val file = viewModel.originalPhoto(photo)
            file != null && PhotoSharing.saveOriginalToGallery(
                context,
                file,
                photo.originalId?.value.orEmpty().take(12),
                photo.originalMime ?: "image/jpeg",
            )
        },
    )
}
