package blog.nextlab.echo.backup

import android.content.Context
import blog.nextlab.echo.data.MessageEnvelope
import blog.nextlab.echo.model.MessageContent
import blog.nextlab.echo.model.MessageId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONException
import org.json.JSONObject

/**
 * バックアップの復元が、実際に端末へ何をするか。
 *
 * 素直な実装はメッセージを Firestore に戻すことで、それは間違い。しかも間違いが
 * 出るのは他人の端末の上。戻したドキュメントは**新しい**メッセージで、今日の鍵で
 * 暗号化されていて、相手のスレッドには手元にあるはずの会話の複製が積み上がる。
 * 復元は再送ではない。
 *
 * なので復元は端末内で完結し、この端末に1つのことだけを教える。
 * **復号できないメッセージの平文**。Firestore のドキュメントはそのまま
 * （同じ id、同じ時刻、誰も鍵を持たない同じ暗号文）。スレッドがそれに出会ったら、
 * ここへ聞きに来る。
 *
 * おかげで復元は何度やっても安全で、履歴の一部をすでに読める端末でやっても安全。
 * 暗号ストアが開けるものは普段どおり開かれ、このクラスには近寄らない。
 *
 * ディスクに置くのは平文で、場所はアプリの専用領域。他の全部の鍵を持つ暗号ストアの
 * 隣。端末を解錠して root を取った人には読めるが、その人は Megolm のセッションも
 * 読めるので、線が動くわけではない。ただ「そこにある」ことは、封がされていると
 * 思い込むより知っておく価値がある。
 */
class RestoredMessages(context: Context) {

    private val file = File(context.filesDir, FILE)
    private val entries = ConcurrentHashMap<String, String>()
    private var loaded = false

    /**
     * 復元するたびに増える数。中身に意味は無く、変わったことだけを使う。
     *
     * これが無いと、**復元しても開いている画面は変わらなかった**。表を書き足しても
     * Firestore は何も動かないので、スレッドは前の結果を映したまま。押した人には
     * 「復元できなかった」に見える（実際には復元できていて、次に開いたときに読める）。
     */
    private val revisions = MutableStateFlow(0)
    val revision: StateFlow<Int> = revisions

    /** この端末が開けないメッセージの平文。復元がそれを持っていれば。 */
    @Synchronized
    fun contentFor(id: MessageId): MessageContent? {
        load()
        val envelope = entries[id.value] ?: return null
        return MessageEnvelope.open(envelope)
    }

    fun isEmpty(): Boolean {
        load()
        return entries.isEmpty()
    }

    fun size(): Int {
        load()
        return entries.size
    }

    /**
     * バックアップの中身を足す。
     *
     * 置き換えではなく統合。新しい書庫のあとに古いものを復元しても、新しいほうが
     * 教えたものを消してはいけない。目的は、前より読める履歴が増えることであって、
     * 減ることではない。
     */
    @Synchronized
    fun merge(archive: BackupArchive.Parsed) {
        load()
        for (entry in archive.entries) {
            entries[entry.messageId.value] = MessageEnvelope.seal(entry.content)
        }
        persist()
        revisions.value = revisions.value + 1
    }

    @Synchronized
    fun clear() {
        entries.clear()
        // swallow-ok: メモリ上の対応表はもう空で、読む側にはそれが全部。消せなかった
        // ファイルは次の起動で読み直され、そこでまた空にされる。
        runCatching { file.delete() }
        loaded = true
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return

        val json = runCatching { file.readText() }
            .onFailure { android.util.Log.w(TAG, "restored history unreadable", it) }
            .getOrNull() ?: return

        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            android.util.Log.w(TAG, "restored history is not an archive")
            return
        }

        for (key in root.keys()) {
            entries[key] = root.optString(key)
        }
    }

    private fun persist() {
        val root = JSONObject()
        for ((id, envelope) in entries) root.put(id, envelope)

        runCatching { file.writeText(root.toString()) }
            // 報告する。うまくいったように見えて再起動で消えている復元は、失敗したと
            // 言う復元より悪い（後者はもう一度試してもらえる）。
            .onFailure { android.util.Log.w(TAG, "could not save restored history", it) }
    }

    private companion object {
        const val FILE = "restored-history.json"
        const val TAG = "Rinowa/backup"
    }
}
