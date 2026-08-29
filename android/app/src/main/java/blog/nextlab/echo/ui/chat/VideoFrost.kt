package blog.nextlab.echo.ui.chat

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.backdropBlurSupported
import kotlinx.coroutines.flow.conflate

/**
 * 再生中の動画の上に、チャットと同じすりガラスを出す。
 *
 * リアクション選択と同じコードにはできない。`FrostedBar` がぼかすのは Compose の
 * 背景で、スレッドをレイヤに記録して各バーがそれをぼかして描き直す。動画はその
 * レイヤに無い。デコーダから渡された自前の面に届き、**Compose は1フレームも描いて
 * いない**（SurfaceView の上に描いた図形が消えたのと同じ理由）。
 *
 * なので記録ではなく取りに行く。プレイヤーは TextureView に描いていて、
 * SurfaceView と違って読み戻せる。操作ボタンが出ている間だけ写しを取り、各ボタンは
 * 自分の裏にある部分を切り出してぼかして描く。
 *
 * 撮る回数は動画が決める。TextureView は新しいフレームを描いたときに知らせて
 * くれるので、その時だけ撮る。一定間隔で撮っていたときは、映像が動いているのに
 * ボタンの中身が飛び飛びに追いかけてきて、ガラスというより遅れた写真に見えた。
 * 止まっている映像では1枚も撮らない。
 *
 * 読み戻しはただではない。安く保つのは3つ:
 *
 * - 操作ボタンが出ている数秒だけ動く
 * - 1/4の大きさで撮る（720p の読みが 180p になる）。引き伸ばす時点でぼけるので、
 *   本物のぼかしの負担も減る
 * - API 31 未満ではぼかし自体が無いので何も撮らず、暗い円に戻す
 *
 * 代案は静止した半透明の幕で、実際そうしていたが、白いカーテンの前では
 * ガラスではなく灰色の塊に見えた。
 */
class VideoFrost internal constructor(
    internal val frame: MutableState<ImageBitmap?>,
    /**
     * 映像がどこにあるか。ボタン側と同じ座標系で。
     *
     * 最初の版はこれが無く、撮った映像を画面の左上から描いていた。**映像は画面の
     * 左上には無い** — 中央にあり、周りに黒帯があり、その量は動画の形で変わる。
     * だから黒帯の上にある閉じる・保存のボタンの中に動いている映像の切れ端が映り、
     * 横向きの動画では大きくずれてボタンに何も映らなかった。映像が無い場所でだけ
     * バグが見えていた。
     */
    internal val source: MutableState<androidx.compose.ui.geometry.Rect>,
)

/** 映像が配置されたとき、および動いたたびにプレイヤーが呼ぶ。 */
fun VideoFrost.reportSource(topLeft: Offset, size: IntSize) {
    source.value = androidx.compose.ui.geometry.Rect(
        offset = topLeft,
        size = androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()),
    )
}

/**
 * [active] の間 [textureView] を写し、ボタンが透かすものを返す。
 *
 * @param active 通常は「操作ボタンが出ている」。消えた瞬間に写しも止まる。
 */
