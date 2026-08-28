package blog.nextlab.echo.data

import blog.nextlab.echo.model.CallOutcome
import blog.nextlab.echo.model.MediaId
import blog.nextlab.echo.model.MessageContent
import blog.nextlab.echo.model.MessageText
import blog.nextlab.echo.model.StickerId
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONException
import org.json.JSONObject

/**
 * 暗号化された封の中に、実際に入るもの。
 *
 * 最初の版は*本文*（1行の文字列）だけを封じ、それ以外はサーバーから読める Firestore の
 * ドキュメントに残していた。文字だけを封じている間はそれでよかったが、写真を送った
 * 瞬間に成り立たなくなる。id もサイズも形も**サムネイルも**封の外に出る。32pxの
 * サムネイルは小さいが、写真そのものには違いない。写真の説明文だけ暗号化して写真は
 * 平文で送るのは、暗号化ではなく装飾。
 *
 * なので封じる単位は、種類を問わず**content 全体**にして、小さな JSON 1つとして書く。
 * 外のドキュメントに残るのは経路に要るものだけ（誰が、いつ、封がされていること）。
 *
 * メディアの鍵がここを通るのは、保管庫のファイルが専用の鍵で暗号化されていて、その鍵を
 * 相手の端末へ届ける必要があるから。メッセージのドキュメントに入れると、データベースを
 * 読める者が全部の写真を取って開けることになり、保管庫を作った意味が消える。封の中なら、
 * そのメッセージを読める端末にしか届かない。保管庫には、運用者を含む誰にとっても
 * 無意味なバイト列だけが残る。
 *
 * これができる前に書かれたものは、封の中身が素の文字列だった。[open] は解釈できない
 * 平文を文字として扱うので、そういうメッセージも開き続ける。代償は失敗する JSON 解析
 * 1回で、それが「古いメッセージも読める」と「古いメッセージは消えた」の差になる。
 * 履歴を捨ててよい研究用のビルドでも、読めるメッセージを黙って失うのは、この形式が
 * どこまで信用できるかについて間違ったことを教える。
 */
@OptIn(ExperimentalEncodingApi::class)
object MessageEnvelope {

    /** 封じる平文として [content] を書き出す。 */
    fun seal(content: MessageContent): String = when (content) {
        is MessageContent.Text -> JSONObject()
            .put(TYPE, TYPE_TEXT)
            .put(BODY, content.body.value)
            .toString()

        is MessageContent.Image -> JSONObject()
            .put(TYPE, TYPE_IMAGE)
            .put(MEDIA_ID, content.mediaId.value)
            .put(WIDTH, content.width)
            .put(HEIGHT, content.height)
            .put(BYTES, content.byteCount)
            .put(THUMBNAIL, Base64.encode(content.thumbnail))
            .apply { content.mediaKey?.let { put(MEDIA_KEY, Base64.encode(it)) } }
            .apply { content.originalId?.let { put(ORIGINAL_ID, it.value) } }
            .apply { content.originalKey?.let { put(ORIGINAL_KEY, Base64.encode(it)) } }
            .apply { content.originalBytes?.let { put(ORIGINAL_BYTES, it) } }
            .apply { content.originalMime?.let { put(ORIGINAL_MIME, it) } }
            .toString()

        is MessageContent.Video -> JSONObject()
            .put(TYPE, TYPE_VIDEO)
            .put(MEDIA_ID, content.mediaId.value)
            .put(WIDTH, content.width)
            .put(HEIGHT, content.height)
            .put(DURATION_MS, content.durationMs)
            .put(BYTES, content.byteCount)
            .put(SEALED_BYTES, content.sealedBytes)
            .put(THUMBNAIL, Base64.encode(content.thumbnail))
            .apply { content.mediaKey?.let { put(MEDIA_KEY, Base64.encode(it)) } }
            .toString()

        is MessageContent.Sticker -> JSONObject()
            .put(TYPE, TYPE_STICKER)
            .put(STICKER_ID, content.stickerId.value)
            .toString()

        // 通話の記録は「通話があった」と言うだけで、鍵の要る要素は無い。それでも封じる。
        // 「どのメッセージが暗号化する価値が無かったか」自体、外から見えてよいものではない。
        is MessageContent.Call -> JSONObject()
            .put(TYPE, TYPE_CALL)
            .put(CALL_VIDEO, content.video)
            .put(CALL_OUTCOME, content.outcome.name.lowercase())
            .put(CALL_SECONDS, content.seconds)
            .toString()

        is MessageContent.Locked -> error("cannot seal an already-sealed message")
        MessageContent.Retracted -> error("cannot seal a retracted message")
    }

