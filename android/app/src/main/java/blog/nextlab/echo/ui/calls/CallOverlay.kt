package blog.nextlab.echo.ui.calls

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.R
import blog.nextlab.echo.calls.CallController
import blog.nextlab.echo.calls.CallKind
import blog.nextlab.echo.calls.CallRecord
import blog.nextlab.echo.calls.CallState
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.ui.LocalInPictureInPicture
import kotlinx.coroutines.delay

/**
 * 通話画面。すべての上に出す。
 *
 * 通話は行き先ではなく、別のことをしている最中に来るもので、どこにいても出られる
 * 必要がある。全画面のオーバーレイならそれができる。画面遷移にすると、鳴っている
 * 電話の上に会話が積まれることが起こりうる。
 *
 * 「呼び出し中 → 接続中 → 通話中」は分けて出す。2つ目と3つ目の間は ICE 交渉の数秒で、
 * 電話で一番不安な時間。ここで何も言わないと、人は切ってかけ直す。
 */
@Composable
fun CallOverlay(
    controller: CallController,
    peerName: String,
    incoming: CallRecord?,
    onRequestMicrophone: () -> Unit,
) {
    // コントローラは全画面より上にいて、どの会話かを知らない。名前が分かるのはここ。
    LaunchedEffect(peerName) { controller.peerLabel = peerName }

    val visible = controller.active != null || incoming != null
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 不透明にする。会話が透けると触りたくなるし、通話中の押し間違いは高くつく。
                .background(if (RinowaTheme.colors.isLight) Color(0xFF101014) else Color(0xFF0A0A0C)),
            contentAlignment = Alignment.Center,
        ) {
            if (controller.active == null && incoming != null) {
                IncomingCall(
                    peerName = peerName,
                    kindLabel = if (incoming.kind == CallKind.Video) "ビデオ通話" else "音声通話",
                    onAccept = onRequestMicrophone,
                    onDecline = { controller.decline(incoming) },
                )
            } else if (controller.active?.kind == CallKind.Video) {
                VideoCall(controller = controller, peerName = peerName)
            } else {
                OngoingCall(controller = controller, peerName = peerName)
            }
        }
    }
}

@Composable
private fun IncomingCall(
    peerName: String,
    kindLabel: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val haptics = LocalRinowaHaptics.current

    // 鳴った瞬間から触覚。伏せて置いた端末でも、音を聞いていない人が出られるように。
    LaunchedEffect(Unit) {
        while (true) {
            haptics.perform(HapticToken.Threshold)
            delay(1400)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    ) {
        // ボタンより先に種類を出す。ビデオ通話に出るとカメラが入るので、
        // あとから気付くことにしてはいけない。
        Text(kindLabel, style = RinowaTheme.type.label, color = Color(0xFF9A9AA5))
        Spacer(Modifier.height(10.dp))
        Text(
            text = peerName,
            style = RinowaTheme.type.screenTitle.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(64.dp))

        // 応答と拒否はわざと離す。この画面の最悪の形は、半分寝たまま出てしまうこと。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CallButton(label = "拒否", tint = Color(0xFFE5484D), icon = R.drawable.ic_call_end) {
                haptics.perform(HapticToken.SoftConfirm)
                onDecline()
            }
            CallButton(label = "応答", tint = Color(0xFF30A46C), icon = R.drawable.ic_call) {
                haptics.perform(HapticToken.Send)
                onAccept()
            }
        }
    }
}

/**
 * ビデオ通話。
 *
 * 相手が全画面で、自分は隅。ビデオ通話が始まって以来ずっとこの配置で、通話の主役は相手。
 * 自分の映像は内カメラのときだけ左右反転する。反転しないと他人から見た自分の顔になり、
 * 鏡で自分を知っている人には違和感になる。
 */
