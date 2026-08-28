package blog.nextlab.echo.ui.chat

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.nextlab.echo.calls.CallId
import blog.nextlab.echo.calls.CallRecord
import blog.nextlab.echo.data.LocalStickerStore
import blog.nextlab.echo.data.RinowaServices
import blog.nextlab.echo.data.MediaImages
import blog.nextlab.echo.media.VideoTranscoder
import blog.nextlab.echo.core.model.Conversation
import blog.nextlab.echo.core.model.MediaId
import blog.nextlab.echo.core.model.Message
import blog.nextlab.echo.core.model.MessageContent
import blog.nextlab.echo.core.model.MessageId
import blog.nextlab.echo.core.model.MessageStatus
import blog.nextlab.echo.core.model.MessageText
import blog.nextlab.echo.core.model.ReplyPreview
import blog.nextlab.echo.core.model.StickerId
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.core.model.UserProfile
import blog.nextlab.echo.core.model.previewText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会話1つ分。
 *
 * 送信が即座に見えるのは Firestore の性質で、このクラスの工夫ではない。オフライン
 * 永続化が有効だと書き込みはまずローカルキャッシュに入り、その場でスナップショットが
 * 流れる。だから「送ったがまだ確定していないメッセージ」の一覧をここで持たなくてよく、
 * 本物が届いたときに吹き出しが二重になることもない。
 *
 * 区別は [Message.isPending]。サーバー時刻がまだ埋まっていないものが飛行中。
 */
