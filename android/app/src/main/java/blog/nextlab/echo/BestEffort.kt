package blog.nextlab.echo

/**
 * 失敗してよいが、*黙って*失敗してはいけないもの。
 *
 * 後始末はたいていこう書かれる:
 *
 * ```
 * runCatching { capturer.dispose() }
 * ```
 *
 * 狙いは正しい。1つの解放が例外を投げても、そのあとの解放を止めてはいけない
 * （止まると通話がカメラを掴んだままになる）。間違っているのは黙っていること。
 * 実際に投げたとき、カメラが動いたままだという記録がどこにも残らず、症状は
 * あとから「理由もなく端末が熱い」という形で来る。
 *
 * 同じ形はもっと悪いものも隠す。`runCatching { context.startForegroundService(…) }` の
 * 失敗は**端末が鳴らない**ということで、それが振動子の解放と同じ書き方をされていた。
 *
 * なので、挙動はそのままに、操作に名前を付けて、失敗を書き留める。ログを読む価値に
 * するのは名前のほう。「解放に失敗」は、何も無いのとほとんど変わらない。
 *
 * 使ってはいけない場面: 呼び出し側にできることがあるとき。これは寿命の終わりで、
 * 本当に他に打つ手が無い場所のためのもの。人が動けるものは、その人まで届く Result に入れる。
 */
inline fun bestEffort(what: String, block: () -> Unit) {
    runCatching { block() }
        .onFailure { android.util.Log.w(TAG, what + " failed", it) }
}

@PublishedApi
internal const val TAG: String = "Rinowa/besteffort"
