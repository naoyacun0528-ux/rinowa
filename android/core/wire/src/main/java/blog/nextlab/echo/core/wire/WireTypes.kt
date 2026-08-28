package blog.nextlab.echo.core.wire

/**
 * Yosegi が運ぶものと、それを運ぶ前提になる共有知識。
 *
 * `model/Models.kt` とはわざと分けてある。アプリの `Message` は画面のためのもの
 * （`isOutgoing`、Compose 向けの不変リスト、sealed な content）。線の上に要るのは
 * 別のもので、結び付けると画面側の都合がそのまま転送形式の判断になる。
 * 両者の対応付けは呼び出し側に置く。
 *
 * id をアプリの value class ではなく素の `String` にしているのも同じ理由。
 * このモジュールはアプリに依存しないので、アプリ無しで試験できる。
 */

/** `MessageStatus` と対応。線の上を通るのは序数なので、**並びは凍結**。 */
enum class WireStatus { Sending, Sent, Delivered, Read, Failed }

data class WireReply(
    val messageId: String,
    val senderName: String,
    val excerpt: String,
)

data class WireMessage(
    /** 20文字の Firestore ドキュメント id。 */
    val id: String,
    /** 28文字の Firebase uid。 */
    val senderId: String,
    val timestampMs: Long,
    val status: WireStatus = WireStatus.Sent,
    val text: String? = null,
    val stickerId: String? = null,
    val replyTo: WireReply? = null,
    /** 参加者一覧から引けないときだけ。普通は線の上に出ない。 */
    val senderName: String? = null,
    /** uid とパレットの添字。 */
    val reactions: Map<String, Int> = emptyMap(),
    val retracted: Boolean = false,
)

/**
 * 1バイトも送る前から、両端がすでに知っていること。
 *
 * これは仮定ではない。会話ドキュメントは `memberIds` を持ち、クラウドでも Direct でも
 * 配達先はすでに開かれた会話。スタンプの目録はアプリに同梱されている。それらを
 * 言わないことがこの形式で一番大きな節約になる（送信者が42バイトから1バイトになる）。
 *
 * **[memberIds] の順序は会話の一部で、絶対に変えてはいけない。** 新しい参加者は末尾に
 * 足す。並べ替えると、それまでに符号化した全フレームの意味が黙って変わり、症状は
 * 「別人の発言として表示される」。それは破損ではなく成りすましに見える。
 * 食い違いを事前に捕まえるハッシュは RESEARCH_ADAPTIVE_TRANSPORT.md §4。
 */
class YosegiContext(
    val conversationId: String,
    val memberIds: List<String>,
    val stickerCatalogue: List<String>,
) {
    internal val memberIndex: Map<String, Int> =
        memberIds.withIndex().associate { (index, id) -> id to index }

    internal val stickerIndex: Map<String, Int> =
        stickerCatalogue.withIndex().associate { (index, id) -> id to index }

    init {
        require(memberIds.size < 255) { "member index 0xFF is reserved for a full uid" }
    }
}

/**
 * 復号できなかったフレーム。
 *
 * 型を分けるのは、「相手がおかしなものを送ってきた」と「この復号器にバグがある」を
 * 呼び出し側が区別できるようにするため。対応が違う（前者は捨てて相手側の問題として
 * 数える、後者は起きてはならず、大きな声で報告する）。分けないと、電波の悪い区間が
 * ログの上ではクラッシュ級の不具合とまったく同じに見える。
 *
 * **この文言にメッセージの中身は入らない。** ログに出して安全で、そこが要点。
 * ログに出せない復号の失敗は、直せない復号の失敗になる。
 * docs/PRIVACY_PRINCIPLES.md の防御層3。
 */
class YosegiError(message: String) : Exception(message)
