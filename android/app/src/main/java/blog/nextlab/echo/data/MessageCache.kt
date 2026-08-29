package blog.nextlab.echo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import blog.nextlab.echo.core.model.ConversationId

/**
 * 開いたメッセージの、この端末の写し。
 *
 * ## なぜ要るのか
 *
 * これが無いと、一覧を開くたびにサーバーへ聞き直すことになる。会話が10件あれば
 * 10往復。それが「起動してから一覧が開くまで数秒」の正体だった。そして
 * **圏外では何も出なかった。**
 *
 * ## なぜ filesDir なのか（cacheDir ではなく）
 *
 * `cacheDir` は OS が予告なく消せる。「ストレージを空ける」を押した瞬間、
 * 空き容量が逼迫したとき、設定アプリの「キャッシュを削除」——どれでも黙って
 * 消える。そうなると一覧はまた数秒かかり、圏外で何も読めなくなり、しかも
 * **なぜそうなったか誰にも分からない。**
 *
 * 4 MB を守るために、アプリの一番効く部分を OS の気分に預ける理由が無い。
 * 写真の本体は逆で、あれは取り直せるので `cacheDir`（[MediaBudget] を参照）。
 *
 * ## 中身は封を開けた本文
 *
 * ここには読める形の文字が入る。端末の中なので設計としてはそれでよいが、
 * **Android の自動バックアップに載せてはいけない。** 載れば本文が Google の
 * クラウドへ行き、E2EE の約束が設定1つで崩れる。マニフェストは
 * `allowBackup="false"` だが、それに依存せず `data_extraction_rules.xml` でも
 * 明示的に除外してある。設定は誰かがいつか変える。
 *
 * ## 予算の配り方
 *
 * 会話ごとに件数を決めない。クラスのグループは1日100通いくのに、たまにしか
 * 話さない人は月に3通。同じ件数を割り当てると、片方は無駄で片方は足りない。
 *
 *  1. **全会話の最後の1件は必ず残す。** 一覧が通信ゼロで出る条件で、
 *     100会話でも約 20 KB。
 *  2. 残りを、最近開いた会話から順に埋める。
 *  3. あふれたら、いちばん長く開いていない会話の古いものから捨てる。
 *
 * 1件はおよそ 154 バイト（現実に近い1,874通で実測）。4 MB でおよそ
 * 27,000件——活発な数人が大半を使い、休んでいる会話は最後の1件だけ持つ。
 */
class MessageCache(context: Context) {

