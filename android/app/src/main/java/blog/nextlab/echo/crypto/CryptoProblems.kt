package blog.nextlab.echo.crypto

/**
 * 暗号まわりの失敗の行き先。どこかへ行くようにするためのもの。
 *
 * 暗号層の呼び出しはどれも失敗しうるし、どれも例外を投げても意味が無い
 * （鍵を取りに行っている最中の通信のつまずきで、会話の途中にアプリが落ちては困る）。
 * 反射で書くと `runCatching { }.getOrNull()` になり、結果として暗号化が静かに
 * 止まって何も言わないアプリができる。「暗号エンジンを開けませんでした」がそれ以上
 * 何も出なかったのも、壊れた通話リスナーが「誰もかけてこない」と同じに見えたのも、それ。
 *
 * なので捕まえたうえで、**ここに記録する**。捕まえるのはよい。捕まえて忘れるのが問題。
 *
 * 1行のログではなく環状バッファなのは、手の中の端末では logcat が使えないから。
 * Direct Lab の検査画面から読めるので、ぶつかった本人が報告できる（机の上で再現
 * しなくてよい）。件数に上限があるのは、これが日誌ではなく診断だから。
 *
 * ここに入れてはいけないもの: **本文、平文、鍵**。この層が守っているのは、それらが
 * 暗号化されずに端末を出ないこと。診断がそれを漏らせば台無しになる。書くのは
 * どこで起きたかと、例外が何と言ったかだけ。
 */
object CryptoProblems {

    private const val CAPACITY = 20

    private val entries = ArrayDeque<String>()

    /** 新しい順。 */
    val recent: List<String> get() = synchronized(entries) { entries.toList() }

    fun record(where: String, error: Throwable) {
        val line = "$where: ${error::class.java.simpleName}: ${error.message ?: "(no message)"}"
        synchronized(entries) {
            entries.addFirst(line)
            while (entries.size > CAPACITY) entries.removeLast()
        }
        // logcat にも出す。ケーブルを繋いだ端末が机の上にある場合のために。
        android.util.Log.w("Rinowa/crypto", line)
    }

    fun clear() = synchronized(entries) { entries.clear() }
}
