package jp.echo.android.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import jp.echo.android.model.BuiltInStickers
import jp.echo.android.model.ContentHash
import jp.echo.android.model.StickerAsset
import jp.echo.android.model.StickerFormat
import jp.echo.android.model.StickerId
import jp.echo.android.model.StickerOrigin

/** Why a sticker could not be shown. */
sealed interface StickerMiss {
    /** Not held locally, and there is no remote to ask in Prototype 0. */
    data object NotAvailableLocally : StickerMiss

    /** Bytes on disk did not match the expected hash; the copy was discarded. */
    data object IntegrityFailed : StickerMiss
}

/**
 * The device's own copy of the stickers it has seen.
 *
 * ## Where the files live, and why it matters
 *
 * Under `filesDir`, **not** `cacheDir`. Android clears `cacheDir` without warning when
 * storage runs short, and a sticker that disappears while offline is a conversation with
 * holes in it. These are persistent local assets, not cache.
 *
 * They are still not the master copy. From Prototype 1 the master lives in Cloud Storage
 * and this directory is a local materialisation of it, re-fetchable at any time — which
 * is exactly why it is excluded from backup. See docs/SYNC_AND_BACKUP.md.
 *
 * ## Resolution
 *
 * ```
 * stickerId -> HIT  : render immediately, no network
 *           -> MISS : fetch once, verify hash, persist, render
 * ```
 *
 * A sender never checks whether the recipient already holds a sticker. It sends the id;
 * caching is entirely the receiving client's business.
 */
class LocalStickerStore(private val context: Context) {

    private val directory = File(context.filesDir, STICKER_DIR)
    private val index = ConcurrentHashMap<StickerId, StickerAsset>()
    private val decoded = ConcurrentHashMap<StickerId, ImageBitmap>()

    /**
     * Materialises the bundled pack into the local store.
     *
     * The pack ships inside the APK, but it is installed into the same directory as
     * everything fetched later, so there is exactly one lookup path rather than a
     * special case for built-ins that would drift from the real one.
     */
    fun installBuiltIns() {
        directory.mkdirs()

        BuiltInStickers.entries.forEach { entry ->
            val file = File(directory, entry.fileName)
            if (!file.exists()) {
                runCatching {
                    context.assets.open("$STICKER_DIR/${entry.fileName}").use { source ->
                        file.outputStream().use { source.copyTo(it) }
                    }
                }.onFailure { return@forEach }
            }

            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            index[entry.id] = StickerAsset(
                id = entry.id,
                packId = BuiltInStickers.packId,
                contentHash = ContentHash.of(bytes),
                widthPx = bounds.outWidth,
                heightPx = bounds.outHeight,
                byteSize = bytes.size,
                format = StickerFormat.Png,
                origin = StickerOrigin.BuiltIn,
            )
        }
    }

    /** Metadata for a locally held sticker, or null on a miss. */
    fun asset(id: StickerId): StickerAsset? = index[id]

    /**
     * The image, if this device already holds it.
     *
     * Returns null rather than blocking on a network call: a miss must render a
     * placeholder and let the conversation scroll, never stall the list.
     */
    fun image(id: StickerId): ImageBitmap? {
        decoded[id]?.let { return it }
        val asset = index[id] ?: return null
        val file = fileFor(asset) ?: return null

        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        return bitmap.asImageBitmap().also { decoded[id] = it }
    }

    /**
     * The miss path: fetch once from the master copy, verify, persist, then render.
     *
     * Prototype 0 has no backend by design, so this always reports a miss. The shape is
     * here now so that Prototype 1 fills in a body rather than reshaping every call site.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchAndPersist(id: StickerId): Result<StickerAsset> =
        Result.failure(StickerUnavailable(StickerMiss.NotAvailableLocally))

    private fun fileFor(asset: StickerAsset): File? =
        BuiltInStickers.entries.firstOrNull { it.id == asset.id }
            ?.let { File(directory, it.fileName) }
            ?.takeIf { it.exists() }

    private companion object {
        const val STICKER_DIR = "stickers"
    }
}

class StickerUnavailable(val reason: StickerMiss) : Exception("sticker unavailable: $reason")
