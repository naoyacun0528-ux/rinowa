@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package blog.nextlab.echo.data

import blog.nextlab.echo.backup.BackupArchive
import blog.nextlab.echo.backup.RestoredMessages
import blog.nextlab.echo.core.model.CallOutcome
import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.core.model.MediaId
import blog.nextlab.echo.core.model.Message
import blog.nextlab.echo.core.model.MessageContent
import blog.nextlab.echo.core.model.MessageId
import blog.nextlab.echo.core.model.MessageStatus
import blog.nextlab.echo.core.model.MessageText
import blog.nextlab.echo.core.model.Reaction
import blog.nextlab.echo.core.model.ReplyPreview
import blog.nextlab.echo.core.model.StickerId
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.core.model.UserProfile
import blog.nextlab.echo.core.model.previewText
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.util.Date
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.tasks.await

/**
 * 1つの会話の中のメッセージ。
 *
 * 本文は書いたら変えられない。firestore.rules が許す更新は自分のリアクションだけで、
 * 本文・送信者・時刻は対象外。クライアントの行儀ではなくサーバ側で決まっている。
 */
class MessageRepository(
    private val db: FirebaseFirestore,
    /**
     * 本文を Megolm イベントにする。できなければ null。
     *
     * null は平文で送る合図では**ない**。暗号化に失敗したら送信を失敗させる
     * （黙って平文に落ちるアプリは、暗号を破れる相手に平文をただで渡す）。
     * プロパティ自体が null なのは別の話で、Firebase の無いビルド。
     */
    private val encrypt: (suspend (ConversationId, UserId, List<UserId>, String) -> String?)? = null,
    /** 開く。鍵がまだ来ていなければ null。 */
    private val decrypt: (suspend (ConversationId, UserId, UserId, String) -> String?)? = null,
    /** 復元したバックアップの内容。鍵で開けなかったときだけ見る。 */
    private val restored: RestoredMessages? = null,
) {

    /**
     * この端末から出る本文が封をされているか。
     *
     * 通知に本文を載せてよいかの判断に使う。push は自前の PHP と Google を通るので、
     * 載せると2つのサーバーに平文を渡すことになる。
     */
    val encrypts: Boolean get() = encrypt != null

    private fun messages(id: ConversationId) =
        db.collection(RinowaDb.Conversations.COLLECTION)
            .document(id.value)
            .collection(RinowaDb.Messages.COLLECTION)

    /** 表示中のスレッド（古い順）。[PAGE_SIZE] 件まで。画面に必要なのは末尾だけ。 */
    fun observe(
        conversationId: ConversationId,
        me: UserId,
        profiles: Map<UserId, UserProfile>,
    ): Flow<List<Message>> = messages(conversationId)
        .orderBy(RinowaDb.Messages.SENT_AT, Query.Direction.DESCENDING)
        .limit(PAGE_SIZE)
        // 降順で取って反転する。limit は先頭 N 件なので、新しい N 件が要るなら降順。
        .documentsFlow("Rinowa/messages") { snapshot ->
            snapshot.documents
                .asReversed()
                .mapNotNull { it.toMessage(me, profiles, snapshot.metadata.hasPendingWrites()) }
        }
        // 復元が入ったら開き直す。
        //
        // バックアップの復元は表を書き足すだけで、Firestore は何も動かない。ここで
        // 見ていないと、開いている画面は封のままで、押した人には失敗に見える。
        .combine(restored?.revision ?: MutableStateFlow(0)) { snapshot, _ -> snapshot }
        // 復号は別の段。suspend するので snapshot リスナーの中では待てない。
        //
        // 鍵はメッセージとは別に to-device で届き、届いても Firestore は何も変わらない。
        // スナップショットのときだけ復号すると、1秒後に鍵が来たメッセージは永久に開かない。
        // なので封のままのものが残っている間は見直す。回数は上限つき（絶対に開かないものもある）。
        .transformLatest { snapshot ->
            var opened = snapshot.map { it.opened(conversationId, me) }
            emit(opened)

            var attempts = 0
            while (opened.any { it.content is MessageContent.Locked } && attempts < OPEN_ATTEMPTS) {
                kotlinx.coroutines.delay(OPEN_RETRY_MS)
                attempts++
                opened = snapshot.map { it.opened(conversationId, me) }
                emit(opened)
            }
        }

    /**
     * 1件開く。開けなければ封のまま返す。
     *
     * 鍵より先にメッセージが着くのは普通のことなので、失敗扱いにはしない。
     */
    private suspend fun Message.opened(conversationId: ConversationId, me: UserId): Message {
        val locked = content as? MessageContent.Locked ?: return this
        val open = decrypt ?: return this
        val plaintext = open(conversationId, me, senderId, locked.ciphertext)
            ?: return restored?.contentFor(id)?.let { copy(content = it) } ?: this
        // 出てくるのは本文ではなく content 一式（写真とその鍵、通話記録など）。
        val content = MessageEnvelope.open(plaintext) ?: return this
        return copy(content = content)
    }

    /**
     * 通知に出すための、最新1件の本文。
     *
     * push には本文を載せていないので、鍵を持っているこの端末が取りに行って復号する。
     * 開けないとき（サインアウト、鍵が未着、圏外）は null。呼び出し側は本文なしの
     * 通知を出す — 届いたこと自体は伝える価値がある。
     */
    suspend fun newestBody(conversationId: ConversationId, me: UserId): String? {
        val snapshot = runCatching {
            messages(conversationId)
                .orderBy(RinowaDb.Messages.SENT_AT, Query.Direction.DESCENDING)
                .limit(1)
                // 明示的にサーバーから読む。
                //
                // push は必ずメッセージより先に着く。既定のままだとローカルキャッシュが
                // 答えて、通知が常に1件前の内容になる（それぞれは正常に見える）。
                .get(com.google.firebase.firestore.Source.SERVER)
                .await()
        }
            .onFailure { android.util.Log.w("Rinowa/push", "newest message failed", it) }
            .getOrNull() ?: return null

        val document = snapshot.documents.firstOrNull() ?: return null
        val senderId = document.getString(RinowaDb.Messages.SENDER_ID)?.let(::UserId) ?: return null

        return when (document.getString(RinowaDb.Messages.KIND)) {
            RinowaDb.Messages.KIND_TEXT -> document.getString(RinowaDb.Messages.TEXT)

            RinowaDb.Messages.KIND_ENC -> {
                val cipher = document.getString(RinowaDb.Messages.CIPHERTEXT) ?: return null
                val plaintext = decrypt?.invoke(conversationId, me, senderId, cipher)
                    ?: return null
                // 写真の通知は「写真」。鍵やハッシュを出しても意味がない。
                MessageEnvelope.open(plaintext)?.previewText()?.value
            }

            RinowaDb.Messages.KIND_IMAGE -> "写真"
            RinowaDb.Messages.KIND_STICKER -> "スタンプ"
            else -> null
        }
    }

    /**
     * バックアップ用に、会話の全メッセージを開いて返す。
     *
     * [observe] とは求めるものが逆（画面は新しい数件をすぐ、控えは全部を待ってでも）。
     * 開けなかったものは入れない。暗号文を控えても、鍵を失った日に何も戻らない。
     */
    suspend fun exportAll(
        conversationId: ConversationId,
        me: UserId,
    ): List<BackupArchive.Entry> {
        val snapshot = runCatching {
            messages(conversationId)
                .orderBy(RinowaDb.Messages.SENT_AT, Query.Direction.ASCENDING)
                .limit(EXPORT_LIMIT)
                .get()
                .await()
        }
            .onFailure { android.util.Log.w("Rinowa/backup", "export failed", it) }
            .getOrNull()
            ?: return emptyList()

        return buildList {
            for (document in snapshot.documents) {
                val senderId = document.getString(RinowaDb.Messages.SENDER_ID)?.let(::UserId)
                    ?: continue
                val sentAt = document.getTimestamp(RinowaDb.Messages.SENT_AT)?.toDate()?.time
                    ?: continue

                val content = when (document.getString(RinowaDb.Messages.KIND)) {
                    RinowaDb.Messages.KIND_ENC -> {
                        val cipher = document.getString(RinowaDb.Messages.CIPHERTEXT) ?: continue
                        val plaintext = decrypt?.invoke(conversationId, me, senderId, cipher)
                        // 一度復元したものも持っていく。復元が片道にならないように。
                        plaintext?.let(MessageEnvelope::open)
                            ?: restored?.contentFor(MessageId(document.id))
                            ?: continue
                    }

                    RinowaDb.Messages.KIND_TEXT ->
                        MessageContent.Text(MessageText(document.getString(RinowaDb.Messages.TEXT).orEmpty()))

                    else -> continue
                }

                add(
                    BackupArchive.Entry(
                        conversationId = conversationId,
                        messageId = MessageId(document.id),
                        senderId = senderId,
                        sentAtMs = sentAt,
                        content = content,
                    ),
                )
            }
        }
    }

    /**
     * 封をする対象か。
     *
     * 人が送るものは全部入れる。スタンプや通話記録は単体では大した情報ではないが、
     * 「どれが暗号化されていないか」自体を外から読めるようにはしない。
     */
    private fun MessageContent.sealable(): Boolean = when (this) {
        is MessageContent.Text, is MessageContent.Image, is MessageContent.Video,
        is MessageContent.Sticker, is MessageContent.Call,
        -> true

        is MessageContent.Locked, is MessageContent.Retracted -> false
    }

    /** 平文の経路と同じ上限。暗号化を使って長い本文を通せないように。 */
    private fun MessageContent.trimmed(): MessageContent = when (this) {
        is MessageContent.Text ->
            MessageContent.Text(MessageText(body.value.take(RinowaDb.Messages.MAX_TEXT_LENGTH)))

        else -> this
    }

    /**
     * メッセージを書き、会話一覧のプレビューを更新する。
     *
     * 待つのはメッセージの書き込みだけ。プレビューが失敗してもメッセージは届く。
     */
    suspend fun send(
        conversationId: ConversationId,
        sender: UserId,
        content: MessageContent,
        replyTo: ReplyPreview?,
        /** 読めるべき全員。画面がすでに持っているので、ここで読み直さない。 */
        members: List<UserId> = listOf(sender),
    ): Result<MessageId> = runCatching {
        // 先に封をする。会話一覧のプレビューがこの結果を見るため。
        //
        // 一覧の行は会話ドキュメントにあり、サーバーから読める。ここに本文を書くと
        // 全会話の最後の1行が漏れる。
        val sealed = content.takeIf { it.sealable() }?.let { sealable ->
            val cipher = encrypt?.invoke(
                conversationId,
                sender,
                members,
                MessageEnvelope.seal(sealable.trimmed()),
            )
            // 暗号化する仕組みがあるのに失敗した場合。平文には落とさない。
            if (cipher == null && encrypt != null) error("暗号化できませんでした")
            cipher
        }

        val payload = buildMap<String, Any?> {
            put(RinowaDb.Messages.SENDER_ID, sender.value)
            put(RinowaDb.Messages.SENT_AT, FieldValue.serverTimestamp())
            put(RinowaDb.Messages.REACTIONS, emptyMap<String, Int>())
            when {
                // 暗号化したときは封だけ書く。写真なら id もサイズもサムネイルも書かない
                // （32px でもそれは写真そのもの）。
                sealed != null -> {
                    put(RinowaDb.Messages.KIND, RinowaDb.Messages.KIND_ENC)
                    put(RinowaDb.Messages.CIPHERTEXT, sealed)
                }

                content is MessageContent.Text -> {
                    // 暗号化の仕組みが無いビルド（Firebase 無し）。相手もいない。
                    put(RinowaDb.Messages.KIND, RinowaDb.Messages.KIND_TEXT)
                    put(
                        RinowaDb.Messages.TEXT,
                        content.body.value.take(RinowaDb.Messages.MAX_TEXT_LENGTH),
                    )
                }

                content is MessageContent.Sticker -> {
                    put(RinowaDb.Messages.KIND, RinowaDb.Messages.KIND_STICKER)
                    put(RinowaDb.Messages.STICKER_ID, content.stickerId.value)
                }

                content is MessageContent.Image -> {
                    put(RinowaDb.Messages.KIND, RinowaDb.Messages.KIND_IMAGE)
                    put(RinowaDb.Messages.MEDIA_ID, content.mediaId.value)
                    put(RinowaDb.Messages.MEDIA_WIDTH, content.width.toLong())
                    put(RinowaDb.Messages.MEDIA_HEIGHT, content.height.toLong())
                    put(RinowaDb.Messages.MEDIA_BYTES, content.byteCount.toLong())
                    // サムネイルは数KBなので同じドキュメントに入れる（届いた瞬間に出る）。
                    // 本体は入らない。docs/MEDIA_ARCHITECTURE.md §4。
                    put(RinowaDb.Messages.MEDIA_THUMB, Blob.fromBytes(content.thumbnail))
                }

                content is MessageContent.Call -> {
                    put(RinowaDb.Messages.KIND, RinowaDb.Messages.KIND_CALL)
                    put(RinowaDb.Messages.CALL_KIND, if (content.video) "video" else "audio")
                    put(RinowaDb.Messages.CALL_OUTCOME, content.outcome.name.lowercase())
                    put(RinowaDb.Messages.CALL_SECONDS, content.seconds.toLong())
                }

                // どちらも送るものではない。Locked は受け取った側で開けなかった状態、
                // Retracted は送ったあと取り消した状態。
                content is MessageContent.Locked -> error("cannot send an already-sealed message")
                content is MessageContent.Retracted -> error("cannot send a retracted message")
                else -> error("unsendable content: " + content)
            }
            replyTo?.let {
                put(RinowaDb.Messages.REPLY_TO_ID, it.messageId.value)
                put(RinowaDb.Messages.REPLY_TO_NAME, it.senderName)
                put(
                    RinowaDb.Messages.REPLY_TO_EXCERPT,
                    it.excerpt.value.take(RinowaDb.Messages.PREVIEW_LENGTH),
                )
            }
        }

        // id は add() 任せにせずここで作る。書き込み回数は同じで、送る前に識別子が決まる。
        // Direct と併用したとき、同じメッセージが2件にならない。docs/DIRECT_ARCHITECTURE.md §8.3。
        val created = messages(conversationId).document()
        created.set(payload).await()

        db.collection(RinowaDb.Conversations.COLLECTION).document(conversationId.value)
            .set(
                mapOf(
                    RinowaDb.Conversations.LAST_MESSAGE to mapOf(
                        // 暗号化したときは本文を入れない。ここはサーバーから読めるので、
                        // 入れると全会話の最後の1件だけ暗号化が無意味になる。
                        RinowaDb.Conversations.LastMessage.PREVIEW to
                            if (sealed != null) {
                                LOCKED_PREVIEW
                            } else {
                                content.previewText().value.take(RinowaDb.Messages.PREVIEW_LENGTH)
                            },
                        RinowaDb.Conversations.LastMessage.SENDER_ID to sender.value,
                        RinowaDb.Conversations.LastMessage.KIND to when {
                            sealed != null -> RinowaDb.Messages.KIND_ENC
                            content is MessageContent.Sticker -> RinowaDb.Messages.KIND_STICKER
                            content is MessageContent.Image -> RinowaDb.Messages.KIND_IMAGE
                            content is MessageContent.Call -> RinowaDb.Messages.KIND_CALL
                            else -> RinowaDb.Messages.KIND_TEXT
                        },
                        RinowaDb.Conversations.LastMessage.SENT_AT to FieldValue.serverTimestamp(),
                    ),
                    RinowaDb.Conversations.LAST_MESSAGE_AT to FieldValue.serverTimestamp(),
                    RinowaDb.Conversations.UPDATED_AT to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
                // **待たないが、捨てもしない。**
                //
                // 一覧の1行はメッセージ本体とは別の書き込みで、送信の完了条件ではない。
                // だから await しない。ただし結果を見ないままだと、規則に弾かれても
                // 「送れたのに一覧だけ古い」という形で静かに壊れる。実際 0.20.2 で
                // lastMessage に検査を足したとき、それを確かめる手段がここに無かった。
                .addOnFailureListener {
                    android.util.Log.w("Rinowa/messages", "last-message update failed", it)
                }

        MessageId(created.id)
    }

    /** リアクションを付ける・変える・外す。uid をキーにするので一人1つ。null で取り消し。 */
    suspend fun react(
        conversationId: ConversationId,
        messageId: MessageId,
        me: UserId,
        paletteIndex: Int?,
    ): Result<Unit> = runCatching {
        val key = FieldPath.of(RinowaDb.Messages.REACTIONS, me.value)
        messages(conversationId).document(messageId.value)
            .update(key, paletteIndex ?: FieldValue.delete())
            .await()
    }

    /**
     * 送信を取り消す。相手が読んだかどうかで結果が変わる。
     *
     *  - 未読なら丸ごと削除。見られていないので、痕跡を残すほうが不自然。
     *  - 既読なら本文を消して跡だけ残す。読まれたものを黙って消すのは、相手の記憶を
     *    書き換えることになる。
     *
     * @param readByOthersAt 相手側の既読位置のうち最も古いもの。
     */
    suspend fun retract(
        conversationId: ConversationId,
        messageId: MessageId,
        sentAt: Long,
        readByOthersAt: Long,
    ): Result<Boolean> = runCatching {
        val document = messages(conversationId).document(messageId.value)
        val wasRead = readByOthersAt > 0 && sentAt <= readByOthersAt

        if (!wasRead) {
            document.delete().await()
            return@runCatching false
        }

        document.update(
            mapOf(
                RinowaDb.Messages.RETRACTED_AT to FieldValue.serverTimestamp(),
                // 本文は消す。残るのは「ここに何かあった」という事実だけ。
                RinowaDb.Messages.TEXT to FieldValue.delete(),
                RinowaDb.Messages.STICKER_ID to FieldValue.delete(),
                // サムネイルも本文。残すと「取り消しました」と言いながら写真が見えたままになる。
                // id は残す（単体では何も示さず、あとで取得済みの複製を捨てるのに使う）。
                RinowaDb.Messages.MEDIA_THUMB to FieldValue.delete(),
            ),
        ).await()
        true
    }

    suspend fun delete(
        conversationId: ConversationId,
        messageId: MessageId,
    ): Result<Unit> = runCatching {
        messages(conversationId).document(messageId.value).delete().await()
    }

    /** 最後に見たあとに届いた件数。件数だけ要るのでサーバー側の集計を使う。 */
    suspend fun unreadCount(
        conversationId: ConversationId,
        me: UserId,
        since: Long,
    ): Int = runCatching {
        if (since <= 0L) return@runCatching 0
        messages(conversationId)
            .whereGreaterThan(RinowaDb.Messages.SENT_AT, Date(since))
            .whereNotEqualTo(RinowaDb.Messages.SENDER_ID, me.value)
            .count()
            .get(AggregateSource.SERVER)
            .await()
            .count
            .toInt()
    }
        // 失敗して 0 になると、届いたメッセージが誰にも知らされない。
        .onFailure { android.util.Log.w("Rinowa/messages", "unread count failed", it) }
        .getOrDefault(0)

    companion object {
        /** 本文を置けないときに一覧が出すもの。 */
        const val LOCKED_PREVIEW = "🔒 メッセージ"

        /** 未着の鍵を待つ回数と間隔。合計30秒。 */
        const val OPEN_ATTEMPTS = 10
        const val OPEN_RETRY_MS = 3_000L

        const val PAGE_SIZE = 200L

        /** 1会話あたりバックアップに入れる上限。目標ではなく天井。 */
        const val EXPORT_LIMIT = 5_000L
    }
}

private fun DocumentSnapshot.toMessage(
    me: UserId,
    profiles: Map<UserId, UserProfile>,
    pendingWrites: Boolean,
): Message? {
    val senderId = UserId(getString(RinowaDb.Messages.SENDER_ID) ?: return null)
    val kind = getString(RinowaDb.Messages.KIND) ?: return null

    // kind より先に見る。取り消したメッセージは kind が元のまま残るので、
    // 先に kind を見ると空の吹き出しになる。
    if (getTimestamp(RinowaDb.Messages.RETRACTED_AT) != null) {
        val isOutgoing = senderId == me
        return Message(
            id = MessageId(id),
            senderId = senderId,
            content = MessageContent.Retracted,
            timestampMs = getTimestamp(RinowaDb.Messages.SENT_AT)?.toDate()?.time
                ?: System.currentTimeMillis(),
            isOutgoing = isOutgoing,
            senderName = if (isOutgoing) "自分" else profiles[senderId]?.displayName ?: "…",
            status = MessageStatus.Sent,
        )
    }

    val content = when (kind) {
        RinowaDb.Messages.KIND_TEXT ->
            MessageContent.Text(MessageText(getString(RinowaDb.Messages.TEXT).orEmpty()))

        RinowaDb.Messages.KIND_STICKER ->
            MessageContent.Sticker(StickerId(getString(RinowaDb.Messages.STICKER_ID) ?: return null))

        RinowaDb.Messages.KIND_IMAGE -> {
            val thumb = getBlob(RinowaDb.Messages.MEDIA_THUMB)?.toBytes()
            MessageContent.Image(
                mediaId = MediaId(getString(RinowaDb.Messages.MEDIA_ID) ?: return null),
                // 無ければ正方形にする。縦横比が違うと本体が届いたときに崩れるだけだが、
                // 0 のままだと落ちる。
                width = getLong(RinowaDb.Messages.MEDIA_WIDTH)?.toInt()?.takeIf { it > 0 } ?: 1,
                height = getLong(RinowaDb.Messages.MEDIA_HEIGHT)?.toInt()?.takeIf { it > 0 } ?: 1,
                thumbnail = thumb ?: ByteArray(0),
                byteCount = getLong(RinowaDb.Messages.MEDIA_BYTES)?.toInt() ?: 0,
            )
        }

        // そのまま持つ。復号は suspend するので、待てないここではやらない。
        RinowaDb.Messages.KIND_ENC ->
            MessageContent.Locked(getString(RinowaDb.Messages.CIPHERTEXT).orEmpty())

        RinowaDb.Messages.KIND_CALL -> MessageContent.Call(
            video = getString(RinowaDb.Messages.CALL_KIND) == "video",
            // 不明な結果は Failed 扱い。繋がらなかった通話を「通話しました」と出すほうが悪い。
            outcome = when (getString(RinowaDb.Messages.CALL_OUTCOME)) {
                "completed" -> CallOutcome.Completed
                "missed" -> CallOutcome.Missed
                "declined" -> CallOutcome.Declined
                else -> CallOutcome.Failed
            },
            seconds = getLong(RinowaDb.Messages.CALL_SECONDS)?.toInt()?.coerceAtLeast(0) ?: 0,
        )

        // このビルドが知らない種類。推測せずに飛ばす。
        else -> return null
    }

    @Suppress("UNCHECKED_CAST")
    val rawReactions = (get(RinowaDb.Messages.REACTIONS) as? Map<String, Any?>).orEmpty()
    val reactions = rawReactions
        .mapNotNull { (uid, value) -> (value as? Number)?.toInt()?.let { uid to it } }
        .groupBy({ it.second }, { it.first })
        .map { (paletteIndex, voters) ->
            Reaction(
                paletteIndex = paletteIndex,
                count = voters.size,
                mine = me.value in voters,
            )
        }
        .sortedBy { it.paletteIndex }

    val isOutgoing = senderId == me
    // サーバー時刻は、書いてから確定するまでの一瞬 null になる。その間「いま」にしておくと
    // 吹き出しが上下に飛ばない。
    val sentAt = getTimestamp(RinowaDb.Messages.SENT_AT)?.toDate()?.time
        ?: System.currentTimeMillis()

    val replyToId = getString(RinowaDb.Messages.REPLY_TO_ID)

    return Message(
        id = MessageId(id),
        senderId = senderId,
        content = content,
        timestampMs = sentAt,
        isOutgoing = isOutgoing,
        senderName = if (isOutgoing) "自分" else profiles[senderId]?.displayName ?: "…",
        status = when {
            pendingWrites && getTimestamp(RinowaDb.Messages.SENT_AT) == null -> MessageStatus.Sending
            isOutgoing -> MessageStatus.Sent
            else -> MessageStatus.Read
        },
        replyTo = replyToId?.let {
            ReplyPreview(
                messageId = MessageId(it),
                senderName = getString(RinowaDb.Messages.REPLY_TO_NAME).orEmpty(),
                excerpt = MessageText(getString(RinowaDb.Messages.REPLY_TO_EXCERPT).orEmpty()),
            )
        },
        reactions = reactions.toPersistentList(),
    )
}
