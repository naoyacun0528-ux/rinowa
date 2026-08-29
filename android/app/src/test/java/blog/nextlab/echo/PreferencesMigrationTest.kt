package blog.nextlab.echo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 設定ファイルは、改名を通り抜けられる形で開くこと。
 *
 * ## なぜこの検査があるか
 *
 * アプリの名前を Echo → Rinowa に変えたとき、設定ファイルも改名した。移行の
 * 関数を用意して3箇所に入れ、**1箇所忘れた。** 忘れたのは `CryptoEngine` で、
 * そこには**この端末の身元**が入っていた。
 *
 * 端末IDが失われて新しく作られ、鍵の保管庫だけが古いIDのまま残った。保管庫が
 * 開かなくなり、会話が全部「まだ開けません」になり、**その端末が過去に受け取った
 * ものは永久に読めなくなった。** docs/INCIDENT_2026-08-29_CRYPTO_DEVICE_ID.md。
 *
 * 他の3つ（既知の端末・設定・push トークン）なら、失われても取り直せる。
 * たまたま忘れた1つが、取り直せないものを持っていた。
 *
 * ## 何を見ているか
 *
 * `getSharedPreferences` を直接呼んでいる場所。改名を経ていないアプリなら
 * それでよいが、この企画は一度改名している。**新しく書くときも、次に改名する
 * ときも、ここで止まる。**
 *
 * 直す方法は2つ。`renamedPreferences(新, 旧)` を使うか、旧名が無いと分かって
 * いるなら、なぜ無いかをその場に書く:
 *
 *     // rename-ok: <理由>
 */
class PreferencesMigrationTest {

    @Test
    fun `preferences are opened in a way that survives the rename`() {
        val offenders = mutableListOf<String>()

        sources().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (!line.contains(DIRECT)) return@forEachIndexed
                if (line.trimStart().startsWith("//")) return@forEachIndexed
                if (line.contains(MARKER)) return@forEachIndexed
                // 直前の行に書いてあってもよい。長い理由は上に置きたくなる。
                val before = file.readLines().getOrNull(index - 1).orEmpty()
                if (before.contains(MARKER)) return@forEachIndexed
                offenders += "${file.name}:${index + 1}"
            }
        }

        assertTrue(
            buildString {
                appendLine("改名を通り抜けられない形で設定ファイルを開いている:")
                appendLine()
                offenders.forEach { appendLine("  $it") }
                appendLine()
                appendLine("renamedPreferences(新, 旧) を使うか、旧名が無い理由を書くこと:")
                appendLine("  // rename-ok: <理由>")
            },
            offenders.isEmpty(),
        )
    }

    private fun sources(): List<File> =
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    private companion object {
        const val DIRECT = "getSharedPreferences("
        const val MARKER = "rename-ok:"
    }
}
