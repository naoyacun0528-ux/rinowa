package blog.nextlab.echo.data

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * 取得した写真をどれだけ持つか。
 *
 * これまで上限が無く、開いた写真が `filesDir` に貯まり続けていた。二重に間違って
 * いて、**貯まり続けること**と、**置き場所が消せない側だったこと**の両方。
 *
 * ## なぜ cacheDir なのか
 *
 * 写真の本体は取り直せる。消えて失うのは通信だけで、会話は失われない。
 * だから端末の空きが逼迫したとき、**OS が黙って回収してよい種類のもの**。
 * 設定アプリの「キャッシュを削除」で消せるのも正しい。
 *
 * メッセージの文字は逆で、あれは `filesDir` に置く。消えると一覧が遅くなり、
 * 圏外で何も読めなくなり、しかもなぜそうなったか誰にも分からない。
 * 4 MB を守るために OS の気分に預けるものではない。
 *
 * ## 予算
 *
 * 空きに応じて変える。写真1枚が最大 600 KB なので、60 MB でおよそ100枚。
 * 空きが 1 GB を切っている端末では**何も貯めない**。そこで 30 MB を握って
 * いても誰も得をしない。
 */
object MediaBudget {

    /** 貯めない。空きが本当に無い端末。 */
    private const val NONE = 0L

    private const val MB = 1024L * 1024L

    fun bytesFor(context: Context): Long {
        val free = freeBytes(context)
        return when {
            free < 1L * 1024 * MB -> NONE
            free < 8L * 1024 * MB -> 30 * MB
            free < 32L * 1024 * MB -> 60 * MB
            else -> 200 * MB
        }
    }

    // swallow-ok: 空きが読めなくても、予算を決めるだけの話。真ん中を取る。
    // ここで止めると、写真が1枚も出せなくなる。
    private fun freeBytes(context: Context): Long = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(8L * 1024 * MB) // 分からなければ真ん中を取る

    /**
     * 予算を超えた分を、最後に開いたのが古いものから捨てる。
     *
     * **最後に開いた順**であって、届いた順ではない。古い写真でも、さっき見返した
     * ものはまた見る。届いた順で捨てると、遡って見ていた会話の写真から消える。
     */
    fun prune(directory: File, budget: Long) {
        if (!directory.isDirectory) return

        val files = directory.listFiles().orEmpty().filter { it.isFile }
        var total = files.sumOf { it.length() }
        if (total <= budget) return

        // 予算が 0 のときは全部消える。それが意図した動き。
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= budget) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /**
     * 前の版が `filesDir/media` に貯めた分を捨てる。
     *
     * 移し替えない。取り直せるものを、消せない場所から消せる場所へ運ぶために
     * 端末の I/O を使う理由が無い。次に開いたとき落とし直す。
     */
    fun forgetOldLocation(context: Context) {
        val old = File(context.filesDir, "media")
        if (!old.isDirectory) return
        old.listFiles()?.forEach { it.delete() }
        old.delete()
    }
}
