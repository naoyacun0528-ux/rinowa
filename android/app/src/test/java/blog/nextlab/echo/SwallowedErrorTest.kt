package blog.nextlab.echo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 報告されずに捨てられている失敗があったら、ビルドを落とす。
 *
 * 握り潰した失敗は、他の全部のバグを合わせたより時間を溶かしてきた。形はいつも同じで、
 * 「動かないのに、理由を言わない」。
 *
 * - 通話のリスナーがエラーで黙って return していたので、「ルールが違う」と
 *   「誰もかけてこない」が同じに見えた。1セッション溶けた。
 * - `CryptoEngine.open` が失敗で null を返し、端末には「暗号エンジンを開けませんでした」
 *   しか出なかった。本当の原因（R8 が JNA を削っていた）は例外を出すまで見えなかった。
 * - `collect` を `runCatching` で包んだせいで `CancellationException` まで捕まえ、
 *   停止を押した人に「開始できませんでした」と出した。
 *
 * そのたびに気を付けると決めたが、効かなかった。`runCatching { }.getOrNull()` は
 * 落ちないコードを書く最短経路で、反射で書ける。だから覚えておくのではなく、ここで縛る。
 *
 * 通るもの（コンパイラから見えるかたちで失敗を扱っている）:
 *
 *     runCatching { ... }.onFailure { report(it) }.getOrNull()
 *     runCatching { ... }.getOrElse { report(it); fallback }
 *
 * 落とすもの:
 *
 *     runCatching { ... }.getOrNull()
 *     runCatching { ... }.getOrDefault(x)
 *
 * 本当に情報を持たない失敗（後始末、消えているかもしれないドキュメントの削除）は
 * あってよいが、宣言する:
 *
 *     // swallow-ok: すでに消えているかもしれない。どちらにせよ今は無い
 *     runCatching { file.delete() }
 *
 * 1行で済み、その判断を文章で1回させる。次に読む人はそれに反対できる。
 */
class SwallowedErrorTest {

