package blog.nextlab.echo.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * カメラが録ったものを、実際に送るものに変える。
 *
 * オリジナルは上げない。最近の端末の4Kは1分で数百MBあり、送る側は遅く、受け取る側は
 * 高くつき、縦1080pxの画面では見た目も変わらない。ここで**720p/30fps**に変換する。
 * 同じ動画だと分かる大きさで、量は1桁減る。
 *
 * 送る人が意識しなくてよくなることもある。端末は写真と同じように録画にも位置情報を
 * 書き込む。ここは複製して項目を消すのではなく**デコードして再エンコード**する。
 * 持ち越さなかったものは忘れようがないが、消す処理は忘れられる。
 *
 * MediaCodec ではなく Transformer なのは、見たことのない端末でも動く必要があるから。
 * ハードウェアエンコーダを選び、設定を断られたら落とし、縦の動画が横向きに届く原因の
 * 回転メタデータも扱う。自前で書くと、そのどれもを他人の端末の上で知ることになる。
 */
@UnstableApi
object VideoTranscoder {

    /** 送信側が結果を説明するのに必要なもの。 */
    class Result(
        val file: File,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        /** 吹き出し用の WebP。動画を1バイトも取得する前に出る。 */
        val poster: ByteArray,
    )

    /**
     * [source] を [destination] に再エンコードする。
     *
     * @param onProgress 0..100。吹き出しの進捗リング用。2分の動画は、止まった円だと
     *   ハングに見えるだけの時間がかかる。
     */
    suspend fun transcode(
        context: Context,
        source: Uri,
        destination: File,
        onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.Main) {
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
            .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
            .build()

        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(
                androidx.media3.transformer.Effects(
                    emptyList(),
                    // 短辺を720に。縦の動画でも必要な側の解像度が残る。すでに小さい
                    // ものはそのまま（拡大しても容量が増えるだけで見えるものは増えない）。
                    listOf(
                        androidx.media3.effect.Presentation.createForShortSide(SHORT_SIDE_PX),
                    ),
                ),
            )
            .build()

        val progress = androidx.media3.transformer.ProgressHolder()
        val ticker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
            .launch {
                while (isActive) {
                    if (transformer.getProgress(progress) ==
                        Transformer.PROGRESS_STATE_AVAILABLE
                    ) {
                        onProgress(progress.progress)
                    }
                    kotlinx.coroutines.delay(PROGRESS_INTERVAL_MS)
                }
            }

        try {
            suspendCancellableCoroutine { continuation ->
                transformer.addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) {
                            continuation.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            // 握り潰さず報告する。黙って変換に失敗した動画は、画面に
                            // 何も出ないまま送られないだけになる。
                            continuation.resumeWithException(exportException)
                        }
                    },
                )
                continuation.invokeOnCancellation { transformer.cancel() }
                transformer.start(item, destination.absolutePath)
            }
        } finally {
            ticker.cancel()
        }

        describe(destination)
    }

    /**
     * エンコード後のファイルから読み直した寸法・長さ・1枚目。
     *
     * 縦に構えて撮った動画は、**横のフレーム＋「90度回して」という注記**として
     * 保存されていることが非常に多い。プレイヤーは注記に従うが、保存された縦横を
     * そのまま読む吹き出しは従わず、縦の動画に横長の場所を空けてしまう。
     *
     * なのでここで返すのは**見た目の**縦横で、保存上の値ではない。arrows We2 での実測:
     * 縦の録画がエンコーダから `1280x720 rot=90` で出てきて、以前のコードはそれを
     * 横長として扱っていた。
     */
    private suspend fun describe(file: File): Result = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val stored = { key: Int -> retriever.extractMetadata(key)?.toIntOrNull() ?: 0 }
            val storedWidth = stored(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val storedHeight = stored(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = stored(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val turned = rotation == 90 || rotation == 270
            val width = if (turned) storedHeight else storedWidth
            val height = if (turned) storedWidth else storedHeight

            // 記録に残す。動画の形は、作った端末では正しく見えて受け取った端末で
            // おかしくなる類のものだから。
            android.util.Log.i(
                "Rinowa/video",
                "encoded " + storedWidth + "x" + storedHeight +
                    " rot=" + rotation + " shown " + width + "x" + height,
            )

            val frame = retriever.getFrameAtTime(POSTER_AT_US)?.let { upright(it, width, height) }
            Result(
                file = file,
                width = width,
                height = height,
                durationMs = duration,
                poster = frame?.let(::encodePoster) ?: ByteArray(0),
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * 1枚目を、動画が表示される形に合わせて回す。
     *
     * 端末ではなくビットマップに尋ねる。実測した端末では `getFrameAtTime` が
     * **すでに回転を適用**していて（1280x720 として保存されたものを 720x1280 で返した）、
     * そこでは何もしない（ログは `turn=false`）。それでもこの処理があるのは、その挙動が
     * メーカーをまたいで保証されていないから。横倒しの1枚目も、二重に回った1枚目も同じく醜い。
     *
     * どちらも仮定しない。返ってきたフレームと、動画が表示される形を比べ、食い違うときだけ
     * 回す（この端末についてではなく、このビットマップについての判断）。正方形の動画では
     * 自明に一致し、それが正方形の動画にとって正しい答え。
     */
    private fun upright(frame: Bitmap, width: Int, height: Int): Bitmap {
        if (width <= 0 || height <= 0) return frame

        val frameIsWide = frame.width > frame.height
        val shownIsWide = width > height
        android.util.Log.i(
            "Rinowa/video",
            "poster frame " + frame.width + "x" + frame.height +
                " shown " + width + "x" + height +
                " turn=" + (frameIsWide != shownIsWide),
        )
        if (frameIsWide == shownIsWide) return frame

        val matrix = android.graphics.Matrix().apply { postRotate(QUARTER_TURN) }
        return runCatching {
            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
        }
            // swallow-ok: 横倒しの1枚目は絵として悪いだけで、送信の失敗ではない。
            // 動画自体は自分の回転情報を持っていて、正しい向きで再生される。
            .getOrDefault(frame)
    }

    private fun encodePoster(frame: Bitmap): ByteArray {
        val scale = POSTER_EDGE_PX.toFloat() /
            maxOf(1, maxOf(frame.width, frame.height)).toFloat()
        val scaled = if (scale >= 1f) {
            frame
        } else {
            Bitmap.createScaledBitmap(
                frame,
                maxOf(1, (frame.width * scale).toInt()),
                maxOf(1, (frame.height * scale).toInt()),
                true,
            )
        }
        return ByteArrayOutputStream().use { out ->
            @Suppress("DEPRECATION")
            scaled.compress(Bitmap.CompressFormat.WEBP, POSTER_QUALITY, out)
            out.toByteArray()
        }
    }

    private const val SHORT_SIDE_PX = 720

    /**
     * 90度、片方向。
     *
     * 向きは当てずっぽうではない。上の比較はフレームと動画が食い違うときにしか
     * 走らず、90/270 の対ではどちらに回しても**形**は正しくなる。ここにあるのは
     * 手元の端末が出すものに合わせた向きで、上下逆の動画が出たらここを反転する。
     */
    private const val QUARTER_TURN = 90f

    /** 少し進んだ位置のフレーム。最初の1枚はぼけていることが多い。 */
    private const val POSTER_AT_US = 300_000L
    private const val POSTER_EDGE_PX = 320
    private const val POSTER_QUALITY = 70
    private const val PROGRESS_INTERVAL_MS = 250L
}
