package blog.nextlab.echo

import blog.nextlab.echo.backup.BackupCipher
import blog.nextlab.echo.data.MessageEnvelope
import blog.nextlab.echo.core.model.CallOutcome
import blog.nextlab.echo.core.model.MediaId
import blog.nextlab.echo.core.model.MessageContent
import blog.nextlab.echo.core.model.MessageText
import java.io.File
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 各形式を、どの実装にも当てはめられるデータとして書き留めたもの。
 *
 * 封もバックアップも**2つのプログラムの間の契約**。いまは両端が同じこの実装なので、
 * 形式を変えても構造上一致し、食い違いようが無い。2つ目の実装（iOS アプリ、
 * デスクトップの道具、移行スクリプト）ができた日にそれが終わり、終わり方は静かで、
 * 項目名が1つ違うだけ。症状は「他の点はどこも正常な端末で、他人の古いメッセージだけが
 * 開かない」。
 *
 * なので形式をベクタとして固定する。片側に入力、もう片側に読み取りの正解。
 * どちらの実装も同じファイルを走らせる。2つ目の実装がずれればテストが赤くなり、
 * こちらがずれても同じ。
 *
 * 向きは意図的に「このバイト列を、こう理解しなければならない」であって、
 * 「この内容から、このバイト列を作らなければならない」ではない。正しい JSON の
 * 書き手が2つあれば鍵の順序は違うので、出力を固定すると、それが失敗になる一方で
 * 本当に大事な食い違いを見逃す。書き手を通した往復は隣のテストで確認している。
 *
 * 作り直しはファイルを消してテストを走らせる。現在の実装から書き戻されるので、
 * 消すことは修正ではなく判断（今日の挙動を正解の定義にする、ということ）。
 * ベクタが落ちたときの問いは常に「どちらが間違っているか」で、答えはテストでない
 * ことが多い。
 */
class FormatVectorsTest {

    // モジュールのディレクトリからの相対。Gradle が単体テストを走らせる場所がそこ。
    // 2つ上がリポジトリの根で、1つ上だと android/ になり、ベクタが誰も見ない場所に落ちた。
    private val file = File("../../research/vectors/formats.json")

    @Test
    fun `the vectors describe this implementation`() {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(generate())
            println("wrote " + file.absolutePath)
        }

        val root = JSONObject(file.readText())

        assertEquals(
            "envelope format version",
            ENVELOPE_VERSION,
            root.getInt("envelopeVersion"),
        )

        val envelopes = root.getJSONArray("envelopes")
        for (i in 0 until envelopes.length()) {
            val vector = envelopes.getJSONObject(i)
            val name = vector.getString("name")
            val opened = MessageEnvelope.open(vector.getString("plaintext"))

            when (vector.getString("kind")) {
                "text" -> {
                    val text = opened as? MessageContent.Text
                    assertTrue(name + ": expected text", text != null)
                    assertEquals(name, vector.getString("body"), text!!.body.value)
                }

                "image" -> {
                    val image = opened as? MessageContent.Image
                    assertTrue(name + ": expected an image", image != null)
                    assertEquals(name, vector.getString("mediaId"), image!!.mediaId.value)
                    assertEquals(name, vector.getInt("width"), image.width)
                    assertEquals(name, vector.getInt("height"), image.height)
                    assertEquals(name, vector.getInt("bytes"), image.byteCount)
                    assertEquals(
                        name + ": media key",
                        vector.getString("mediaKeyBase64"),
                        Base64.getEncoder().encodeToString(image.mediaKey),
                    )
                    assertEquals(
                        name + ": thumbnail",
                        vector.getString("thumbnailBase64"),
                        Base64.getEncoder().encodeToString(image.thumbnail),
                    )
                }

                "video" -> {
                    val video = opened as? MessageContent.Video
                    assertTrue(name + ": expected a video", video != null)
                    assertEquals(name, vector.getString("mediaId"), video!!.mediaId.value)
                    assertEquals(name, vector.getLong("durationMs"), video.durationMs)
                    assertEquals(name, vector.getLong("sealedBytes"), video.sealedBytes)
                }

                "call" -> {
                    val call = opened as? MessageContent.Call
                    assertTrue(name + ": expected a call", call != null)
                    assertEquals(name, vector.getBoolean("video"), call!!.video)
                    assertEquals(name, vector.getInt("seconds"), call.seconds)
                    assertEquals(
                        name,
                        vector.getString("outcome"),
                        call.outcome.name.lowercase(),
                    )
                }
            }
        }

        // バックアップ。実装が読み違えたときに送り直せない側で、その頃には
        // 元の端末はたいてい無い。
        val backups = root.getJSONArray("backups")
        for (i in 0 until backups.length()) {
            val vector = backups.getJSONObject(i)
            val blob = Base64.getDecoder().decode(vector.getString("sealedBase64"))
            val opened = BackupCipher.open(blob, vector.getString("secret").toCharArray())

            assertTrue(vector.getString("name") + ": did not open", opened != null)
            assertEquals(
                vector.getString("name"),
                vector.getString("plaintext"),
                String(opened!!),
            )
        }

