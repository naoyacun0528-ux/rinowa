package blog.nextlab.echo.backup

import blog.nextlab.echo.data.ConversationRepository
import blog.nextlab.echo.data.MessageRepository
import blog.nextlab.echo.core.model.UserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * バックアップを作り、戻す。
 *
 * 流れ:
 *
 * ```
 * メッセージ（ここで復号）→ BackupArchive → BackupCipher → DriveAppData
 * ```
 *
 * 各段は他所の担当。このクラスは*何を*いつやるかだけを決め、JSON も AES も HTTP も
 * 知らない。理由はいつもと同じで、暗号化を変える日に直すファイルがちょうど1つで済むように。
 *
 * バックアップに入らないもの: メディアのバイト列と、この端末が読めないメッセージ。
 * 前者は保管庫から取り直せる。後者は**誰も鍵を持たない暗号文**で、それで埋まった書庫は
 * 完全に見えて何も戻さない。BackupArchive を参照。
 *
 * 復元はサーバーに何も書かない。この端末が復号できないメッセージの平文を教えるだけで、
 * ほかには手を触れない（[RestoredMessages]）。
 */
class BackupRepository(
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val restored: RestoredMessages,
    private val drive: DriveAppData,
    private val now: () -> Long = System::currentTimeMillis,
) {

    class Summary(val conversations: Int, val messages: Int, val bytes: Int)

    /**
     * 書庫を作って送る。
     *
     * @param secret 暗証番号または合言葉。**失うとバックアップも失う**。これ無しで
     *   開ける仕組みはここにも他所にも無い。
     */
    suspend fun backUp(me: UserId, secret: CharArray): Result<Summary> = runCatching {
        val list = withTimeoutOrNull(CONVERSATION_WAIT_MS) { conversations.observe(me).first() }
            ?: error("会話の一覧を読み込めませんでした")

        val entries = buildList {
            for (conversation in list) {
                addAll(messages.exportAll(conversation.id, me))
            }
        }

        val archive = BackupArchive.write(me, now(), entries).toByteArray()
        val sealed = BackupCipher.seal(archive, secret)

        drive.upload(fileName(now()), sealed).getOrThrow()
        // 向こうに完全なファイルができてからにする。先に整理すると、持っている
        // バックアップを、送信に失敗するかもしれないものと引き換えにすることになる。
        drive.prune()
            // 報告はするが失敗にはしない。バックアップ自体はもう無事。古いファイルが
            // 残るのは他人のドライブの容量の話で、知る価値はあるが、成功した
            // バックアップを失敗にする理由にはならない。
            .onFailure { android.util.Log.w(TAG, "could not prune old backups", it) }

        Summary(
            conversations = list.size,
            messages = entries.size,
            bytes = sealed.size,
        )
    }

    /** すでにドライブにあるもの。新しい順。 */
    suspend fun available(): Result<List<DriveAppData.Item>> = drive.list()

    /**
     * 1件開いて、この端末の読める履歴に混ぜる。
     *
     * 失敗は画面に出せる文で返す。その場にいる人にとって意味のある違いは
     * 「暗証番号が違う」と「落とせなかった」で、分けているのはその2つ。
     */
    suspend fun restore(item: DriveAppData.Item, secret: CharArray): Result<Int> = runCatching {
        val blob = drive.download(item.id).getOrThrow()
        val opened = BackupCipher.open(blob, secret)
            ?: error("暗証番号が違うか、ファイルが壊れています")
        val archive = BackupArchive.read(String(opened))
            ?: error("このバックアップは、このバージョンでは読めません")

        restored.merge(archive)
        archive.entries.size
    }

    private fun fileName(at: Long): String = "rinowa-" + at + ".backup"

    private companion object {
        const val TAG = "Rinowa/backup"

        /**
         * 会話一覧は流し続けるクエリで、バックアップはそこから答えを1つ欲しい。
         *
         * 悪い回線での冷えた起動に足りる程度に長く、どうやっても駄目なときに
         * 座り込まずそう言える程度に短く。
         */
        const val CONVERSATION_WAIT_MS = 20_000L
    }
}
