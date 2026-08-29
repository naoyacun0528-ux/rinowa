package blog.nextlab.echo.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.model.MessageContent
import androidx.compose.ui.graphics.ImageBitmap
import blog.nextlab.echo.ui.common.formatDuration

/**
 * スレッドの中の写真。
 *
 * 32px のサムネイルがメッセージに入っているので、届いた瞬間に「この写真」だと分かる
 * ものが出る。本体はタップされてから取りに行く（全部落とすと通信量を使い切る）。
 * 縦横はメッセージに入っているので、届く前から場所を正しく空けられる。空けないと
 * 読んでいる途中で行が飛ぶ。docs/MEDIA_ARCHITECTURE.md §4。
 */
@Composable
internal fun PhotoMessage(
    image: MessageContent.Image,
    isOutgoing: Boolean,
    isFirstOfGroup: Boolean,
    full: ImageBitmap?,
    onOpen: () -> Unit,
) {
    val colors = RinowaTheme.colors

    val (width, height) = bubbleSize(image.aspectRatio)
    val thumbnail = rememberThumbnail(image.thumbnail, image.mediaId)

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(bubbleShape(isOutgoing, isFirstOfGroup))
            .background(colors.surfaceSunken)
            // 本体が来てから押せる。ぼけた仮画像を全画面にしても何も見えない。
            .clickable(
                enabled = full != null,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            ),
    ) {
        // 仮画像は場所を持たせるためのもの。本体が来たあとも残っていたら失敗。
        if (full == null && thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "写真を読み込んでいます",
                contentScale = ContentScale.Crop,
                // 低画質フィルタだと、四角いブロックがピンボケ写真に見える。
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (full != null) {
            Image(
                bitmap = full,
                contentDescription = "写真",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * まだ見ていない動画。
 *
 * 1枚目はメッセージの中に入っているので、動画が並んでいても再生するまで通信は起きない。
 * 保管庫がバイト範囲を返すのはそのため（再生しながら取る）。長さを出すのは、押すかどうかを
 * 決められるようにするため。
 */
@Composable
internal fun VideoMessage(
    video: MessageContent.Video,
    isOutgoing: Boolean,
    isFirstOfGroup: Boolean,
    onOpen: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    val (width, height) = bubbleSize(video.aspectRatio)
    val poster = rememberThumbnail(video.thumbnail, video.mediaId)

    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(bubbleShape(isOutgoing, isFirstOfGroup))
            .background(colors.surfaceSunken)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen,
            ),
    ) {
        if (poster != null) {
            Image(
                bitmap = poster,
                contentDescription = "動画",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 他の絵柄と同じく自前で描く。
        Canvas(modifier = Modifier.align(Alignment.Center).size(PLAY_BADGE)) {
            drawCircle(color = Color.Black.copy(alpha = 0.45f))
            val r = size.minDimension * 0.22f
            val cx = size.width / 2f + r * 0.15f
            val cy = size.height / 2f
            drawPath(
                path = Path().apply {
                    moveTo(cx - r * 0.7f, cy - r)
                    lineTo(cx + r, cy)
                    lineTo(cx - r * 0.7f, cy + r)
                    close()
                },
                color = Color.White,
            )
        }

        Text(
            text = formatDuration(video.durationMs),
            style = type.messageMeta,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

private val PLAY_BADGE = 44.dp

/**
 * スレッドでの写真の幅。
 *
 * 吹き出しの幅は使わない。文字と同じ幅にすると、縦長の写真が壁になって会話が流れる。
 */
private const val PHOTO_WIDTH_FRACTION = 0.56f
private val PHOTO_MAX_WIDTH = 240.dp

/** 縦長は縦長のままでよいが、画面を占有はさせない。 */
private val PHOTO_MAX_HEIGHT = 320.dp

/**
 * 縦横比から吹き出しの寸法を出す。
 *
 * 縦横比は写真そのままにして箱に収める。範囲に丸めて切ると、相手が選んだ構図を
 * 勝手に変えることになる。縦長は幅を削って高さの上限に合わせる。
 */
@Composable
private fun bubbleSize(aspectRatio: Float): Pair<Dp, Dp> {
    val boxWidth = (LocalConfiguration.current.screenWidthDp.dp * PHOTO_WIDTH_FRACTION)
        .coerceAtMost(PHOTO_MAX_WIDTH)
    val ratio = aspectRatio.coerceAtLeast(0.05f)
    val naturalHeight = boxWidth / ratio
    return if (naturalHeight > PHOTO_MAX_HEIGHT) {
        PHOTO_MAX_HEIGHT * ratio to PHOTO_MAX_HEIGHT
    } else {
        boxWidth to naturalHeight
    }
}

/**
 * メッセージに入っている小さな1枚を絵にする。
 *
 * 本体の取得を待たずに出せるのはこれがあるから。key は写真ごとに変わる値で、
 * 同じ写真のあいだは decode をやり直さない。
 */
@Composable
internal fun rememberThumbnail(bytes: ByteArray, key: Any?): ImageBitmap? =
    remember(key, bytes.size) {
        if (bytes.isEmpty()) null
        // swallow-ok: 壊れたサムネイルは「無い」として扱う。本体の取得は別経路で報告される。
        else runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
