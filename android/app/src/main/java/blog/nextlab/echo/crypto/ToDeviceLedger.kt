package blog.nextlab.echo.crypto

/**
 * 受信箱のイベントをいつ消してよいかを決める。
 *
 * 防いでいる失敗: 部屋の鍵は to-device のイベントとして届き、暗号ストアに取り込まれ、
 * そのイベントは消される（エンジンがもう持っているものを残す理由が無いため）。
 * その順番は、**2つの手順の間でプロセスが消えなければ**正しい。実際に起きた。
 * ネイティブのクラッシュで途中で落ち、イベントは受信箱から消え、相手の端末の
 * メッセージ2件が永久に読めなくなった。あとから復旧する方法は無い
 * （送信者にはもうそのセッションが無く、受信者は最初から持っていない）。
 *
 * 規則: **初めて見たイベントは絶対に消さない。**
 *
 * 消すのは、前の回にも記録されていたと分かった回だけ。これで、届いてから消えるまでの
 * 間に、取り込み・保存・プロセスの生存という往復が最低1回あったことが保証される。
 * すでに持っている鍵をもう一度取り込む費用は0（Olm も Megolm も持っている鍵は無視する）。
 *
 * 台帳は永続化する。保証が再起動をまたぐ必要があり、まさにそこが問題になる場面だから。
 *
 * 時刻で判定しない理由: 「10分より古いものを消す」は同じに見えて違う。1時間
 * オフラインだった端末は、すでに古いイベントを取り込み、その同じ回で消してしまう。
 * 中断されやすい端末に限って、元の穴が開き直す。ここで正直な単位は秒ではなく回数。
 */
class ToDeviceLedger(private val store: Store) {

    /** 回をまたいで id を置く場所。 */
    interface Store {
        fun read(): Set<String>
        fun write(ids: Set<String>)
    }

    /** 1回ぶんの取り出しについて [sift] が出した結論。 */
    class Sifted(
        /** 前の回にも見た。消してよい。 */
        val deletable: Set<String>,
        /** 初めて見た。残して、次の回に考え直す。 */
        val held: Set<String>,
    )

    fun sift(present: List<String>): Sifted {
        val seen = store.read()
        val deletable = present.filter(seen::contains).toSet()
        val held = present.filterNot(deletable::contains).toSet()

        // 次に持ち越すのは残したぶんだけ。消すぶんはもう出ていくところで、受信箱に
        // 無い id には守るものが残っていない。持ち続けるとこの集合がアカウントの寿命の
        // あいだ増え続ける。
        //
        // 削除が失敗しても、その id は次の回にまた新しく見え、もう1回ぶん待つだけ。
        // ここでは遅いのは構わない。早いほうがメッセージを失う。
        store.write(held.take(LIMIT).toSet())

        return Sifted(deletable = deletable, held = held)
    }

    private companion object {
        /**
         * 期待値ではなく上限。
         *
         * 受信箱に入るのは、鍵を配るごとに端末1台につき1件。数百件あればもう何かが
         * おかしい。設定ファイルが無制限に育つのを止めるための線。
         */
        const val LIMIT = 1_000
    }
}
