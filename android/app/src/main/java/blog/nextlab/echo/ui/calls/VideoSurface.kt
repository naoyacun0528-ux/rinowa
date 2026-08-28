package blog.nextlab.echo.ui.calls

import blog.nextlab.echo.bestEffort
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.webrtc.EglRenderer
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * 画面上の映像1本。
 *
 * WebRTC は GL の面にデコードするので、これは Compose の描画ではなく本物の
 * Android ビュー。包んでいるのは Compose の寿命を与えるため（コンポジションに
 * 入ったら初期化、出たら解放）。そして重要なのは、**解放の前にトラックから外す**こと。
 * トラックが流し込んでいる最中に解放すると、デコーダごと落ちる。
 *
 * @param mirror 内カメラの自画面なら true。反転しないと他人から見た自分になり、
 *   鏡で自分を知っている人には違和感になる。
 * @param eglBase [WebRtcSession] が factory を作ったときと同じものであること。
 *   GL コンテキストが2つあると、エラー無しで黒い矩形になる。
 */
@Composable
fun VideoSurface(
    track: VideoTrack?,
    eglBase: EglBase?,
    mirror: Boolean,
    fillCrop: Boolean,
    modifier: Modifier = Modifier,
    /**
     * 小さいほう（ピクチャーインピクチャー側）なら true。
     *
     * `SurfaceViewRenderer` は `SurfaceView` で、システムがウィンドウの*裏*で合成し、
     * ビューはそこへ通すために自分の矩形を透明に打ち抜く。2つ重ねると、上のものが
     * 下の映像を打ち抜いて重なりが**黒**になる。「画面に黒が挿入される」の正体はこれで、
     * デコードの問題ではない。
     *
     * `setZOrderMediaOverlay` は小さいほうを合成の手前側に置くので、穴はもう一方の
     * 映像ではなくウィンドウに開く。
     */
    overlay: Boolean = false,
    /**
     * 最後のフレームの写しを置く場所（要るときだけ）。
     *
     * 渡すのは相手側の面だけ。[FrameSnapshot] を参照。
     */
    snapshot: FrameSnapshot? = null,
) {
    if (eglBase == null) return

    val holder = remember(eglBase) { RendererHolder() }

    // 取り付け・取り外しは AndroidView の factory ではなくここでやる。factory は
    // ビューごとに1回しか走らないが、トラックはその下で入れ替わりうる。解放済みの
    // レンダラに sink が付いたままだと、デコーダのスレッドごと落ちる。
    DisposableEffect(holder, track) {
        val view = holder.view
        if (view != null && track != null) runCatching { track.addSink(view) }
        onDispose {
            if (view != null && track != null) runCatching { track.removeSink(view) }
        }
    }

    // 届かなくなった瞬間のために、映像の写しを取っておく。
    if (snapshot != null && track != null) {
        LaunchedEffect(holder, track) {
            while (true) {
                val view = holder.view
                if (view != null) {
                    val listener = EglRenderer.FrameListener { bitmap ->
                        if (bitmap != null) snapshot.image = bitmap.asImageBitmap()
                    }
                    // リスナーを付けている間、レンダラはフレームごとにビットマップを
                    // 確保する。だから1フレームぶんだけ付けて外す。この縮尺なら
                    // 3秒に数KB。
                    runCatching { view.addFrameListener(listener, SNAPSHOT_SCALE) }
                    delay(250)
                    // メインスレッドからはやらない。removeFrameListener は描画スレッドの
                    // 応答を待ってブロックし、その描画スレッドはデコードで忙しい。
                    withContext(Dispatchers.Default) {
                        runCatching { view.removeFrameListener(listener) }
                    }
                }
                delay(3000)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                // 報告する。初期化に失敗したレンダラは顔の場所に黒い矩形を出し、
                // そのあとの症状はどれも別の場所を指す。
                bestEffort("init renderer") { init(eglBase.eglBaseContext, null) }
                setEnableHardwareScaler(true)
                if (overlay) setZOrderMediaOverlay(true)
                setScalingType(
                    if (fillCrop) {
                        RendererCommon.ScalingType.SCALE_ASPECT_FILL
                    } else {
                        RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    },
                )
                setMirror(mirror)
                holder.view = this
                // このビューができる前にトラックが解決していることがあり、その場合は
                // 上の effect が取り付ける相手を見つけられていない。
                if (track != null) runCatching { track.addSink(this) }
            }
        },
        update = { view -> view.setMirror(mirror) },
        onRelease = { view ->
            bestEffort("remove sink") { track?.removeSink(view) }
            bestEffort("release renderer") { view.release() }
            if (holder.view === view) holder.view = null
        },
    )
}

/** `factory` は作ったビューを直接返せないので、外へ渡すための箱。 */
private class RendererHolder {
    var view: SurfaceViewRenderer? = null
}

/**
 * 最後に届いたフレーム。届かなくなったあとに何かを描くために取っておく。
 *
 * 相手がカメラを切ると WebRTC は送るのをやめ、レンダラは最後にデコードした
 * フレームを出し続ける。その固まった絵を Compose から覆うことはできない。
 * `SurfaceViewRenderer` は `SurfaceView` で、絵はシステムが Compose の層の**外**で
 * 合成する。Compose はそのピクセルを持っていないので `Modifier.blur` にはぼかす対象が
 * 無く、上に描いた覆いも当てにならない（面が自分でウィンドウに穴を開ける）。
 *
 * なので、まだ届いている間に絵を写し取る。Compose の手にビットマップが渡れば、
 * 固まった面を画面から外し、その場所に写しを描ける（ぼかす、覆う、文字を置く、何でも）。
 * 普通の Compose の内容になるので、合成順の問題は残らない。
 *
 * [SNAPSHOT_SCALE] はフレームを1/12ほどに落とす。面いっぱいに引き伸ばすと、その
 * 拡大**そのもの**がぼかしになる。どの Android でも動き、RenderEffect も
 * シェーダも API 31 も要らない。使える環境では `Modifier.blur` も重ねるが、
 * 効果はそれに依存しない。
 */
class FrameSnapshot {
    var image: ImageBitmap? by mutableStateOf(null)
        internal set
}

/**
 * 引き伸ばすとぼけて見える程度に小さく、顔の形は保つ程度に大きく。
 *
 * 写しが安い理由でもある。1080p のフレームがこの縮尺で約90x160ピクセル。
 */
private const val SNAPSHOT_SCALE = 0.08f
