package blog.nextlab.echo.data

import blog.nextlab.echo.core.wire.Yosegi
import blog.nextlab.echo.core.wire.YosegiContext
import blog.nextlab.echo.core.wire.WireMessage
import blog.nextlab.echo.core.wire.WireReply
import blog.nextlab.echo.core.wire.WireStatus
import blog.nextlab.echo.model.ConversationId
import blog.nextlab.echo.model.Message
import blog.nextlab.echo.model.MessageContent
import blog.nextlab.echo.model.MessageId
import blog.nextlab.echo.model.MessageStatus
import blog.nextlab.echo.model.MessageText
import blog.nextlab.echo.model.Reaction
import blog.nextlab.echo.model.ReplyPreview
import blog.nextlab.echo.model.UserId
import kotlinx.collections.immutable.toImmutableList
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Rinowa のモデルと Yosegi v1 のつなぎ目。
 *
 * まだどこでも有効になっていない。使う場所は [YosegiRollout] が決め、いまのところ
 * メッセージ経路では使っていない。これは書き忘れではなく結論。
 *
 * 調査は「Rinowa が送るもの全部に Yosegi を」と勧めていた。配線しながら
 * firestore.rules を読んで結論が変わった。実測だけを見た結論とは逆なので、はっきり書く。
 *
 * **Firestore のメッセージドキュメントを Yosegi に置き換えてはいけない。**
 *
 * Rinowa のプライバシーを「約束」ではなく「構造」にしているルールは*項目*に対して働く。
 * 取り消しは `retractedAt` に触れて `text` を消す場合だけ許され、リアクションは
 * 書き手自身のキーを変える場合だけ許され、本文は4000文字まで、`senderId` は呼び出し元と
 * 一致していなければならない。どれもサーバーが名前付きの項目を見られることが前提。
 * 同じメッセージを1つの不透明な塊にすると、ルールは中身について何も言えなくなる。
 * `rules-tests/run.js` を通った保証（「admin を名乗るトークンでもメッセージは読めない」を
 * 含む）が、クライアントの行儀への期待に置き換わる。
 *
 * **1通200バイトのために、構造的な保証を約束と交換する価値は無い。**
 *
 * 残るのは、転送が本当にバイト列で、ルールエンジンが見ていない場所:
 *
 *  - **Rinowa Direct** — 端末間のソケット。Yosegi はこのために設計した。Direct の
 *    メッセージ経路はまだ無い（Direct-2）ので、準備しても壊すものが無い。
 *  - **メッシュ** — さらに顕著。BLE の 5〜20kB/s では、267 バイト対 66 バイトは
 *    最適化ではなく成立するかどうかの分かれ目。
 *  - **一括書き出しと端末間の移行** — 大きな1フレームで、項目ごとのルールは関係ない。
 *
 * E2EE に移ればドキュメント自体が不透明になり、ルールも全面的に書き直しになる。
 * クラウド側を考え直すのはそのときで、いまではない。
 */
object MessageWire {

