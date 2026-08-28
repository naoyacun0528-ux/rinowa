package blog.nextlab.echo.data

import blog.nextlab.echo.model.ContentHash
import blog.nextlab.echo.model.StickerFormat
import blog.nextlab.echo.model.StickerId
import blog.nextlab.echo.model.StickerLimits
import blog.nextlab.echo.model.UserId
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/** クラウド上にある、スタンプ1つの正本。 */
class RemoteSticker(
    val id: StickerId,
    val bytes: ByteArray,
    val contentHash: ContentHash,
    val widthPx: Int,
    val heightPx: Int,
    val format: StickerFormat,
    val ownerId: UserId,
)

/**
 * スタンプの正本。
 *
 * docs/ROADMAP.md は Cloud Storage を挙げているが、意図的に Firestore を使っている:
 *
 *  - スタンプは [StickerLimits.MAX_BYTES]＝200KB が上限で、Firestore の1MBに十分収まる。
 *    Cloud Storage が解決する問題（ドキュメントに入らない大きさ）がそもそも起きない。
 *  - Cloud Storage は Blaze プランが要る。Rinowa は友人数人のための試作で、200KBの
 *    ファイルを動かすために課金アカウントを抱えるのは、まだ割に合わない。
 *  - サービスが1つで済めば、正しく書くべきセキュリティルールも1つで済む。
 *
 * **これで設計が変わるわけではない。** メッセージが運ぶのは [StickerId] だけで画像は
 * 運ばない。正本は1回だけ保存され、端末ごとに1回だけ取得される。描画に使うのは端末内の
 * 複製。あとで Cloud Storage に移すときは、このクラスの中身を差し替えるだけで他はどこも
 * 触らない。docs/STICKER_ARCHITECTURE.md が id 越しの参照にこだわった理由がそこ。
 *
 * 実際の代償は2つ。CDN が無いことと、取得が1回のドキュメント読み取りになること。
 * どちらも許せるのは、スタンプの取得が端末につき最大1回だから（[LocalStickerStore]）。
 */
class StickerRepository(private val db: FirebaseFirestore) {

    private val collection get() = db.collection(RinowaDb.Stickers.COLLECTION)

    suspend fun fetch(id: StickerId): Result<RemoteSticker> = runCatching {
        val document = collection.document(id.value).get().await()
        require(document.exists()) { "sticker not found" }

        val blob = document.getBlob(RinowaDb.Stickers.BYTES) ?: error("sticker has no bytes")
        RemoteSticker(
            id = id,
            bytes = blob.toBytes(),
            contentHash = ContentHash(document.getString(RinowaDb.Stickers.CONTENT_HASH).orEmpty()),
            widthPx = document.getLong(RinowaDb.Stickers.WIDTH_PX)?.toInt() ?: 0,
            heightPx = document.getLong(RinowaDb.Stickers.HEIGHT_PX)?.toInt() ?: 0,
            format = when (document.getString(RinowaDb.Stickers.FORMAT)) {
                StickerFormat.Webp.name -> StickerFormat.Webp
                else -> StickerFormat.Png
            },
            ownerId = UserId(document.getString(RinowaDb.Stickers.OWNER_ID).orEmpty()),
        )
    }

