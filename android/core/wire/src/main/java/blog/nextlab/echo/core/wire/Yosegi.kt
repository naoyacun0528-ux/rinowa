package blog.nextlab.echo.core.wire

/**
 * Yosegi v1 — Rinowa の転送形式。
 *
 * 正本は docs/YOSEGI_V1_SPEC.md（2026-08-17 凍結）。ここはその実装で、食い違ったら
 * 仕様が正しくこちらが間違い。
 *
 * JSON はメッセージ以外のものにほとんどのバイトを使う。実測では1通267.7バイトのうち
 * 人が打った部分は26.7バイトで、`senderId` と `conversationId` だけで1/3を占める。
 * しかもどちらも受け取る側がすでに知っている。Yosegi は相手が持っていないものだけを
 * 言い、98.8バイトになる。**圧縮アルゴリズムを一切使わずに63%減**、1通5.2マイクロ秒。
 *
 * 暗号でもチェックサムでも認証でもない。このバイト列を持つ者はこのファイルで読める。
 * 完全性と身元は下の層（Cloud なら TLS、Direct なら Nearby の暗号化路、E2EE が入れば
 * AEAD タグ）から来る。Yosegi を唯一の関門にしてはいけない。仕様 §1。
 *
 * 復号側の契約: **どんな**バイト列に対しても、[decode] は正しいメッセージ一覧を返すか
 * [YosegiError] を投げるかのどちらかしかしない。特に、送られていないバイトから
 * メッセージを組み立てない（途中で切れたフレームは黙って補完せず拒否する）。
 * `YosegiFuzzTest` の92,362件で確認。
 */
object Yosegi {

    const val VERSION: Byte = 0x01

    /** Firestore の自動 id と Firebase の uid はこの文字集合・この順で作られる。 */
    private const val ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** 62^20 < 2^120 なので、20文字のドキュメント id は必ず15バイトに収まる。 */
    const val ID20_BYTES = 15

    /** 62^28 < 2^167 なので、28文字の uid は21バイトに収まる。 */
    const val ID28_BYTES = 21

    /**
     * 8バイト＝56ビットで、ミリ秒のエポックには十分余る。
     *
     * 大事なのは大きさより上限があること。無いと、継続バイトの連なりは長さを
     * 攻撃者が決めるループになる。
     */
    const val MAX_VARINT_BYTES = 8

    /** id、送信者の添字、時刻の差分、状態、END。これより小さくはならない。 */
    const val MIN_MESSAGE_BYTES = ID20_BYTES + 4

    /** 1フレームの上限。それらしいヘッダで無制限の作業を要求できないように。 */
    const val MAX_MESSAGES_PER_FRAME = 4096

    /** `ReactionPalette.emoji` と対応する。線の上を通るのは添字だけ。 */
    const val REACTION_PALETTE_SIZE = 6

    private val ID_VALUE = IntArray(128) { -1 }.also { table ->
        ID_ALPHABET.forEachIndexed { index, ch -> table[ch.code] = index }
    }

    // タグは (フィールド番号 shl 3) or ワイヤ型。ワイヤ型があるおかげで、知らない
    // フィールドを読み飛ばせる。v1.1 が項目を足しても v1.0 が残りを失わないのはこの性質。
    private const val WT_FLAG = 0
    private const val WT_VARINT = 1
    private const val WT_BYTES = 2
    private const val WT_ID20 = 3
    private const val WT_COMPOSITE = 5

    private const val T_END = 0
    private const val T_TEXT = (1 shl 3) or WT_BYTES
    private const val T_STICKER_IDX = (2 shl 3) or WT_VARINT
    private const val T_STICKER_STR = (2 shl 3) or WT_BYTES
    private const val T_REPLY = (3 shl 3) or WT_COMPOSITE
    private const val T_REACTIONS = (4 shl 3) or WT_COMPOSITE
    private const val T_SENDER_NAME = (5 shl 3) or WT_BYTES
    private const val T_RETRACTED = (6 shl 3) or WT_FLAG

    // -----------------------------------------------------------------------------------
    // 符号化
    // -----------------------------------------------------------------------------------

