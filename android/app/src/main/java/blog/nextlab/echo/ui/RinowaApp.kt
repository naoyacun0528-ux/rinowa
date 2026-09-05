package blog.nextlab.echo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import blog.nextlab.echo.auth.AuthState
import blog.nextlab.echo.auth.AuthViewModel
import blog.nextlab.echo.auth.RinowaUser
import blog.nextlab.echo.calls.CallController
import blog.nextlab.echo.calls.CallKind
import blog.nextlab.echo.core.analytics.Analytics
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.core.haptics.RinowaHaptics
import blog.nextlab.echo.data.LocalStickerStore
import blog.nextlab.echo.data.RinowaServices
import blog.nextlab.echo.core.model.Conversation
import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.core.model.MessageText
import blog.nextlab.echo.core.model.UserId
import blog.nextlab.echo.ui.auth.AccountScreen
import blog.nextlab.echo.ui.auth.DeleteAccountScreen
import blog.nextlab.echo.ui.auth.SignInScreen
import blog.nextlab.echo.ui.auth.VerifyEmailScreen
import blog.nextlab.echo.ui.backup.BackupRoute
import blog.nextlab.echo.ui.chat.ChatScreen
import blog.nextlab.echo.ui.chat.ChatViewModel
import blog.nextlab.echo.ui.chatlist.ChatListScreen
import blog.nextlab.echo.ui.chatlist.ChatListViewModel
import blog.nextlab.echo.ui.chatlist.NewConversationScreen
import blog.nextlab.echo.ui.chatlist.NewGroupScreen
import blog.nextlab.echo.ui.common.backPull
import blog.nextlab.echo.ui.direct.DirectLabScreen
import blog.nextlab.echo.ui.feedback.FeedbackScreen
import blog.nextlab.echo.ui.feedback.FeedbackViewModel
import blog.nextlab.echo.ui.lab.HapticLabScreen
import blog.nextlab.echo.ui.privacy.PrivacyScreen
import blog.nextlab.echo.ui.profile.ProfileScreen
import blog.nextlab.echo.ui.security.SafetyScreen
import blog.nextlab.echo.ui.profile.ProfileViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun RinowaApp(
    haptics: RinowaHaptics,
    analytics: Analytics,
    stickers: LocalStickerStore,
    services: RinowaServices?,
    /**
     * このコンポジションより長く生きるスコープ。通話はこの中で動く。
     *
     * 通話がコンポジションのスコープを使ってはいけない理由は RinowaApplication.appScope。
     * Activity が作り直されると、相手に終了を伝えるコルーチンごと消える。
     */
    appScope: kotlinx.coroutines.CoroutineScope,
    /** 通知がタップされたときに入る。[onConversationOpened] で消す。 */
    openConversationId: String? = null,
    onConversationOpened: () -> Unit = {},
) {
    RinowaTheme {
        // コントローラは全画面より上でここに持つ。会話を離れても通話が続く必要があるため。
        // スコープを Application から取るのも同じ理由の一段強い版で、コンポジションの
        // スコープは Activity の作り直しで死ぬ。
        val callContext = LocalContext.current
        val callScope = appScope
        val authState = services?.auth?.state?.collectAsStateWithLifecycle()?.value
        val signedIn = authState as? AuthState.SignedIn
        val callerId = signedIn?.user?.uid

        // サインインした瞬間に、この端末の識別鍵とワンタイム鍵を publish する。
        //
        // 最初のメッセージを暗号化するときまで遅らせていたら、端末は**自分から
        // 話しかけるまで届かない**状態になっていた。相手は端末を問い合わせて0件を見て、
        // 誰にも部屋の鍵を配らない。エラーはどこにも出ず、メッセージだけが開かない。
        val application = LocalContext.current.applicationContext
        LaunchedEffect(callerId) {
            val uid = callerId ?: return@LaunchedEffect
            (application as? blog.nextlab.echo.RinowaApplication)
                ?.cryptoEngine(UserId(uid))
        }
        val callController = remember(services?.calls, callerId) {
            val signaling = services?.calls
            if (signaling != null && callerId != null) {
                CallController(
                    context = callContext,
                    signaling = signaling,
                    scope = callScope,
                    me = UserId(callerId),
                    push = services.push?.let { sender ->
                        { conversationId, callId, kind ->
                            sender.notify(
                                conversationId = conversationId,
                                senderName = signedIn.user.displayName.orEmpty(),
                                body = MessageText(
                                    if (kind == CallKind.Video) {
                                        "ビデオ通話"
                                    } else {
                                        "音声通話"
                                    },
                                ),
                                type = "call",
                                callId = callId.value,
                            )
                        }
                    },
                    // SDP と ICE 候補を、会話の鍵（メッセージ用に両端がすでに持っている
                    // もの）で封をする。これより前は何が読めたかは CallController.seal。
                    seal = { conversationId, members, value ->
                        (application as? blog.nextlab.echo.RinowaApplication)
                            ?.cryptoEngine(UserId(callerId))
                            ?.encrypt(conversationId, members, value)
                    },
                    open = { conversationId, sender, ciphertext ->
                        val engine = (application as? blog.nextlab.echo.RinowaApplication)
                            ?.cryptoEngine(UserId(callerId))
                        // メッセージと同じく、開く鍵より先に信号が着くことがある。
                        // 外れたら受信箱を空にしてもう一度だけ試す。
                        engine?.decrypt(conversationId, sender, ciphertext)
                            ?: engine?.let {
                                it.receive()
                                it.decrypt(conversationId, sender, ciphertext)
                            }
                    },
                    // スレッドに残す1行。待たずにアプリのスコープへ投げる。終わった通話が
                    // 書き込みの完了を待ってマイクを握り続けてはいけない。
                    recordCall = { conversationId, members, call ->
                        callScope.launch {
                            services.messages.send(
                                conversationId = conversationId,
                                sender = UserId(callerId),
                                content = call,
                                replyTo = null,
                                // 渡さないと既定の「送信者だけ」になり、相手の端末では
                                // 復号できない記録がスレッドに1行増えるだけになる。
                                members = members,
                            )
                        }
                    },
                )
            } else {
                null
            }
        }

        CompositionLocalProvider(
            LocalRinowaHaptics provides haptics,
            LocalAnalytics provides analytics,
            LocalStickers provides stickers,
            LocalCalls provides callController,
        ) {
            val colors = RinowaTheme.colors

            RequestNotificationPermission()

            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.background),
            ) {
                if (services == null) {
                    // このビルドには Firebase が無い。RinowaApplication.services を参照。
                    MainNavigation(
                        services = null,
                        stickers = stickers,
                        analytics = analytics,
                        authViewModel = null,
                        user = null,
                        openConversationId = null,
                        onConversationOpened = {},
                    )
                } else {
                    AuthGate(
                        services = services,
                        stickers = stickers,
                        analytics = analytics,
                        openConversationId = openConversationId,
                        onConversationOpened = onConversationOpened,
                    )
                }
            }
        }
    }
}

