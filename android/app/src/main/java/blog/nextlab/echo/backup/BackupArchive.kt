package blog.nextlab.echo.backup

import blog.nextlab.echo.data.MessageEnvelope
import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.core.model.MessageContent
import blog.nextlab.echo.core.model.MessageId
import blog.nextlab.echo.core.model.UserId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * バックアップに何が入り、どう書かれるか。
 *
 * 入るのはメッセージで、メディアは入らない。写真や動画は保管庫から取り直せるが、
 * メッセージは開く鍵が消えたらどこからも戻らない。なので書庫は会話と、
 * **その中のメディアへの参照**（id と鍵）を運び、バイト列は運ばない。すでにサーバーに
 * ある動画で他人の15GBを埋めるのは割に合わないし、それこそ人がバックアップを
 * 切る理由になる。
 *
 * 中身を MessageEnvelope で書くのは、メッセージ自身が通るのと同じ形式だから。
 * 符号化器も復号器もテストも1組で済み、写真が復元を生き延びる理由は送信を生き延びる
 * 理由とまったく同じになる。同じものに形式を2つ作れば必ずずれ、そのずれは
 * 誰かが復元する日にだけ現れる。
 *
 * 版の番号は書いて確認する。新しいビルドが書いたファイルは、半分読まずに拒否する。
 * 会話の部分的な復元は、何も戻らないより悪い（うまくいったように見えるから）。
 */
object BackupArchive {

    const val VERSION = 1

    /** 復号済みのメッセージ1件。書き戻される形。 */
    class Entry(
        val conversationId: ConversationId,
        val messageId: MessageId,
        val senderId: UserId,
        val sentAtMs: Long,
        val content: MessageContent,
    )

    class Parsed(
        val version: Int,
        val owner: UserId,
        val createdAtMs: Long,
        val entries: List<Entry>,
    )

    fun write(owner: UserId, createdAtMs: Long, entries: List<Entry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject()
                    .put(CONVERSATION, entry.conversationId.value)
                    .put(MESSAGE, entry.messageId.value)
                    .put(SENDER, entry.senderId.value)
                    .put(SENT_AT, entry.sentAtMs)
                    // 封の中身をそのまま。MessageEnvelope を参照。
                    .put(CONTENT, MessageEnvelope.seal(entry.content)),
            )
        }

        return JSONObject()
            .put(VERSION_KEY, VERSION)
            .put(OWNER, owner.value)
            .put(CREATED_AT, createdAtMs)
            .put(MESSAGES, array)
            .toString()
    }

    /**
     * 書庫を読む。このビルドが解せるものでなければ null。
     *
     * null になるのは「JSON でない」「版が無い」「もっと新しいものが書いた」の3つ。
     * 復元する人にとってはどれも同じ意味で、このファイルはここでは使えない。
     */
    fun read(json: String): Parsed? {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return null
        }

        val version = root.optInt(VERSION_KEY, 0)
        if (version <= 0 || version > VERSION) return null

        val owner = root.optString(OWNER).takeIf { it.isNotEmpty() } ?: return null
        val messages = root.optJSONArray(MESSAGES) ?: JSONArray()

        val entries = buildList {
            for (i in 0 until messages.length()) {
                val item = messages.optJSONObject(i) ?: continue
                val content = MessageEnvelope.open(item.optString(CONTENT)) ?: continue
                val conversation = item.optString(CONVERSATION).takeIf { it.isNotEmpty() }
                    ?: continue
                val message = item.optString(MESSAGE).takeIf { it.isNotEmpty() } ?: continue
                val sender = item.optString(SENDER).takeIf { it.isNotEmpty() } ?: continue

                add(
                    Entry(
                        conversationId = ConversationId(conversation),
                        messageId = MessageId(message),
                        senderId = UserId(sender),
                        sentAtMs = item.optLong(SENT_AT),
                        content = content,
                    ),
                )
            }
        }

        return Parsed(
            version = version,
            owner = UserId(owner),
            createdAtMs = root.optLong(CREATED_AT),
            entries = entries,
        )
    }

    private const val VERSION_KEY = "v"
    private const val OWNER = "owner"
    private const val CREATED_AT = "at"
    private const val MESSAGES = "messages"
    private const val CONVERSATION = "c"
    private const val MESSAGE = "m"
    private const val SENDER = "s"
    private const val SENT_AT = "t"
    private const val CONTENT = "e"
}
