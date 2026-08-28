package blog.nextlab.echo.core.model

import androidx.compose.runtime.Immutable
import java.security.MessageDigest
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

/**
 * スタンプのモデル。docs/STICKER_ARCHITECTURE.md。
 *
 * このファイルで一番大事な性質: **メッセージはスタンプを [StickerId] で参照し、
 * 画像そのものは運ばない。** 画像のバイト列をメッセージに入れると、同じ絵を送るたびに
 * 費用を払い、会話の読み込みが重くなり、あとで E2EE が分離を必要とするまさにその場所で
 * 添付と本文が絡まる。
 */
@JvmInline
value class StickerId(val value: String)

@JvmInline
value class StickerPackId(val value: String)

/**
 * 素材のバイト列の SHA-256（小文字16進）。
 *
 * 完全性の確認、キャッシュの妥当性、同一ファイルの判定に使う。秘密では**なく**、
 * 秘密として扱ってもいけない。ハッシュを知っていても何の権限も得られない。
 * 見てよいかを決めるのは認証と保存側のルール。docs/STICKER_ARCHITECTURE.md §5。
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
 * `Public` は無い。公開共有には、先に審査・削除対応・権利の扱いを設計する必要がある。
 * だから値を作って使わずに置くのではなく、値そのものを作らない。
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
    /** 同梱セットは誰のものでもないので null。 */
    val ownerId: String?,
    val title: String,
    val visibility: PackVisibility,
    /** 単調増加。クライアントが変わったぶんだけ取れるように。 */
    val version: Int,
    val stickerIds: ImmutableList<StickerId>,
)

/** Prototype 0 から効かせている。保管の乱用が、そもそも起こりえないように。 */
object StickerLimits {
    const val MAX_DIMENSION_PX = 512
    const val MAX_BYTES = 200 * 1024
}

/** APK に同梱したセット。画像は `assets/stickers/` にある。 */
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
        title = "Rinowa",
        visibility = PackVisibility.Private,
        version = 1,
        stickerIds = entries.map { it.id }.toPersistentList(),
    )
}