/**
 * 起動時に1回だけ通知の許可を求める。
 *
 * 理由の画面を先に出さずに求める唯一の権限。通知できないメッセンジャーは仕事をして
 * いないので、アプリ自体が理由になる。Rinowa Direct に要るものは、理由を説明できる
 * 画面で求める。docs/DIRECT_ARCHITECTURE.md §16。
 *
 * 断っても他に影響は無い。メッセージは届き、開けば見える。
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Either answer is fine. */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * アプリに入れる状態かどうかを決める。
 *
 * 真偽値にまとめず3状態のままにする。[AuthState.NeedsVerification] は実際に
 * 留まる場所だから（Firebase から見ればサインイン済みで、まだ通していない）。
 */
@Composable
private fun AuthGate(
    services: RinowaServices,
    stickers: LocalStickerStore,
    analytics: Analytics,
    openConversationId: String?,
    onConversationOpened: () -> Unit,
) {
    val viewModel: AuthViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AuthViewModel(
                    services.auth,
                    services.googleCredentials,
                    // 出ていく前に、この端末を相手の端末一覧から外す。ここで渡さないと
                    // 登録が残り、サインアウト済みの端末に前のアカウント宛の通知が届く。
                    { services.unregisterPushToken(it) },
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Crossfade(
        targetState = state,
        animationSpec = tween(RinowaMotion.DURATION_STANDARD, easing = RinowaMotion.standardEasing),
        label = "authGate",
    ) { current ->
        when (current) {
            // Firebase はディスクからセッションを戻すので、長くても数フレーム。
            // 空のページ以上のものを出すと一瞬だけ光る。
            AuthState.Loading -> Box(Modifier.fillMaxSize())

            AuthState.SignedOut -> SignInScreen(viewModel)

            is AuthState.NeedsVerification -> VerifyEmailScreen(viewModel, current)

            is AuthState.SignedIn -> MainNavigation(
                services = services,
                stickers = stickers,
                analytics = analytics,
                authViewModel = viewModel,
                user = current.user,
                openConversationId = openConversationId,
                onConversationOpened = onConversationOpened,
            )
        }
    }
}

@Composable
private fun MainNavigation(
    services: RinowaServices?,
    stickers: LocalStickerStore,
    analytics: Analytics,
    authViewModel: AuthViewModel?,
    user: RinowaUser?,
    openConversationId: String?,
    onConversationOpened: () -> Unit,
) {
    var screen by remember { mutableStateOf<Screen>(Screen.ChatList) }

    val scope = rememberCoroutineScope()

    // 誰かが忘れずに書いた画面だけでなく、全部の画面がジェスチャーに応える。
    val backPull = blog.nextlab.echo.ui.common.rememberBackPull(
        enabled = screen !is Screen.ChatList,
    ) {
        screen = screen.parent()
    }

    val me = user?.let { UserId(it.uid) }

    // サインイン中のアカウントに紐付ける。別のアカウントで入り直したとき、前の
    // アカウントの会話を渡さないため。
    val chatList: ChatListViewModel? = if (services != null && me != null) {
        viewModel(
            key = "chatList:${me.value}",
            factory = viewModelFactory { initializer { ChatListViewModel(services, me) } },
        )
    } else {
        null
    }

    LaunchedEffect(user) { if (user != null) chatList?.bind(user) }
    // その画面になるたびに一覧を作り直す。
    //
    // スレッドを読むと conversations/{id}/reads/{me} に書くが、これは**サブ
    // コレクション**で、一覧の元になる会話ドキュメントは変わらない。何も再送されず、
    // 読んだばかりのメッセージにバッジが残る。
    //
    // 最初は ChatListScreen の中に Unit を鍵にして置き、スレッドを開いている間は
    // 一覧がコンポジションから外れると思っていた。**外れない**。AnimatedContent が
    // 保持するので2度目が走らず、バッジは消えないままだった。実際に変わるのは
    // 画面の状態なので、そちらを見る。
    LaunchedEffect(screen) {
        if (screen is Screen.ChatList) chatList?.refreshVisibleState()
    }

    /**
     * 通知が指していた会話を開く。
     *
     * id だけでなく一覧も鍵にする。会話が読み込まれる前に通知が来ることがあり
     * （多いのは冷えた起動＝まさに通知で開いたとき）、行が現れるのを待つ必要がある。
     * タップの瞬間に飛ぶのではなく effect にしているのはそのため。
     */
    val conversations by (chatList?.conversations ?: remember { MutableStateFlow(emptyList()) })
        .collectAsStateWithLifecycle()

    LaunchedEffect(openConversationId, conversations) {
        val target = openConversationId ?: return@LaunchedEffect
        val conversation = conversations.firstOrNull { it.id.value == target }
            ?: return@LaunchedEffect
        screen = Screen.Chat(conversation)
        onConversationOpened()
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            // 深いほうへ動くなら進む、浅いほうへ動くなら戻る。同じ深さの入れ替えは
            // 進む扱い（いまその経路は無い）。
            val forward = targetState.depth() >= initialState.depth()
            val duration = RinowaMotion.DURATION_STANDARD
            if (forward) {
                slideInHorizontally(
                    animationSpec = tween(duration, easing = RinowaMotion.standardEasing),
                ) { it / 3 } + fadeIn(tween(duration)) togetherWith
                    slideOutHorizontally(
                        animationSpec = tween(duration, easing = RinowaMotion.standardEasing),
                    ) { -it / 6 } + fadeOut(tween(RinowaMotion.DURATION_QUICK))
            } else {
                slideInHorizontally(
                    animationSpec = tween(duration, easing = RinowaMotion.standardEasing),
                ) { -it / 6 } + fadeIn(tween(duration)) togetherWith
                    slideOutHorizontally(
                        animationSpec = tween(duration, easing = RinowaMotion.exitEasing),
                    ) { it / 3 } + fadeOut(tween(RinowaMotion.DURATION_QUICK))
            }
        },
        label = "screen",
        // 指が下りている間に画面が応える。ここ（入れ物）に付けるので、誰かが
        // 配線を書いた画面だけでなく全部で効く。最初の版は写真ビューアだけが
        // 動いていた。
        modifier = Modifier.backPull(backPull),
    ) { current ->
        when (current) {
            Screen.ChatList -> ChatListScreen(
                viewModel = chatList,
                onOpenConversation = { screen = Screen.Chat(it) },
                onOpenHapticLab = { screen = Screen.HapticLab },
                onNewConversation = { screen = Screen.NewConversation },
                onNewGroup = { screen = Screen.NewGroup },
                photos = services?.photos,
                hasAccount = user != null,
                onOpenAccount = { screen = Screen.Account },
            )

            is Screen.Chat -> if (services != null && me != null) {
                ChatDestination(
                    services = services,
                    stickers = stickers,
                    me = me,
                    conversation = current.conversation,
                    onBack = { screen = Screen.ChatList },
                    onOpenSafety = { screen = Screen.Safety(current.conversation) },
                )
            }

            is Screen.Safety -> {
                val context = LocalContext.current
                val conversation = current.conversation
                // 1対1のときだけ相手が決まる。グループは全員ぶんの端末が並ぶので、
                // 読み合わせの画面としては別の作りが要る。いまは出さない。
                val peer = conversation.memberIds
                    .firstOrNull { it != me }
                    .takeIf { !conversation.isGroup }
                SafetyScreen(
                    peerName = conversation.title,
                    peerId = peer,
                    engineOf = {
                        me?.let { user ->
                            (context.applicationContext as? blog.nextlab.echo.RinowaApplication)
                                ?.cryptoEngine(user)
                        }
                    },
                    known = remember(context) { blog.nextlab.echo.crypto.KnownDevices(context) },
                    onBack = { screen = Screen.Chat(conversation) },
                )
            }

            Screen.NewConversation -> if (chatList != null) {
                NewConversationScreen(
                    viewModel = chatList,
                    onOpened = { id -> screen = openConversation(chatList, id) },
                    onBack = { screen = Screen.ChatList },
                )
            }

            Screen.NewGroup -> if (chatList != null) {
                NewGroupScreen(
                    viewModel = chatList,
                    onCreated = { id -> screen = openConversation(chatList, id) },
                    onBack = { screen = Screen.ChatList },
                )
            }

            Screen.HapticLab -> HapticLabScreen(onBack = { screen = Screen.ChatList })

            Screen.Account -> if (user != null && authViewModel != null) {
                AccountScreen(
                    user = user,
                    inviteCode = chatList?.inviteCode,
                    onSignOut = authViewModel::signOut,
                    onDeleteAccount = {
                        authViewModel.beginDeleteAccount()
                        screen = Screen.DeleteAccount
                    },
                    onOpenFeedback = { screen = Screen.Feedback },
                    onOpenProfile = { screen = Screen.Profile },
                    onOpenPrivacy = { screen = Screen.Privacy },
                    onOpenBackup = { screen = Screen.Backup },
                    onOpenDirectLab = { screen = Screen.DirectLab },
                    onBack = { screen = Screen.ChatList },
                )
            }

            Screen.Backup -> BackupRoute(
                backup = services?.backup,
                me = me,
                onBack = { screen = Screen.Account },
            )

            Screen.DirectLab -> DirectLabScreen(onBack = { screen = Screen.Account })

            Screen.Profile -> if (services != null && me != null) {
                val profile: ProfileViewModel = viewModel(
                    key = "profile:${me.value}",
                    factory = viewModelFactory { initializer { ProfileViewModel(services, me) } },
                )
                ProfileScreen(
                    viewModel = profile,
                    photos = services.photos,
                    me = me,
                    onBack = { screen = Screen.Account },
                )
            }

            Screen.DeleteAccount -> if (user != null && authViewModel != null) {
                DeleteAccountScreen(
                    viewModel = authViewModel,
                    user = user,
                    onBack = { screen = Screen.Account },
                )
            }

            Screen.Feedback -> if (services != null && me != null) {
                val feedback: FeedbackViewModel = viewModel(
                    key = "feedback:${me.value}",
                    factory = viewModelFactory {
                        initializer { FeedbackViewModel(services, analytics, me) }
                    },
                )
                FeedbackScreen(feedback, onBack = { screen = Screen.ChatList })
            }

            Screen.Privacy -> PrivacyScreen(
                analytics = analytics,
                settings = services?.settings,
                onNotificationBodyChanged = { showsBody ->
                    services?.settings?.putLocal(notificationShowsBody = showsBody)
                    // すぐサーバーへ送る。通知に何を出すかはサーバーがこれを読んで
                    // 決めるので、端末内だけの変更では「効いているように見えて効かない
                    // 設定」になる。
                    me?.let { owner -> scope.launch { services?.settings?.push(owner) } }
                },
                onBack = { screen = Screen.Account },
            )
        }
    }
}

/**
 * 作ったばかりの会話へそのまま入る。
 *
 * 一覧がまだサーバーから受け取っていないことがあり、行が一瞬無い。
 * 会話の無い画面を開くより、一覧に落とすほうがまし。
 */
private fun openConversation(viewModel: ChatListViewModel, id: ConversationId): Screen =
    viewModel.conversations.value
        .firstOrNull { it.id == id }
        ?.let { Screen.Chat(it) }
        ?: Screen.ChatList

@Composable
private fun ChatDestination(
    services: RinowaServices,
    stickers: LocalStickerStore,
    me: UserId,
    conversation: Conversation,
    onBack: () -> Unit,
    onOpenSafety: () -> Unit,
) {
    val chat: ChatViewModel = viewModel(
        key = "chat:${conversation.id.value}",
        factory = viewModelFactory {
            initializer { ChatViewModel(services, stickers, me, conversation) }
        },
    )
    ChatScreen(
        viewModel = chat,
        conversation = conversation,
        photos = services.photos,
        onBack = onBack,
        onOpenSafety = onOpenSafety,
    )
}