    /**
     * 復号された平文を content に戻す。
     *
     * 例外は投げない。解釈できないメッセージは新しい版が書いたものか壊れたもので、
     * それで落ちるスレッドは、1行描けないスレッドより悪い。null は
     * 「この端末にはこれが何か分からない」という意味。
     */
    fun open(plaintext: String): MessageContent? {
        val json = try {
            JSONObject(plaintext)
        } catch (_: JSONException) {
            // 封ができる前に書かれたもの。平文そのものが本文だった。
            return MessageContent.Text(MessageText(plaintext))
        }

        return when (json.optString(TYPE)) {
            TYPE_TEXT -> MessageContent.Text(MessageText(json.optString(BODY)))

            TYPE_IMAGE -> MessageContent.Image(
                mediaId = MediaId(json.optString(MEDIA_ID)),
                width = json.optInt(WIDTH),
                height = json.optInt(HEIGHT),
                thumbnail = json.optString(THUMBNAIL).let {
                    if (it.isEmpty()) ByteArray(0) else decode(it)
                },
                byteCount = json.optInt(BYTES),
                mediaKey = json.optString(MEDIA_KEY).ifEmpty { null }?.let(::decode),
                originalId = json.optString(ORIGINAL_ID).ifEmpty { null }?.let(::MediaId),
                originalKey = json.optString(ORIGINAL_KEY).ifEmpty { null }?.let(::decode),
                originalBytes = json.optInt(ORIGINAL_BYTES).takeIf { it > 0 },
                originalMime = json.optString(ORIGINAL_MIME).ifEmpty { null },
            )

            TYPE_VIDEO -> MessageContent.Video(
                mediaId = MediaId(json.optString(MEDIA_ID)),
                width = json.optInt(WIDTH),
                height = json.optInt(HEIGHT),
                durationMs = json.optLong(DURATION_MS),
                thumbnail = json.optString(THUMBNAIL).let {
                    if (it.isEmpty()) ByteArray(0) else decode(it)
                },
                byteCount = json.optInt(BYTES),
                sealedBytes = json.optLong(SEALED_BYTES),
                mediaKey = json.optString(MEDIA_KEY).ifEmpty { null }?.let(::decode),
            )

            TYPE_STICKER -> MessageContent.Sticker(
                StickerId(json.optString(STICKER_ID)),
            )

            TYPE_CALL -> MessageContent.Call(
                video = json.optBoolean(CALL_VIDEO),
                outcome = CallOutcome.entries
                    .firstOrNull { it.name.equals(json.optString(CALL_OUTCOME), ignoreCase = true) }
                    ?: CallOutcome.Completed,
                seconds = json.optInt(CALL_SECONDS),
            )

            // 封ではない普通の JSON オブジェクト。誰かがそう打っただけ。解釈はできたので
            // 上の分岐には落ちないが、これもただの文字。
            else -> MessageContent.Text(MessageText(plaintext))
        }
    }

    private fun decode(value: String): ByteArray =
        try {
            Base64.decode(value)
        } catch (_: IllegalArgumentException) {
            // 途中で切れているか壊れている。空のサムネイルは仮画像になるだけだが、
            // 例外を投げるとスレッドの残り全部を失う。
            ByteArray(0)
        }

    private const val TYPE = "t"
    private const val TYPE_TEXT = "text"
    private const val TYPE_IMAGE = "image"
    private const val TYPE_VIDEO = "video"
    private const val TYPE_STICKER = "sticker"
    private const val TYPE_CALL = "call"

    private const val BODY = "b"
    private const val MEDIA_ID = "id"
    private const val MEDIA_KEY = "k"
    private const val WIDTH = "w"
    private const val HEIGHT = "h"
    private const val BYTES = "n"
    private const val THUMBNAIL = "th"
    private const val ORIGINAL_ID = "oid"
    private const val ORIGINAL_KEY = "ok"
    private const val ORIGINAL_BYTES = "on"
    private const val ORIGINAL_MIME = "om"
    private const val DURATION_MS = "ms"
    private const val SEALED_BYTES = "sn"
    private const val STICKER_ID = "s"
    private const val CALL_VIDEO = "v"
    private const val CALL_OUTCOME = "o"
    private const val CALL_SECONDS = "sec"
}
