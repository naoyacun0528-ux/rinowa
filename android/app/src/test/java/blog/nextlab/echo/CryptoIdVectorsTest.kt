package blog.nextlab.echo

import blog.nextlab.echo.crypto.CryptoIds
import blog.nextlab.echo.model.ConversationId
import blog.nextlab.echo.model.UserId
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * research/vectors/crypto-ids.json と突き合わせる。
 *
 * **ここが iOS と1文字でも違うと、iPhone と Android の間で鍵が噛み合わない。**
 * 症状は「鍵が無い」ではなく「署名が一致しない」で、移行ではなく攻撃に見える。
 *
 * Swift 側の同じテストは ios/RinowaCore/Tests/RinowaCoreTests/CryptoIdVectorsTests.swift。
 */
class CryptoIdVectorsTest {

    private val file = File("../../research/vectors/crypto-ids.json")

    private fun root(): JSONObject {
        assertTrue("research/vectors/crypto-ids.json が無い", file.exists())
        return JSONObject(file.readText())
    }

    @Test
    fun `the domain is frozen`() {
        assertEquals(
            "ドメインは凍結。変えるなら全員の鍵の作り直し",
            root().getString("domain"),
            CryptoIds.DOMAIN,
        )
    }

    @Test
    fun `user ids match`() {
        val a = root().getJSONArray("user")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            assertEquals(c.getString("matrix"), CryptoIds.matrixUser(UserId(c.getString("uid"))))
        }
    }

    @Test
    fun `room ids match`() {
        val a = root().getJSONArray("room")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            assertEquals(
                c.getString("matrix"),
                CryptoIds.matrixRoom(ConversationId(c.getString("conversationId"))),
            )
        }
    }

    @Test
    fun `parsing back`() {
        val p = root().getJSONObject("parse")
        val valid = p.getJSONArray("valid")
        for (i in 0 until valid.length()) {
            val c = valid.getJSONObject(i)
            assertEquals(
                "${c.getString("matrix")} が読めない",
                c.getString("uid"),
                CryptoIds.userFromMatrix(c.getString("matrix"))?.value,
            )
        }
        val invalid = p.getJSONArray("invalid")
        for (i in 0 until invalid.length()) {
            val bad = invalid.getString(i)
            assertNull("\"$bad\" から uid をでっち上げた", CryptoIds.userFromMatrix(bad))
        }
    }
}
