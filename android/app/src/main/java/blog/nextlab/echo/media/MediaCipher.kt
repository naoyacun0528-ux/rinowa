package blog.nextlab.echo.media

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.File
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 保管庫に入るもの全部の暗号化。
 *
 * 保管庫はウェブサーバー上のディレクトリ。HTTP で配るファイルの前にルールエンジンは
 * 無く、当てにくい URL は保証ではなく願望。なのでバイト列は端末を出る前に暗号化し、
 * 鍵は封をしたメッセージの中を通る（MessageEnvelope）。結果ははっきり書ける。
 * **そのサーバーを運用する者は、そこにある写真を見られない。**
 *
 * 素の AES-GCM ではなくストリーム構成なのは、GCM のタグ1つがファイル全体を覆うから。
 * 全部届くまで何も信用できない。写真なら耐えられるが動画では使えない
 * （最初の1フレームのために200MBを待ち、シークのたびにやり直す）。
 * AES-GCM-HKDF-STREAMING（Tink による STREAM の実装）はファイルを区間に分けて
 * 区間ごとにタグを付けるので、**バイト範囲だけで復号できる**。
 * 「落とさずに流す」という設計全体がこの性質の上に乗っている。
 *
 * ここに新しい形式は無い。方式は公開されていて実装は Tink のもの。このファイルは
 * 引数を選ぶだけで、暗号処理は何もしない。
 *
 * 鍵は会話からもアカウントからも導かない。ファイルごとに乱数なので、漏れても
 * 失うのはそのファイル1つ。同じ写真を2人が送っても暗号文は別になり、
 * 保管庫はそれが同じだと分からない。
 */
object MediaCipher {

    /** 32バイト。封をしたメッセージの中にしか存在しない。 */
    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)

    /**
     * [plaintext] を暗号化して [destination] に書き、保管庫での id を返す。
     *
     * id は**暗号文**の SHA-256。サーバーは中身をまったく読めないまま、announce された
     * ものを受け取ったかどうかを検証できる。
     */
    fun seal(plaintext: File, destination: File, key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        destination.outputStream().use { file ->
            streaming(key).newEncryptingStream(file, ASSOCIATED_DATA).use { encrypting ->
                plaintext.inputStream().use { source ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        encrypting.write(buffer, 0, read)
                    }
                }
            }
        }
        destination.inputStream().use { stored ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = stored.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** すでにメモリにあるバイト列を暗号化する。圧縮済みの写真用で、動画では使わない。 */
    fun seal(plaintext: ByteArray, destination: File, key: ByteArray): String {
        val temporary = File.createTempFile("seal", null, destination.parentFile)
        return try {
            temporary.writeBytes(plaintext)
            seal(temporary, destination, key)
        } finally {
            temporary.delete()
        }
    }

    /** すでに取得済みのファイルを丸ごと開く。 */
    fun open(ciphertext: File, key: ByteArray): InputStream =
        streaming(key).newDecryptingStream(ciphertext.inputStream(), ASSOCIATED_DATA)

    /**
     * 順番どおりでなく読める、暗号化ファイルの見え方。
     *
     * 動画プレイヤーに渡すもの。見ている位置の周りだけを要求し、その区間だけが
     * 取得され検証される。[source] は通常、読み取りを HTTP の範囲要求に変えるチャネル。
     */
    fun openSeekable(source: SeekableByteChannel, key: ByteArray): SeekableByteChannel =
        streaming(key).newSeekableDecryptingChannel(source, ASSOCIATED_DATA)

    private fun streaming(key: ByteArray) = AesGcmHkdfStreaming(
        key,
        HKDF_ALGORITHM,
        KEY_BYTES,
        SEGMENT_BYTES,
        FIRST_SEGMENT_OFFSET,
    )

    private const val KEY_BYTES = 32
    private const val HKDF_ALGORITHM = "HmacSha256"

    /**
     * 1メガバイト。
     *
     * 区間の大きさは範囲要求の粒度。動画の途中へ飛ぶ無駄は最大でも1区間。
     * これより小さいとタグと区間ごとの付随情報が増えて写真に響き、大きいと
     * シークが遅く感じる。
     */
    private const val SEGMENT_BYTES = 1 shl 20
    private const val FIRST_SEGMENT_OFFSET = 0

    /**
     * すべての区間タグに結び付ける文字列。
     *
     * この形式をこのアプリのこの版に固定する。上の引数を変えるときはこれも変え、
     * 古いファイルが新しい規則で黙って読まれることを防ぐ（認証に失敗する）。
     */
    private val ASSOCIATED_DATA = "rinowa-media-v1".toByteArray()

    private const val COPY_BUFFER = 64 * 1024
}