@Composable
fun rememberVideoFrost(textureView: TextureView?, active: Boolean): VideoFrost {
    val frame = remember { mutableStateOf<ImageBitmap?>(null) }
    val source = remember {
        mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)
    }
    val frost = remember { VideoFrost(frame, source) }

    // 新しいフレームが来た回数。値そのものに意味はなく、変わったことだけを使う。
    val rendered = remember { mutableIntStateOf(0) }

    // media3 が付けた listener を外さずに間に入る。外すと映像そのものが止まる
    // （表示先の面を管理しているのが、まさにその listener）。
    DisposableEffect(textureView, active) {
        val view = textureView
        if (!active || view == null || !backdropBlurSupported) return@DisposableEffect onDispose {}
        val inner = view.surfaceTextureListener
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                inner?.onSurfaceTextureAvailable(s, w, h)
            }

            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                inner?.onSurfaceTextureSizeChanged(s, w, h)
            }

            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean =
                inner?.onSurfaceTextureDestroyed(s) ?: true

            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                inner?.onSurfaceTextureUpdated(s)
                rendered.intValue++
            }
        }
        onDispose { view.surfaceTextureListener = inner }
    }

    LaunchedEffect(textureView, active) {
        if (!active || textureView == null || !backdropBlurSupported) return@LaunchedEffect

        // 毎回確保せず使い回す。1フレームごとにビットマップを作ると1秒に1回ほど
        // GC が走る。ちょうど、かくつきが目に付く画面で。
        var reusable: Bitmap? = null

        // conflate: 読み戻しがフレームより遅い端末では、待っている間に来たフレームを
        // 飛ばして最新だけを撮る。遅れて古い絵を出すより、間引いて今を出すほうがよい。
        snapshotFlow { rendered.intValue }.conflate().collect {
            val width = (textureView.width * SAMPLE_SCALE).toInt()
            val height = (textureView.height * SAMPLE_SCALE).toInt()
            if (width <= 0 || height <= 0 || !textureView.isAvailable) return@collect

            val target = reusable?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .also { reusable = it }

            // swallow-ok: 読めなかったフレームがあれば、その回は前の写しを描く。
            // どちらでもボタンは読めるし、読み戻しの失敗は人が対処できることではない。
            runCatching { textureView.getBitmap(target) }
                .getOrNull()
                ?.let { frame.value = it.asImageBitmap() }
        }
    }

    // 止まっている映像では新しいフレームが来ないので、上の流れは一度も動かない。
    // 操作を出した最初の1枚だけはここで撮る。
    LaunchedEffect(textureView, active) {
        if (!active || textureView == null || !backdropBlurSupported) return@LaunchedEffect
        val width = (textureView.width * SAMPLE_SCALE).toInt()
        val height = (textureView.height * SAMPLE_SCALE).toInt()
        if (width <= 0 || height <= 0 || !textureView.isAvailable) return@LaunchedEffect
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // swallow-ok: 上と同じ。撮れなければ色だけのボタンになる。
        runCatching { textureView.getBitmap(target) }.getOrNull()?.let {
            frame.value = it.asImageBitmap()
        }
    }

    DisposableEffect(active) {
        onDispose { if (!active) frame.value = null }
    }

    return frost
}

/**
 * 動画の上のガラス1枚。
 *
 * 自分の位置を測り、撮った映像のどの部分が裏にあるかを求め、それをぼかして色を
 * 重ねる。ぼかせないとき（API 31 未満、最初の写しが来る前）は色だけにする。
 */
@Composable
fun FrostedOver(
    frost: VideoFrost,
    shape: Shape,
    modifier: Modifier = Modifier,
    tint: Color = Color.Black.copy(alpha = 0.22f),
    /**
     * 画面のピクセル単位。しかも**すでにぼけている**写しに対しての値。
     *
     * 1/4で撮って4倍で描くので、引き伸ばしだけで4ピクセルほど滲む。元が鮮明な
     * つもりで選んだ値（最初は20dp）だと、絵が何も残らない灰色の円になる。
     * 7dp でもまだ絵が消え、5dp で裏の形が形として残る。それがガラスに見えるか
     * 塗りに見えるかの分かれ目。
     */
    blurRadius: Dp = 5.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val frame by frost.frame

    Box(
        modifier = modifier
            .onGloballyPositioned {
                origin = it.positionInRoot()
                size = it.size
            }
            .clip(shape),
    ) {
        // 黒帯の上に完全に乗っているボタンにはぼかす対象が無い。あるふりをしたのが、
        // 閉じるボタンの中で動画が動いていた原因。
        val overPicture = frost.source.value.overlaps(
            androidx.compose.ui.geometry.Rect(
                offset = origin,
                size = androidx.compose.ui.geometry.Size(
                    size.width.toFloat(),
                    size.height.toFloat(),
                ),
            ),
        )

        val captured = frame.takeIf { overPicture }
        if (captured != null && size.width > 0) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        clip = true
                        this.shape = shape
                        val radius = blurRadius.toPx()
                        renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    }
                    .drawBehind {
                        // 映像が実際に占めている大きさと位置で描き、このボタンの裏の
                        // 部分が下に来るようにずらす。スレッドのすりガラスと同じ考え方で、
                        // 記録したレイヤの代わりにビットマップを使う。最初の版と違い、
                        // 画面いっぱいだと仮定せず実際の位置を使う。
                        val picture = frost.source.value
                        if (picture.width <= 0f) return@drawBehind
                        translate(
                            left = picture.left - origin.x,
                            top = picture.top - origin.y,
                        ) {
                            drawImage(
                                image = captured,
                                dstSize = IntSize(
                                    picture.width.toInt(),
                                    picture.height.toInt(),
                                ),
                            )
                        }
                    },
            )
        }

        Box(
            Modifier
                .matchParentSize()
                .background(
                    // 裏にぼかしが無いぶん、色だけで可読性を持たせるので濃くする。
                    // 白いカーテンの上でも読めないといけない。
                    if (captured != null) tint else tint.copy(alpha = 0.46f),
                ),
        )

        content()
    }
}

/** 縦横とも1/4。720p のフレームが 180p の読み戻しになる。 */
private const val SAMPLE_SCALE = 0.25f
