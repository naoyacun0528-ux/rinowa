package blog.nextlab.echo.ui.chatlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.nextlab.echo.core.model.Conversation
import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.core.model.MessageText
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.core.model.UserProfile
import blog.nextlab.echo.data.MessageRepository
import blog.nextlab.echo.data.RinowaServices
import blog.nextlab.echo.data.UserRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 会話を始めるときに何が失敗したか。 */
sealed interface StartChatOutcome {
    data class Opened(val id: ConversationId) : StartChatOutcome
    data object NotFound : StartChatOutcome
    data object Yourself : StartChatOutcome
    data object Failed : StartChatOutcome
}

/**
 * 会話一覧と、その出入り口。
 *
 * 未読件数は会話ドキュメントに保存せず、ここで数える。サーバー側に持つと、
 * メッセージを読む人が書き込めるようにする必要があり、それは会話にいる誰でも
 * 好きな値を書けるということ。自分の既読位置と照らして手元で数えるほうが、
 * 守るのも安く、他人のぶんを間違えることもない。
 */
class ChatListViewModel(
    private val services: RinowaServices,
    private val me: UserId,
) : ViewModel() {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    var loading by mutableStateOf(true)
        private set

    var myProfile by mutableStateOf<UserProfile?>(null)
        private set

    var inviteCode by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            // 設定が先。アカウントから戻した触覚の設定は、最初のタップより前に
            // 入っている必要がある。
            services.settings.pull(me)
            services.conversations.observe(me).collect { list ->
                // **まず、そのまま出す。**
                //
                // 未読数もプレビューも、出すのに時間がかかる。それを待ってから
                // 描くと、会話の名前すら出ないまま画面が空のままになる。
                // 名前と時刻は最初から手元にあるので、先に見せる。
                _conversations.value = list
                loading = false
                refreshContacts(list)

                // 足りない分をあとから埋める。
                _conversations.value = decorate(list)
            }
        }
    }

    /** アカウントが分かった時点でプロフィールと招待コードを埋める。 */
    fun bind(user: blog.nextlab.echo.auth.RinowaUser) {
        viewModelScope.launch {
            services.users.ensureProfile(user).onSuccess { profile ->
                myProfile = profile
                inviteCode = services.users.inviteCode(profile.id).getOrNull()
            }
            services.settings.push(me)
            // アカウントができたので、その下に登録する。Firebase はサインインの
            // ずっと前にトークンを渡していることがある（PushTokenRegistrar を参照）。
            services.registerPushToken(me)
        }
    }

    /**
     * 名前の下の1行を、この端末で開いたもの。
     *
     * 保存されているプレビューは使えない。会話ドキュメントには一覧を描くための
     * プレビューが入っていて、それはサーバーから読める。本文を暗号化したあとに
     * そこへ本文を書くと、**全会話の最新の1行**（たいてい一番大事な行）を渡すことになる。
     * なので送信側は目印だけを置く。
     *
     * 読める版はここで作る。この端末が最新のメッセージを取ってきて自分の鍵で開く
     * （通知と同じやり方）。一覧には言われたことが出るが、間に立った誰にも読めていない。
     *
     * 鍵が来ていないときや、端末ができる前のメッセージのときは目印のまま。
     * 「何かある」と言いつつ中身を知ったふりをしない、正しい落とし方。
     */
    private suspend fun previewFor(conversation: Conversation): MessageText {
        if (conversation.preview.value != MessageRepository.LOCKED_PREVIEW) {
            return conversation.preview
        }
        // 一覧はサーバーに聞かない。手元にあるもので出す。
        val opened = services.messages.newestBodyCached(conversation.id, me)
            ?: return conversation.preview
        return MessageText(opened)
    }

    /**
     * 一覧が画面に戻ってきたら数え直す。
     *
     * スレッドを読むと `conversations/{id}/reads/{me}` に書くが、これは**サブ
     * コレクション**で、会話ドキュメント自体は変わらない。だから一覧の元になる
     * フローは再送されず、読んだあともバッジが残っていた。「すべて既読」だけで
     * 消えていたのは、その経路がメモリ上の一覧を直接いじっていたから。
     */
    fun refreshVisibleState() {
        viewModelScope.launch {
            _conversations.value = decorate(_conversations.value)
        }
    }

    /**
     * 未読数とプレビューを埋める。**会話ごとに同時に走らせる。**
     *
     * 以前は map の中で suspend を呼んでいて、会話が10件あれば10回ぶんの待ちが
     * 順番に積み上がっていた。どれも互いを必要としないので、待つ意味が無い。
     * 10回の待ちが1回ぶんの長さになる。
     */
    private suspend fun decorate(list: List<Conversation>): List<Conversation> =
        coroutineScope {
            list.map { conversation ->
                async {
                    conversation.copy(
                        unreadCount = unreadFor(conversation),
                        preview = previewFor(conversation),
                    )
                }
            }.awaitAll()
        }

    private suspend fun unreadFor(conversation: Conversation): Int {
        val since = services.conversations.lastReadAt(me, conversation.id)
        android.util.Log.i(
            "Rinowa/unread",
            "conv=" + conversation.id.value + " since=" + since + " last=" + conversation.lastTimestampMs,
        )
        // 一度も開いていない会話。全部が新着だが、履歴を全部数えるのは会話の全読み込みに
        // なる。最初に開くまではバッジを出さない。
        if (since <= 0L) return 0
        if (conversation.lastTimestampMs <= since) return 0
        return services.messages.unreadCount(conversation.id, me, since)
    }

    /**
     * グループに入れられる相手。
     *
     * 友達一覧を別に持たず、1対1の会話から導く。Rinowa には「友達追加」の段階が
     * 独自にはなく、招待コードを交換して話すことが関係そのもの。だから会話がある人が
     * ここで知っている人。一覧をもう1つ持つと、同期する対象が増え、しかも先に古くなる。
     */
    var contacts by mutableStateOf<List<UserProfile>>(emptyList())
        private set

    private suspend fun refreshContacts(list: List<Conversation>) {
        val others = list
            .filterNot { it.isGroup }
            .flatMap { it.memberIds }
            .filter { it != me }
            .distinct()
        if (others.isEmpty()) {
            contacts = emptyList()
            return
        }
        val resolved = services.users.profiles(others)
        contacts = resolved.values.sortedBy { it.displayName }
        profilesById = resolved

        // 写真は名前のあと。頭文字でも一覧は正しく描けるので、画像を待って
        // 動くものを遅らせない。
        resolved.values.forEach { services.photos?.fetch(it.id, it.photoHash) }
    }

    /** 解決済みのプロフィール。行に頭文字ではなく写真を出すため。 */
    var profilesById by mutableStateOf<Map<UserId, UserProfile>>(emptyMap())
        private set

    /** 1対1の会話の相手。行のアイコン用。 */
    fun counterpart(conversation: Conversation): UserProfile? =
        if (conversation.isGroup) {
            null
        } else {
            conversation.memberIds.firstOrNull { it != me }?.let { profilesById[it] }
        }

    fun createGroup(
        title: String,
        members: List<UserId>,
        onResult: (StartChatOutcome) -> Unit,
    ) {
        viewModelScope.launch {
            if (members.isEmpty()) {
                onResult(StartChatOutcome.Failed)
                return@launch
            }
            services.conversations.createGroup(me, members, title).fold(
                onSuccess = { onResult(StartChatOutcome.Opened(it)) },
                onFailure = { onResult(StartChatOutcome.Failed) },
            )
        }
    }

    fun startDirect(rawCode: String, onResult: (StartChatOutcome) -> Unit) {
        viewModelScope.launch {
            val code = UserRepository.normalise(rawCode)
            val found = services.users.findByInviteCode(code).getOrNull()
            when {
                found == null -> onResult(StartChatOutcome.NotFound)
                found.id == me -> onResult(StartChatOutcome.Yourself)
                else -> services.conversations.openDirect(me, found.id).fold(
                    onSuccess = { onResult(StartChatOutcome.Opened(it)) },
                    onFailure = { onResult(StartChatOutcome.Failed) },
                )
            }
        }
    }

    fun leave(id: ConversationId) {
        viewModelScope.launch { services.conversations.leave(me, id) }
    }

    /** [markAllRead] にやることがあれば true。 */
    val hasUnread: Boolean get() = _conversations.value.any { it.unreadCount > 0 }

    /**
     * どれも開かずに全部を既読にする。
     *
     * ここで**やらない**ことに注意: 本文を1件も取得しない。既読は会話ごと・人ごとの
     * 時刻として記録されるので、バッジを消すのは書き込みであって、誰かのメッセージを
     * 読むことではない。
     */
    fun markAllRead(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val unread = _conversations.value.filter { it.unreadCount > 0 }
            unread.forEach { services.conversations.markRead(me, it.id) }
            _conversations.value = _conversations.value.map { it.copy(unreadCount = 0) }
            onDone(unread.size)
        }
    }
}
