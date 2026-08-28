package blog.nextlab.echo.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * メッセージの本文。
 *
 * [toString] は中身を出さない。ログへの文字列展開は本文が端末の外へ出る一番ありがちな
 * 経路で、`Log.d(TAG, "sending $text")` は `MessageText(len=24)` になる。
 * docs/PRIVACY_PRINCIPLES.md の防御層3。
 */
@JvmInline
value class MessageText(val value: String) {
    val length: Int get() = value.length
    val isBlank: Boolean get() = value.isBlank()

    override fun toString(): String = "MessageText(len=$length)"
}

/**
 * 識別子。
 *
 * 素の String にしないのは、会話 id をメッセージ id の場所へ渡せないようにするため。
 * 中身は Firestore のドキュメント id で、解釈も並べ替えもしない。
 */
@JvmInline
value class ConversationId(val value: String)

@JvmInline
value class MessageId(val value: String)

@JvmInline
value class UserId(val value: String)

/**
 * メッセージが運ぶもの。
 *
 * スタンプは id だけを持ち、画像のバイト列は持たない（docs/STICKER_ARCHITECTURE.md）。
 * sealed にしておくと、あとから種類を足すときも「とりあえずバイト列を入れる」に流れない。
 */
@Immutable
sealed interface MessageContent {

    @Immutable
    data class Text(val body: MessageText) : MessageContent

    @Immutable
    data class Sticker(val stickerId: StickerId) : MessageContent

    /**
     * 写真。
     *
     * 持つのは id と形と、メッセージの中に入る大きさのサムネイルだけ。本体は
     * タップされてから id で取りに行く。[width] [height] があるので、画像が届く前から
     * 場所を正しく空けられる。docs/MEDIA_ARCHITECTURE.md。
     */
    @Immutable
    data class Image(
        val mediaId: MediaId,
        val width: Int,
        val height: Int,
        /** WebP、長辺32px。どの複製にも入っている。 */
        val thumbnail: ByteArray,
        /** 本体の大きさ。取得前に「3.2 MB」と言えるように。 */
        val byteCount: Int,
        /**
         * 保管されたファイルを開く鍵。保管庫より前の写真では null。
         *
         * 鍵がメッセージの中を通るのはこれが理由。ファイルはルールの無いウェブサーバー上に
         * あり、バイト列は暗号化されていて、鍵がアクセス制御そのもの。メッセージ
         * ドキュメントに入れるとデータベースを読める者に読まれ、保管庫を作った意味が消える。
         * 封の中なら、そのメッセージを読める端末にしか届かない。MessageEnvelope を参照。
         */
        val mediaKey: ByteArray? = null,
        /**
         * 送信者が「オリジナルも送る」を選んだときのファイル。
         *
         * 既定は off。オリジナルは数百KBに対して3〜15MBあり、全部保管するのは
         * この設計が避けたかったもの（docs/MEDIA_ARCHITECTURE.md §11.3）。
         * アカウント単位で送信者が選んだときだけ、しかもサーバー側は30日で消す。
         *
         * [originalMime] を運ぶのは、受け取る側がファイルを一度も見ないから。そのまま
         * 書き戻すには、それが何なのかを言う必要がある。
         */
        val originalId: MediaId? = null,
        val originalKey: ByteArray? = null,
        val originalBytes: Int? = null,
        val originalMime: String? = null,
    ) : MessageContent {
        val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 1f

        /** 保存のときに選ぶものが2つあるか。 */
        val hasOriginal: Boolean get() = originalId != null && originalKey != null

        // data class の ByteArray は同一性比較になる。同じメッセージを2回デコードすると
        // 別物になり、Compose の再描画スキップが効かなくなる。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Image) return false
            return mediaId == other.mediaId && width == other.width && height == other.height &&
                byteCount == other.byteCount && thumbnail.contentEquals(other.thumbnail) &&
                mediaKey.contentEquals(other.mediaKey) &&
                originalId == other.originalId && originalBytes == other.originalBytes &&
                originalMime == other.originalMime &&
                originalKey.contentEquals(other.originalKey)
        }