@Composable
private fun VideoCall(controller: CallController, peerName: String) {
    val haptics = LocalRinowaHaptics.current

    /**
     * どちらの映像が全画面か。
     *
     * 小さいほうを押すと入れ替わる。見たい映像が常に相手とは限らない（自分の写り方を
     * 確認する、カメラに何かを見せる）。
     */
    var selfIsLarge by remember { mutableStateOf(false) }

    val largeTrack = if (selfIsLarge) controller.localVideo else controller.remoteVideo
    val smallTrack = if (selfIsLarge) controller.remoteVideo else controller.localVideo
    val largeIsMirrored = selfIsLarge && controller.usingFrontCamera
    val smallIsMirrored = !selfIsLarge && controller.usingFrontCamera

    // 入れ替えても PiP に入っても保つ。出せる価値のあるフレームは、カメラを切るずっと
    // 前に撮れていることがある。
    val peerSnapshot = remember { FrameSnapshot() }
    val peerDark = !controller.peerCameraOn

    // 小窓に入るのは1つだけで、それは相手。
    if (LocalInPictureInPicture.current) {
        PipVideoCall(controller, peerSnapshot)
        return
    }

    /**
     * 操作ボタンを出しているか。
     *
     * 最初は出す（消音できないまま通話に入るほうが悪い）。そのあと引っ込める。画面の
     * 主役は相手の顔で、その上に居座る帯はずっと謝っているようなもの。どこを押しても戻る。
     */
    var controlsVisible by remember { mutableStateOf(true) }
    var lastShown by remember { mutableLongStateOf(0L) }

    LaunchedEffect(controlsVisible, lastShown) {
        if (controlsVisible) {
            delay(CONTROLS_LINGER_MS)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.perform(HapticToken.Selection)
                // 出ているときに押したら消す。同じ場所を押して同じ結果になるなら、
                // 押した手応えだけあって何も起きていないのと同じ。
                controlsVisible = !controlsVisible
                // 出したときはタイマーを引き直す。押せば必ず満額の時間が付く。
                lastShown = System.nanoTime()
            },
    ) {
        // 相手の面は覆うのではなく差し替える。SurfaceView の上に Compose で描けない
        // 理由は CameraOffCover を参照。
        if (peerDark && !selfIsLarge) {
            CameraOffCover(
                snapshot = peerSnapshot.image,
                compact = false,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            VideoSurface(
                track = largeTrack,
                eglBase = controller.eglBase,
                mirror = largeIsMirrored,
                // 埋めるように切り抜く。顔の周りに黒帯が出ると、会話ではなく動画再生に見える。
                fillCrop = true,
                snapshot = if (selfIsLarge) null else peerSnapshot,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 相手の映像が来るまで描くものが無い。文字の無い黒画面は壊れた通話と区別が付かない。
        if (largeTrack == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(peerName, color = Color.White, style = RinowaTheme.type.screenTitle)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (controller.state) {
                        CallState.Ringing -> "呼び出し中…"
                        CallState.Connecting -> "接続中…"
                        else -> "映像を待っています…"
                    },
                    color = Color(0xFF9A9AA5),
                    style = RinowaTheme.type.label,
                )
            }
        }

        // 右上ではなく左上。時計と電池は右上にあり、その下に映像を置くと片隅に物が積み上がる。
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 152.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF15151A))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.perform(HapticToken.Selection)
                        selfIsLarge = !selfIsLarge
                    },
            ) {
                if (peerDark && selfIsLarge) {
                    CameraOffCover(
                        snapshot = peerSnapshot.image,
                        compact = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    VideoSurface(
                        track = smallTrack,
                        eglBase = controller.eglBase,
                        mirror = smallIsMirrored,
                        fillCrop = true,
                        // 片方をもう片方の上に。これが無いと重なった部分が黒くなる
                        // （VideoSurface を参照）。
                        overlay = true,
                        snapshot = if (selfIsLarge) peerSnapshot else null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // 自分の映像の角に置く。変えるのはその映像だから。操作ボタンの1つなので
            // 他のボタンと一緒に出入りする。
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 10.dp, y = 10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(RinowaTheme.colors.accent)
                        .clickable {
                            haptics.perform(HapticToken.Selection)
                            controller.switchCamera()
                            controlsVisible = true
                            lastShown = System.nanoTime()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (controller.usingFrontCamera) "前" else "後",
                        style = RinowaTheme.type.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = RinowaTheme.colors.onAccent,
                    )
                }
            }
        }

        // 失敗は操作ボタンではないので一緒には消えない。読んで閉じるまで残す。
        controller.failure?.let { message ->
            Text(
                text = message,
                style = RinowaTheme.type.labelSmall,
                color = Color(0xFFFF9AA0),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 172.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC5A1418))
                    .clickable { controller.dismissFailure() }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SmallToggle(
                        label = if (controller.muted) "ミュート中" else "ミュート",
                        on = controller.muted,
                    ) {
                        haptics.perform(HapticToken.SoftConfirm)
                        controller.toggleMute()
                        controlsVisible = true
                        lastShown = System.nanoTime()
                    }
                    SmallToggle(
                        label = if (controller.cameraOn) "カメラ" else "カメラ切",
                        on = controller.cameraOn,
                    ) {
                        haptics.perform(HapticToken.SoftConfirm)
                        controller.toggleCamera()
                        controlsVisible = true
                        lastShown = System.nanoTime()
                    }
                }

                Spacer(Modifier.height(24.dp))
                CallButton(label = "終了", tint = Color(0xFFE5484D), icon = R.drawable.ic_call_end) {
                    haptics.perform(HapticToken.SoftConfirm)
                    controller.hangUp()
                }
            }
        }
    }
}

