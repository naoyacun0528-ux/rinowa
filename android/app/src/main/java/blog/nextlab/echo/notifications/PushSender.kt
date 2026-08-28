package blog.nextlab.echo.notifications

import blog.nextlab.echo.model.ConversationId
import blog.nextlab.echo.model.MessageText
import com.google.firebase.auth.FirebaseAuth
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 会話の他の人に通知するよう、push サーバーに頼む。
 *
 * サーバーが要るのは、FCM が信頼された環境からの送信しか受け付けないから。
 * クライアントから別のクライアントへは押せない。それが正しい設計で、できたら
 * 誰でも誰にでも通知できてしまう。なので自前のホストに、サービスの資格情報を持つ
 * 小さな窓口を置いてある。
 *
 * 要求にはこのアカウントの Firebase ID トークンを載せる。サーバーは署名を検証し、
 * 送信者の身元を本文ではなくトークンから取る。だから他人を名乗ると、忘れられうる
 * 検査ではなく署名で落ちる。
 *
 * 本文を要求に載せるのは、サーバーに他の入手方法が無いから（メッセージの
 * コレクションを読むことは禁じてある。server/push.php）。送信者はその会話の
 * 参加者だと検証済みで、送る本文はもともと自分が持っているもの。
 *
 * 失敗はエラーではない。ここが失敗してもメッセージは届く（すでに Firestore にあり、
 * 相手はアプリを開けば見る）。失うのは肩を叩く動作だけ。なので強く再試行もせず、
 * 上へ投げもしない。
 */
class PushSender(private val auth: FirebaseAuth) {

    /**
     * @param type "message" か "call"。通話は期限を短くして配る。発信側が
     *   あきらめたあとに鳴り始める端末が出ないように（遅れた着信は遅れた通知ではなく、
     *   間違った着信）。サーバーはこの項目でそれを決める。
     */
    suspend fun notify(
        conversationId: ConversationId,
        senderName: String,
        body: MessageText,
        type: String = "message",
        callId: String = "",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val idToken = auth.currentUser?.getIdToken(false)?.await()?.token
                ?: return@runCatching

            val payload = JSONObject().apply {
                put("conversationId", conversationId.value)
                put("senderName", senderName)
                // サーバー側だけでなくここでも切り詰める。通知に出るのは1〜2行なので、
                // 4000文字の本文を送るのは、何の役にも立たない場所へ運ぶこと。
                put("body", body.value.take(PREVIEW_CHARS))
                put("type", type)
                if (callId.isNotEmpty()) put("callId", callId)
            }.toString()

            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $idToken")
            }

            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            // 切る前に返事を読む。
            //
            // **1台にも届かなかった 200 は、こちらからは配達済みと同じに見える。**
            // 入れ直せばトークンは全部古くなり、通知が来なくなった端末は、静かさ以外に
            // 症状の無いバグになる。返事には何台に送ったかと、使えなかったトークンが
            // 入っている。どちらも本文ではないので書き留めて安全。
            val reply = runCatching {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
                .getOrElse { failure ->
                    android.util.Log.w("Rinowa/push", "no reply from push.php", failure)
                    ""
                }
            connection.disconnect()

            android.util.Log.i("Rinowa/push", "push.php status=" + status + " reply=" + reply)
            check(status in 200..299) { "push rejected: $status" }
        }
    }

    private companion object {
        const val ENDPOINT = "https://echo.nextlab.blog/push.php"
        const val PREVIEW_CHARS = 200
        const val TIMEOUT_MS = 8_000
    }
}