    /**
     * この端末で作ったスタンプを公開する。
     *
     * id は内容のハッシュではなく新しいドキュメント id。ハッシュを id にすると、
     * アカウントをまたいで同じ画像が黙って1つにまとめられる。重複排除には誰も答えて
     * いない問いが付いてくる（共有された物の持ち主は誰か、片方が消したらどうなるか、
     * 「あなたと見知らぬ他人が同じスタンプを作った」を仕組みが知ってよいのか）。
     * docs/STICKER_ARCHITECTURE.md。
     */
    suspend fun publish(
        owner: UserId,
        bytes: ByteArray,
        widthPx: Int,
        heightPx: Int,
        format: StickerFormat,
    ): Result<StickerId> = runCatching {
        require(bytes.size <= StickerLimits.MAX_BYTES) { "sticker too large" }
        require(widthPx in 1..StickerLimits.MAX_DIMENSION_PX)
        require(heightPx in 1..StickerLimits.MAX_DIMENSION_PX)

        val document = collection.document()
        document.set(
            mapOf(
                RinowaDb.Stickers.OWNER_ID to owner.value,
                RinowaDb.Stickers.BYTES to Blob.fromBytes(bytes),
                RinowaDb.Stickers.CONTENT_HASH to ContentHash.of(bytes).value,
                RinowaDb.Stickers.WIDTH_PX to widthPx,
                RinowaDb.Stickers.HEIGHT_PX to heightPx,
                RinowaDb.Stickers.FORMAT to format.name,
                RinowaDb.Stickers.CREATED_AT to FieldValue.serverTimestamp(),
            ),
        ).await()

        StickerId(document.id)
    }

    suspend fun delete(id: StickerId): Result<Unit> = runCatching {
        collection.document(id.value).delete().await()
    }

    /** このアカウントが持つスタンプの id。新しい順。 */
    suspend fun owned(owner: UserId): Result<List<StickerId>> = runCatching {
        collection
            .whereEqualTo(RinowaDb.Stickers.OWNER_ID, owner.value)
            .orderBy(RinowaDb.Stickers.CREATED_AT, Query.Direction.DESCENDING)
            .limit(MAX_OWNED)
            .get().await()
            .documents
            .map { StickerId(it.id) }
    }

    /**
     * アカウントのスタンプ帳。参照だけで、画像は入らない。
     *
     * 新しい端末でも同じアカウントだと感じられるのはこれのおかげ。id の一覧なので
     * 1回の読み取りで戻り、指している画像は必要になったときに取りに行く。
     * docs/SYNC_AND_BACKUP.md §3: サインインがダウンロードの行列になってはいけない。
     */
    suspend fun saveLibrary(
        owner: UserId,
        owned: List<StickerId>,
        favourites: List<StickerId>,
        recent: List<StickerId>,
    ): Result<Unit> = runCatching {
        db.collection(RinowaDb.Users.COLLECTION).document(owner.value)
            .collection(RinowaDb.Users.STICKER_LIBRARY)
            .document(RinowaDb.Users.STICKER_LIBRARY_DOC)
            .set(
                mapOf(
                    RinowaDb.StickerLibrary.OWNED to owned.map { it.value },
                    RinowaDb.StickerLibrary.FAVOURITES to favourites.map { it.value },
                    RinowaDb.StickerLibrary.RECENT to recent.take(MAX_RECENT).map { it.value },
                    RinowaDb.StickerLibrary.UPDATED_AT to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            ).await()
    }

    suspend fun library(owner: UserId): Result<StickerLibrary> = runCatching {
        val document = db.collection(RinowaDb.Users.COLLECTION).document(owner.value)
            .collection(RinowaDb.Users.STICKER_LIBRARY)
            .document(RinowaDb.Users.STICKER_LIBRARY_DOC)
            .get().await()

        fun ids(field: String) = (document.get(field) as? List<*>)
            ?.filterIsInstance<String>()
            ?.map(::StickerId)
            .orEmpty()

        StickerLibrary(
            owned = ids(RinowaDb.StickerLibrary.OWNED),
            favourites = ids(RinowaDb.StickerLibrary.FAVOURITES),
            recent = ids(RinowaDb.StickerLibrary.RECENT),
        )
    }

    companion object {
        private const val MAX_OWNED = 500L
        private const val MAX_RECENT = 30
    }
}

class StickerLibrary(
    val owned: List<StickerId>,
    val favourites: List<StickerId>,
    val recent: List<StickerId>,
) {
    val isEmpty: Boolean get() = owned.isEmpty() && favourites.isEmpty() && recent.isEmpty()
}
