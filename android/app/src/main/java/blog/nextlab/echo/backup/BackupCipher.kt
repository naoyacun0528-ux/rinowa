package blog.nextlab.echo.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * バックアップの錠。
 *
 * ファイルは本人の Google ドライブの、アプリ専用の隠し領域に入る。置き場所としては
 * よいが、信頼できる場所では**ない**。ドライブのアカウントを持つ者が読めるバックアップは、
 * そのアカウントを乗っ取った者が読めるバックアップで、メッセージを暗号化する意味は
 * 「サーバーを持つこと＝会話を持つことではない」ところにある。なので出る前に、
 * 本人しか知らないものから導いた鍵で封をする。
 *
 * docs/PRIVACY_PRINCIPLES.md にそのまま書いてある。クラウドバックアップを足すために
 * 禁止事項を1つでも緩めてはいけない。読める書庫を Google に渡すのは、預ける先を
 * 1社から別の1社に移して前進と呼ぶこと。
 *
 * 形式:
 *
 * ```
 * "LOWANBK" 0x01   8バイト   識別子と版
 *
 * **この7文字は変えない。** アプリ名が Rinowa になっても、既に Drive に上がっている
 * 書庫の先頭にはこのバイトが入っている。名前を揃えた瞬間、過去の書庫は全部
 * 「これは違うファイルだ」と拒否される。**名札を綺麗にするために、
 * 人が復元できなくなるのは割に合わない。**
 * iterations       4バイト   ビッグエンディアン。将来コストを上げても古いファイルを読める
 * salt            16バイト
 * nonce           12バイト
 * ciphertext       nバイト   AES-256-GCM、タグ込み
 * ```
 *
 * 暗号文より前の全部を関連データとして結び付ける。既存ファイルの反復回数を下げる
 * （総当たりを安くする一番わかりやすい手）と、弱いファイルができるのではなく認証に失敗する。
 *
 * 正直な限界: **6桁の暗証番号は100万通り**。どんな鍵導出でもそれを大きな数には
 * できず、1回の試行を高くするだけ。[ITERATIONS] では端末で1秒ほど、借りた計算機なら
 * はるかに速いので、ファイルを持っていて本気の動機がある者は数字だけの暗証番号を
 * 総当たりできる。ここで買えるのは、ドライブのアカウントを*たまたま*持つ人
 * （家族、盗まれたノート、サポート担当）に読まれないことと、長い合言葉なら本当に強いこと。
 * 設定画面はそう書いてあるし、数字以外も受け付ける。
 *
 * LINE も WhatsApp も同じ形の問題を、預けた鍵に対するサーバー側の試行回数制限で
 * 解いている。それには復旧に参加するサーバーが要り、それこそこの企画が作らないもの。
 * docs/RESEARCH_E2EE.md §3 の D 行。
 *
 * **忘れたらバックアップは終わり。** 構造上、復旧手段は無い。
 */
object BackupCipher {

    /** [secret] から導いた鍵で封をする。空の secret で呼んではいけない。 */
    fun seal(plaintext: ByteArray, secret: CharArray): ByteArray {
        require(secret.isNotEmpty()) { "バックアップの暗証番号が空です" }

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(ITERATIONS, salt, nonce)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derive(secret, salt, ITERATIONS), "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            updateAAD(header)
        }

        val sealed = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(header.size + sealed.size)
            .put(header)
            .put(sealed)
            .array()
    }

    /**
     * バックアップを開く。開かなければ null。
     *
     * null は「この secret ではこのファイルは開かない」で、暗証番号違い・途中で切れた
     * ダウンロード・改竄のどれでも同じ答えになる。意図的にそうしている。違いを教えるのは、
     * ファイルを持つ者に*どの推測が近かったか*を教えること。
     */
    fun open(blob: ByteArray, secret: CharArray): ByteArray? {
        if (secret.isEmpty()) return null
        if (blob.size < HEADER_BYTES + TAG_BITS / 8) return null

        val buffer = ByteBuffer.wrap(blob)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) return null

        val iterations = buffer.int
        // ばかげて小さいコストを名乗るファイルは、誰かが編集したファイル。下の認証でも
        // 捕まるが、ここで拒否すればそもそも計算しない。
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return null

        val salt = ByteArray(SALT_BYTES).also(buffer::get)
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val sealed = ByteArray(buffer.remaining()).also(buffer::get)

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(derive(secret, salt, iterations), "AES"),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                updateAAD(header(iterations, salt, nonce))
            }
            cipher.doFinal(sealed)
        }
            // swallow-ok: ここで失敗すること*が*答え。secret 違い、壊れたファイル、
            // 編集されたファイルはどれも AEADBadTagException で終わり、呼び出し側が
            // 動けるのは「開かなかった」という1点だけ。
            .getOrNull()
    }

    private fun header(iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_BYTES)
            .put(MAGIC)
            .putInt(iterations)
            .put(salt)
            .put(nonce)
            .array()

    private fun derive(secret: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            // 中間の配列は文字の複製を持つ。呼び出し側の配列は呼び出し側のもので、
            // 画面から読んだ人が消す。
            spec.clearPassword()
        }
    }

    /**
     * PBKDF2-HMAC-SHA256。
     *
     * 2026年時点で最強ではない（Argon2id がある）が、**プラットフォームに入っている
     * 中では**最強。これのためにネイティブのパスワードハッシュを持ち込むと、署名し、
     * 配り、更新し続けるライブラリが1つ増える。docs/PRIVACY_PRINCIPLES.md が禁じて
     * いるのは暗号を発明することで、公開されたものから選ぶことではない。
     *
     * API 26 以上が要る。それより古い端末では、黙って SHA-1 に落ちるのではなく
     * バックアップの画面がそう言う。黙った代替は誰も気付かない。
     */
    private const val KDF = "PBKDF2withHmacSHA256"
    const val MIN_API = android.os.Build.VERSION_CODES.O

    /** 中位機で約1秒。当てずっぽうではなく実測（テストを参照）。 */
    const val ITERATIONS = 600_000

    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 10_000_000

    private val MAGIC = byteArrayOf(
        'L'.code.toByte(), 'O'.code.toByte(), 'W'.code.toByte(), 'A'.code.toByte(),
        'N'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 1,
    )

    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HEADER_BYTES = 8 + 4 + SALT_BYTES + NONCE_BYTES
}
