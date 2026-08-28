package blog.nextlab.echo.data

import blog.nextlab.echo.core.model.Conversation
import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.core.model.MessageText
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.core.model.UserProfile
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

/**
 * このアカウントが属する会話の一覧。
 *
 * どのクエリも `memberIds array-contains me` で絞る。便宜ではなく、firestore.rules が
 * 要求する形。これが無いクエリはサーバーに弾かれるので、「うっかり全員の会話を取る」
 * というバグはここには書けない。
 */
class ConversationRepository(
    private val db: FirebaseFirestore,
    private val users: UserRepository,
) {

    /**
     * 会話一覧を、動きの新しい順に流し続ける。
     *
     * 1回取って終わりにしない。別の会話にメッセージが届けばその行が上へ動く必要があり、
     * それを定期取得で追うのは、Firestore がすでに持っているリスナーより遅くて高い。
     */
    fun observe(me: UserId): Flow<List<Conversation>> = db
        .collection(RinowaDb.Conversations.COLLECTION)
        .whereArrayContains(RinowaDb.Conversations.MEMBER_IDS, me.value)
        .orderBy(RinowaDb.Conversations.LAST_MESSAGE_AT, Query.Direction.DESCENDING)
        .limit(MAX_CONVERSATIONS)
        .documentsFlow("Rinowa/conversations") { it.documents }
        .let { documents ->
        // 名前の解決はリスナーの外でやって手元に持つ。10件の会話が、スナップショット
        // 1回ごとに10回のプロフィール読み込みにならないように。
        kotlinx.coroutines.flow.flow {
            val nameCache = mutableMapOf<UserId, UserProfile>()
            documents.collect { docs ->
                val needed = docs
                    .flatMap { it.memberIds() }
                    .filter { it != me && it !in nameCache }
                    .distinct()
                if (needed.isNotEmpty()) nameCache += users.profiles(needed)
                emit(docs.map { it.toConversation(me, nameCache) })
            }
        }
    }

    /**
     * [other] との1対1の会話を探し、無ければ作る。
     *
     * 両者の uid から決まる id を組み立てず、自分の会話を走査して探す。組み立てた id は
     * 推測できてしまい、2人の uid を知っている誰でも「この2人は話しているか」を
     * 調べられることになる。
     */
    suspend fun openDirect(me: UserId, other: UserId): Result<ConversationId> = runCatching {
        val existing = db.collection(RinowaDb.Conversations.COLLECTION)
            .whereArrayContains(RinowaDb.Conversations.MEMBER_IDS, me.value)
            .get()
            .await()
            .documents
            .firstOrNull { document ->
                document.getString(RinowaDb.Conversations.TYPE) == RinowaDb.Conversations.TYPE_DIRECT &&
                    document.memberIds().toSet() == setOf(me, other)
            }

        if (existing != null) return@runCatching ConversationId(existing.id)

        val created = db.collection(RinowaDb.Conversations.COLLECTION).add(
            mapOf(
                RinowaDb.Conversations.TYPE to RinowaDb.Conversations.TYPE_DIRECT,
                RinowaDb.Conversations.TITLE to null,
                RinowaDb.Conversations.MEMBER_IDS to listOf(me.value, other.value),
                // 始めた人だけ。相手側は友達追加を押す必要がある。
                RinowaDb.Conversations.ACCEPTED_BY to listOf(me.value),
                RinowaDb.Conversations.CREATED_AT to FieldValue.serverTimestamp(),
                RinowaDb.Conversations.UPDATED_AT to FieldValue.serverTimestamp(),
                RinowaDb.Conversations.LAST_MESSAGE_AT to FieldValue.serverTimestamp(),
                RinowaDb.Conversations.LAST_MESSAGE to null,
            ),
        ).await()

        ConversationId(created.id)
    }

    suspend fun createGroup(
        me: UserId,
        others: List<UserId>,
        title: String,
    ): Result<ConversationId> = runCatching {
        require(others.isNotEmpty())
        val members = (listOf(me) + others).distinct().map { it.value }

        val created = db.collection(RinowaDb.Conversations.COLLECTION).add(
            mapOf(
                RinowaDb.Conversations.TYPE to RinowaDb.Conversations.TYPE_GROUP,
                RinowaDb.Conversations.TITLE to title.trim().take(MAX_TITLE_LENGTH),
                RinowaDb.Conversations.MEMBER_IDS to members,
                RinowaDb.Conversations.ACCEPTED_BY to listOf(me.value),
                RinowaDb.Conversations.CREATED_AT to FieldValue.serverTimestamp(),
                RinowaDb.Conversations.UPDATED_AT to FieldValue.serverTimestamp(),
                RinowaDb.Conversations.LAST_MESSAGE_AT to FieldValue.serverTimestamp(),
                RinowaDb.Conversations.LAST_MESSAGE to null,
            ),
        ).await()

        ConversationId(created.id)
    }

    /**
     * 会話から自分を外して抜ける。
     *
     * 削除ではない。firestore.rules は会話そのものの削除を禁じている。1人が消すと、
     * 他の全員の履歴まで持っていくことになるため。
     */
    suspend fun leave(me: UserId, id: ConversationId): Result<Unit> = runCatching {
        db.collection(RinowaDb.Conversations.COLLECTION).document(id.value).update(
            mapOf(
                RinowaDb.Conversations.MEMBER_IDS to FieldValue.arrayRemove(me.value),
                RinowaDb.Conversations.UPDATED_AT to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun conversation(me: UserId, id: ConversationId): Result<Conversation> = runCatching {
        val document = db.collection(RinowaDb.Conversations.COLLECTION).document(id.value)
            .get().await()
        val others = document.memberIds().filter { it != me }
        document.toConversation(me, users.profiles(others))
    }

    /**
     * 招待を受ける — 友達追加のボタン。
     *
     * これで新しく読めるようになるものは無い。メッセージは元から読めた（参加者で
     * あることがそれを担う）。変わるのは、その会話が要求ではなく会話になること。
     */
    suspend fun accept(me: UserId, id: ConversationId): Result<Unit> = runCatching {
        db.collection(RinowaDb.Conversations.COLLECTION).document(id.value).update(
            mapOf(
                RinowaDb.Conversations.ACCEPTED_BY to FieldValue.arrayUnion(me.value),
                RinowaDb.Conversations.UPDATED_AT to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    /** このアカウントについて、いままでを既読にする。 */
    suspend fun markRead(me: UserId, id: ConversationId): Result<Unit> = runCatching {
        db.collection(RinowaDb.Conversations.COLLECTION).document(id.value)
            .collection(RinowaDb.Reads.COLLECTION).document(me.value)
            .set(mapOf(RinowaDb.Reads.LAST_READ_AT to FieldValue.serverTimestamp()))
            .await()
    }

    /**
     * この会話の全員の既読位置を、流し続けて見る。
     *
     * 定期取得ではなく監視。既読は送った人が画面を見ている間に出る必要があり、
     * その瞬間こそがこの機能の意味。開いたときに更新する作りでは、いつも事後にしか出ない。
     */
    fun observeReads(id: ConversationId): Flow<Map<UserId, Long>> = db
        .collection(RinowaDb.Conversations.COLLECTION)
        .document(id.value)
        .collection(RinowaDb.Reads.COLLECTION)
        .documentsFlow("Rinowa/conversations") { snapshot ->
            snapshot.documents.associate { document ->
                UserId(document.id) to
                    (document.getTimestamp(RinowaDb.Reads.LAST_READ_AT)?.toDate()?.time ?: 0L)
            }
        }

    suspend fun lastReadAt(me: UserId, id: ConversationId): Long =
        runCatching {
            db.collection(RinowaDb.Conversations.COLLECTION).document(id.value)
                .collection(RinowaDb.Reads.COLLECTION).document(me.value)
                .get().await()
                .getTimestamp(RinowaDb.Reads.LAST_READ_AT)?.toDate()?.time ?: 0L
        }
            // swallow-ok: 0 に落ちると「何も読んでいない」＝全部未読として出る。
            // 安全側で、失敗の出方は「同じメッセージを2回知らされる」であって
            // 「見落とす」ではない。
            .getOrDefault(0L)

    companion object {
        const val MAX_TITLE_LENGTH = 60
        private const val MAX_CONVERSATIONS = 200L
    }
}

internal fun DocumentSnapshot.memberIds(): List<UserId> =
    (get(RinowaDb.Conversations.MEMBER_IDS) as? List<*>)
        ?.filterIsInstance<String>()
        ?.map(::UserId)
        .orEmpty()

private fun DocumentSnapshot.toConversation(
    me: UserId,
    profiles: Map<UserId, UserProfile>,
): Conversation {
    val members = memberIds()
    val isGroup = getString(RinowaDb.Conversations.TYPE) == RinowaDb.Conversations.TYPE_GROUP
    val others = members.filter { it != me }

    @Suppress("UNCHECKED_CAST")
    val last = get(RinowaDb.Conversations.LAST_MESSAGE) as? Map<String, Any?>
    val lastSenderId = last?.get(RinowaDb.Conversations.LastMessage.SENDER_ID) as? String

    val title = when {
        isGroup -> getString(RinowaDb.Conversations.TITLE)?.takeIf { it.isNotBlank() } ?: "グループ"
        // 1対1の会話は相手の名前で呼ぶ。相手が誰もいなくなったら（アカウントを消した）、
        // そうなったものの名前で呼ぶ。
        others.isEmpty() -> "退会したユーザー"
        else -> profiles[others.first()]?.displayName ?: "…"
    }

    val lastAt = getTimestamp(RinowaDb.Conversations.LAST_MESSAGE_AT)
        ?: get(RinowaDb.Conversations.LAST_MESSAGE_AT) as? Timestamp

    val accepted = (get(RinowaDb.Conversations.ACCEPTED_BY) as? List<*>)
        ?.filterIsInstance<String>()
        ?.map(::UserId)
        // この項目ができる前に作られた会話は、全員が最初から入っている扱い。
        // 使い続けている会話をもう一度承諾させるのは、バグに見える移行になる。
        ?: members

    return Conversation(
        id = ConversationId(id),
        title = title,
        preview = MessageText(
            last?.get(RinowaDb.Conversations.LastMessage.PREVIEW) as? String ?: "",
        ),
        lastTimestampMs = lastAt?.toDate()?.time ?: 0L,
        // 会話一覧の ViewModel が埋める。既読の位置を知っているのはあちら。
        unreadCount = 0,
        isGroup = isGroup,
        avatarSeed = (if (isGroup) id else others.firstOrNull()?.value ?: id).hashCode(),
        previewIsOutgoing = lastSenderId == me.value,
        memberIds = members.toPersistentList(),
        acceptedByMe = me in accepted,
    )
}
