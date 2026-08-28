package blog.nextlab.echo.ui.chat

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.media.EncryptedMediaSource
import blog.nextlab.echo.media.MediaStoreClient
import blog.nextlab.echo.core.model.MessageContent
import blog.nextlab.echo.ui.common.formatDuration

/**
 * 複製を一度も持たずに動画を見る。
 *
 * 画面ではなく部品にしてある。以前は別画面としてビューアの上に開いていて、その間は
 * 列の外に出ていた（再生した瞬間、横スワイプもドラッグも下の画面のものになって効かない）。
 * いまはビューアが**ページの中に置く**コンポーザブルで、閉じる・保存・戻るは元の場所の
 * まま、ここは映像と操作だけを描く。
 *
 * 再生は [EncryptedMediaSource] を通す。バイト列は範囲要求で届き、1MBごとに検証され、
 * このプロセスの中で復号される。**ディスクには書かれず、端末の外には1フレームも出ない。**
 * 長い動画の終盤へ飛べば、その手前を全部取らずに終盤を取る。
 *
 * 操作を自前で描くのは、media3 の付属コントローラが media3 の顔をしているから。
 * 入れると独自のボタン・シークバー・「速度」「音声」の設定シートが別の書体で付いてきて、
 * 2つのアプリが混ざったものになる。ここは Rinowa の他と同じ形（Canvas で描いた絵柄、
 * 控えめなアクセント、確定するものには触覚）。
 *
 * 速度だけは長い動画で実際に役立つので残したが、設定シートにはしない。ラベル自体が
 * ボタンで、押すと段階が回る。
 *
 * 失敗は画面に出す。再生できない理由は数種類あり（オブジェクトが消えた、鍵が合わない、
 * 通信が切れた）、黒い矩形はそのどれも言わない。やり直すか、繋ぎ直すか、送り直して
 * もらうかを決められるのは見ている人。
 */