    fun encode(messages: List<WireMessage>, context: YosegiContext): ByteArray {
        require(messages.size <= MAX_MESSAGES_PER_FRAME) {
            "a frame holds at most $MAX_MESSAGES_PER_FRAME messages, got ${messages.size}"
        }
        val w = YosegiWriter()
        w.u8(VERSION.toInt())
        w.varint(messages.size.toLong())

        val base = messages.firstOrNull()?.timestampMs ?: 0L
        w.varint(base)

        var previous = base
        for (m in messages) {
            w.bytes(packId(m.id, ID20_BYTES))

            val memberIndex = context.memberIndex[m.senderId]
            if (memberIndex == null) {
                // 文脈に載っていない人（握手のあとに入った参加者）。完全な id に
                // 落として届くようにする。たまに太る形式より、落とせる形式のほうが悪い。
                w.u8(0xFF)
                w.bytes(packId(m.senderId, ID28_BYTES))
            } else {
                w.u8(memberIndex)
            }

            val delta = m.timestampMs - previous
            w.varint(if (delta >= 0) delta * 2 else -delta * 2 - 1)
            previous = m.timestampMs

            w.u8(m.status.ordinal)

            if (m.retracted) w.u8(T_RETRACTED)
            m.text?.let { w.u8(T_TEXT); w.string(it) }
            m.stickerId?.let { id ->
                val index = context.stickerIndex[id]
                if (index == null) {
                    w.u8(T_STICKER_STR); w.string(id)
                } else {
                    w.u8(T_STICKER_IDX); w.varint(index.toLong())
                }
            }
            m.replyTo?.let { reply ->
                val sub = YosegiWriter()
                sub.bytes(packId(reply.messageId, ID20_BYTES))
                sub.string(reply.senderName)
                sub.string(reply.excerpt)
                w.u8(T_REPLY)
                w.block(sub.toByteArray())
            }
            if (m.reactions.isNotEmpty()) {
                val sub = YosegiWriter()
                sub.varint(m.reactions.size.toLong())
                for ((uid, palette) in m.reactions) {
                    val index = context.memberIndex[uid]
                    if (index == null) {
                        sub.u8(0xFF); sub.bytes(packId(uid, ID28_BYTES))
                    } else {
                        sub.u8(index)
                    }
                    sub.u8(palette)
                }
                w.u8(T_REACTIONS)
                w.block(sub.toByteArray())
            }
            m.senderName?.let { w.u8(T_SENDER_NAME); w.string(it) }

            w.u8(T_END)
        }
        return w.toByteArray()
    }

    // -----------------------------------------------------------------------------------
    // 復号
    // -----------------------------------------------------------------------------------

    fun decode(frame: ByteArray, context: YosegiContext): List<WireMessage> {
        if (frame.isEmpty()) throw YosegiError("empty frame")
        val r = YosegiReader(frame)

        val version = r.u8()
        if (version != VERSION.toInt()) throw YosegiError("unsupported Yosegi version $version")

        val count = r.varint()
        if (count > MAX_MESSAGES_PER_FRAME) {
            throw YosegiError("frame declares $count messages, limit is $MAX_MESSAGES_PER_FRAME")
        }
        val base = r.varint()

        // 何かを確保する前に、実際にあるバイト数と照合する。長さの項目は他人の申告で、
        // 信じた復号器はメモリ爆弾になる。
        if (count * MIN_MESSAGE_BYTES > r.remaining) {
            throw YosegiError("frame declares $count messages but only ${r.remaining} bytes remain")
        }

        val out = ArrayList<WireMessage>(count.toInt())
        var previous = base
        repeat(count.toInt()) {
            val id = unpackId(r.bytes(ID20_BYTES), ID20_BYTES, 20)

            val memberIdx = r.u8()
            val senderId = if (memberIdx == 0xFF) {
                unpackId(r.bytes(ID28_BYTES), ID28_BYTES, 28)
            } else {
                // 見つからない添字を素通りさせない。送信者を解決できないメッセージは
                // 「誰のものでもない」ものとして出るか、参加者一覧が変わったあとに
                // その添字にいる別人のものとして出てしまう。
                if (memberIdx >= context.memberIds.size) {
                    throw YosegiError(
                        "sender index $memberIdx outside a ${context.memberIds.size}-member conversation",
                    )
                }
                context.memberIds[memberIdx]
            }

            val zig = r.varint()
            val delta = if (zig % 2 == 0L) zig / 2 else -(zig + 1) / 2
            val timestampMs = previous + delta
            previous = timestampMs

            val statusOrdinal = r.u8()
            if (statusOrdinal >= WireStatus.entries.size) throw YosegiError("unknown status $statusOrdinal")
            val status = WireStatus.entries[statusOrdinal]

            var text: String? = null
            var stickerId: String? = null
            var replyTo: WireReply? = null
            var senderName: String? = null
            var reactions: MutableMap<String, Int>? = null
            var retracted = false

            while (true) {
                val tag = r.u8()
                if (tag == T_END) break
                when (tag) {
                    T_RETRACTED -> retracted = true
                    T_TEXT -> text = r.string()
                    T_STICKER_IDX -> {
                        val index = r.varint()
                        if (index >= context.stickerCatalogue.size) {
                            throw YosegiError("sticker index $index not in catalogue")
                        }
                        stickerId = context.stickerCatalogue[index.toInt()]
                    }
                    T_STICKER_STR -> stickerId = r.string()
                    T_REPLY -> {
                        // ブロック長で縛る。中の嘘が次のメッセージまで届かないように。
                        val sub = YosegiReader(r.bytes(r.length()))
                        replyTo = WireReply(
                            messageId = unpackId(sub.bytes(ID20_BYTES), ID20_BYTES, 20),
                            senderName = sub.string(),
                            excerpt = sub.string(),
                        )
                    }
                    T_REACTIONS -> {
                        val sub = YosegiReader(r.bytes(r.length()))
                        val n = sub.varint()
                        if (n * 2 > sub.remaining) throw YosegiError("reaction count exceeds its block")
                        val map = LinkedHashMap<String, Int>(n.toInt().coerceAtMost(16))
                        repeat(n.toInt()) {
                            val idx = sub.u8()
                            val uid = if (idx == 0xFF) {
                                unpackId(sub.bytes(ID28_BYTES), ID28_BYTES, 28)
                            } else {
                                if (idx >= context.memberIds.size) {
                                    throw YosegiError("reaction member index out of range")
                                }
                                context.memberIds[idx]
                            }
                            val palette = sub.u8()
                            if (palette >= REACTION_PALETTE_SIZE) {
                                throw YosegiError("reaction palette index out of range")
                            }
                            map[uid] = palette
                        }
                        reactions = map
                    }
                    T_SENDER_NAME -> senderName = r.string()
                    else -> skipUnknown(r, tag)
                }
            }

            out.add(
                WireMessage(
                    id = id,
                    senderId = senderId,
                    timestampMs = timestampMs,
                    status = status,
                    text = text,
                    stickerId = stickerId,
                    replyTo = replyTo,
                    senderName = senderName,
                    reactions = reactions ?: emptyMap(),
                    retracted = retracted,
                ),
            )
        }
        return out
    }