        override fun hashCode(): Int {
            var result = mediaId.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + byteCount
            return result
        }

        override fun toString(): String = "Image($mediaId, ${width}x$height, $byteCount B)"
    }

    /**
     * 動画。
     *
     * 送るのはカメラが書いたファイルではなく、この端末で 720p/30fps に変換したもの
     * （VideoTranscoder）。モバイル回線で送れる大きさになり、同時にカメラが書き込む
     * 位置情報も落ちる。
     *
     * [byteCount] は動画そのもの、[sealedBytes] はサーバー上の暗号化オブジェクト。
     * 途中から復号しながら読むには暗号文の長さが要るので、再生前に問い合わせる代わりに
     * 封の中に数バイトで持たせる。
     */
    @Immutable
    data class Video(
        val mediaId: MediaId,
        val width: Int,
        val height: Int,
        /** 1バイトも取得せずに 0:14 と出せるように。 */
        val durationMs: Long,
        /** 冒頭の1枚。WebP、写真のサムネイルと同じ大きさ。 */
        val thumbnail: ByteArray,
        val byteCount: Int,
        val sealedBytes: Long,
        val mediaKey: ByteArray? = null,
    ) : MessageContent {
        val aspectRatio: Float get() = if (height > 0) width.toFloat() / height else 1f

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Video) return false
            return mediaId == other.mediaId && width == other.width && height == other.height &&
                durationMs == other.durationMs && byteCount == other.byteCount &&
                sealedBytes == other.sealedBytes &&
                thumbnail.contentEquals(other.thumbnail) &&
                mediaKey.contentEquals(other.mediaKey)
        }

        override fun hashCode(): Int {
            var result = mediaId.hashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + durationMs.hashCode()
            result = 31 * result + byteCount
            return result
        }

        override fun toString(): String =
            "Video($mediaId, ${width}x$height, ${durationMs}ms, $byteCount B)"
    }

    /**
     * 行われた通話。
     *
     * 会話は打った文字だけではない。「火曜に12分話した」も会話のうちで、通話を落とすと
     * 一番よく話した場所に穴が空く。
     *
     * 書くのは発信側だけ。同じ1件を、かけた側は「応答なし」、受けた側は「不在着信」と
     * 読む。両方が書くと必ず2件になり、各自が測った通話時間で食い違う。代わりに、
     * 通話が終わってから書き込みが届くまでの間にプロセスが死ぬと記録が残らない。
     * 無い行のほうが、実在しない通話が残るよりましだと判断した。
     *
     * @param seconds 実際につながっていた時間。呼び出し時間は含まない。出なければ0。
     */
    @Immutable
    data class Call(
        val video: Boolean,
        val outcome: CallOutcome,
        val seconds: Int,
    ) : MessageContent

    /**
     * この端末ではまだ読めないメッセージ。
     *
     * エラーではなく状態。部屋の鍵はメッセージとは別に届くので、鍵より先にメッセージが
     * 着くことがある（たいてい1〜2秒で解ける）。端末ができる前に送られたものは永久に
     * 開かない。ここからは両方同じに見えるので、正直に「何かあるが読めない」と出す。
     * 空欄にはしない（空だと「送られていない」と受け取られる）。
     */
    @Immutable
    data class Locked(val ciphertext: String) : MessageContent

    /**
     * 読まれたあとに取り消したもの。
     *
     * 空の [Text] ではなく別の状態にする。中身は無くなり、会話はそう言う、という別の事実。
     * 未読のものは削除されるので、これにはならない。
     */
    @Immutable
    data object Retracted : MessageContent
}

/**
 * 通話がどう終わったか。スレッドに書くための粒度。
 *
 * `CallEndReason` よりわざと粗い。経路が見つからなかったのか、話す前に切れたのかは、
 * 当事者にとっては同じ「通話できなかった」。
 */
