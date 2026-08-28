package blog.nextlab.echo.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import blog.nextlab.echo.core.model.BuiltInStickers
import blog.nextlab.echo.core.model.ContentHash
import blog.nextlab.echo.core.model.StickerAsset
import blog.nextlab.echo.core.model.StickerFormat
import blog.nextlab.echo.core.model.StickerId
import blog.nextlab.echo.core.model.StickerOrigin
import blog.nextlab.echo.core.model.StickerPackId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** スタンプを出せなかった理由。 */
sealed interface StickerMiss {
    /** 手元に無く、問い合わせ先も設定されていない。 */
    data object NotAvailableLocally : StickerMiss

    /** バイト列が期待するハッシュと合わず、複製を捨てた。 */
    data object IntegrityFailed : StickerMiss

    /** 問い合わせたが向こうにも無い。消されたか、そもそも公開されていない。 */
    data object NotFoundRemotely : StickerMiss

    data object NetworkFailed : StickerMiss
}

/**
 * この端末が見たことのあるスタンプの、自分用の複製。
 *
 * 置き場所は `cacheDir` ではなく `filesDir`。Android は容量が減ると予告なく
 * `cacheDir` を消し、圏外でスタンプが消えた会話は穴だらけになる。これはキャッシュ
 * ではなく、端末に置く資産。
 *
 * それでも正本ではない。正本は [StickerRepository] にあり、ここはその実体化で
 * いつでも取り直せる。だからバックアップの対象から外している。docs/SYNC_AND_BACKUP.md。
 *
 * 解決:
 *
 * ```
 * stickerId -> あり : そのまま描く。通信なし
 *           -> 無し : 1回取得し、ハッシュを検証し、保存して描く
 * ```
 *
 * 送る側は相手が持っているか確認しない。id を送るだけで、キャッシュは受け取る側の
 * 都合。取得は**1回**にまとめる（[inFlight] があるので、同じ新スタンプの吹き出しが
 * 20個あってもダウンロードは1回）。
 */
