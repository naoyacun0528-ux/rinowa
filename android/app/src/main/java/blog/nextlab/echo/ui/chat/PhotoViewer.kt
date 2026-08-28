package blog.nextlab.echo.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.FrostedBar
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.backdropSource
import blog.nextlab.echo.core.designsystem.rememberBackdropState
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.model.Message
import blog.nextlab.echo.model.MessageContent
import blog.nextlab.echo.ui.common.backPull
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 写真を全画面で見る。
 *
 * ピンチで拡大、下へドラッグで閉じる。倍率は画面外へ逃げない範囲に丸める。
 */
@Composable
fun PhotoViewer(
    /**
     * 会話の写真と動画を古い順に並べたもの。
     *
     * 会話の中の1枚は「送り合った列の1枚」なので、横スワイプで前後に行ける。
     * 最初は写真だけ集めていて、動画が飛ばされていた。
     */
    photos: List<Message>,
    startIndex: Int,
    /** 本体の画像。取得中は null。 */
    bitmapFor: (MessageContent.Image) -> ImageBitmap?,
    /** 取得が届くたびに増える。読むことで、スワイプ中に届いた画像も反映される。 */
    revision: Int,
    onDismiss: () -> Unit,
    onDelete: ((Message) -> Unit)?,
    onShare: (ImageBitmap, MessageContent.Image) -> Unit,
    /** 保存できたかを返す。画面に出す文言をここで決めるため。 */
    onSave: (ImageBitmap, MessageContent.Image) -> Boolean,
    /**
     * 送信側がオリジナルも送っていたときだけ非 null。
     *
     * これがあるかどうかで、保存が「動作」から「二択」に変わる。
     */
    onSaveOriginal: (suspend (MessageContent.Image) -> Boolean)? = null,
    /**
     * 再生中の動画を、そのページいっぱいに描く。
     *
     * 別画面のプレイヤーにすると列からもドラッグからも外れて、再生した瞬間に
     * スワイプも下ドラッグも効かなくなる。ページの上で再生する。
     */
    playerFor: (@Composable (MessageContent.Video, Modifier, () -> Unit) -> Unit)? = null,
    /** 動画を取得してギャラリーに書く。 */
    onSaveVideo: (suspend (MessageContent.Video) -> Boolean)? = null,
    /** 動画を取得して他のアプリに渡す。取得が終わるまで共有シートは出せない。 */
    onShareVideo: (suspend (MessageContent.Video) -> Boolean)? = null,
) {
    if (photos.isEmpty()) return

    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = startIndex.coerceIn(0, photos.lastIndex),
    ) { photos.size }

    // 再生中のページ。ページを離れたら止める（画面外で音だけ鳴らさない）。
    var playingPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pager.currentPage) { playingPage = null }

    val current = photos[pager.currentPage.coerceIn(0, photos.lastIndex)]
    val currentPhoto = current.content as? MessageContent.Image
    val currentVideo = current.content as? MessageContent.Video
    @Suppress("UNUSED_EXPRESSION") revision
    val image = currentPhoto?.let(bitmapFor)
    val compressedBytes = currentPhoto?.byteCount ?: 0
    val originalBytes = currentPhoto?.originalBytes
    val haptics = LocalRinowaHaptics.current

    // 保存は画面が何も変わらないので、言葉で出さないと成否がわからない。
    var saveResult by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val backdrop = rememberBackdropState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(saveResult) {
        // 取得中の表示は結果が来るまで消さない。途中で消えると失敗に見える。
        if (saveResult != null && saveResult != "保存しています…" && saveResult != "準備しています…") {
            delay(1800)
            saveResult = null
        }
    }

    // 戻るは一番上にあるものが受ける。ここで受けないとチャット一覧まで戻ってしまう。
    val backPull = blog.nextlab.echo.ui.common.rememberBackPull {
        if (choosing) choosing = false else onDismiss()
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // 次の写真は等倍で開く。前の倍率を持ち越すと、まだ全体を見ていない写真の中央に着く。
    LaunchedEffect(pager.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        choosing = false
        haptics.perform(HapticToken.Selection)
    }

    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        // 等倍のときのドラッグは下の「閉じる」に渡す。両方効くとどちらも曖昧になる。
        if (scale > 1f) {
            offsetX += panChange.x
            offsetY += panChange.y
        }
    }

    // 下へドラッグして閉じる。
    //
    // 画像だけ動かすと画面がばらばらに見え、途中の閾値で閉じると指を離す前に決まってしまう。
    // ビューア全体を一緒に動かし、判定は指を離したとき。届かなければ戻る。
    val dismissY = remember { androidx.compose.animation.core.Animatable(0f) }
    val dragScope = androidx.compose.runtime.rememberCoroutineScope()
    var lastRung by remember { mutableFloatStateOf(0f) }

    /** 画面外とみなす距離。レイヤの高さが分かった時点で入る。 */
    var exitTo by remember { mutableFloatStateOf(2400f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070A))
            .transformable(transform)
            .pointerInput(scale) {
                if (scale > 1.02f) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(dismissY.value) > DISMISS_AT) {
                            haptics.perform(HapticToken.Navigation)
                            // その場で消すと動きが途切れる。指の向きへ送り出してから閉じる。
                            dragScope.launch {
                                dismissY.animateTo(
                                    targetValue = if (dismissY.value > 0f) exitTo else -exitTo,
                                    animationSpec = androidx.compose.animation.core.tween(
                                        durationMillis = 220,
                                        easing = androidx.compose.animation.core.FastOutLinearInEasing,
                                    ),
                                )
                                onDismiss()
                            }
                        } else {
                            dragScope.launch {
                                lastRung = 0f
                                dismissY.animateTo(
                                    0f,
                                    androidx.compose.animation.core.spring(
                                        dampingRatio = 0.7f,
                                        stiffness = 420f,
                                    ),
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        dragScope.launch {
                            lastRung = 0f
                            dismissY.animateTo(0f)
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    dragScope.launch { dismissY.snapTo(dismissY.value + amount) }

                    // 戻るジェスチャーと同じ作り。最初は無音、そこから強くなる。
                    val travelled = (kotlin.math.abs(dismissY.value) / DISMISS_AT)
                        .coerceIn(0f, 1f)
                    if (travelled >= 0.34f && travelled - lastRung >= 0.12f) {
                        lastRung = travelled
                        haptics.performProgress(HapticToken.Selection, 0.35f + 0.65f * travelled)
                    }
                }
            }
            // 画像もボタンも一緒に動く。
            .graphicsLayer {
                exitTo = size.height * 1.05f

                // 指を下ろしている間は閾値までの割合、離れたあとは画面外までの割合で測る。
                val travelled = (kotlin.math.abs(dismissY.value) / DISMISS_AT).coerceIn(0f, 1f)
                val away = (kotlin.math.abs(dismissY.value) / size.height.coerceAtLeast(1f))
                    .coerceIn(0f, 1f)

                translationY = dismissY.value
                scaleX = 1f - travelled * 0.12f - away * 0.18f
                scaleY = 1f - travelled * 0.12f - away * 0.18f
                alpha = 1f - (away * 1.8f).coerceAtMost(1f)
            },
    ) {
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            // 拡大中の横スワイプは写真の移動。ページ送りとは両立しないので止める。
            userScrollEnabled = scale <= 1.02f,
            modifier = Modifier
                .fillMaxSize()
                // 二択の窓が写真を透かすための記録。窓が出ている間だけ撮る。
                .backdropSource(backdrop, capture = choosing || saveResult != null)
                .backPull(backPull),
        ) { page ->
            @Suppress("UNUSED_EXPRESSION") revision
            val photo = photos[page].content as? MessageContent.Image
            val full = photo?.let(bitmapFor)

            // サムネイルはメッセージの中に入っているので、取得前でも真っ黒にはならない。
            val preview = rememberThumbnail(photo?.thumbnail ?: ByteArray(0), photo?.mediaId)

            // 動画のページ。1枚目はメッセージの中にあるので即出る。本体は再生を押すまで取りに行かない。
            val video = photos[page].content as? MessageContent.Video
            if (video != null) {
                val poster = rememberThumbnail(video.thumbnail, video.mediaId)

                if (playingPage == page && playerFor != null) {
                    // 最後まで再生したらポスターに戻す。削除・共有・保存もここで戻る。
                    playerFor(video, Modifier.fillMaxSize()) { playingPage = null }
                    return@HorizontalPager
                }

                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    poster?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it,
                            contentDescription = "動画",
                            contentScale = ContentScale.Fit,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                haptics.perform(HapticToken.SoftConfirm)
                                playingPage = page
                            },
                    ) {
                        Canvas(Modifier.size(28.dp)) {
                            drawPath(
                                path = Path().apply {
                                    moveTo(size.width * 0.16f, 0f)
                                    lineTo(size.width, size.height / 2f)
                                    lineTo(size.width * 0.16f, size.height)
                                    close()
                                },
                                color = Color.White,
                            )
                        }
                    }
                }
                return@HorizontalPager
            }

            val shown = full ?: preview
            if (shown != null) {
                androidx.compose.foundation.Image(
                    bitmap = shown,
                    contentDescription = "写真",
                    contentScale = ContentScale.Fit,
                    // 32px のサムネを高画質で拡大すると壊れて見える。低画質ならピンボケに見える。
                    filterQuality = if (full == null) {
                        androidx.compose.ui.graphics.FilterQuality.Low
                    } else {
                        androidx.compose.ui.graphics.FilterQuality.Medium
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 拡大は見ているページだけ。隣は等倍のまま待たせる。
                            val zoomed = page == pager.currentPage
                            scaleX = if (zoomed) scale else 1f
                            scaleY = if (zoomed) scale else 1f
                            translationX = if (zoomed && scale > 1f) offsetX else 0f
                            translationY = if (zoomed && scale > 1f) offsetY else 0f
                        },
                )
            }
        }

        // 再生中は出さない。プレイヤーの操作列と同じ帯に来て、シークバーの上に
        // ボタンが乗る。再生を止めれば戻る。
        if (playingPage != pager.currentPage) Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                // 下は詰める。9:16 の動画では、映像の下端にボタンが軽くかぶっていた。
                .padding(top = 18.dp, bottom = 12.dp),
        ) {
            // 消せるのは自分のものだけ。列には二人の写真が混ざるので1枚ごとに判定する。
            if (onDelete != null && current.isOutgoing) {
                ViewerAction(label = "削除", icon = ViewerIcon.Delete, tint = Color(0xFFFF9AA0)) {
                    haptics.perform(HapticToken.SoftConfirm)
                    onDelete(current)
                }
            }
            if (currentVideo != null) {
                // 動画は本体がまだ端末に無いことがある。共有シートは待てないので、
                // 取得を終えてから開く。そのあいだ何も出ないと固まって見える。
                if (onShareVideo != null) {
                    ViewerAction(label = "共有", icon = ViewerIcon.Share) {
                        haptics.perform(HapticToken.SoftConfirm)
                        saveResult = "準備しています…"
                        scope.launch {
                            val shared = onShareVideo(currentVideo)
                            haptics.perform(if (shared) HapticToken.Send else HapticToken.SoftConfirm)
                            saveResult = if (shared) null else "共有できませんでした"
                        }
                    }
                }
                if (onSaveVideo != null) {
                    ViewerAction(label = "保存", icon = ViewerIcon.Save) {
                        haptics.perform(HapticToken.SoftConfirm)
                        saveResult = "保存しています…"
                        scope.launch {
                            val saved = onSaveVideo(currentVideo)
                            haptics.perform(if (saved) HapticToken.Send else HapticToken.SoftConfirm)
                            saveResult = if (saved) "保存しました" else "保存できませんでした"
                        }
                    }
                }
            }

            if (image != null && currentPhoto != null) {
                ViewerAction(label = "共有", icon = ViewerIcon.Share) {
                    haptics.perform(HapticToken.SoftConfirm)
                    onShare(image, currentPhoto)
                }
                ViewerAction(label = "保存", icon = ViewerIcon.Save) {
                    if (onSaveOriginal != null && currentPhoto.hasOriginal) {
                        haptics.perform(HapticToken.SoftConfirm)
                        choosing = true
                    } else {
                        val saved = onSave(image, currentPhoto)
                        haptics.perform(if (saved) HapticToken.Send else HapticToken.SoftConfirm)
                        saveResult = if (saved) "保存しました" else "保存できませんでした"
                    }
                }
            }
        }

        // 二択の外側をタップしたら閉じる。見ている写真についての質問なので、写真を触れば「やめる」。
        if (choosing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { choosing = false },
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = choosing,
            // 中央でフェードのみ。下から出すとシート（スワイプで消すもの）に見える。
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(180),
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(180),
            ),
            modifier = Modifier.align(Alignment.Center),
        ) {
            // 黒い板ではなくガラス。写真を撮ってぼかして描き直す（チャットのバーと同じ仕組み）。
            FrostedBar(
                state = backdrop,
                tint = Color(0x8C101014),
                shape = RoundedCornerShape(20.dp),
                blurRadius = 24.dp,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    // 影がないと、いくらぼかしても写真の「中」に沈んで見える。
                    .shadow(18.dp, RoundedCornerShape(20.dp), clip = false)
                    .padding(vertical = 6.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ChoiceRow("オリジナル", size(originalBytes)) {
                        choosing = false
                        saveResult = "保存しています…"
                        scope.launch {
                            val saved = currentPhoto != null &&
                                onSaveOriginal?.invoke(currentPhoto) == true
                            haptics.perform(
                                if (saved) HapticToken.Send else HapticToken.SoftConfirm,
                            )
                            saveResult = if (saved) {
                                "保存しました"
                            } else {
                                "元のファイルは取得できませんでした"
                            }
                        }
                    }
                    ChoiceRow("圧縮版", size(compressedBytes)) {
                        choosing = false
                        val saved = image != null && currentPhoto != null &&
                            onSave(image, currentPhoto)
                        haptics.perform(if (saved) HapticToken.Send else HapticToken.SoftConfirm)
                        saveResult = if (saved) "保存しました" else "保存できませんでした"
                    }
                    ChoiceRow("やめる", null) { choosing = false }
                }
            }
        }

        // 結果も中央のガラスに出す。動画プレイヤーと同じ形・同じ位置に揃える。
        androidx.compose.animation.AnimatedVisibility(
            visible = saveResult != null,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(180),
            ) + androidx.compose.animation.scaleIn(
                initialScale = 0.88f,
                animationSpec = androidx.compose.animation.core.tween(220),
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(260),
            ) + androidx.compose.animation.scaleOut(
                targetScale = 0.94f,
                animationSpec = androidx.compose.animation.core.tween(260),
            ),
            modifier = Modifier.align(Alignment.Center),
        ) {
            FrostedBar(
                state = backdrop,
                tint = Color(0x8C101014),
                shape = RoundedCornerShape(18.dp),
                blurRadius = 24.dp,
                modifier = Modifier.shadow(16.dp, RoundedCornerShape(18.dp), clip = false),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    // チェックは終わってから。途中で出すと言い過ぎになる。
                    if (saveResult == "保存しました") {
                        Canvas(Modifier.size(18.dp)) {
                            drawPath(
                                path = Path().apply {
                                    moveTo(size.width * 0.06f, size.height * 0.54f)
                                    lineTo(size.width * 0.38f, size.height * 0.86f)
                                    lineTo(size.width * 0.96f, size.height * 0.16f)
                                },
                                color = Color.White,
                                style = Stroke(
                                    width = size.width * 0.16f,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = saveResult.orEmpty(),
                        style = RinowaTheme.type.listName,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, detail: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 13.dp),
    ) {
        Text(text = label, style = RinowaTheme.type.listName, color = Color.White)
        if (detail != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = detail,
                style = RinowaTheme.type.labelSmall,
                color = Color(0xFF9E9E9E),
            )
        }
    }
}

/** 指を離したときに閉じる距離。手が写真の外へ届いただけで捨てない程度。 */
private const val DISMISS_AT = 300f

/**
 * 240 KB、1.0 MB のような表示。大きさが分からなければ null。
 *
 * 1000 で割る。1024 で割って KB と書くと、1,014,526 バイトが 990 KB になり、
 * 同じファイルを 1.01 MB と呼ぶ端末のファイル管理と食い違う。
 */
private fun size(bytes: Int?): String? {
    val value = bytes?.takeIf { it > 0 } ?: return null
    return if (value >= 1_000_000) {
        String.format(java.util.Locale.US, "%.1f MB", value / 1_000_000f)
    } else {
        "" + (value / 1000) + " KB"
    }
}
