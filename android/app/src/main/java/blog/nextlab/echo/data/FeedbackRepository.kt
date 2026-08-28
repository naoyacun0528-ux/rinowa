package blog.nextlab.echo.data

import androidx.compose.runtime.Immutable
import blog.nextlab.echo.core.analytics.FeedbackCategory
import blog.nextlab.echo.model.UserId
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

@Immutable
data class FeedbackItem(
    val id: String,
    val authorId: UserId,
    val title: String,
    val body: String,
    val category: FeedbackCategory,
    val createdAtMs: Long,
    val voteCount: Int,
    val votedByMe: Boolean,
    val mine: Boolean,
)

/**
 * フィードバックと、それへの投票。
 *
 * ここが、利用者の書いた文章を保存して開発者が読む唯一の場所。
 *
 * docs/PRIVACY_PRINCIPLES.md の穴ではなく、原則が引いている線そのもの。
 * メッセージの本文は*他人に向けて*書かれ、Rinowa は読まずに運ぶ。フィードバックは
 * *開発者に向けて*、そう書いてある画面で、意図して書かれる。意図が違えば規則も違う。
 *
 * その線は、ぼかさない限りでしか意味を持たない。なのでフィードバックの文章は
 * Firestore へ行き、他のどこへも行かない。計測に渡るのは長さだけ。
 * docs/ANALYTICS_SCHEMA.md §6。
 */
class FeedbackRepository(private val db: FirebaseFirestore) {

    private val collection get() = db.collection(RinowaDb.Feedback.COLLECTION)

    suspend fun submit(
        author: UserId,
        title: String,
        body: String,
        category: FeedbackCategory,
    ): Result<String> = runCatching {
        val cleanTitle = title.trim().take(RinowaDb.Feedback.MAX_TITLE_LENGTH)
        val cleanBody = body.trim().take(RinowaDb.Feedback.MAX_BODY_LENGTH)
        require(cleanTitle.isNotEmpty())

        val document = collection.add(
            mapOf(
                RinowaDb.Feedback.AUTHOR_ID to author.value,
                RinowaDb.Feedback.TITLE to cleanTitle,
                RinowaDb.Feedback.BODY to cleanBody,
                RinowaDb.Feedback.CATEGORY to category.wireName,
                RinowaDb.Feedback.CREATED_AT to FieldValue.serverTimestamp(),
            ),
        ).await()
        document.id
    }

    /**
     * 投稿された全部を新しい順に、投票数付きで。
     *
     * 投票数はドキュメントのカウンタ項目ではなく、項目ごとの集計クエリから取る。
     * カウンタにすると投票する全員が書き込めることになり、つまり誰でも好きな値を
     * 書ける。誰も信じられない数字は、クエリが1つ増えるより悪い。
     */
    suspend fun list(me: UserId): Result<List<FeedbackItem>> = runCatching {
        val documents = collection
            .orderBy(RinowaDb.Feedback.CREATED_AT, Query.Direction.DESCENDING)
            .limit(MAX_ITEMS)
            .get().await()
            .documents

        documents.map { document ->
            val votes = document.reference.collection(RinowaDb.Feedback.VOTES)
            // swallow-ok: 読めなかった投票数は0として出す。一覧は使えるままで、
            // 投票そのものは報告のある submit() を通る。
            val count = runCatching {
                votes.count().get(AggregateSource.SERVER).await().count.toInt()
            }.getOrDefault(0)
            // swallow-ok: 上と同じ。「不明」は「未投票」と同じ見た目になり、
            // 押せば報告のある経路を通って表示も直る。
            val mineVote = runCatching {
                votes.document(me.value).get().await().exists()
            }.getOrDefault(false)

            val authorId = UserId(document.getString(RinowaDb.Feedback.AUTHOR_ID).orEmpty())
            FeedbackItem(
                id = document.id,
                authorId = authorId,
                title = document.getString(RinowaDb.Feedback.TITLE).orEmpty(),
                body = document.getString(RinowaDb.Feedback.BODY).orEmpty(),
                category = categoryOf(document.getString(RinowaDb.Feedback.CATEGORY)),
                createdAtMs = document.getTimestamp(RinowaDb.Feedback.CREATED_AT)
                    ?.toDate()?.time ?: 0L,
                voteCount = count,
                votedByMe = mineVote,
                mine = authorId == me,
            )
        }.sortedWith(compareByDescending<FeedbackItem> { it.voteCount }.thenByDescending { it.createdAtMs })
    }

    /** @return 変更後の投票の状態。 */
    suspend fun toggleVote(me: UserId, feedbackId: String): Result<Boolean> = runCatching {
        val vote = collection.document(feedbackId)
            .collection(RinowaDb.Feedback.VOTES)
            .document(me.value)

        if (vote.get().await().exists()) {
            vote.delete().await()
            false
        } else {
            vote.set(mapOf(RinowaDb.Feedback.VOTE_CREATED_AT to FieldValue.serverTimestamp())).await()
            true
        }
    }

    suspend fun withdraw(feedbackId: String): Result<Unit> = runCatching {
        collection.document(feedbackId).delete().await()
    }

    private fun categoryOf(wire: String?): FeedbackCategory =
        FeedbackCategory.entries.firstOrNull { it.wireName == wire } ?: FeedbackCategory.Other

    private companion object {
        const val MAX_ITEMS = 200L
    }
}
