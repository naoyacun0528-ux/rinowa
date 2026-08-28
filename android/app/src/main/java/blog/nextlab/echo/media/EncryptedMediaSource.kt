package blog.nextlab.echo.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * 暗号化された動画を、先に落とさずにサーバーから読ませる。
 *
 * 経路:
 *
 * ```
 * ExoPlayer → ここ → Tink の復号チャネル → RangeChannel → media_get.php
 * ```
 *
 * プレイヤーが位置を求め、Tink がそれを暗号化された区間に直し、範囲チャネルが
 * `Range:` 要求に変える。**この端末の外に1フレームも出ない**し、これから映すもの
 * 以外は落とさない。2分の動画の最後の10秒に飛べば、真ん中は一度も取りに行かない。
 *
 * AES-GCM-HKDF-STREAMING を選んだのはこの性質のため。ファイル全体に認証タグが
 * 1つだと、最初の1フレームに動画全体ぶんの費用がかかる。
 *
 * localhost にプロキシサーバーを立てて再生する方法もあるが、それは開いたポートと、
 * 認証の話の2つ目の複製と、端末上のどのアプリからも読めるソケットを通る復号済みの
 * 流れを意味する。こちらはプロセスの中で完結する。
 */
@UnstableApi
class EncryptedMediaSource(
    private val client: MediaStoreClient,
    private val mediaId: String,
    /** **暗号化後**のオブジェクトの大きさ。メッセージから来る。 */
    private val sealedBytes: Long,
    private val key: ByteArray,
) : BaseDataSource(true) {

    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var remaining: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val decrypting = MediaCipher.openSeekable(
            client.channel(mediaId, sealedBytes),
            key,
        )
        decrypting.position(dataSpec.position)
        channel = decrypting

        val plaintextSize = decrypting.size()
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            plaintextSize - dataSpec.position
        } else {
            dataSpec.length
        }

        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT

        val decrypting = channel ?: throw IOException("read before open")
        val want = minOf(length.toLong(), remaining).toInt()
        val read = decrypting.read(ByteBuffer.wrap(buffer, offset, want))
        if (read <= 0) return C.RESULT_END_OF_INPUT

        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val decrypting = channel
        channel = null
        uri = null
        if (decrypting != null) {
            // swallow-ok: もう使い終わったチャネルを閉じているだけ。プレイヤー側に
            // できることは何も変わらないし、ここで投げると「動画が終わった」が
            // エラーになる。
            runCatching { decrypting.close() }
            transferEnded()
        }
    }

    /** ExoPlayer は再生ごとにソースを作るので、ファクトリにしてある。 */
    class Factory(
        private val client: MediaStoreClient,
        private val mediaId: String,
        private val sealedBytes: Long,
        private val key: ByteArray,
    ) : androidx.media3.datasource.DataSource.Factory {

        override fun createDataSource() =
            EncryptedMediaSource(client, mediaId, sealedBytes, key)
    }
}
