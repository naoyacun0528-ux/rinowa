package blog.nextlab.echo.ui.profile

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.data.ProfilePhotos
import blog.nextlab.echo.ui.auth.PrimaryButton
import blog.nextlab.echo.ui.auth.PrimaryButtonLabel
import blog.nextlab.echo.ui.auth.QuietButton
import blog.nextlab.echo.ui.auth.QuietButtonLabel
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * プロフィール画像を自分で切り取る。
 *
 * 自動の中央切り取りは、既定として使える程度には当たり、苛立つ程度には外れる
 * （顔が写真の真ん中にあることは少ない）。円をどこに置きたいか気にする人が、
 * それを言えなかった。
 *
 * 出力は、ジェスチャーの計算を逆算して元画像の矩形を求めるのではなく、**同じ変換**を
 * 256pxのキャンバスに描き直して作る。逆算は切り取りツールが数ピクセルずれていく原因で、
 * 同じ操作をもう一度やるやり方ならずれようがない。
 */
@Composable
fun PhotoCropScreen(
    source: Uri,
    photos: ProfilePhotos,
    onCancel: () -> Unit,
    onCropped: (Bitmap) -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    val context = androidx.compose.ui.platform.LocalContext.current

    var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var failure by remember(source) { mutableStateOf<String?>(null) }

    LaunchedEffect(source) {
        val result = withContext(Dispatchers.IO) {
            // アプリケーションのものではなく、この画面自身の context
            // （ピッカーの結果を受け取ったほう）から読む。
            runCatching { photos.decodeForCrop(source, reader = context) }
        }
        result.fold(
            onSuccess = { bitmap = it },
            // そのまま出す。ここで気を利かせた文にしたせいで、もう2回診断不能になった。
            onFailure = { failure = it.message ?: it::class.simpleName ?: "unknown" },
        )
    }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // 画像と表示領域が分かった時点で、円を覆う倍率まで引き上げる。
    var scale by remember(source) { mutableFloatStateOf(1f) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }

    val image = bitmap

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        Text(
            text = "位置と大きさを決める",
            style = type.screenTitle,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 24.dp, top = 10.dp, bottom = 4.dp),
        )
        Text(
            text = "指でドラッグして動かし、つまんで拡大できます。",
            style = type.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(0.dp))
                .background(Color.Black)
                .onSizeChanged { viewport = it },
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                /**
                 * 写真が円を覆う最小の倍率。
                 *
                 * `ContentScale.Fit` は余白を付けるので、正方形の表示領域に入れた
                 * 縦長の写真は1倍では円より細い。そこから始めると円が黒帯にかかり、
                 * 保存したアイコンの縁に背景が焼き付いた。これより下へは行けない。
                 */
                val minScale = remember(image, viewport) {
                    minimumScale(viewport, image)
                }
                LaunchedEffect(minScale) {
                    if (scale < minScale) scale = minScale
                    offset = clampOffset(offset, scale, viewport, image)
                }

                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    scale = (scale * zoomChange).coerceIn(minScale, minScale * MAX_ZOOM)
                    offset += panChange
                    offset = clampOffset(offset, scale, viewport, image)
                }

                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(transformState),
                )

                // 円は線で描くのではなく、暗い層に開けた穴にする。外にあるものが
                // 写真の一部でないと目に見えるように。
                //
                // 穴を穴にしているのは CompositingStrategy.Offscreen。自前のレイヤが
                // 無いと BlendMode.Clear は消すアルファを持たず、真っ黒に塗る。
                // 最初の版がまさにそれだった。
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                ) {
                    val radius = size.minDimension * CIRCLE_FRACTION / 2f
                    drawRect(color = Color.Black.copy(alpha = 0.55f))
                    drawCircle(
                        color = Color.Transparent,
                        radius = radius,
                        center = center,
                        blendMode = BlendMode.Clear,
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.9f),
                        radius = radius,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                }
            }

            failure?.let { reason ->
                Text(
                    text = "読み込めませんでした\n\n$reason",
                    style = type.listPreview,
                    color = colors.danger,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Column(Modifier.padding(horizontal = 24.dp)) {
            PrimaryButton(
                enabled = image != null,
                onClick = {
                    val ready = image ?: return@PrimaryButton
                    haptics.perform(HapticToken.SoftConfirm)
                    onCropped(renderCrop(ready, viewport, scale, offset))
                },
            ) { color -> PrimaryButtonLabel("この範囲にする", color) }

            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                QuietButton(
                    enabled = true,
                    onClick = {
                        haptics.perform(HapticToken.Navigation)
                        onCancel()
                    },
                ) { color -> QuietButtonLabel("やめる", color) }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/**
 * 写真が円を覆い続けるようにする。
 *
 * 無いと、円の半分が空くまで画像を動かせて、結果は欠けたアイコンになる。制限は
 * 画像が実際に描かれている位置から計算する（`ContentScale.Fit` が決める位置であって、
 * ビットマップ自身の大きさではない）。
 */
private fun clampOffset(
    offset: Offset,
    scale: Float,
    viewport: IntSize,
    bitmap: Bitmap,
): Offset {
    if (viewport.width == 0 || viewport.height == 0) return offset

    val fit = min(
        viewport.width.toFloat() / bitmap.width,
        viewport.height.toFloat() / bitmap.height,
    )
    val drawnWidth = bitmap.width * fit * scale
    val drawnHeight = bitmap.height * fit * scale
    val circleRadius = min(viewport.width, viewport.height) * CIRCLE_FRACTION / 2f

    val maxX = max(0f, drawnWidth / 2f - circleRadius)
    val maxY = max(0f, drawnHeight / 2f - circleRadius)

    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

/**
 * 画面上の変換を、正方形のビットマップに再演する。
 *
 * 逆算はしない。見えているとおりに、円の大きさのキャンバスへもう一度描く。
 * だから保存される画像は、構造上、枠に収めた画像そのもの。
 */
private fun renderCrop(
    bitmap: Bitmap,
    viewport: IntSize,
    scale: Float,
    offset: Offset,
): Bitmap {
    val output = Bitmap.createBitmap(OUTPUT_PX, OUTPUT_PX, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(output)

    val viewSize = min(viewport.width, viewport.height).toFloat().coerceAtLeast(1f)
    val circleDiameter = viewSize * CIRCLE_FRACTION
    // すべて「出力ピクセル／画面ピクセル」で表す。
    val outputPerScreen = OUTPUT_PX / circleDiameter

    val fit = min(
        viewport.width.toFloat() / bitmap.width,
        viewport.height.toFloat() / bitmap.height,
    )
    val drawnWidth = bitmap.width * fit * scale
    val drawnHeight = bitmap.height * fit * scale

    // 画像が画面上のどこにあるか。円の左上を原点として。
    val circleLeft = viewport.width / 2f - circleDiameter / 2f
    val circleTop = viewport.height / 2f - circleDiameter / 2f
    val imageLeft = viewport.width / 2f + offset.x - drawnWidth / 2f
    val imageTop = viewport.height / 2f + offset.y - drawnHeight / 2f

    val destination = RectF(
        (imageLeft - circleLeft) * outputPerScreen,
        (imageTop - circleTop) * outputPerScreen,
        (imageLeft - circleLeft + drawnWidth) * outputPerScreen,
        (imageTop - circleTop + drawnHeight) * outputPerScreen,
    )

    canvas.drawBitmap(
        bitmap,
        Rect(0, 0, bitmap.width, bitmap.height),
        destination,
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
    )
    return output
}

/**
 * 写真がちょうど円を覆う倍率。
 *
 * これより下だと、円の一部が写真ではなく余白の上に来る。
 */
private fun minimumScale(viewport: IntSize, bitmap: Bitmap): Float {
    if (viewport.width == 0 || viewport.height == 0) return 1f
    val fit = min(
        viewport.width.toFloat() / bitmap.width,
        viewport.height.toFloat() / bitmap.height,
    )
    val shortestDrawn = min(bitmap.width * fit, bitmap.height * fit)
    val circleDiameter = min(viewport.width, viewport.height) * CIRCLE_FRACTION
    return max(1f, circleDiameter / shortestDrawn)
}

/** 正方形の表示領域のうち、円が占める割合。 */
private const val CIRCLE_FRACTION = 0.78f

/** 「ちょうど覆う」からどこまで拡大してよいか。 */
private const val MAX_ZOOM = 6f
private const val OUTPUT_PX = 256