    /**
     * エラーを渡されて黙って return する Firestore のリスナー。
     *
     * `runCatching { }.getOrNull()` と同じ失敗の別の服。しかも一番高くついた
     * （「ルールに拒否された」と「誰もかけてこない」が同じ挙動になり、1セッション溶けた）。
     *
     * 判定はわざと雑にしてある。ラムダの中で `error` が1回だけ出てくるのは null 検査
     * だけということ。2回出てくるなら何かに使っている。
     */
    @Test
    fun `no snapshot listener ignores its error`() {
        val offenders = mutableListOf<String>()

        kotlinSources()
            .forEach { file ->
                val text = file.readText().withCommentsBlanked()
                var index = text.indexOf(LISTENER)
                while (index >= 0) {
                    val end = endOfBlock(text, index)
                    if (end > 0) {
                        val body = text.substring(index, end)
                        val mentions = Regex("\\berror\\b").findAll(body).count()
                        if (mentions == 1 && !body.contains(MARKER)) {
                            offenders += "${file.name}:${text.lineAt(index)}"
                        }
                    }
                    index = text.indexOf(LISTENER, index + LISTENER.length)
                }
            }

        assertTrue(
            "Snapshot listeners that drop their error:\n" +
                offenders.joinToString("\n") { "  $it" } +
                "\n\nSay what went wrong, or declare why there is nothing to say.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no error is discarded without being reported`() {
        val offenders = mutableListOf<String>()

        kotlinSources().forEach { file -> offenders += file.offences() }

        assertTrue(
            buildString {
                appendLine("Errors are discarded without being reported:")
                appendLine()
                offenders.forEach { appendLine("  $it") }
                appendLine()
                appendLine("Report the failure, or declare why there is nothing to report:")
                appendLine("  // swallow-ok: <reason>")
            },
            offenders.isEmpty(),
        )
    }

    private fun File.offences(): List<String> {
        // コメントは削らず空白に置き換える。行番号がファイルと一致するように。
        //
        // 削ると、禁止された書き方を*説明している*コメントがその実例として報告される
        // （実際、この問題を直すために書いたファイルで起きた）。自分について書くことを
        // 罰する規則は長続きしない。
        val text = readText().withCommentsBlanked()
        val found = mutableListOf<String>()
        var index = text.indexOf(RUN_CATCHING)

        while (index >= 0) {
            // `return@runCatching` はブロックからの脱出であって新しいブロックではない。
            // これを拾うと、呼び出しの無い行を報告してしまう。狼少年になった検査は
            // 無視されるので、検出対象より慎重に扱う価値がある。
            val precededByLabel = index > 0 && text[index - 1] == '@'
            if (!precededByLabel) {
                val blockEnd = endOfBlock(text, index)
                if (blockEnd > 0) {
                    val chain = chainAfter(text, blockEnd)
                    val handled = HANDLERS.any { chain.contains(it) }
                    val discards = DISCARDS.any { chain.contains(it) }

                    // どこにも行かない Result。
                    //
                    // これは長い間見逃していて、メッセージを失った。形はこう:
                    //
                    //     runCatching { import(keys) }.onSuccess { deleteThem() }
                    //
                    // .getOrNull も代入も無いので捨てているように見えないが、import が
                    // 例外を投げたら黙って何も起きない。扱いも使用もされない Result は
                    // この検査が防ぎたいものの純粋形なので、静かでも拾う。
                    val dropped = !handled && !usesTheResult(text, index, chain)

                    if ((discards || dropped) && !handled && !declaredSafe(text, index)) {
                        found += "${name}:${text.lineAt(index)}"
                    }
                }
            }
            index = text.indexOf(RUN_CATCHING, index + RUN_CATCHING.length)
        }
        return found
    }

    /**
     * その Result を誰かが読むか。
     *
     * 代入・return・引数渡し・問い合わせのどれかなら、呼び出し側が失敗をどうするか
     * 決めている。`runCatching` で始まり、扱う者がいないまま終わる文だけが何も決めていない。
     */
    private fun usesTheResult(text: String, index: Int, chain: String): Boolean {
        if (chain.contains(".isSuccess") || chain.contains(".isFailure")) return true
        if (chain.contains(".fold") || chain.contains(".map") || chain.contains(".recover")) {
            return true
        }
        if (DISCARDS.any { chain.contains(it) }) return true

        // 前に何があるか。`val x = runCatching`、`return runCatching`、`emit(runCatching`
        // はどれも Result を誰かに渡している。
        //
        // 1つ上の行も見る。式本体はたいていこう書かれるため:
        //
        //     suspend fun lastReadAt(...): Long =
        //         runCatching { ... }
        //
        // 現在行だけを読むとこれを「捨てている」と判定した。正しいコードを報告する
        // 検査は、無い検査より悪い（切られて、本物も止まる）。
        val lineStart = text.lastIndexOf('\n', maxOf(0, index - 1)) + 1
        val before = text.substring(lineStart, index).trim()
        if (before.isNotEmpty()) return !before.endsWith("{")

        val previous = text.substring(0, maxOf(0, lineStart - 1))
            .substringAfterLast('\n')
            .trim()
        if (CONTINUATIONS.any { previous.endsWith(it) }) return true

        // ブロックの最後の式はそのブロックの値。
        //
        //     ): Result<Unit> = withContext(Dispatchers.IO) {
        //         runCatching { ... }
        //     }
        //
        // ここでは Result が関数の戻り値で、判断するのは呼び出し側。上の行だけを見て
        // 正しい関数を3つ報告した。検査が切られるのはこういうとき。
        return endsTheBlock(text, index)
    }

    /** 連鎖のあとに空白と閉じ括弧しか無ければ true。 */
    private fun endsTheBlock(text: String, index: Int): Boolean {
        val blockEnd = endOfBlock(text, index)
        if (blockEnd <= 0) return false
        var i = blockEnd
        // まず連鎖している呼び出しを飛ばし、そのあとに何が残るかを見る。
        while (i < text.length && (text[i] == '.' || text[i].isLetterOrDigit() || text[i].isWhitespace() || text[i] == '(' || text[i] == ')' || text[i] == '{' || text[i] == '}')) {
            if (text[i] == '{') {
                val inner = endOfBlock(text, i - 1)
                if (inner <= 0) return false
                i = inner
                continue
            }
            if (text[i] == '}') return true
            i++
        }
        return false
    }

    /**
     * 実際に result に連鎖している呼び出しだけを取る。
     *
     * ブロックの後ろを決め打ちの文字数で読むのは間違いだった。式の終わりを越えて
     * *次の文*の `.getOrNull()` を拾い、正しく Result を返している関数を
     * 「捨てている」と報告した。
     *
     * ここでは `.name(...)` / `.name { ... }` の連なりだけを辿り、そうでないものが
     * 出た時点で止める。
     */
    private fun chainAfter(text: String, start: Int): String {
        val chain = StringBuilder()
        var i = start
        while (true) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '.') break
            val nameStart = i
            i++
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            chain.append(text, nameStart, i)
            // 引数か末尾ラムダ。どちらも複数行にまたがりうる。
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && (text[i] == '(' || text[i] == '{')) {
                val closing = if (text[i] == '(') ')' else '}'
                val opening = text[i]
                var depth = 0
                while (i < text.length) {
                    if (text[i] == opening) depth++
                    if (text[i] == closing) {
                        depth--
                        if (depth == 0) { i++; break }
                    }
                    i++
                }
                chain.append(opening).append(closing)
            }
        }
        return chain.toString()
    }

    /** `runCatching` に続くラムダの閉じ括弧の次の位置。 */
    private fun endOfBlock(text: String, start: Int): Int {
        val open = text.indexOf('{', start)
        if (open < 0) return -1
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return -1
    }

    /** 直前3行に、理由付きの明示的な除外があれば true。 */
    private fun declaredSafe(text: String, index: Int): Boolean {
        val from = maxOf(0, text.lastIndexOf('\n', maxOf(0, index - 1)))
        val window = text.substring(maxOf(0, from - LOOKBACK), minOf(text.length, index))
        return window.contains(MARKER)
    }

    private fun String.lineAt(index: Int): Int =
        substring(0, index).count { it == '\n' } + 1

    /**
     * コメントの中身を空白に置き換える。改行はそのまま残す。
     *
     * 除外の目印はコメントの中にあるものなので生き残る必要がある。`swallow-ok:` は
     * 元の位置に書き戻す。
     */
    private fun String.withCommentsBlanked(): String {
        val out = StringBuilder(this)
        var i = 0
        while (i < length) {
            when {
                startsWith("//", i) -> {
                    val end = indexOf('\n', i).let { if (it < 0) length else it }
                    blank(out, i, end)
                    i = end
                }

                startsWith("/*", i) -> {
                    val end = indexOf("*/", i).let { if (it < 0) length else it + 2 }
                    blank(out, i, end)
                    i = end
                }

                else -> i++
            }
        }
        return out.toString()
    }

    private fun String.blank(out: StringBuilder, from: Int, to: Int) {
        val marker = indexOf(MARKER, from).takeIf { it in from until to }
        for (k in from until to) if (out[k] != '\n') out[k] = ' '
        if (marker != null) {
            for ((offset, ch) in MARKER.withIndex()) out[marker + offset] = ch
        }
    }

    /**
     * 検査するソースの根。
     *
     * main と debug の両方を見る。開発用の画面を debug へ移したときに、この検査の外へ
     * 出てしまわないように。Gradle はモジュールのディレクトリを作業ディレクトリにして
     * 単体テストを走らせる。
     */
    private fun sourceRoots(): List<File> =
        listOf("src/main/java", "src/debug/java", "app/src/main/java", "app/src/debug/java")
            .map(::File)
            .filter { it.isDirectory }

    private fun kotlinSources(): Sequence<File> =
        sourceRoots().asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }

    private companion object {
        const val RUN_CATCHING = "runCatching"
        const val MARKER = "swallow-ok:"
        const val LISTENER = "addSnapshotListener"

        const val LOOKBACK = 240

        /** 行末がこれなら、次の行の値をどこかへ渡している。 */
        val CONTINUATIONS = listOf("=", "return", "(", ",", "->", "?:")

        val DISCARDS = listOf(".getOrNull", ".getOrDefault")
        /**
         * 本当に失敗を見るのはこれだけ。
         *
         * `.onSuccess` を一時この一覧に入れていたが、あれは扱う側ではない（何も
         * 問題が無かったときに走る）。そのせいで本物の握り潰しが通った。to-device の
         * 配送が毎回黙って失敗していて、書き込みは
         * `runCatching { ... }.onSuccess { sent++ }` に包まれ、このテストは扱い済みと
         * 判定していた。
         *
         * 扱っていないものを通す検出器は、検出器が無いより悪い。緑のビルドが証拠に
         * なってしまう。
         */
        // .exceptionOrNull() が入っているのは、例外を取り出して使うのは扱っていることだから
    // （MediaImages は送信を押した人への文言にしている）。.onSuccess は意図的に入れない。
    val HANDLERS = listOf(".onFailure", ".getOrElse", ".exceptionOrNull")
    }
}
