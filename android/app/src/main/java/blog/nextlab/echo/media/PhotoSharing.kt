package blog.nextlab.echo.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 写真を他のアプリに渡す。ファイルそのものは渡さない。
 *
 * [FileProvider] の content:// は Intent 1回分だけ読み取りを許す。file:// は
 * 今の Android では落ちるうえ、許可の範囲も広すぎる。
 */
object PhotoSharing {

    fun share(context: Context, image: ImageBitmap, name: String) {
        val uri = writeShareable(context, image, name) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/webp"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "写真を共有"))
    }

    /**
     * ギャラリーに保存する。
     *
     * パスに直接書かず MediaStore 経由。RELATIVE_PATH なら storage 権限が要らない。
     *
     * 送るときは WebP だが、保存は JPEG。保存したファイルはアプリの外（フォームや
     * 古い印刷アプリ）へ出ていくもので、そこで断られるのは WebP のほう。再圧縮の劣化は
     * 2048 への縮小に比べれば見えない。
     */
    fun saveToGallery(context: Context, image: ImageBitmap, name: String): Boolean = runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Rinowa")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { stream ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 95, stream)
        } ?: return false
        true
    }
        // 画面には「保存できませんでした」しか出ないので、理由はここに残す。
        .onFailure { android.util.Log.w("Rinowa/photo", "save to gallery failed", it) }
        .getOrDefault(false)

    /**
     * オリジナルをそのまま保存する。再圧縮も縮小も回転もしない。
     *
     * デコーダを通した時点でオリジナルではなくなる。MIME は送信側から来たものを使う
     * （元のファイルを見たのは送信側だけ）。
     */
    fun saveOriginalToGallery(
        context: Context,
        source: File,
        name: String,
        mime: String,
    ): Boolean = runCatching {
        val values = android.content.ContentValues().apply {
            put(
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                name + extensionFor(mime),
            )
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Rinowa")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return false

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
        }
        true
    }
        .onFailure { android.util.Log.w("Rinowa/photo", "save original failed", it) }
        .getOrDefault(false)

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/heic", "image/heif" -> ".heic"
        else -> ".jpg"
    }

    /**
     * 動画を他のアプリに渡す。
     *
     * 写真と違って、渡せるファイルはまだ手元に無いことがある（本体は再生か保存を
     * 押されてから取りに行く）。呼ぶ側が取得を終えてから、そのファイルを渡す。
     *
     * 復号済みの本体をそのまま指すことはしない。FileProvider に見せているのは
     * cacheDir/share だけで、それ以外を公開すると受信済みの動画が全部外から
     * 取れることになる。1本だけ写して渡す。
     */
    fun shareVideo(context: Context, source: File, name: String): Boolean = runCatching {
        val directory = File(context.cacheDir, "share").apply { mkdirs() }
        val copy = File(directory, name + ".mp4")
        source.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }

        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", copy)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "動画を共有"))
        true
    }
        .onFailure { android.util.Log.w("Rinowa/video", "share failed", it) }
        .getOrDefault(false)

    /**
     * 動画をギャラリーに保存する。
     *
     * メモリに載せず流し込む（数十MBを読み込むと低メモリ時に殺される）。書いている間は
     * IS_PENDING を立てて、途中の動画がギャラリーに出ないようにする。
     */
    fun saveVideoToGallery(context: Context, source: File, name: String): Boolean = runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name + ".mp4")
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Rinowa")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return false

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
        }
        true
    }
        .onFailure { android.util.Log.w("Rinowa/video", "save to gallery failed", it) }
        .getOrDefault(false)

    private fun writeShareable(context: Context, image: ImageBitmap, name: String): android.net.Uri? =
        runCatching {
            val directory = File(context.cacheDir, "share").apply { mkdirs() }
            val file = File(directory, "$name.webp")
            file.outputStream().use { image.asAndroidBitmap().compress(webpFormat(), 95, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
            // ここで null を返すと共有シートが黙って開かないので、理由をログに残す。
            .onFailure { android.util.Log.w("Rinowa/photo", "share file failed", it) }
            .getOrNull()

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
}