    private val helper = object : SQLiteOpenHelper(context, NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    conversation_id TEXT NOT NULL,
                    message_id      TEXT NOT NULL,
                    sender_id       TEXT NOT NULL,
                    sent_at         INTEGER NOT NULL,
                    kind            TEXT NOT NULL,
                    body            TEXT,
                    PRIMARY KEY (conversation_id, message_id)
                )
                """.trimIndent(),
            )
            // 一覧が引くのは「会話ごとの最新1件」。索引が無いと全件を走る。
            db.execSQL("CREATE INDEX idx_recent ON $TABLE (conversation_id, sent_at DESC)")
            db.execSQL(
                """
                CREATE TABLE $OPENED (
                    conversation_id TEXT PRIMARY KEY,
                    opened_at       INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // 作り直す。**取り直せるものなので、移し替える価値が無い。**
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            db.execSQL("DROP TABLE IF EXISTS $OPENED")
            onCreate(db)
        }
    }

    class Entry(
        val conversationId: String,
        val messageId: String,
        val senderId: String,
        val sentAt: Long,
        val kind: String,
        val body: String?,
    )

    // ---------------------------------------------------------------- 書く

    fun put(entries: List<Entry>) {
        if (entries.isEmpty()) return
        runCatching {
            helper.writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    entries.forEach { entry ->
                        db.insertWithOnConflict(
                            TABLE,
                            null,
                            ContentValues().apply {
                                put("conversation_id", entry.conversationId)
                                put("message_id", entry.messageId)
                                put("sender_id", entry.senderId)
                                put("sent_at", entry.sentAt)
                                put("kind", entry.kind)
                                put("body", entry.body)
                            },
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
        // swallow-ok: これはアプリが書いた写し。書けなくても、サーバーから
        // 取り直せば同じものが出る。遅くなるだけで、何も失われない。
    }

    /** その会話を開いた。捨てる順がこれで決まる。 */
    fun markOpened(conversationId: ConversationId) {
        runCatching {
            helper.writableDatabase.use { db ->
                db.insertWithOnConflict(
                    OPENED,
                    null,
                    ContentValues().apply {
                        put("conversation_id", conversationId.value)
                        put("opened_at", System.currentTimeMillis())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 読む

    /**
     * 一覧に出す、会話ごとの最新1件。
     *
     * **1回の問い合わせで全会話ぶんを取る。** 会話ごとに聞くと、通信をやめた
     * 意味が半分になる（往復はしないが、問い合わせの数は会話の数だけ残る）。
     */
    fun newestPerConversation(): Map<String, Entry> = runCatching {
        val out = LinkedHashMap<String, Entry>()
        helper.readableDatabase.use { db ->
            db.rawQuery(
                """
                SELECT m.conversation_id, m.message_id, m.sender_id, m.sent_at, m.kind, m.body
                FROM $TABLE m
                JOIN (
                    SELECT conversation_id, MAX(sent_at) AS newest
                    FROM $TABLE GROUP BY conversation_id
                ) t
                ON m.conversation_id = t.conversation_id AND m.sent_at = t.newest
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    out[id] = Entry(
                        conversationId = id,
                        messageId = cursor.getString(1),
                        senderId = cursor.getString(2),
                        sentAt = cursor.getLong(3),
                        kind = cursor.getString(4),
                        body = cursor.getString(5),
                    )
                }
            }
        }
        out
    }.getOrDefault(emptyMap())

    // ---------------------------------------------------------------- 捨てる

    /**
     * 予算を超えた分を捨てる。
     *
     * 会話ごとの最新1件は必ず残す。そこが消えると一覧が空になり、この写しを
     * 持っている意味が無くなる。
     */
    fun prune(budgetBytes: Long = DEFAULT_BUDGET) {
        runCatching {
            helper.writableDatabase.use { db ->
                val rows = DatabaseUtilsCount(db)
                val allowed = budgetBytes / BYTES_PER_ROW
                if (rows <= allowed) return@use

                db.execSQL(
                    """
                    DELETE FROM $TABLE WHERE rowid IN (
                        SELECT m.rowid FROM $TABLE m
                        LEFT JOIN $OPENED o ON o.conversation_id = m.conversation_id
                        WHERE m.sent_at <> (
                            SELECT MAX(sent_at) FROM $TABLE
                            WHERE conversation_id = m.conversation_id
                        )
                        ORDER BY COALESCE(o.opened_at, 0) ASC, m.sent_at ASC
                        LIMIT ?
                    )
                    """.trimIndent(),
                    arrayOf((rows - allowed).toString()),
                )
            }
        }
    }

    private fun DatabaseUtilsCount(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    /** ログアウトと退会で呼ぶ。**残しておくと、次に入った人が前の人の会話を見る。** */
    fun clear() {
        runCatching {
            helper.writableDatabase.use { db ->
                db.execSQL("DELETE FROM $TABLE")
                db.execSQL("DELETE FROM $OPENED")
            }
        }
    }

    companion object {
        private const val NAME = "rinowa_messages.db"
        private const val VERSION = 1
        private const val TABLE = "messages"
        private const val OPENED = "opened"

        /**
         * 1件あたりの見積り。現実に近いコーパス1,874通で 154 バイト（平均）。
         * 索引と SQLite の諸費用を足して、少し多めに見ておく。
         */
        const val BYTES_PER_ROW = 200L

        /** 4 MB。およそ2万件で、100会話なら平均200件ぶん。 */
        const val DEFAULT_BUDGET = 4L * 1024 * 1024
    }
}
