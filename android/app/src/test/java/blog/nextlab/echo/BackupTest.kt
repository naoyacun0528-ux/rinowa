package blog.nextlab.echo

import blog.nextlab.echo.backup.BackupArchive
import blog.nextlab.echo.backup.BackupCipher
import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.core.model.MediaId
import blog.nextlab.echo.core.model.MessageContent
import blog.nextlab.echo.core.model.MessageId
import blog.nextlab.echo.core.model.MessageText
import blog.nextlab.echo.core.model.UserId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * バックアップを、試せる場所で試す。端末もドライブもアカウントも無しで。
 *
 * 書庫は、端末を失ったあとに残る会話の唯一の複製なので、テストは「その人を裏切る
 * 2通りの失敗」について書いてある。違う人に対して開いてしまうことと、
 * 正しい人に対して開かないこと。
 */
class BackupTest {

    private val pin = "483920".toCharArray()

    @Test
    fun `a sealed backup opens with the same secret`() {
        val plain = "こんばんは、これは履歴です".toByteArray()
        val sealed = BackupCipher.seal(plain, pin)

        assertArrayEquals(plain, BackupCipher.open(sealed, pin))
    }

    @Test
    fun `it does not open with a different secret`() {
        val sealed = BackupCipher.seal("秘密".toByteArray(), pin)

        assertNull(BackupCipher.open(sealed, "483921".toCharArray()))
        assertNull(BackupCipher.open(sealed, "".toCharArray()))
    }

    @Test
    fun `a passphrase works as well as digits`() {
        val phrase = "本当に長いあいことば-2026".toCharArray()
        val sealed = BackupCipher.seal("x".toByteArray(), phrase)

        assertArrayEquals("x".toByteArray(), BackupCipher.open(sealed, phrase))
    }

    /** 同じ内容を2回控えても、同じファイルになってはいけない。 */
    @Test
    fun `the same content seals differently every time`() {
        val a = BackupCipher.seal("same".toByteArray(), pin)
        val b = BackupCipher.seal("same".toByteArray(), pin)

        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(BackupCipher.open(a, pin), BackupCipher.open(b, pin))
    }

    @Test
    fun `an edited file does not open`() {
        val sealed = BackupCipher.seal("履歴".toByteArray(), pin)

        val flipped = sealed.copyOf()
        flipped[flipped.size - 1] = (flipped[flipped.size - 1].toInt() xor 1).toByte()
        assertNull(BackupCipher.open(flipped, pin))

        val truncated = sealed.copyOf(sealed.size - 4)
        assertNull(BackupCipher.open(truncated, pin))
    }

    /**
     * ヘッダに書かれた計算量を下げるのが、総当たりを安くする一番簡単な手。
     * これは失敗しなければならない。開いてしまう弱いファイルができてはいけない。
     */
    @Test
    fun `the work factor cannot be edited down`() {
        val sealed = BackupCipher.seal("履歴".toByteArray(), pin)

        val weakened = sealed.copyOf()
        // 反復回数は8バイトの識別子のすぐあとにある。
        weakened[8] = 0
        weakened[9] = 0
        weakened[10] = 0
        weakened[11] = 1

        assertNull(BackupCipher.open(weakened, pin))
    }

    @Test
    fun `something that is not a backup is refused`() {
        assertNull(BackupCipher.open(ByteArray(0), pin))
        assertNull(BackupCipher.open("not a backup at all".toByteArray(), pin))
    }

    @Test
    fun `an archive survives the round trip`() {
        val entries = listOf(
            BackupArchive.Entry(
                conversationId = ConversationId("conv1"),
                messageId = MessageId("msg1"),
                senderId = UserId("uid1"),
                sentAtMs = 1_787_000_000_000,
                content = MessageContent.Text(MessageText("ただいま")),
            ),
            BackupArchive.Entry(
                conversationId = ConversationId("conv1"),
                messageId = MessageId("msg2"),
                senderId = UserId("uid2"),
                sentAtMs = 1_787_000_001_000,
                content = MessageContent.Image(
                    mediaId = MediaId("a".repeat(64)),
                    width = 1440,
                    height = 1920,
                    thumbnail = byteArrayOf(9, 8, 7),
                    byteCount = 812_345,
                    mediaKey = ByteArray(32) { it.toByte() },
                ),
            ),
        )

        val parsed = BackupArchive.read(
            BackupArchive.write(UserId("uid1"), 1_787_000_002_000, entries),
        )!!

        assertEquals(BackupArchive.VERSION, parsed.version)
        assertEquals(UserId("uid1"), parsed.owner)
        assertEquals(2, parsed.entries.size)
        assertEquals(MessageId("msg2"), parsed.entries[1].messageId)

        // 写真は、それを開く鍵と一緒に戻る必要がある。でないと復元したメッセージが
        // 誰にも読めないファイルを指すことになる。
        val photo = parsed.entries[1].content as MessageContent.Image
        assertTrue(ByteArray(32) { it.toByte() }.contentEquals(photo.mediaKey))
        assertEquals(1440, photo.width)
    }

    @Test
    fun `an archive from a newer build is refused rather than half read`() {
        val json = BackupArchive.write(UserId("uid1"), 0, emptyList())
            .replace("\"v\":1", "\"v\":99")

        assertNull(BackupArchive.read(json))
    }

    @Test
    fun `rubbish is refused`() {
        assertNull(BackupArchive.read("{"))
        assertNull(BackupArchive.read("{}"))
    }

    /** 両方合わせたもの。実際にドライブへ行くのはこれ。 */
    @Test
    fun `an archive sealed and opened again is the same archive`() {
        val entries = listOf(
            BackupArchive.Entry(
                ConversationId("c"),
                MessageId("m"),
                UserId("u"),
                42,
                MessageContent.Text(MessageText("ぬ")),
            ),
        )

        val file = BackupCipher.seal(
            BackupArchive.write(UserId("u"), 1, entries).toByteArray(),
            pin,
        )
        val restored = BackupArchive.read(String(BackupCipher.open(file, pin)!!))!!

        assertEquals(1, restored.entries.size)
        assertEquals(
            MessageContent.Text(MessageText("ぬ")),
            restored.entries[0].content,
        )
    }
}