/** タップしてから操作ボタンが残る時間。急がずにボタンへ届く長さ。 */
private const val CONTROLS_LINGER_MS = 4200L

@Composable
private fun OngoingCall(controller: CallController, peerName: String) {
    val haptics = LocalRinowaHaptics.current
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(controller.state) {
        if (controller.state != CallState.Active) return@LaunchedEffect
        // 時間が出ていない通話は繋がっていないように感じる。まだ動いている証拠として
        // 一番安いのがこれ。
        haptics.perform(HapticToken.Send)
        while (true) {
            delay(1000)
            elapsed++
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    ) {
        Text(
            text = when (controller.state) {
                CallState.Ringing -> "呼び出し中…"
                CallState.Connecting -> "接続中…"
                CallState.Active -> "%d:%02d".format(elapsed / 60, elapsed % 60)
                else -> ""
            },
            style = RinowaTheme.type.label,
            color = Color(0xFF9A9AA5),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = peerName,
            style = RinowaTheme.type.screenTitle.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            textAlign = TextAlign.Center,
        )

        // 失敗する前に言う。中継が無いと別回線同士は繋がらないが、繋がらない様子を
        // 見ていても理由は分からない。
        if (!controller.relayAvailable && controller.state != CallState.Active) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "中継サーバー未設定 — 同じ Wi-Fi 以外では繋がらないことがあります",
                style = RinowaTheme.type.labelSmall,
                color = Color(0xFFE0B341),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x22E0B341))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }

        controller.failure?.let { message ->
            Spacer(Modifier.height(18.dp))
            Text(
                text = message,
                style = RinowaTheme.type.labelSmall,
                color = Color(0xFFFF9AA0),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x22E5484D))
                    .clickable { controller.dismissFailure() }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }

        Spacer(Modifier.height(56.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SmallToggle(label = if (controller.muted) "ミュート中" else "ミュート", on = controller.muted) {
                haptics.perform(HapticToken.SoftConfirm)
                controller.toggleMute()
            }
            SmallToggle(label = "スピーカー", on = controller.speakerOn) {
                haptics.perform(HapticToken.SoftConfirm)
                controller.toggleSpeaker()
            }
        }

        Spacer(Modifier.height(36.dp))
        CallButton(label = "終了", tint = Color(0xFFE5484D), icon = R.drawable.ic_call_end) {
            haptics.perform(HapticToken.SoftConfirm)
            controller.hangUp()
        }
    }
}

