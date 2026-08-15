package blog.nextlab.echo.model

import androidx.compose.runtime.Immutable
import java.security.MessageDigest
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

/**
 * Sticker domain model. See docs/STICKER_ARCHITECTURE.md.
 *
 * The single most important property of this file: **a message references a sticker by
 * [StickerId] and never carries its image.** Embedding image bytes in a message would
 * mean paying for the same picture on every send, would bloat conversation loads, and
 * would entangle attachments with message bodies right where end-to-end encryption will
 * later need them separated.
 */
@JvmInline
value class StickerId(val value: String)

@JvmInline
value class StickerPackId(val value: String)

/**
 * SHA-256 of the asset bytes, as lowercase hex.
 *
 * Used for integrity, cache validation, and identifying identical files. It is **not**
 * a secret and must never be treated as one: knowing a hash grants no access. Access is
 * decided by authentication and storage rules. See docs/STICKER_ARCHITECTURE.md §5.
 */
@JvmInline
value class ContentHash(val value: String) {
    companion object {
        fun of(bytes: ByteArray): ContentHash = ContentHash(
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) },
        )
    }
}

enum class StickerFormat { Webp, Png }

enum class StickerOrigin { BuiltIn, Custom, Group }

/**
 * No `Public`. Public sharing needs moderation, takedown, and rights handling designed
 * first, so the value does not exist rather than existing and being unused.
 */
enum class PackVisibility { Private, Group, Shared }

@Immutable
data class StickerAsset(
    val id: StickerId,
    val packId: StickerPackId,
    val contentHash: ContentHash,
    val widthPx: Int,
    val heightPx: Int,
    val byteSize: Int,
    val format: StickerFormat,
    val origin: StickerOrigin,
)

@Immutable
data class StickerPack(
    val id: StickerPackId,
    /** Null for the bundled pack, which nobody owns. */
    val ownerId: String?,
    val title: String,
    val visibility: PackVisibility,
    /** Monotonic, so a client can fetch only what changed. */
    val version: Int,
    val stickerIds: ImmutableList<StickerId>,
)

/** Enforced from Prototype 0 so storage abuse never becomes possible in the first place. */
object StickerLimits {
    const val MAX_DIMENSION_PX = 512
    const val MAX_BYTES = 200 * 1024
}

/** The pack shipped inside the APK. Images live in `assets/stickers/`. */
object BuiltInStickers {
    val packId = StickerPackId("pack_builtin_v1")

    data class Entry(val id: StickerId, val fileName: String, val label: String)

    val entries: List<Entry> = listOf(
        Entry(StickerId("st_iine"), "st_iine.png", "いいね"),
        Entry(StickerId("st_arigato"), "st_arigato.png", "ありがと"),
        Entry(StickerId("st_ok"), "st_ok.png", "OK!"),
        Entry(StickerId("st_ukeru"), "st_ukeru.png", "うける"),
        Entry(StickerId("st_gomen"), "st_gomen.png", "ごめん"),
        Entry(StickerId("st_tasukaru"), "st_tasukaru.png", "たすかる"),
        Entry(StickerId("st_otsukare"), "st_otsukare.png", "おつかれ"),
        Entry(StickerId("st_matteru"), "st_matteru.png", "まってる"),
    )

    val pack = StickerPack(
        id = packId,
        ownerId = null,
        title = "Echo",
        visibility = PackVisibility.Private,
        version = 1,
        stickerIds = entries.map { it.id }.toPersistentList(),
    )
}