class ChatViewModel(
    private val services: RinowaServices,
    private val stickers: LocalStickerStore,
    private val me: UserId,
    private val conversation: Conversation,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    var loading by mutableStateOf(true)
        private set

    var sendFailed by mutableStateOf(false)
        private set

    /** 招待の段階（友達追加待ち）なら false。 */
    var accepted by mutableStateOf(conversation.acceptedByMe)
        private set

    fun accept(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            services.conversations.accept(me, conversation.id).onSuccess {
                accepted = true
                onDone()
            }
        }
    }

    private var profiles: Map<UserId, UserProfile> = emptyMap()

    /** 相手側の通知に出すべきタイトル。 */
    private var myDisplayName: String = ""

    /** 会話ごとに1回だけ解決する。行に文字ではなく顔を出すため。 */
    var senderProfiles by mutableStateOf<Map<UserId, UserProfile>>(emptyMap())
        private set

    /** 取得済みのスタンプ id。同じスタンプが並んでも1回で済む。 */
    private val requestedStickers = mutableSetOf<StickerId>()

    /** 相手の既読位置。自分のメッセージの「既読」を決める。 */
    private val reads = MutableStateFlow<Map<UserId, Long>>(emptyMap())

    /**
     * この画面を開いている間に自分のメッセージが読まれると増える。
     *
     * フラグではなく回数。画面は変化のたびに触覚を鳴らすので、真偽値だと戻す処理が
     * もう1つ必要になる。
     */
    var readPulse by mutableStateOf(0)
        private set

    /** すでに既読と分かっている id。変化した瞬間にだけ鳴らすため。 */
    private var seenRead: Set<MessageId>? = null

    init {
        viewModelScope.launch {
            profiles = services.users.profiles(conversation.memberIds.filter { it != me })
            myDisplayName = services.users.profile(me).getOrNull()?.displayName.orEmpty()
            senderProfiles = profiles
            // 名前のあと。頭文字でもスレッドは読めるので、写真のために止めない。
            profiles.values.forEach { services.photos?.fetch(it.id, it.photoHash) }
            combine(
                services.messages.observe(conversation.id, me, profiles),
                reads,
            ) { list, readAt -> applyReadState(list, readAt) }
                .collect { list ->
                    _messages.value = list
                    loading = false
                    resolveMissingStickers(list)
                    notePulse(list)
                }
        }
        viewModelScope.launch {
            services.conversations.observeReads(conversation.id).collect { reads.value = it }
        }
        markRead()
    }

    /**
     * 全員が読み過ぎたら、自分のメッセージを既読にする。
     *
     * 決めるのは一番遅い人。5人中1人が読んだだけで既読と出すのは画面が吐く小さな嘘で、
     * この種の嘘は気付かれる。
     */
    private fun applyReadState(list: List<Message>, readAt: Map<UserId, Long>): List<Message> {
        val others = conversation.memberIds.filter { it != me }
        if (others.isEmpty()) return list
        val slowest = others.minOf { readAt[it] ?: 0L }
        if (slowest <= 0L) return list

        return list.map { message ->
            if (message.isOutgoing &&
                message.status != MessageStatus.Sending &&
                message.timestampMs <= slowest
            ) {
                message.copy(status = MessageStatus.Read)
            } else {
                message
            }
        }
    }

    private fun notePulse(list: List<Message>) {
        val read = list.filter { it.isOutgoing && it.status == MessageStatus.Read }
            .map { it.id }
            .toSet()
        val previous = seenRead
        seenRead = read
        // 最初の1回は基準を作るだけ。これが無いと、昨日読まれた分の数だけ震える。
        if (previous != null && (read - previous).isNotEmpty()) readPulse++
    }

    fun markRead() {
        viewModelScope.launch { services.conversations.markRead(me, conversation.id) }
    }

    fun sendText(body: String, replyTo: Message?) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        send(MessageContent.Text(MessageText(trimmed)), replyTo)
    }

    fun sendSticker(id: StickerId, replyTo: Message?) {
        // メッセージが持つのは id だけ。docs/STICKER_ARCHITECTURE.md。
        send(MessageContent.Sticker(id), replyTo)
    }

    /** 選ばれた写真を変換している間 true。入力欄がそう言えるように。 */
    var preparingPhoto by mutableStateOf(false)
        private set

    /** 送信中は別に持つ。失敗する理由が違うため。 */
    var uploadingPhoto by mutableStateOf(false)
        private set

    /**
     * 取得を頼んだ写真。1回で済ませるため。
     *
     * 無いと、写真が再コンポーズされるたびに取得が始まる。
     */
    private val requestedMedia = mutableSetOf<MediaId>()

    /** 写真が届いたら増やす。仮画像を出していた行が描き直される。 */
    var mediaRevision by mutableStateOf(0)
        private set

    /**
     * 画面に出ていて、まだ端末に無い写真を取りに行く。
     *
     * スレッドを開いたときにまとめてではなく、行から呼ぶ。写真が200枚ある会話でも、
     * 落とすのは見えている2枚。
     */
    fun requestMedia(id: MediaId, key: ByteArray? = null) {
        val media = services.media ?: return
        if (media.cached(id) != null || media.isKnownMissing(id)) return
        if (!requestedMedia.add(id)) return
        viewModelScope.launch {
            if (media.fetch(id, key)) mediaRevision++
        }
    }

    fun cachedMedia(id: MediaId) = services.media?.cached(id)

    /** 再生用の通信路。動画は取得せず、再生しながら読む。 */
    val mediaStore get() = services.mediaStore

    /**
     * 端末が既に持っている平文のファイル。
     *
     * 送信者は送る前から持っている。サーバーから流し直すと自分の動画を自分で
     * ダウンロードすることになる。
     */
    fun localVideo(id: MediaId) = services.media?.fileOf(id)

    /**
     * 保存用にオリジナルを取りに行く。
     *
     * 保存を押して選んだときだけ。表示のためには一度も触らない（圧縮版も送るのはそのため）。
     */
    suspend fun originalPhoto(image: MessageContent.Image): java.io.File? {
        val media = services.media ?: return null
        val id = image.originalId ?: return null
        val key = image.originalKey ?: return null
        return media.fetchOriginal(id, key)
            .onFailure { android.util.Log.w("Rinowa/media", "original fetch failed", it) }
            .getOrNull()
    }

    /**
     * ギャラリー保存用に、動画を丸ごと復号する。
     *
     * 再生はこれを呼ばない（範囲で読む）。全部が一度に要るのは保存だけなので、
     * 頼まれてから落とす。
     */
    suspend fun wholeVideo(video: MessageContent.Video): java.io.File? {
        val media = services.media ?: return null
        val key = video.mediaKey ?: return null
        media.fetch(video.mediaId, key)
        return media.fileOf(video.mediaId)
    }

    /** 通話画面に出す。黙って null にはしない。 */
    var callError by mutableStateOf<String?>(null)
        private set

    fun dismissCallError() { callError = null }

    /**
     * 通知から応答した通話。
     *
     * 着信リスナーを待たずに id で取りに行く。応答はタップで起こされたばかりの
     * プロセスで起きるので、温まったリスナーは無い。待っていたせいで、発信側が
     * 鳴らし続けているのに応答側は関係のない会話を見ている状態になった。
     */
    suspend fun callById(id: CallId) =
        services.calls?.fetchCall(conversation.id, id)

    /** 1対1の通話相手を画面側が決められるように公開する。 */
    val meId: UserId get() = me

    /**
     * この会話に着信がある。
     *
     * push ではなく Firestore のリスナー。この画面が開いている間はもう繋がっていて、
     * メッセージと同じ往復で鳴る。push は開いていないときに起こすための別経路。
     */
    private val _incomingCall = MutableStateFlow<CallRecord?>(null)
    val incomingCall: StateFlow<CallRecord?> = _incomingCall.asStateFlow()

    init {
        services.calls?.let { signaling ->
            viewModelScope.launch {
                signaling.observeIncoming(
                    conversation.id,
                    me,
                    // 握り潰さない。黙って失敗するリスナーは「誰もかけてこない」と
                    // 見分けが付かず、索引が無いだけで一晩溶かした。
                    onError = { callError = "着信の監視に失敗: " + it },
                ).collect { _incomingCall.value = it }
            }
        }
    }

    /** そのまま出す。黙って失敗した写真は説明のしようがない。 */
    var photoError by mutableStateOf<String?>(null)
        private set

    fun dismissPhotoError() { photoError = null }

    /**
     * 選ばれた写真を送る。
     *
     * 元のファイルは送らない。[MediaImages] でデコード・縮小・再エンコードしてから出す。
     * これで EXIF が落ち、カメラが書き込んだ位置情報も一緒に消える。
     * docs/MEDIA_ARCHITECTURE.md §1。
     *
     * メインスレッドから外す。1200万画素をメインで解くと、ピッカーが閉じるときの
     * アニメーションが全部落ちる。
     */
    fun sendPhoto(source: Uri, reader: Context, replyTo: Message?) {
        if (preparingPhoto) return
        preparingPhoto = true
        photoError = null
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                runCatching { MediaImages.prepare(source, reader) }
            }
            preparingPhoto = false
            val image = prepared.getOrElse {
                photoError = "${it::class.simpleName}: ${it.message.orEmpty()}"
                return@launch
            }

            // 写真を先に上げてからメッセージを書く。逆だと、まだ無いバイト列を指す
            // メッセージが画面に出る。内容アドレスなので、既に保管済みなら上げ直しは無料。
            val media = services.media
            if (media == null) {
                photoError = "写真の保存先が使えません"
                return@launch
            }
            uploadingPhoto = true
            val stored = media.publish(image.bytes)
            uploadingPhoto = false
            val object_ = stored.getOrElse {
                photoError = "アップロードに失敗: ${it::class.simpleName} ${it.message.orEmpty()}"
                return@launch
            }

            // 端末が「オリジナルも送る」設定なら、そのままのファイルも。
            //
            // 圧縮版はこの時点でもうメッセージになりかけているので、ここで失敗しても
            // 失うのはオリジナルだけ。両方欲しいのは分かるが、通ったほうまで捨てる理由にはならない。
            val original = if (services.settings.localSendsOriginals()) {
                runCatching {
                    reader.contentResolver.openInputStream(source)?.use { stream ->
                        media.publishOriginal(stream).getOrThrow()
                    }
                }
                    .onFailure {
                        android.util.Log.w("Rinowa/media", "original upload failed", it)
                        photoError = "オリジナルは送れませんでした（写真は送信済み）"
                    }
                    .getOrNull()
            } else {
                null
            }

            send(
                MessageContent.Image(
                    // id は写真ではなく暗号化後のファイルのハッシュ。保管庫が検証するのも、
                    // 相手が要求するのもそれ。隣の鍵だけが中身を開ける手段で、これは
                    // ドキュメントではなく封の中を通る。
                    mediaId = object_.id,
                    width = image.width,
                    height = image.height,
                    thumbnail = image.thumbnail,
                    byteCount = object_.byteCount,
                    mediaKey = object_.key,
                    originalId = original?.id,
                    originalKey = original?.key,
                    originalBytes = original?.byteCount,
                    originalMime = original?.let {
                        reader.contentResolver.getType(source) ?: "image/jpeg"
                    },
                ),
                replyTo,
            )
        }
    }

    /** 変換中と送信中の 0..100。何もしていなければ null。 */
    var videoProgress by mutableStateOf<Int?>(null)
        private set

    /**
     * 選ばれた動画を送る。
     *
     * 送る前にこの端末で 720p に変換し（VideoTranscoder）、暗号化して分割で上げる。
     * 4K の1分なら数十秒の実作業になるので、ぐるぐるではなく進捗を出す。止まって見える
     * 円は30秒続くとハングと区別が付かず、ハングだと思った人はもう一度送信を押す。
     *
     * カメラが書いたファイルそのものは送らない。再エンコードで位置情報も落ちる。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun sendVideo(source: Uri, reader: Context, replyTo: Message?) {
        if (videoProgress != null) return
        photoError = null
        videoProgress = 0

        viewModelScope.launch {
            val media = services.media
            if (media == null) {
                photoError = "動画の保存先が使えません"
                videoProgress = null
                return@launch
            }

            val working = java.io.File.createTempFile(
                "video",
                ".mp4",
                reader.cacheDir,
            )

            try {
                val encoded = runCatching {
                    VideoTranscoder.transcode(
                        context = reader,
                        source = source,
                        destination = working,
                    ) { percent -> videoProgress = percent / 2 }
                }
                    .getOrElse {
                        photoError = "動画を変換できませんでした: " + it.message.orEmpty()
                        return@launch
                    }

                // 変換と送信は別の作業だが、押した人には1つの「まだ」なので1本の帯にする。
                val stored = media.publishFile(working) { sent, total ->
                    videoProgress = if (total > 0) {
                        50 + (sent * 50L / total).toInt()
                    } else {
                        50
                    }
                }
                    .getOrElse {
                        photoError = "アップロードに失敗: " + it.message.orEmpty()
                        return@launch
                    }

                send(
                    MessageContent.Video(
                        mediaId = stored.id,
                        width = encoded.width,
                        height = encoded.height,
                        durationMs = encoded.durationMs,
                        thumbnail = encoded.poster,
                        byteCount = stored.byteCount,
                        sealedBytes = stored.sealedBytes,
                        mediaKey = stored.key,
                    ),
                    replyTo,
                )
            } finally {
                working.delete()
                videoProgress = null
            }
        }
    }

    private fun send(content: MessageContent, replyTo: Message?) {
        sendFailed = false
        viewModelScope.launch {
            services.messages.send(
                conversationId = conversation.id,
                sender = me,
                content = content,
                replyTo = replyTo?.let {
                    ReplyPreview(it.id, it.senderName, it.content.previewText())
                },
                // 読めるべき全員。会話の参加者が読めていないときは自分だけになり、
                // 誰にも配れないぶん目に見えて失敗する（黙って平文になるよりよい）。
                members = conversation.memberIds.ifEmpty { listOf(me) },
            ).fold(
                onSuccess = {
                    // 書き込みのあと、しかも書き込みを止めない。配達は Firestore がやる。
                    // push は肩を叩くだけなので、失敗しても失うのは通知だけ。PushSender を参照。
                    services.push?.notify(
                        conversationId = conversation.id,
                        senderName = myDisplayName,
                        // 暗号化しているときは本文を載せない。
                        //
                        // push は push.php と Google を通る。本文を載せると、アプリを
                        // 閉じている間に届くメッセージ（＝ほとんど）の平文がその経路を通る。
                        // 受け取る端末はもうメッセージを持っていて、push は見に行けと
                        // 伝えるだけでよい。
                        body = if (services.messages.encrypts) {
                            MessageText("新しいメッセージ")
                        } else {
                            content.previewText()
                        },
                    )
                },
                onFailure = { sendFailed = true },
            )
        }
    }

    /**
     * リアクションを付ける・変える・外す。
     *
     * 一人1つ。同じものを選ぶと外れ、別のものを選ぶと置き換わる。同じ規則は
     * サーバー側のルールでも効いている（map のキーは uid で、自分のキーしか触れない）。
     * ここは近道であって安全装置ではない。
     */
    fun react(messageId: MessageId, paletteIndex: Int) {
        val current = _messages.value
            .firstOrNull { it.id == messageId }
            ?.reactions
            ?.firstOrNull { it.mine }
            ?.paletteIndex

        viewModelScope.launch {
            services.messages.react(
                conversationId = conversation.id,
                messageId = messageId,
                me = me,
                paletteIndex = if (current == paletteIndex) null else paletteIndex,
            )
        }
    }

    fun delete(messageId: MessageId) {
        viewModelScope.launch { services.messages.delete(conversation.id, messageId) }
    }

    /**
     * 自分のメッセージを取り消す。
     *
     * @param onDone 跡が残ったら true、丸ごと消えたら false。呼び出し側はこれで触覚を
     *   選ぶ（2つは別の出来事なので）。
     */
    fun retract(messageId: MessageId, onDone: (leftMark: Boolean) -> Unit = {}) {
        val message = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!message.isOutgoing) return

        val others = conversation.memberIds.filter { it != me }
        val readAt = reads.value
        // ここでも決めるのは一番遅い人。誰も見ていないときだけ黙って消える。
        val slowest = if (others.isEmpty()) 0L else others.minOf { readAt[it] ?: 0L }

        viewModelScope.launch {
            services.messages.retract(
                conversationId = conversation.id,
                messageId = messageId,
                sentAt = message.timestampMs,
                readByOthersAt = slowest,
            ).onSuccess(onDone)
        }
    }

    /**
     * まだ持っていないスタンプ画像を取りに行く。
     *
     * 必要になった吹き出しからではなくメッセージの流れから呼ぶので、素早く通り過ぎた
     * スタンプも1回で解決して持っておける。同じ id の同時要求はストア側でまとめられる。
     */
    private fun resolveMissingStickers(list: List<Message>) {
        list.asSequence()
            .mapNotNull { (it.content as? MessageContent.Sticker)?.stickerId }
            .filter { stickers.asset(it) == null && it !in requestedStickers }
            .distinct()
            .toList()
            .forEach { id ->
                requestedStickers += id
                viewModelScope.launch { stickers.fetchAndPersist(id) }
            }
    }
}