class LocalStickerStore(
    private val context: Context,
    private val remote: StickerRepository? = null,
) {

    private val directory = File(context.filesDir, STICKER_DIR)
    private val index = ConcurrentHashMap<StickerId, StickerAsset>()
    private val decoded = ConcurrentHashMap<StickerId, ImageBitmap>()
    private val missed = ConcurrentHashMap<StickerId, StickerMiss>()

    private val inFlight = ConcurrentHashMap<StickerId, Boolean>()
    private val diskLock = Mutex()

    /**
     * 取得が届くたびに増える。
     *
     * Compose の state なので、仮画像を描いていた吹き出しが再コンポーズして
     * 画像を拾う。無いと、関係ない再コンポーズが起きるまで出ず、スクロールすると
     * 直るバグのように見える。
     */
    var revision by mutableStateOf(0)
        private set

    /**
     * 同梱のセットを端末の置き場に展開する。
     *
     * APK の中に入ってはいるが、あとから取得したものと同じディレクトリに入れる。
     * 参照経路を1本にするため（組み込み用の特別扱いを作ると、本物の経路とずれていく）。
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

            // swallow-ok: これはアプリが書いたキャッシュ。読めない＝持っていないと
            // 同じ扱いで、また取りに行く。
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

    /**
     * 前回のインストールから残っているスタンプを索引に取り込む。
     *
     * 安いし、アプリのファイルが残る再インストールでは何も落とし直さずに済む。
     */
    fun rescan() {
        directory.mkdirs()
        directory.listFiles().orEmpty().forEach { file ->
            val id = StickerId(file.nameWithoutExtension)
            if (index.containsKey(id)) return@forEach
            if (BuiltInStickers.entries.any { it.fileName == file.name }) return@forEach

            // swallow-ok: これはアプリが書いたキャッシュ。読めない＝持っていないと
            // 同じ扱いで、また取りに行く。
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            index[id] = StickerAsset(
                id = id,
                packId = CUSTOM_PACK,
                contentHash = ContentHash.of(bytes),
                widthPx = bounds.outWidth,
                heightPx = bounds.outHeight,
                byteSize = bytes.size,
                format = if (file.extension == "webp") StickerFormat.Webp else StickerFormat.Png,
                origin = StickerOrigin.Custom,
            )
        }
    }

    /** 端末が持っているスタンプの情報。無ければ null。 */
    fun asset(id: StickerId): StickerAsset? = index[id]

    fun missReason(id: StickerId): StickerMiss? = missed[id]

    /**
     * 画像。この端末がすでに持っていれば。
     *
     * 通信を待たずに null を返す。無いときは仮画像を出して会話をスクロールさせる
     * べきで、一覧を止めてはいけない。
     */
    fun image(id: StickerId): ImageBitmap? {
        decoded[id]?.let { return it }
        val asset = index[id] ?: return null
        val file = fileFor(asset) ?: return null

        // swallow-ok: デコードできないスタンプは「無い」として取り直す。報告しても
        // 取り直しがすでにやること以上のことは言えない。
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        return bitmap.asImageBitmap().also { decoded[id] = it }
    }

    /**
     * 無かったとき: 正本から1回取得し、検証し、保存して描く。
     *
     * ハッシュの確認は完全性のためで、安全のためではない。途中で切れたダウンロードや
     * 壊れたファイルを捕まえるだけで、誰がスタンプを見てよいかについては何も言わない。
     * それを決めるのは firestore.rules。docs/STICKER_ARCHITECTURE.md §5。
     */
    suspend fun fetchAndPersist(id: StickerId): Result<StickerAsset> {
        index[id]?.let { return Result.success(it) }
        val repository = remote
            ?: return Result.failure(StickerUnavailable(StickerMiss.NotAvailableLocally))

        // 別の吹き出しがすでに取りに行っている。そちらに任せる。
        if (inFlight.putIfAbsent(id, true) != null) {
            return Result.failure(StickerUnavailable(StickerMiss.NotAvailableLocally))
        }

        try {
            val fetched = repository.fetch(id).getOrElse { error ->
                val reason = if (error.message?.contains("not found") == true) {
                    StickerMiss.NotFoundRemotely
                } else {
                    StickerMiss.NetworkFailed
                }
                missed[id] = reason
                return Result.failure(StickerUnavailable(reason))
            }

            val actual = ContentHash.of(fetched.bytes)
            if (fetched.contentHash.value.isNotEmpty() && actual != fetched.contentHash) {
                missed[id] = StickerMiss.IntegrityFailed
                return Result.failure(StickerUnavailable(StickerMiss.IntegrityFailed))
            }

            val extension = if (fetched.format == StickerFormat.Webp) "webp" else "png"
            val file = File(directory, "${id.value}.$extension")
            withContext(Dispatchers.IO) {
                diskLock.withLock {
                    directory.mkdirs()
                    file.writeBytes(fetched.bytes)
                }
            }

            val asset = StickerAsset(
                id = id,
                packId = CUSTOM_PACK,
                contentHash = actual,
                widthPx = fetched.widthPx,
                heightPx = fetched.heightPx,
                byteSize = fetched.bytes.size,
                format = fetched.format,
                origin = StickerOrigin.Custom,
            )
            index[id] = asset
            missed.remove(id)
            revision++
            return Result.success(asset)
        } finally {
            inFlight.remove(id)
        }
    }

    /** この端末が作ったばかりのスタンプを、往復せずに置く。 */
    suspend fun persistLocal(
        id: StickerId,
        bytes: ByteArray,
        widthPx: Int,
        heightPx: Int,
        format: StickerFormat,
    ): StickerAsset {
        val extension = if (format == StickerFormat.Webp) "webp" else "png"
        val file = File(directory, "${id.value}.$extension")
        withContext(Dispatchers.IO) {
            diskLock.withLock {
                directory.mkdirs()
                file.writeBytes(bytes)
            }
        }
        return StickerAsset(
            id = id,
            packId = CUSTOM_PACK,
            contentHash = ContentHash.of(bytes),
            widthPx = widthPx,
            heightPx = heightPx,
            byteSize = bytes.size,
            format = format,
            origin = StickerOrigin.Custom,
        ).also {
            index[id] = it
            revision++
        }
    }

    /** いますぐ描けるスタンプ全部。 */
    fun localIds(): List<StickerId> = index.keys.toList()

    private fun fileFor(asset: StickerAsset): File? {
        BuiltInStickers.entries.firstOrNull { it.id == asset.id }?.let {
            return File(directory, it.fileName).takeIf(File::exists)
        }
        val extension = if (asset.format == StickerFormat.Webp) "webp" else "png"
        return File(directory, "${asset.id.value}.$extension").takeIf(File::exists)
    }

    private companion object {
        const val STICKER_DIR = "stickers"
        val CUSTOM_PACK = StickerPackId("pack_custom")
    }
}

class StickerUnavailable(val reason: StickerMiss) : Exception("sticker unavailable: $reason")