@OptIn(UnstableApi::class)
@Composable
fun InlineVideo(
    video: MessageContent.Video,
    store: MediaStoreClient,
    /** 送信者のように、この端末がすでに動画を持っているとき。 */
    local: java.io.File?,
    modifier: Modifier = Modifier,
    /**
     * 最後まで再生し終わったとき。
     *
     * 呼び元はここでポスターに戻す。終端の1枚を出したまま留まると、その上に何も
     * 置けない（ビューアの削除・共有・保存はプレイヤーと場所が重なるので引っ込んで
     * いる）。戻せば3つとも出るし、もう一度見るのは再生ボタンを押すだけになる。
     */
    onFinished: () -> Unit = {},
) {
    val context = LocalContext.current
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current

    var failure by remember(video.mediaId) { mutableStateOf<String?>(null) }
    var playing by remember(video.mediaId) { mutableStateOf(true) }
    var positionMs by remember(video.mediaId) { mutableLongStateOf(0L) }
    var durationMs by remember(video.mediaId) { mutableLongStateOf(video.durationMs) }
    var speed by remember(video.mediaId) { mutableFloatStateOf(1f) }
    var controlsShown by remember(video.mediaId) { mutableStateOf(true) }
    var scrubbing by remember(video.mediaId) { mutableStateOf(false) }
    var ended by remember(video.mediaId) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var surface by remember(video.mediaId) { mutableStateOf<android.view.TextureView?>(null) }

    // 動画の上に何か描くときだけ写しを取る。
    val frost = rememberVideoFrost(surface, active = controlsShown)

    val player = remember(video.mediaId) {
        ExoPlayer.Builder(context).build().apply {
            val key = video.mediaKey
            if (local != null) {
                // もう手元にある。サーバーから流し直すと自分の動画を自分で落とすことになる。
                setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(local)))
                prepare()
                playWhenReady = true
            } else if (key == null) {
                // 保管庫より前のビルドの動画。読むものが無い。
                failure = "この動画は開けません"
            } else {
                setMediaSource(
                    ProgressiveMediaSource.Factory(
                        EncryptedMediaSource.Factory(
                            store,
                            video.mediaId.value,
                            video.sealedBytes,
                            key,
                        ),
                    ).createMediaSource(MediaItem.fromUri(video.mediaId.value)),
                )
                prepare()
                playWhenReady = true
            }

            addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        failure = "再生できませんでした: " + (error.message ?: error.errorCodeName)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playing = isPlaying
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        ended = state == Player.STATE_ENDED
                    }
                },
            )
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // シークバーが使う時計。
    //
    // 通知ではなく定期取得。プレイヤーにフレーム単位の位置通知は無く、不連続の
    // リスナーだけだと動画が進んでいるのにバーが止まる。
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) {
                positionMs = player.currentPosition
                val known = player.duration
                if (known > 0) durationMs = known
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
    }

    // 終端まで来たらその場で返す。間を置くと、操作列が出てから消えるように見える。
    LaunchedEffect(ended) {
        if (ended) onFinished()
    }

    // 見ている間は消え、触ると戻る。消えない操作列は、操作したい当のものの上に居座る。
    LaunchedEffect(controlsShown, playing, ended) {
        if (controlsShown && playing && !ended) {
            kotlinx.coroutines.delay(HIDE_AFTER_MS)
            controlsShown = false
        }
    }


    Box(modifier = modifier.fillMaxSize()) {
        // 付属のプレイヤービューではなく TextureView。
        //
        // 理由は2つで、決め手は後者。映像だけを描くので media3 の操作が出てこないこと、
        // そして SurfaceView と違い**読み戻せる**こと。ボタンが裏の映像をぼかせるのは
        // それがあるから（スレッドのバーが会話をぼかすのと同じ）。VideoFrost を参照。
        //
        // 形はここで決める。表示上の縦横比はメッセージから分かっているので、1フレームも
        // デコードする前に正しく配置でき、最初のフレームが来ても何も飛ばない。
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { viewContext ->
                android.view.TextureView(viewContext).also { view ->
                    surface = view
                    player.setVideoTextureView(view)
                }
            },
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(video.aspectRatio.coerceIn(0.2f, 5f))
                // 映像が収まった場所。ボタンはこれで、自分の裏にあるのがフレームの
                // どこか（そもそもあるのか）を知る。
                .onGloballyPositioned { frost.reportSource(it.positionInRoot(), it.size) },
        )

        // タップを受けるのは動画の**上**で、周りではない。
        //
        // 親に付けていたときは、レターボックスの黒い余白を触ったときだけ操作が出た。
        // プレイヤー側が自分の面でタッチを受けるので、映像そのものを触っても何も
        // 起きなかった。余白を触る人はいない。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsShown = !controlsShown },
        )

        failure?.let {
            Text(
                text = it,
                style = type.listPreview,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        if (controlsShown && failure == null) {
            // 中央。誰もが最初に手を伸ばす操作。
            FrostedOver(
                frost = frost,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.perform(HapticToken.SoftConfirm)
                        when {
                            player.isPlaying -> player.pause()

                            // 終端では「再生」を押しても何も起きなかった。位置がすでに
                            // 終わりで、再開する先が無いため。押して無反応なのは
                            // 壊れたプレイヤーに見えるし、実際壊れていた。
                            player.playbackState == Player.STATE_ENDED -> {
                                player.seekTo(0)
                                player.play()
                                ended = false
                            }

                            else -> player.play()
                        }
                    },
            ) {
                Canvas(Modifier.size(28.dp).align(Alignment.Center)) {
                    if (playing) {
                        val barWidth = size.width * 0.24f
                        val gap = size.width * 0.20f
                        drawRect(
                            color = Color.White,
                            topLeft = androidx.compose.ui.geometry.Offset(
                                (size.width - barWidth * 2 - gap) / 2f,
                                0f,
                            ),
                            size = androidx.compose.ui.geometry.Size(barWidth, size.height),
                        )
                        drawRect(
                            color = Color.White,
                            topLeft = androidx.compose.ui.geometry.Offset(
                                (size.width - barWidth * 2 - gap) / 2f + barWidth + gap,
                                0f,
                            ),
                            size = androidx.compose.ui.geometry.Size(barWidth, size.height),
                        )
                    } else {
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

            // 下。いまどこか、全体でどれだけか、どの速度か。
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatDuration(positionMs) + " / " + formatDuration(durationMs),
                        style = type.messageMeta,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        // ラベルがボタン。数字1つのために設定シートを出しても、
                        // 二度と開かれない画面になる。
                        text = speedLabel(speed),
                        style = type.messageMeta,
                        color = if (speed == 1f) Color.White.copy(alpha = 0.82f) else colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable {
                                haptics.perform(HapticToken.SoftConfirm)
                                speed = nextSpeed(speed)
                                player.playbackParameters = PlaybackParameters(speed)
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Seek(
                    fraction = if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    accent = colors.accent,
                    onScrubStart = { scrubbing = true },
                    onScrub = { fraction ->
                        if (durationMs > 0) positionMs = (durationMs * fraction).toLong()
                    },
                    onScrubEnd = {
                        scrubbing = false
                        player.seekTo(positionMs)
                        haptics.perform(HapticToken.SoftConfirm)
                    },
                )
            }

        }
    }
}

/**
 * シークバー。
 *
 * Slider を組み合わせずに描くのは、他の絵柄と同じ理由。Material のスライダーは
 * つまみ・波紋・軌道という別の判断を持ち込む。ドラッグ中はラベルだけ動かし、
 * 離したときにシークするので、長い動画をなぞっても通過した範囲を全部は取りに行かない。
 */
@Composable
private fun Seek(
    fraction: Float,
    accent: Color,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
) {
    var width by remember { mutableFloatStateOf(1f) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        onScrubStart()
                        onScrub((start.x / width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onScrubEnd() },
                    onDragCancel = { onScrubEnd() },
                ) { change, _ ->
                    onScrub((change.position.x / width).coerceIn(0f, 1f))
                }
            },
    ) {
        width = size.width
        val middle = size.height / 2f
        val track = 3.dp.toPx()

        drawRoundRect(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = androidx.compose.ui.geometry.Offset(0f, middle - track / 2f),
            size = androidx.compose.ui.geometry.Size(size.width, track),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(track),
        )
        drawRoundRect(
            color = accent,
            topLeft = androidx.compose.ui.geometry.Offset(0f, middle - track / 2f),
            size = androidx.compose.ui.geometry.Size(size.width * fraction, track),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(track),
        )
        drawCircle(
            color = Color.White,
            radius = 6.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(size.width * fraction, middle),
        )
    }
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "" + speed.toInt() + "x" else "" + speed + "x"

/** つまみではなく段階。使える値4つを1タップずつ。 */
private fun nextSpeed(current: Float): Float = when (current) {
    1f -> 1.5f
    1.5f -> 2f
    2f -> 0.5f
    else -> 1f
}

private const val POLL_MS = 200L

/** 時間を読める程度に長く、映像の上に居座らない程度に短く。 */
private const val HIDE_AFTER_MS = 2_600L
