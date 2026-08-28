package blog.nextlab.echo.core.wire

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ビルド用の機械の上で計測を走らせる。
 *
 * **ここの数字は大事な数字ではない。** デスクトップの JVM は端末ではなく、ここに
 * 出る報告は比較の基準であって Android についての主張ではない。端末での実行は
 * Direct Lab の画面から呼ぶ同じ [YosegiBenchmark]。それを実機で走らせるまで、
 * 端末の時間は存在しないので、引用してはいけない。
 *
 * このテストの本来の目的は最後の検証。計測が作るサンプルが往復できること。
 * 黙ってデータを失うコーデックを測っても何も測っていないし、計測自身のサンプル生成器は、
 * まさに誰も確かめないコードだから。
 */
class YosegiBenchmarkTest {

    @Test
    fun `the benchmark sample round-trips before any timing is believed`() {
        val (messages, context) = YosegiBenchmark.buildSample(count = 500)
        val back = Yosegi.decode(Yosegi.encode(messages, context), context)
        for (i in messages.indices) {
            org.junit.Assert.assertEquals("sample message $i", messages[i], back[i])
        }
    }

    @Test
    fun `report the desktop baseline`() {
        val report = YosegiBenchmark.run(count = 500, warmup = 100, iterations = 200)
        println()
        println(report)
        println()
        println("  Desktop JVM. Phone numbers come from the Direct Lab run; see docs/YOSEGI_V1_SPEC.md.")
        println()

        assertTrue("Yosegi should be well under half of the JSON it replaces",
            report.yosegiBytes * 2 < report.rawJsonEquivalentBytes)
    }
}