/**
 * 通話の丸ボタン。
 *
 * **色だけで意味を持たせない。** 前は塗っただけの丸で、赤か緑かだけが違っていた。
 * 色が見分けにくい人には同じ丸が二つ並んでいるだけになるし、
 * 誰であっても、鳴っている最中に下の小さな文字を読む余裕はない。
 * 受話器の向きは、読まなくても分かる。
 */
@Composable
private fun CallButton(
    label: String,
    tint: Color,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(tint)
                .clickable(onClick = onClick),
        ) {
            Icon(
                painter = painterResource(icon),
                // 文字が下にあるので、読み上げには要らない。二度言うことになる。
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = RinowaTheme.type.labelSmall, color = Color(0xFFC8C8D0))
    }
}

/**
 * 入/切がはっきり分かる通話ボタン。
 *
 * 最初は「入」を少し明るい灰色にしていたが、ほぼ黒の画面では探さないと分からない。
 * 特に消音は、ちらっと見て分かる必要がある（読み違えると、聞こえていない相手に話し
 * 続けるか、切っているつもりで話すことになる）。
 * なので「入」はアクセント色で塗り、その上に暗い文字を置く。同じ色の濃淡にはしない。
 */
@Composable
private fun SmallToggle(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = RinowaTheme.colors
    val background by animateColorAsState(
        targetValue = if (on) colors.accent else Color(0x14FFFFFF),
        animationSpec = RinowaMotion.settleSpring(),
        label = "toggleFill",
    )
    val content by animateColorAsState(
        targetValue = if (on) colors.onAccent else Color.White,
        animationSpec = RinowaMotion.settleSpring(),
        label = "toggleText",
    )

    Box(
        modifier = Modifier
            .width(120.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = RinowaTheme.type.labelSmall.copy(
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = content,
        )
    }
}

/**
 * 相手がカメラを切ったときに出すもの。
 *
 * カメラを切っても相手側は暗くならない。WebRTC は送るのをやめるだけで、最後の
 * フレームが残る。つまり相手が動作の途中で固まって見え、それは落ちた通話とそっくり。
 *
 * 最初は live の `VideoSurface` の上に幕と斜線と文字を描いたが、何も見えなかった。
 * `SurfaceViewRenderer` は `SurfaceView` で、その絵はウィンドウの外でシステムが
 * 合成する。ビューはその絵を通すために自分の矩形をウィンドウ側で消すので、同じ矩形に
 * ある Compose の内容も一緒に消える。毎フレーム、描いては消されていた。
 * 重なった映像面が黒くなるのと同じ仕組みで、SurfaceView の上に Compose では描けない。
 *
 * そこでレンダラをレイアウトから外し、フレームが届いていた間に [FrameSnapshot] で
 * 写しておいた最後の1枚を、普通の Compose 画像として同じ場所に描く。合成が全部
 * Compose の中で済むので、幕も斜線も文字もただ上に乗る。
 *
 * ぼかしは本物で、API 31 を必要としない。写しは1/12ほどの大きさで撮ってあり、
 * 面いっぱいに引き伸ばす時点で再標本化によってぼける。`Modifier.blur` も使えるときは
 * 併用するが、効果はそれに依存しない。
 *
 * 写しが無いとき（1枚も届く前にカメラを切った場合）は無地で描く。「カメラオフ」と
 * 書いてある面としては正しく、ぼかす対象が無いだけ。
 */
@Composable
private fun CameraOffCover(
    snapshot: ImageBitmap?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color(0xFF101015)),
        contentAlignment = Alignment.Center,
    ) {
        if (snapshot != null) {
            Image(
                bitmap = snapshot,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // わざと低品質。小さなビットマップの線形補間がぼかしそのものなので、
                // きれいなフィルタを頼むと打ち消し合う。
                filterQuality = FilterQuality.Low,
                modifier = Modifier
                    .matchParentSize()
                    .blur(if (compact) 8.dp else 22.dp),
            )
        }

        Canvas(Modifier.matchParentSize()) {
            // 差し替えではなく上に重ねる。何があったかの形がうっすら残ることで、
            // 「切断された」ではなく「覆われている」と読める。
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xB30C0C10), Color(0x8C16161C), Color(0xB30C0C10)),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )

            val inset = size.minDimension * if (compact) 0.16f else 0.26f
            // 暗い線の上に明るい線の2本。白い静止画の上では白1本だと消えるが、
            // この線が伝えたいことの半分を担っている。
            drawLine(
                color = Color(0x73000000),
                start = Offset(inset + 2f, size.height - inset + 2f),
                end = Offset(size.width - inset + 2f, inset + 2f),
                strokeWidth = size.minDimension * if (compact) 0.030f else 0.016f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color(0xF2FFFFFF),
                start = Offset(inset, size.height - inset),
                end = Offset(size.width - inset, inset),
                strokeWidth = size.minDimension * if (compact) 0.026f else 0.012f,
                cap = StrokeCap.Round,
            )
        }

        Text(
            text = "カメラオフ",
            style = if (compact) RinowaTheme.type.labelSmall else RinowaTheme.type.label,
            color = Color(0xFFF2F2F6),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xD9000000))
                .padding(
                    horizontal = if (compact) 9.dp else 18.dp,
                    vertical = if (compact) 4.dp else 10.dp,
                ),
        )
    }
}