    /**
     * 同梱の辞書を使う Deflate。
     *
     * `java.util.zip` なので NDK も AAR も追加が要らない。zstd に数%勝てなかったのに
     * 依存を1つ増やす必要があったのに対し、これが選ばれた理由の大半がこの「最初からある」。
     * 実測で1通66.4バイト（無圧縮98.8バイト）、しかも**どの長さでも膨らまない**。
     * zstd と brotli は短いデータでそれができなかった。docs/YOSEGI_V1_SPEC.md §7。
     */
    fun compress(frame: ByteArray, dictionary: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setDictionary(dictionary)
            deflater.setInput(frame)
            deflater.finish()
            val out = ByteArray(frame.size + 64)
            var written = 0
            while (!deflater.finished()) {
                if (written == out.size) return frame // 病的なケース。無圧縮で送る
                written += deflater.deflate(out, written, out.size - written)
            }
            return out.copyOf(written)
        } finally {
            deflater.end()
        }
    }

    /**
     * @param maxOutput 結果の上限（バイト）。
     *
     * 任意ではなく必須。DEFLATE は約1032:1まで行くので、1MBの入力で1GBの出力を
     * 要求できる（相手は1MB、こちらは1GB）。上限は展開*しながら*確認する。
     * あとで大きさを見るのは、プロセスが死んだあとに見ること。
     * 制限値は docs/YOSEGI_V1_SPEC.md §7。
     */
    fun decompress(packed: ByteArray, dictionary: ByteArray, maxOutput: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(packed)
            val chunk = ByteArray(8192)
            var out = ByteArray(minOf(maxOutput, packed.size * 4 + 64))
            var written = 0
            var dictionaryNeeded = false
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(chunk)
                } catch (e: java.util.zip.DataFormatException) {
                    throw IllegalArgumentException("corrupt compressed frame", e)
                }
                if (n == 0) {
                    if (inflater.needsDictionary() && !dictionaryNeeded) {
                        inflater.setDictionary(dictionary)
                        dictionaryNeeded = true
                        continue
                    }
                    if (inflater.needsInput() || inflater.finished()) break
                    throw IllegalArgumentException("compressed frame made no progress")
                }
                if (written + n > maxOutput) {
                    throw IllegalArgumentException("expansion exceeds $maxOutput bytes")
                }
                if (written + n > out.size) out = out.copyOf(minOf(maxOutput, maxOf(out.size * 2, written + n)))
                chunk.copyInto(out, written, 0, n)
                written += n
            }
            return out.copyOf(written)
        } finally {
            inflater.end()
        }
    }

    // -----------------------------------------------------------------------------------
    // モデル <-> 転送形式
    // -----------------------------------------------------------------------------------

    /**
     * わざと [Message] のメソッドにしていない。
     *
     * アプリのモデルは画面のためのもの（`isOutgoing`、Compose 向けの不変リスト、
     * sealed な content）。転送形式がそこへ手を伸ばすと、今後の画面の都合が
     * すべて互換性の問題になる。
     */
    fun toWire(message: Message, memberIds: List<UserId>): WireMessage {
        val content = message.content
        return WireMessage(
            id = message.id.value,
            senderId = message.senderId.value,
            timestampMs = message.timestampMs,
            status = WireStatus.entries[message.status.ordinal],
            text = (content as? MessageContent.Text)?.body?.value,
            stickerId = (content as? MessageContent.Sticker)?.stickerId?.value,
            replyTo = message.replyTo?.let {
                WireReply(it.messageId.value, it.senderName, it.excerpt.value)
            },
            // 参加者一覧から引けないときだけ入れる。毎回運ぶと、両端がすでに持っている
            // もののために1通25バイト使うことになる。
            senderName = message.senderName.takeIf { it.isNotEmpty() && UserId(message.senderId.value) !in memberIds },
            reactions = message.reactions
                .filter { it.mine }
                .associate { message.senderId.value to it.paletteIndex },
            retracted = content is MessageContent.Retracted,
        )
    }

    fun fromWire(wire: WireMessage, me: UserId, displayName: (UserId) -> String): Message {
        val content = when {
            wire.retracted -> MessageContent.Retracted
            wire.stickerId != null -> MessageContent.Sticker(blog.nextlab.echo.model.StickerId(wire.stickerId!!))
            else -> MessageContent.Text(MessageText(wire.text.orEmpty()))
        }
        val sender = UserId(wire.senderId)
        return Message(
            id = MessageId(wire.id),
            senderId = sender,
            content = content,
            timestampMs = wire.timestampMs,
            isOutgoing = sender == me,
            senderName = wire.senderName ?: displayName(sender),
            status = MessageStatus.entries[wire.status.ordinal],
            replyTo = wire.replyTo?.let {
                ReplyPreview(MessageId(it.messageId), it.senderName, MessageText(it.excerpt))
            },
            reactions = wire.reactions
                .map { (uid, palette) -> Reaction(palette, 1, mine = UserId(uid) == me) }
                .toImmutableList(),
        )
    }

    fun contextFor(
        conversationId: ConversationId,
        memberIds: List<UserId>,
        stickerCatalogue: List<String>,
    ): YosegiContext = YosegiContext(
        conversationId = conversationId.value,
        memberIds = memberIds.map { it.value },
        stickerCatalogue = stickerCatalogue,
    )

    fun encode(messages: List<WireMessage>, context: YosegiContext): ByteArray =
        Yosegi.encode(messages, context)

    fun decode(frame: ByteArray, context: YosegiContext): List<WireMessage> =
        Yosegi.decode(frame, context)
}

/**
 * Yosegi を使ってよい場所。
 *
 * 全部 off。有効にするのは、差分の残る意図的な操作であるべき。黙って変わる形式は、
 * メッセージが消えたときに誰も戻せない。
 */
object YosegiRollout {

    /**
     * クラウドは JSON 形のドキュメントのまま。フラグではなく決定（[MessageWire] を参照）。
     *
     * 定数として置いておくのは、この問いに見える形で答えを残すため。誰も考えなかった
     * ように見えないように。
     */
    const val CLOUD_USES_Yosegi = false

    /** Direct のメッセージ経路。できたら（Direct-2）。 */
    var directUsesYosegi: Boolean = false

    /** Yosegi を使っている場所の上での圧縮。先に辞書を同梱する必要がある。 */
    var compressionEnabled: Boolean = false
}