enum class CallOutcome {
    /** 出た。[MessageContent.Call.seconds] が意味を持つのはこれだけ。 */
    Completed,

    /** 出なかった。 */
    Missed,

    /** 断った。 */
    Declined,

    /** つながらなかった。 */
    Failed,
}

/** 一覧や引用に出す短い代わりの文字列。 */
fun MessageContent.previewText(): MessageText = when (this) {
    is MessageContent.Text -> body
    is MessageContent.Sticker -> MessageText("スタンプ")
    is MessageContent.Image -> MessageText("写真")
    is MessageContent.Video -> MessageText("動画")
    // 一覧はどちら側か知らないので中立にする。発信・着信の言い分けはスレッドの仕事。
    is MessageContent.Call -> MessageText(if (video) "ビデオ通話" else "音声通話")
    // 一覧はサーバーが読める項目から作るので、暗号化された本文は出せない。最後に読めた
    // メッセージを出して「それ以降なにも無い」ように見せるより、こう言うほうがよい。
    is MessageContent.Locked -> MessageText("🔒 暗号化されたメッセージ")
    MessageContent.Retracted -> MessageText("送信を取り消しました")
}

enum class MessageStatus { Sending, Sent, Delivered, Read, Failed }

@Immutable
data class Reaction(
    val paletteIndex: Int,
    val count: Int,
    val mine: Boolean,
) {
    val emoji: String get() = ReactionPalette.emojiAt(paletteIndex)
}

/** 固定の並び。計測には絵文字ではなく番号を送れるようにするため。 */
object ReactionPalette {
    const val VERSION = 1

    val emoji: List<String> = listOf("❤️", "😂", "😮", "😢", "👍", "🔥")

    fun emojiAt(index: Int): String = emoji.getOrElse(index) { "❓" }
}

@Immutable
data class ReplyPreview(
    val messageId: MessageId,
    val senderName: String,
    val excerpt: MessageText,
)

@Immutable
data class Message(
    val id: MessageId,
    val senderId: UserId,
    val content: MessageContent,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val senderName: String,
    val status: MessageStatus = MessageStatus.Sent,
    val replyTo: ReplyPreview? = null,
    val reactions: ImmutableList<Reaction> = persistentListOf(),
) {
    val isSticker: Boolean get() = content is MessageContent.Sticker

    /**
     * まだこの端末にしか無い状態。
     *
     * 送信はローカルの id で先に表示し、書き込みが届いたらサーバー側の複製に置き換える。
     * これが無いと往復のあいだスレッドが空になる。
     */
    val isPending: Boolean get() = status == MessageStatus.Sending
}

/** 会話の中に出てくる人。 */
@Immutable
data class UserProfile(
    val id: UserId,
    val displayName: String,
    val photoUrl: String?,
    /** 本人が出したい一行。必須ではない。 */
    val statusMessage: String? = null,
    /**
     * プロフィール画像の内容ハッシュ。無ければ null。
     *
     * 利用者ドキュメントに載せることで、画像を落とさずに手元の複製が最新か判断できる。
     * [ProfilePhotos] を参照。
     */
    val photoHash: String? = null,
) {
    val avatarSeed: Int get() = id.value.hashCode()
}

@Immutable
data class Conversation(
    val id: ConversationId,
    val title: String,
    val preview: MessageText,
    val lastTimestampMs: Long,
    val unreadCount: Int,
    val isGroup: Boolean,
    /** 写真が無いときの仮アイコンの色を決める。 */
    val avatarSeed: Int,
    val previewIsOutgoing: Boolean = false,
    val memberIds: ImmutableList<UserId> = persistentListOf(),
    /**
     * まだ招待の段階なら false。
     *
     * メッセージはどちらでも読める（それを決めるのは参加者かどうか）。ここが表すのは
     * 自分が居ることに同意したかだけで、「友達追加」ボタンが変えるのはこれ。
     */
    val acceptedByMe: Boolean = true,
)
