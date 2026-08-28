package blog.nextlab.echo.core.wire

/**
 * Yosegi を、実際に動く場所で測る。
 *
 * 形式はデスクトップの V8 で取った数字から選んだ。圧縮率はハードウェアに依らず
 * そのまま移るが、**時間は移らない**。JIT が温まっていない省電力コアは別の機械で、
 * デスクトップのマイクロ秒を端末の数字として引用するのは、データをでっち上げること。
 *
 * なのでこれは端末上で、開発用の画面から走らせて、見えたものを報告するためにある。
 * 推測は1つも無い。[Report] のどの項目も、その報告を出した機械の上で計ったか数えたもの。
 *
 * Android のインポートを持たない素の Kotlin なので、同じコードが JVM の単体テストでも
 * 走る。だから「端末とデスクトップの差」と「このビルドで遅くなった」を区別できる。
 */
object YosegiBenchmark {

    data class Report(
        val messages: Int,
        val rawJsonEquivalentBytes: Int,
        val yosegiBytes: Int,
        val encodeMicros: Double,
        val decodeMicros: Double,
        val sustainedMessagesPerSecond: Double,
        val allocatedKb: Long,
        /** 一番遅い1*フレーム*。メッセージ単位ではない。理由は下。 */
        val worstEncodeFrameMicros: Double,
        val worstDecodeFrameMicros: Double,
    ) {
        /**
         * 最悪のフレームと、平均のフレームの比。
         *
         * 最初の版は、最悪フレームの数字をメッセージ単位の平均と同じ行に出していて、
         * 「1通に780マイクロ秒」と読めた。そんな事実は一度も無い。500通ぶんの
         * フレーム全体がそれだけかかっただけで、平均は590。比で書けば明らかにただの
         * ばらつきで、前の書き方だと病気に見えた。
         */
        val encodeJitter: Double get() = worstEncodeFrameMicros / (encodeMicros * messages)
        val decodeJitter: Double get() = worstDecodeFrameMicros / (decodeMicros * messages)

        override fun toString(): String = buildString {
            appendLine("Yosegi v1 on this device")
            appendLine("  messages          $messages per frame")
            appendLine("  JSON equivalent   $rawJsonEquivalentBytes B  (${rawJsonEquivalentBytes / messages} B/msg)")
            appendLine("  Yosegi               $yosegiBytes B  (${yosegiBytes / messages} B/msg)")
            appendLine("  reduction         ${100 - (yosegiBytes * 100 / rawJsonEquivalentBytes)}%")
            appendLine("  encode            ${"%.2f".format(encodeMicros)} us/msg")
            appendLine("     per frame      ${"%.0f".format(encodeMicros * messages)} us mean, ${"%.0f".format(worstEncodeFrameMicros)} us worst  (x${"%.1f".format(encodeJitter)})")
            appendLine("  decode            ${"%.2f".format(decodeMicros)} us/msg")
            appendLine("     per frame      ${"%.0f".format(decodeMicros * messages)} us mean, ${"%.0f".format(worstDecodeFrameMicros)} us worst  (x${"%.1f".format(decodeJitter)})")
            appendLine("  sustained         ${"%.0f".format(sustainedMessagesPerSecond)} msg/s")
            append("  heap growth       $allocatedKb KB over the run")
        }
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /**
     * 実態に合った形の文章。
     *
     * 1/3は数文字の相槌。実際の通信の1/3がそれで、枠の付随分がすべてを決める大きさ。
     * 残りは普通の文、URL、数字、絵文字。長い文章で測ると、Rinowa がほとんど扱わない
     * 場合について都合のよい数字が出る。
     */
    private val SAMPLE_TEXT = listOf(
        "うん", "はい", "おけ", "OK", "りょ", "わかった", "ありがとう", "😂", "🙏",
        "今日の夕飯なににする", "七時には帰れると思う", "駅前寄って牛乳買ってきて",
        "https://youtu.be/aB3xQ9zL0pRt7YmK", "1200円だった", "9月14日 19:00",
        "sounds good", "be there in 10",
        "明日の予定なんだけど、午前中は病院で午後から買い物に行こうと思ってるから、" +
            "もし時間あったら一緒に行かない？",
    )

    fun buildSample(count: Int, seed: Int = 1): Pair<List<WireMessage>, YosegiContext> {
        var state = seed.toLong() or 1L
        fun next(bound: Int): Int {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (((state ushr 33).toInt() and 0x7FFFFFFF) % bound)
        }
        fun id(length: Int) = buildString { repeat(length) { append(ALPHABET[next(ALPHABET.length)]) } }

        val members = List(3) { id(28) }
        val context = YosegiContext(
            conversationId = id(20),
            memberIds = members,
            stickerCatalogue = listOf("echo.core.smile", "echo.core.cry", "echo.core.heart"),
        )

        var clock = 1_755_302_400_000L
        val messages = List(count) { i ->
            clock += 3_000L + next(400_000)
            val sticker = next(100) < 8
            WireMessage(
                id = id(20),
                senderId = members[next(members.size)],
                timestampMs = clock,
                status = WireStatus.entries[next(WireStatus.entries.size)],
                text = if (sticker) null else SAMPLE_TEXT[next(SAMPLE_TEXT.size)],
                stickerId = if (sticker) context.stickerCatalogue[next(3)] else null,
                replyTo = if (next(100) < 11) WireReply(id(20), "みなと", "引用の一部") else null,
                reactions = if (next(100) < 15) mapOf(members[next(members.size)] to next(6)) else emptyMap(),
            )
        }
        return messages to context
    }

    /**
     * @param warmup 計測前に捨てる回数。Android では最初の1000回はインタプリタと
     *   JIT が何を compile するか決めている時間で、それを形式の費用として報告するのは
     *   実行環境が温まる様子を測っているだけ。
     */
    fun run(count: Int = 500, warmup: Int = 200, iterations: Int = 400): Report {
        val (messages, context) = buildSample(count)

        // いま Rinowa が送っているもの。この端末の上で比較に意味を持たせるため。
        val jsonEquivalent = messages.joinToString("") { m ->
            """{"id":"${m.id}","conversationId":"${context.conversationId}","senderId":"${m.senderId}",""" +
                """"senderName":"みなと","timestampMs":${m.timestampMs},"status":"${m.status}",""" +
                (m.text?.let { """"text":"$it"""" } ?: """"stickerId":"${m.stickerId}"""") + "},"
        }.toByteArray(Charsets.UTF_8).size

        repeat(warmup) {
            Yosegi.decode(Yosegi.encode(messages, context), context)
        }

        val frame = Yosegi.encode(messages, context)

        var worstEncode = 0L
        val encodeStart = System.nanoTime()
        repeat(iterations) {
            val t0 = System.nanoTime()
            Yosegi.encode(messages, context)
            val dt = System.nanoTime() - t0
            if (dt > worstEncode) worstEncode = dt
        }
        val encodeTotal = System.nanoTime() - encodeStart

        var worstDecode = 0L
        val decodeStart = System.nanoTime()
        repeat(iterations) {
            val t0 = System.nanoTime()
            Yosegi.decode(frame, context)
            val dt = System.nanoTime() - t0
            if (dt > worstDecode) worstDecode = dt
        }
        val decodeTotal = System.nanoTime() - decodeStart

        // 連続で。符号化と復号を交互に、通信がまとまって来るときと同じ形で回す。
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val sustainedStart = System.nanoTime()
        var handled = 0
        repeat(iterations) {
            handled += Yosegi.decode(Yosegi.encode(messages, context), context).size
        }
        val sustainedSeconds = (System.nanoTime() - sustainedStart) / 1e9
        val after = runtime.totalMemory() - runtime.freeMemory()

        return Report(
            messages = count,
            rawJsonEquivalentBytes = jsonEquivalent,
            yosegiBytes = frame.size,
            encodeMicros = encodeTotal / 1000.0 / (iterations * count),
            decodeMicros = decodeTotal / 1000.0 / (iterations * count),
            sustainedMessagesPerSecond = handled / sustainedSeconds,
            // 概算で、そう書いてある。計測中に GC が入ると少なめに出る。それでも
            // 報告するのは傾向が大事だから。項目ごとに確保するコーデックなら
            // ここに数十MBとして現れる。
            allocatedKb = ((after - before).coerceAtLeast(0)) / 1024,
            worstEncodeFrameMicros = worstEncode / 1000.0,
            worstDecodeFrameMicros = worstDecode / 1000.0,
        )
    }
}