/**
 * 小窓の中の通話。
 *
 * 隠すだけでは足りないので別のコンポーザブルにする。ピクチャーインピクチャーは
 * 小さい画面ではなく、**タップが届かない**。窓に触るのは「広げてくれ」という要求で、
 * 指の下のボタンを押したことにはならない。つまり通常のボタンは小さすぎる以前に効かず、
 * 効かないボタンは無いボタンより悪い。
 *
 * 自分の映像も出さない。この大きさでは、唯一見る価値のある映像をかなり覆ってしまう。
 * 残るのは相手だけで、相手がカメラを切ったときは全画面と同じ幕を出す（隅で固まった
 * フレームこそ、死んだ通話に一番よく似ている）。
 */
@Composable
private fun PipVideoCall(controller: CallController, peerSnapshot: FrameSnapshot) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0C)),
        contentAlignment = Alignment.Center,
    ) {
        if (!controller.peerCameraOn && controller.remoteVideo != null) {
            CameraOffCover(
                snapshot = peerSnapshot.image,
                compact = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            VideoSurface(
                track = controller.remoteVideo,
                eglBase = controller.eglBase,
                mirror = false,
                fillCrop = true,
                snapshot = peerSnapshot,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (controller.remoteVideo == null) {
            Text(
                text = when (controller.state) {
                    CallState.Ringing -> "呼び出し中"
                    CallState.Connecting -> "接続中"
                    else -> "通話中"
                },
                style = RinowaTheme.type.labelSmall,
                color = Color(0xFF9A9AA5),
            )
        }

        // 自分の映像は左上。全画面と同じ角。
        //
        // 相手が読める程度に小さく。小窓は数センチしかなく、割合で大きさを決めると
        // 唯一価値のある映像を覆う。`overlay` はいつもの理由（重なりが黒くなる）。
        if (controller.localVideo != null && controller.cameraOn) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(width = 44.dp, height = 60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF15151A)),
            ) {
                VideoSurface(
                    track = controller.localVideo,
                    eglBase = controller.eglBase,
                    mirror = controller.usingFrontCamera,
                    fillCrop = true,
                    overlay = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