        assertEquals(
            "backup work factor",
            BackupCipher.ITERATIONS,
            root.getInt("backupIterations"),
        )
    }

    /** 動いている実装から1回だけ作り、以後はそれに合わせる。 */
    private fun generate(): String {
        val thumbnail = ByteArray(6) { (it * 37).toByte() }
        val mediaKey = ByteArray(32) { it.toByte() }

        val image = MessageContent.Image(
            mediaId = MediaId("a".repeat(64)),
            width = 1440,
            height = 1920,
            thumbnail = thumbnail,
            byteCount = 812_345,
            mediaKey = mediaKey,
        )
        val video = MessageContent.Video(
            mediaId = MediaId("b".repeat(64)),
            width = 720,
            height = 1280,
            durationMs = 7_040,
            thumbnail = thumbnail,
            byteCount = 2_226_977,
            sealedBytes = 2_227_453,
            mediaKey = mediaKey,
        )

        val envelopes = JSONArray()
            .put(
                JSONObject()
                    .put("name", "plain text")
                    .put("kind", "text")
                    .put(
                        "plaintext",
                        MessageEnvelope.seal(MessageContent.Text(MessageText("こんばんは 🌙"))),
                    )
                    .put("body", "こんばんは 🌙"),
            )
            .put(
                JSONObject()
                    .put("name", "text written before the envelope existed")
                    .put("kind", "text")
                    .put("plaintext", "E2EE-final-1552")
                    .put("body", "E2EE-final-1552"),
            )
            .put(
                JSONObject()
                    .put("name", "photo with a key")
                    .put("kind", "image")
                    .put("plaintext", MessageEnvelope.seal(image))
                    .put("mediaId", image.mediaId.value)
                    .put("width", image.width)
                    .put("height", image.height)
                    .put("bytes", image.byteCount)
                    .put(
                        "mediaKeyBase64",
                        Base64.getEncoder().encodeToString(mediaKey),
                    )
                    .put(
                        "thumbnailBase64",
                        Base64.getEncoder().encodeToString(thumbnail),
                    ),
            )
            .put(
                JSONObject()
                    .put("name", "video")
                    .put("kind", "video")
                    .put("plaintext", MessageEnvelope.seal(video))
                    .put("mediaId", video.mediaId.value)
                    .put("durationMs", video.durationMs)
                    .put("sealedBytes", video.sealedBytes),
            )
            .put(
                JSONObject()
                    .put("name", "missed call")
                    .put("kind", "call")
                    .put(
                        "plaintext",
                        MessageEnvelope.seal(
                            MessageContent.Call(
                                video = true,
                                outcome = CallOutcome.Missed,
                                seconds = 0,
                            ),
                        ),
                    )
                    .put("video", true)
                    .put("outcome", "missed")
                    .put("seconds", 0),
            )

        val backupPlaintext = """{"v":1,"owner":"uid1","at":1787000000000,"messages":[]}"""
        val backups = JSONArray()
            .put(
                JSONObject()
                    .put("name", "empty archive, six digit pin")
                    .put("secret", "483920")
                    .put("plaintext", backupPlaintext)
                    .put(
                        "sealedBase64",
                        Base64.getEncoder().encodeToString(
                            BackupCipher.seal(
                                backupPlaintext.toByteArray(),
                                "483920".toCharArray(),
                            ),
                        ),
                    ),
            )
            .put(
                JSONObject()
                    .put("name", "passphrase, non-ascii")
                    .put("secret", "ながいあいことば-2026")
                    .put("plaintext", "ぬ")
                    .put(
                        "sealedBase64",
                        Base64.getEncoder().encodeToString(
                            BackupCipher.seal(
                                "ぬ".toByteArray(),
                                "ながいあいことば-2026".toCharArray(),
                            ),
                        ),
                    ),
            )

        return JSONObject()
            .put(
                "note",
                "Generated by FormatVectorsTest. Any implementation of Rinowa's formats " +
                    "must read these exactly. See docs/WIRE_FORMATS.md.",
            )
            .put("envelopeVersion", ENVELOPE_VERSION)
            .put("backupIterations", BackupCipher.ITERATIONS)
            .put("envelopes", envelopes)
            .put("backups", backups)
            .toString(2)
    }

    private companion object {
        /**
         * 封の中の項目ではない。封には版の印を入れていない（知らない型は読み手が
         * 無視し、知っている読み手には番号が要らない）。これは*ベクタ*を書いた時点の
         * 番号で、ベクタを更新せずに形式を変えたことがここで見える。
         */
        const val ENVELOPE_VERSION = 1
    }
}
