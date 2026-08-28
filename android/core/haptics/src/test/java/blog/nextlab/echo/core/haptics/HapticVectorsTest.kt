package blog.nextlab.echo.core.haptics

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触覚の調整表を、他の実装が読める形で書き出す。
 *
 * **数値がそのまま「感触」なので、手で写すと必ずずれる。** ずれ方も静かで、
 * 症状は「同じアプリなのに iPhone だと送信の手応えが弱い」。誰も原因に辿り着けない。
 *
 * 書き出すのはエンベロープ段だけ。理由は、それが**両方の実装に共通して存在する
 * 唯一の段**だから。Android のプリミティブ段（TICK / CLICK / THUD）は Android の
 * 語彙で、iOS には無い。逆に iOS の CoreHaptics は (強さ, 硬さ, 時間) の
 * 制御点で書くので、[EnvelopeSpec] とほぼそのまま対応する。
 *
 * 段を跨いだ対応表を作ろうとすると、どちらの端末でも正しくない中間物ができる。
 * **共通の言葉があるところだけを共通にする。**
 *
 * ファイルが無ければ現在の値から書き出す。あれば突き合わせる。
 * つまり**触り心地を直したら、このテストが赤くなる**。赤くなったら、
 * 直したのが意図的なら消して作り直す。それは修正ではなく判断。
 */
class HapticVectorsTest {

    private val file = File("../../../research/vectors/haptics.json")

    @Test
    fun `the tuning table is written down where the other implementation can read it`() {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(generate())
            println("wrote " + file.absolutePath)
        }

        val root = JSONObject(file.readText())
        val tokens = root.getJSONObject("tokens")

        // 語彙そのものが揃っていること。片方に増えたら、そこが穴になる。
        val listed = tokens.keys().asSequence().toSortedSet()
        val actual = HapticToken.entries.map { it.name }.toSortedSet()
        assertEquals("触覚の語彙が食い違っている", actual, listed)

        for (token in HapticToken.entries) {
            val spec = HapticTokens[token]
            val want = tokens.getJSONObject(token.name)
            val name = token.name

            assertEquals("$name: 再発火の間隔", want.getLong("minIntervalMs"), spec.minIntervalMs)
            assertEquals("$name: 既定効果", want.getString("predefined"), spec.predefined.name)

            val env = want.getJSONObject("envelope")
            assertEquals(
                "$name: 立ち上がりの硬さ",
                env.getDouble("initialSharpness"),
                spec.envelope.initialSharpness.toDouble(),
                1e-6,
            )

            val points = env.getJSONArray("points")
            assertEquals("$name: 制御点の数", points.length(), spec.envelope.points.size)
            for (i in 0 until points.length()) {
                val p = points.getJSONObject(i)
                val got = spec.envelope.points[i]
                assertEquals("$name[$i]: 強さ", p.getDouble("intensity"), got.intensity.toDouble(), 1e-6)
                assertEquals("$name[$i]: 硬さ", p.getDouble("sharpness"), got.sharpness.toDouble(), 1e-6)
                assertEquals("$name[$i]: 時間", p.getLong("durationMs"), got.durationMs)
            }
        }
    }

    /**
     * **エンベロープは必ず0で終わる。**
     *
     * 終わらないと OS に拒否される。Android では `require` が投げるので気づくが、
     * iOS 側は別の実装なので、ここを表に出しておかないと気づかないまま
     * 「その触覚だけ鳴らない端末」ができる。
     */
    @Test
    fun `every envelope lands on zero`() {
        for (token in HapticToken.entries) {
            val points = HapticTokens[token].envelope.points
            assertEquals("${token.name}: 最後の制御点が0でない", 0f, points.last().intensity)
            assertTrue("${token.name}: 制御点が無い", points.isNotEmpty())
        }
    }

    /**
     * 読まれた通知だけは、指が原因ではない。
     *
     * 頼んでいない振動が、相手がアプリを開いた拍子に来る。**だから一番強く間引く。**
     *
     * 最初この検査を「一番弱いこと」と書いて、落ちた。[HapticToken.Selection] のほうが
     * 弱い（0.25 対 0.26）。そちらは指が動いている最中に鳴り続けるので、
     * もっと小さくないと安っぽくなる。**弱さではなく頻度で守られている。**
     */
    @Test
    fun `the one haptic the finger did not ask for is the most throttled`() {
        val readReceipt = HapticTokens[HapticToken.ReadReceipt]

        for (token in HapticToken.entries) {
            if (token == HapticToken.ReadReceipt) continue
            assertTrue(
                "${token.name} のほうが間引きが強い。ReadReceipt は指が頼んでいない振動",
                readReceipt.minIntervalMs > HapticTokens[token].minIntervalMs,
            )
        }
    }

    /**
     * 指を追い続けるものと、報せるものを取り違えない。
     *
     * [HapticToken.Selection] は指が動いている間ずっと鳴る。連射されるものが強いと、
     * 触覚全体が安っぽくなる。**一覧で一番弱いのはここ**でなければならない。
     */
    @Test
    fun `the haptic that fires while the finger moves is the weakest of all`() {
        val selection = HapticTokens[HapticToken.Selection].envelope.points.maxOf { it.intensity }
        for (token in HapticToken.entries) {
            if (token == HapticToken.Selection) continue
            val other = HapticTokens[token].envelope.points.maxOf { it.intensity }
            assertTrue("${token.name} が Selection より弱い", other >= selection)
        }
    }

    private fun generate(): String {
        val tokens = JSONObject()
        for (token in HapticToken.entries) {
            val spec = HapticTokens[token]
            val points = JSONArray()
            for (p in spec.envelope.points) {
                points.put(
                    JSONObject()
                        .put("intensity", p.intensity.toDouble())
                        .put("sharpness", p.sharpness.toDouble())
                        .put("durationMs", p.durationMs),
                )
            }
            tokens.put(
                token.name,
                JSONObject()
                    .put("minIntervalMs", spec.minIntervalMs)
                    .put("predefined", spec.predefined.name)
                    .put(
                        "envelope",
                        JSONObject()
                            .put("initialSharpness", spec.envelope.initialSharpness.toDouble())
                            .put("points", points),
                    ),
            )
        }

        return JSONObject()
            .put(
                "note",
                "Generated by HapticVectorsTest. 触覚の調整表のうち、両方の実装に共通して" +
                    "存在する段（エンベロープ）だけ。強さ 0..1、硬さ 0..1、時間はミリ秒。" +
                    "iOS の CoreHaptics は同じ3つで書ける。See docs/HAPTIC_DESIGN.md.",
            )
            .put("tokens", tokens)
            .toString(2) + "\n"
    }
}