    /**
     * この版が知らないフィールドを読み飛ばす。
     *
     * これが無いと、あとの版で1項目足したときに、古い側は新項目が見えないだけでなく
     * それ以降が全部読めなくなる（どこで終わるか分からないため）。メッセンジャーでは
     * 「黙って届かないメッセージ」として現れる。この製品で最悪の失敗。
     */
    private fun skipUnknown(r: YosegiReader, tag: Int) {
        when (tag and 7) {
            WT_FLAG -> Unit
            WT_VARINT -> r.varint()
            WT_BYTES, WT_COMPOSITE -> r.bytes(r.length())
            WT_ID20 -> r.bytes(ID20_BYTES)
            else ->
                // 知らないワイヤ型は長さを持たないので、フレームの残りが読めない。
                // 一般の失敗ではなく YosegiError にする。転送中の1ビット反転でもここに
                // 来るので、内部の不具合ではなくただの不正入力。
                throw YosegiError("unknown wire type ${tag and 7}")
        }
    }

    // -----------------------------------------------------------------------------------
    // 識別子
    // -----------------------------------------------------------------------------------

    /**
     * Base62 の文字列を詰めたバイト列に。
     *
     * Firestore の自動 id は119ビットの乱数を20文字で書いたもの。文字列は構造上
     * ランダムなのでどんな圧縮器も縮められない。取り戻す唯一の方法は、数を文字列として
     * 持つのをやめること。
     *
     * BigInteger ではなくバイト配列上の筆算にしている。端末で1通につき2回走り、
     * 15バイト程度では BigInteger は毎回確保するだけで見返りが無い。
     */
    fun packId(value: String, width: Int): ByteArray {
        val out = ByteArray(width)
        for (ch in value) {
            val digit = if (ch.code < 128) ID_VALUE[ch.code] else -1
            if (digit < 0) throw YosegiError("id character '$ch' outside the Firestore alphabet")
            var carry = digit
            for (j in width - 1 downTo 0) {
                val t = (out[j].toInt() and 0xFF) * 62 + carry
                out[j] = (t and 0xFF).toByte()
                carry = t ushr 8
            }
            if (carry != 0) throw YosegiError("id too large for $width bytes")
        }
        return out
    }

    fun unpackId(bytes: ByteArray, width: Int, length: Int): String {
        if (bytes.size != width) throw YosegiError("id needs $width bytes, got ${bytes.size}")
        val work = bytes.copyOf()
        val chars = CharArray(length)
        for (i in length - 1 downTo 0) {
            var remainder = 0
            for (j in 0 until width) {
                val current = remainder * 256 + (work[j].toInt() and 0xFF)
                work[j] = (current / 62).toByte()
                remainder = current % 62
            }
            chars[i] = ID_ALPHABET[remainder]
        }
        return String(chars)
    }
}
