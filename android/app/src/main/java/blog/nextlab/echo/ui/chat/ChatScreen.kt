package blog.nextlab.echo.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import blog.nextlab.echo.calls.CallId
import blog.nextlab.echo.calls.CallKind
import blog.nextlab.echo.calls.CallState
import blog.nextlab.echo.calls.IncomingCallService
import blog.nextlab.echo.calls.PendingCall
import blog.nextlab.echo.core.analytics.AnalyticsEvent
import blog.nextlab.echo.core.analytics.AttachmentType
import blog.nextlab.echo.core.analytics.ConversationType
import blog.nextlab.echo.core.analytics.MessageContentKind
import blog.nextlab.echo.core.analytics.StickerKind
import blog.nextlab.echo.core.designsystem.FrostedBar
import blog.nextlab.echo.core.designsystem.RinowaConfirmDialog
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.RinowaSwipe
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.backdropSource
import blog.nextlab.echo.core.designsystem.preferHighFrameRate
import blog.nextlab.echo.core.designsystem.rememberBackdropState
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.data.ProfilePhotos
import blog.nextlab.echo.core.model.Conversation
import blog.nextlab.echo.core.model.Message
import blog.nextlab.echo.core.model.MessageContent
import blog.nextlab.echo.core.model.MessageId
import blog.nextlab.echo.core.model.StickerId
import blog.nextlab.echo.core.model.StickerOrigin
import blog.nextlab.echo.notifications.ActiveConversation
import blog.nextlab.echo.notifications.RinowaMessagingService
import blog.nextlab.echo.ui.LocalAnalytics
import blog.nextlab.echo.ui.LocalCalls
import blog.nextlab.echo.ui.LocalStickers
import blog.nextlab.echo.ui.calls.CallOverlay
import kotlinx.coroutines.launch


/**
 * どこまでを「一番下にいる」とみなすか。
 *
 * リストは反転していて 0 が最新。1〜2件の余裕があると、少しだけずらした人は
 * ついていき、本当に遡った人はその場に留まる。
 */
private const val NEAR_BOTTOM_ITEMS = 2

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    conversation: Conversation,
    photos: ProfilePhotos?,
    onBack: () -> Unit,
    onOpenSafety: () -> Unit = {},
) {
    val colors = RinowaTheme.colors
    val haptics = LocalRinowaHaptics.current
    val analytics = LocalAnalytics.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // スレッドは Firestore から来る。送信はまずローカルキャッシュに書くので、
    // 新しい吹き出しはタップしたフレームでこの流れに乗って現れる。ChatViewModel を参照。
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    var composerText by remember(conversation.id) { mutableStateOf("") }
    var replyingTo by remember(conversation.id) { mutableStateOf<Message?>(null) }

    // 選ばれた Uri は一時的な許可。結果を受け取った context から読むことで、
    // 端末ごとの許可の切り方に依存しない（プロフィール切り抜きで痛い目を見た）。
    val pickerContext = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        haptics.perform(HapticToken.SoftConfirm)
        // 写真か動画かはファイル名ではなくシステムが答える型で判定する。
        // ピッカーの結果は拡張子の無い content:// のこともある。
        val kind = pickerContext.contentResolver.getType(uri).orEmpty()
        if (kind.startsWith("video/")) {
            viewModel.sendVideo(uri, pickerContext, replyingTo)
        } else {
            viewModel.sendPhoto(uri, pickerContext, replyingTo)
        }
        replyingTo = null
    }

    // 開いている会話の通知は消す。開いたときと、開いたまま次が届いたとき。
    // 読んでいる相手に「未読があります」と言い続けないため。
    LaunchedEffect(conversation.id, messages.size) {
        RinowaMessagingService
            .dismiss(pickerContext, conversation.id.value)
    }

    var viewingPhoto by remember(conversation.id) { mutableStateOf<Message?>(null) }

    // 通話。コントローラはこの画面より上にあるので、会話を離れても通話は続く。
    // ここは開始と表示だけ。
    val calls = LocalCalls.current
    val callPeer = remember(conversation.id) {
        conversation.memberIds.firstOrNull { it != viewModel.meId }.takeIf { !conversation.isGroup }
    }
    val incomingCall by viewModel.incomingCall.collectAsStateWithLifecycle()

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val callPermissions = rememberCallPermissions()

    /** 確認を出している通話の種類。null なら出ていない。 */
    var askCall by remember(conversation.id) { mutableStateOf<CallKind?>(null) }

    // ロック画面から応答・拒否した場合。この画面が存在する前に押されている。
    //
    // 着信のフローではなく通知が持っていた id を鍵にする。通知が起こしたプロセスでは
    // フローがまだ何も流していないし、リスナーの調子次第では流れないこともある。
    LaunchedEffect(calls, conversation.id) {
        val controller = calls ?: return@LaunchedEffect
        val answered = PendingCall.consumeAnswer()
        val declined = PendingCall.consumeDecline()

        if (answered != null) {
            val record = viewModel.callById(CallId(answered))
            if (record != null && record.state != CallState.Ended) {
                callPermissions.then(record.kind) { controller.accept(record) }
            }
        } else if (declined != null) {
            viewModel.callById(CallId(declined))?.let(controller::decline)
        }
    }

    // 通話がどこかで処理された瞬間に着信通知を止める（アプリ内で応答した場合も含む）。
    // 画面で出たのに鳴り続けるのは、通話アプリとして一番あからさまに壊れて見える。
    LaunchedEffect(incomingCall, calls?.active) {
        if (incomingCall == null || calls?.active != null) {
            IncomingCallService.stop(pickerContext)
        }
    }

    // 送ったものが読まれた。最初のコンポーズは飛ばす（昨日読まれた分で震えないように）。
    // ChatViewModel.readPulse を参照。
    LaunchedEffect(viewModel.readPulse) {
        if (viewModel.readPulse > 0) haptics.perform(HapticToken.ReadReceipt)
    }

    val stickerStore = LocalStickers.current
    var stickerPanelOpen by remember(conversation.id) { mutableStateOf(false) }
    var stickerPanelOpenedAt by remember { mutableLongStateOf(0L) }
    var stickerBrowsedIndex by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()

    // この画面が前面にある間、そこ宛のメッセージは見られている＝通知しない。
    // ActiveConversation を参照。
    //
    // コンポーズではなくライフサイクルに結び付ける。ホーム画面の裏で開きっぱなしの
    // チャットは読まれていないので、また通知する必要がある。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, conversation.id) {
        val id = conversation.id.value
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> ActiveConversation.enter(id)
                Lifecycle.Event.ON_PAUSE -> ActiveConversation.leave(id)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ActiveConversation.leave(id)
        }
    }

    // この画面が開いている間に届いたものは見られている。
    //
    // ただし画面に入っている必要がある。開いている画面の下端より下に届いたメッセージは、
    // アプリが開いているせいで通知も出ず、何も知らせるものが無い唯一の場合。
    //
    // 追いかけるのは元々下端付近にいたときだけ。遡って読んでいる人を最新まで引っ張ると、
    // 読んでいる途中で画面を奪うことになる。
    var seenCount by remember(conversation.id) { mutableIntStateOf(-1) }
    LaunchedEffect(messages.size) {
        viewModel.markRead()
        val previous = seenCount
        seenCount = messages.size
        if (previous < 0 || messages.size <= previous) return@LaunchedEffect

        // 自分のメッセージは常に追う。送信時にもスクロールしているが、その時点では
        // まだメッセージが存在せず（Firestore が返すのは少しあと）、1つ前の最新で止まる。
        // 実際にメッセージがあるのはここ。
        //
        // 相手のメッセージは、下端にいるときだけ追う。
        val newest = messages.lastOrNull() ?: return@LaunchedEffect
        if (newest.isOutgoing || listState.firstVisibleItemIndex <= NEAR_BOTTOM_ITEMS) {
            listState.animateScrollToItem(0)
        }
    }

    val conversationType =
        if (conversation.isGroup) ConversationType.Group else ConversationType.Direct

    val thresholdPx = with(density) { RinowaSwipe.ThresholdDistance.toPx() }
    val maxSwipePx = with(density) { RinowaSwipe.MaxDistance.toPx() }

    // ---- リアクションの選択 ------------------------------------------------------
    val bubbleBounds = remember { mutableMapOf<MessageId, Rect>() }
    var picker by remember { mutableStateOf<ReactionPickerState?>(null) }

    // 実行前に確認する。取り消しは戻せないし、既読のメッセージでは相手の会話に跡が残る。
    var retracting by remember { mutableStateOf<Message?>(null) }

    retracting?.let { target ->
        RinowaConfirmDialog(
            title = "送信を取り消しますか",
            // このあと何が起きるかは説明しない。結果は2通りあるが、ここで選んでいるのは
            // 「取り消すかどうか」だけで、跡が残る話は読み物であって判断材料ではない。
            message = "",
            confirmLabel = "取り消す",
            destructive = true,
            onDismiss = { retracting = null },
            onConfirm = {
                retracting = null
                viewModel.retract(target.id) { leftMark ->
                    // 別々の出来事なので手触りも分ける。丸ごと消えたのか、跡に置き換わったのか。
                    haptics.perform(
                        if (leftMark) HapticToken.Warning else HapticToken.Destructive,
                    )
                }
            },
        )
    }
    var hoveredIndices by remember { mutableStateOf(setOf<Int>()) }
    var pickerOpenedAt by remember { mutableLongStateOf(0L) }

    // 長押しが落ちた場所と、そこから意図といえる距離だけ動いたか。指は選択列の真下の
    // 吹き出しの上にあるので、近さだけで判定すると出た瞬間に選ばれてしまう。
    // 意図は「押した場所」ではなく「動き」で表す。
    var pickerOrigin by remember { mutableStateOf(Offset.Zero) }
    var pickerEngaged by remember { mutableStateOf(false) }

    val geometry = rememberPickerGeometry()

    // ---- すりガラスのバー --------------------------------------------------------
    val backdrop = rememberBackdropState()
    var topBarHeight by remember { mutableStateOf(0.dp) }
    var bottomBarHeight by remember { mutableStateOf(0.dp) }

    // スクロール位置はバーを描くときに読む。コンポーザブル本体で読むと、フリックの
    // 1フレームごとに画面全体が再コンポーズされる。
    val backdropInvalidation: () -> Unit = {
        listState.firstVisibleItemIndex
        listState.firstVisibleItemScrollOffset
        messages.size
    }

    // 焦点は切り替えではなく出し入れする。フリックが止まった瞬間にぼけが飛び付かない
    // ように、ぼけが引き終わるまで撮り続ける。
    val frost = remember { Animatable(1f) }
    var capturingBackdrop by remember { mutableStateOf(true) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            frost.animateTo(0f, tween(130, easing = RinowaMotion.exitEasing))
            capturingBackdrop = false
        } else {
            capturingBackdrop = true
            frost.animateTo(1f, tween(280, easing = RinowaMotion.standardEasing))
        }
    }
    val frostAmount: () -> Float = { frost.value }

    fun closePicker(committedIndex: Int) {
        val state = picker ?: return
        val openMs = System.currentTimeMillis() - pickerOpenedAt
        if (committedIndex >= 0) {
            haptics.perform(HapticToken.Reaction)
            // 一人1つと、押し直しで外す動作は ViewModel 側にある。同じ規則は
            // firestore.rules でも効いていて、自分のキー以外は触れない。
            viewModel.react(state.messageId, committedIndex)
            analytics.log(AnalyticsEvent.ReactionSelected(committedIndex, openMs))
            analytics.log(AnalyticsEvent.ReactionAdded(committedIndex, conversationType))
        } else {
            analytics.log(AnalyticsEvent.ReactionPickerDismissed(openMs, hoveredIndices.size))
        }
        picker = null
        hoveredIndices = emptySet()
    }

    /**
     * @param local 指の位置（長押しのとき）。リアクションを押して開いたときは null で、
     *   滑らせる指が無いので最初から選べる状態で出る。
     */
    fun showPicker(message: Message, local: Offset?, holdMs: Long?) {
        val bounds = bubbleBounds[message.id] ?: return
        haptics.perform(HapticToken.SoftConfirm)

        pickerOrigin = local?.let { Offset(bounds.left + it.x, bounds.top + it.y) } ?: Offset.Zero
        pickerEngaged = false

        val left = ReactionPickerMetrics.leftPx(
            anchorCenterX = bounds.center.x,
            widthPx = geometry.pillWidthPx,
            screenWidthPx = geometry.screenWidthPx,
            marginPx = geometry.marginPx,
        )
        val above = bounds.top - geometry.pillHeightPx - geometry.gapPx
        // 上に余裕が無ければ吹き出しの下に出す。
        val top = if (above < geometry.topLimitPx) bounds.bottom + geometry.gapPx else above

        pickerOpenedAt = System.currentTimeMillis()
        hoveredIndices = emptySet()
        picker = ReactionPickerState(
            messageId = message.id,
            anchorBounds = bounds,
            pillLeftPx = left,
            pillTopPx = top,
            highlightedIndex = -1,
            alreadyReactedIndex = message.reactions.firstOrNull { it.mine }?.paletteIndex,
            latched = local == null,
        )
        holdMs?.let { analytics.log(AnalyticsEvent.MessageLongPressed(it)) }
        analytics.log(AnalyticsEvent.ReactionPickerOpened)
    }

    fun trackPicker(message: Message, local: Offset) {
        val state = picker ?: return
        if (state.latched) return
        val bounds = bubbleBounds[message.id] ?: return
        val root = Offset(bounds.left + local.x, bounds.top + local.y)

        // 意図といえる距離を動くまで何も選ばない。置いたままの指（誰の指も少し震える）で
        // 選ばれてはいけない。
        if (!pickerEngaged) {
            if ((root - pickerOrigin).getDistance() < geometry.engageSlopPx) return
            pickerEngaged = true
        }

        val index = ReactionPickerMetrics.indexFor(
            pointer = root,
            pillLeftPx = state.pillLeftPx,
            pillTopPx = state.pillTopPx,
            pillHeightPx = geometry.pillHeightPx,
            itemSizePx = geometry.itemSizePx,
            itemGapPx = geometry.itemGapPx,
            innerPaddingPx = geometry.innerPaddingPx,
            toleranceAbovePx = geometry.toleranceAbovePx,
            toleranceBelowPx = geometry.toleranceBelowPx,
        )
        if (index != state.highlightedIndex) {
            if (index >= 0) {
                // 弱く、間隔を空けて。指はまだ動いている。
                haptics.perform(HapticToken.Selection)
                hoveredIndices = hoveredIndices + index
            }
            picker = state.copy(highlightedIndex = index)
        }
    }

    /**
     * 指が離れた。リアクションの上なら確定、そうでなければ開いたままにして、押して
     * 選べるようにする。離しただけで取り消さない — 押しながら滑らせるのは近道であって
     * 必須ではない。
     */
    fun releasePicker() {
        val state = picker ?: return
        if (state.highlightedIndex >= 0) {
            closePicker(state.highlightedIndex)
        } else {
            picker = state.copy(latched = true)
        }
    }

    fun sendMessage() {
        val body = composerText.trim()
        if (body.isEmpty()) return

        // 押した瞬間、吹き出しが動き始めるのと同じフレームで鳴らす。
        haptics.perform(HapticToken.Send)
        val wasReply = replyingTo != null
        val startedAt = System.currentTimeMillis()
        viewModel.sendText(body, replyingTo)
        replyingTo = null
        scope.launch { listState.animateScrollToItem(0) }

        analytics.log(
            AnalyticsEvent.MessageSent(
                // 計測に渡るのは文字数だけ。本文は渡らない（この API に文字列を受ける
                // 引数が無い）。
                characterCount = body.length,
                contentKind = MessageContentKind.Text,
                conversationType = conversationType,
                isReply = wasReply,
                attachmentType = AttachmentType.None,
                deliveryLatencyMs = System.currentTimeMillis() - startedAt,
                sendSuccess = true,
            ),
        )
        if (wasReply) analytics.log(AnalyticsEvent.ReplySent(conversationType))

        composerText = ""
    }

    fun sendSticker(stickerId: StickerId) {
        haptics.perform(HapticToken.Send)
        val wasReply = replyingTo != null
        val startedAt = System.currentTimeMillis()
        // メッセージが持つのは id だけ。docs/STICKER_ARCHITECTURE.md。
        viewModel.sendSticker(stickerId, replyingTo)
        replyingTo = null
        scope.launch { listState.animateScrollToItem(0) }

        analytics.log(
            AnalyticsEvent.MessageSent(
                characterCount = 0,
                contentKind = MessageContentKind.Sticker,
                conversationType = conversationType,
                isReply = wasReply,
                attachmentType = AttachmentType.None,
                deliveryLatencyMs = System.currentTimeMillis() - startedAt,
                sendSuccess = true,
            ),
        )
        // 種類だけ。スタンプの id は渡さない。
        analytics.log(
            AnalyticsEvent.StickerSent(
                stickerKind = if (stickerStore.asset(stickerId)?.origin == StickerOrigin.BuiltIn) {
                    StickerKind.BuiltIn
                } else {
                    StickerKind.Custom
                },
                conversationType = conversationType,
                isReply = wasReply,
            ),
        )
        if (wasReply) analytics.log(AnalyticsEvent.ReplySent(conversationType))

        analytics.log(
            AnalyticsEvent.StickerPickerDismissed(
                openMs = System.currentTimeMillis() - stickerPanelOpenedAt,
                browsedCount = stickerBrowsedIndex,
                sentSticker = true,
            ),
        )
        stickerPanelOpen = false
    }

    fun toggleStickerPanel() {
        haptics.perform(HapticToken.SoftConfirm)
        if (stickerPanelOpen) {
            analytics.log(
                AnalyticsEvent.StickerPickerDismissed(
                    openMs = System.currentTimeMillis() - stickerPanelOpenedAt,
                    browsedCount = stickerBrowsedIndex,
                    sentSticker = false,
                ),
            )
            stickerPanelOpen = false
        } else {
            stickerPanelOpen = true
            stickerPanelOpenedAt = System.currentTimeMillis()
            stickerBrowsedIndex = 0
            analytics.log(AnalyticsEvent.StickerPickerOpened)
        }
    }

    // 鍵はリスト自体。Firestore が流したときだけ変わり、入力のたびには変わらない。
    val chatItems = remember(messages) { buildChatItems(messages) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // スレッドは画面いっぱいで、上下のバーの下にも潜り込む（だからぼかす意味がある）。
        // 端まで届くように contentPadding で逃がす。
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .fillMaxSize()
                // 実際に動いている間だけ高リフレッシュレートを要求する。
                .preferHighFrameRate(listState.isScrollInProgress)
                // 選択列は常にすりガラスなので、開いている間は撮り続ける。
                .backdropSource(backdrop, capture = capturingBackdrop || picker != null),
            contentPadding = PaddingValues(
                start = RinowaDimens.screenPadding,
                end = RinowaDimens.screenPadding,
                top = topBarHeight + 8.dp,
                bottom = bottomBarHeight + 8.dp,
            ),
        ) {
                items(chatItems.asReversed(), key = { it.message.id.value }) { item ->
                    val message = item.message

                    Column {
                        item.dayHeader?.let { DaySeparator(it) }

                        MessageRow(
                            message = message,
                            isFirstOfGroup = item.isFirstOfGroup,
                            isLastOfGroup = item.isLastOfGroup,
                            dimmed = picker != null && picker?.messageId != message.id,
                            raised = picker?.messageId == message.id,
                            thresholdPx = thresholdPx,
                            maxPx = maxSwipePx,
                            // ここで mediaRevision を読むから、あとから届いた写真が出る。
                            // 取得は非同期で、コンポーズ内で state を読まないと再描画されない。
                            mediaProvider = { id, key ->
                                @Suppress("UNUSED_EXPRESSION") viewModel.mediaRevision
                                viewModel.cachedMedia(id)
                                    ?: null.also { viewModel.requestMedia(id, key) }
                            },
                            // 通話の記録はタップしてもかけ直さない。
                            //
                            // 以前はかけ直していたが、行はスクロールの途中にあって
                            // 押し間違いの代償が「相手の電話が鳴る」。会話の中で1タップ
                            // でそこまで外に出る操作は他に無い。発信はヘッダーに置く。
                            callbacks = MessageRowCallbacks(
                                onReply = { replyingTo = message },
                                onSwipeStarted = {
                                    analytics.log(AnalyticsEvent.ReplySwipeStarted)
                                },
                                onThresholdReached = { ms ->
                                    analytics.log(AnalyticsEvent.ReplySwipeThresholdReached(ms))
                                },
                                onSwipeCancelled = { percent, everPast ->
                                    analytics.log(
                                        AnalyticsEvent.ReplySwipeCancelled(percent, everPast),
                                    )
                                },
                                onSwipeCompleted = { ms ->
                                    analytics.log(AnalyticsEvent.ReplySwipeCompleted(ms))
                                },
                                onLongPressStart = { local, holdMs ->
                                    showPicker(message, local, holdMs)
                                },
                                onLongPressMove = { local -> trackPicker(message, local) },
                                onLongPressFinish = { releasePicker() },
                                onBoundsChanged = { bounds -> bubbleBounds[message.id] = bounds },
                                onPhotoClick = { viewingPhoto = message },
                                onVideoClick = { viewingPhoto = message },
                                onReactionChipClick = { showPicker(message, null, null) },
                            ),
                            senderPhoto = viewModel.senderProfiles[message.senderId]?.let {
                                // revision を読むことで、描いたあとに届いた画像も出る。
                                // ProfilePhotos.revision を参照。
                                photos?.revision
                                photos?.photo(it.id, it.photoHash)
                            },
                            senderName = viewModel.senderProfiles[message.senderId]
                                ?.displayName.orEmpty(),
                        )
                    }
                }
            }

        FrostedBar(
            state = backdrop,
            tint = colors.barGlassTint,
            frostAmount = frostAmount,
            invalidateOn = backdropInvalidation,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onGloballyPositioned {
                    bottomBarHeight = with(density) { it.size.height.toDp() }
                },
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                if (!viewModel.accepted) {
                    // 上のスレッドは読める。何に招かれたのか見えない招待は意味が無い。
                    // 制限しているのは、承諾するまで返信できないことだけ。
                    AcceptInvitation(
                        name = conversation.title,
                        isGroup = conversation.isGroup,
                        onAccept = {
                            haptics.perform(HapticToken.Success)
                            viewModel.accept()
                        },
                    )
                    return@Column
                }

                // 準備中も失敗も画面に出す。大きな写真のデコードには間があり、押しても
                // 何も起きないと壊れたボタンに見える。失敗して何も出ないのはもっと悪い。
                //
                // 文言はプラットフォームの言葉のまま出す。プロフィール写真のときに、
                // 握り潰した例外を「画像を読めません」に置き換えたせいで3件誤診した。
                if (viewModel.preparingPhoto || viewModel.uploadingPhoto ||
                    viewModel.videoProgress != null ||
                    viewModel.photoError != null || viewModel.callError != null
                ) {
                    val failed = viewModel.photoError ?: viewModel.callError
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (failed != null) colors.danger.copy(alpha = 0.14f)
                                else colors.surfaceSunken,
                            )
                            .clickable(enabled = failed != null) {
                                viewModel.dismissPhotoError()
                                viewModel.dismissCallError()
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        // ぐるぐるではなく％。動画は数十秒かかり、止まって見える円は
                        // ハングと区別が付かず、ハングだと思った人はもう一度送信を押す。
                        val video = viewModel.videoProgress
                        Text(
                            text = failed
                                ?: when {
                                    video != null && video < 50 -> "動画を変換しています… " + (video * 2) + "%"
                                    video != null -> "動画を送っています… " + ((video - 50) * 2) + "%"
                                    viewModel.uploadingPhoto -> "写真を送っています…"
                                    else -> "写真を準備しています…"
                                },
                            style = RinowaTheme.type.labelSmall,
                            color = if (failed != null) colors.danger else colors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        if (failed != null) {
                            Text(
                                text = "閉じる",
                                style = RinowaTheme.type.labelSmall,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }

                Composer(
                    text = composerText,
                    onTextChange = { composerText = it },
                    replyingTo = replyingTo,
                    onCancelReply = {
                        haptics.perform(HapticToken.SoftConfirm)
                        replyingTo = null
                    },
                    onSend = { sendMessage() },
                    onAttachmentClick = {
                        haptics.perform(HapticToken.SoftConfirm)
                        analytics.log(AnalyticsEvent.AttachmentPickerOpened)
                        photoPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
                        )
                    },
                    stickerPickerOpen = stickerPanelOpen,
                    onToggleStickerPicker = { toggleStickerPanel() },
                )

                AnimatedVisibility(
                    visible = stickerPanelOpen,
                    enter = expandVertically(animationSpec = RinowaMotion.settleSpring()),
                    exit = shrinkVertically(animationSpec = RinowaMotion.settleSpring()),
                ) {
                    StickerPanel(
                        store = stickerStore,
                        onSelect = { id -> sendSticker(id) },
                        onBrowsed = { index -> stickerBrowsedIndex = maxOf(stickerBrowsedIndex, index) },
                    )
                }
            }
        }

        FrostedBar(
            state = backdrop,
            tint = colors.barGlassTint,
            frostAmount = frostAmount,
            invalidateOn = backdropInvalidation,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onGloballyPositioned {
                    topBarHeight = with(density) { it.size.height.toDp() }
                },
        ) {
            ChatTopBar(
                conversation = conversation,
                onBack = {
                    haptics.perform(HapticToken.Navigation)
                    onBack()
                },
                onOpenSafety = {
                    haptics.perform(HapticToken.Selection)
                    onOpenSafety()
                },
                // いまは1対1だけ。グループ通話は接続の形が別で、たまに何もしない
                // ボタンを置くより出さないほうがよい。
                //
                // 押しても発信しない。相手の端末を鳴らすのは取り消せないので、一度
                // 訊く。入力中に押されることもあるので、キーボードもここで閉じる
                // （残っていると、確認の文面が半分隠れる）。
                onCall = if (callPeer != null && calls != null) {
                    {
                        haptics.perform(HapticToken.SoftConfirm)
                        keyboard?.hide()
                        focus.clearFocus()
                        askCall = CallKind.Audio
                    }
                } else {
                    null
                },
                onVideoCall = if (callPeer != null && calls != null) {
                    {
                        haptics.perform(HapticToken.SoftConfirm)
                        keyboard?.hide()
                        focus.clearFocus()
                        askCall = CallKind.Video
                    }
                } else {
                    null
                },
            )
        }

        viewingPhoto?.let { message ->
            ChatPhotoViewer(
                opened = message,
                messages = messages,
                viewModel = viewModel,
                context = pickerContext,
                onClose = { viewingPhoto = null },
            )
        }

        // 何よりも上。鳴っている電話は、そのとき画面に出ていたものより優先される。
        if (calls != null) {
            CallOverlay(
                controller = calls,
                peerName = conversation.title,
                incoming = incomingCall,
                onRequestMicrophone = {
                    val controller = calls
                    val record = incomingCall
                    if (record != null) {
                        callPermissions.then(record.kind) { controller.accept(record) }
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = picker != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            val latched = picker?.latched == true
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.scrim)
                    .then(
                        // 選べる状態になってからだけ押せる。指が下りている間は
                        // ジェスチャー側のもの。
                        if (latched) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { closePicker(-1) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }

        askCall?.let { kind ->
            val controller = calls
            val target = callPeer
            RinowaConfirmDialog(
                title = if (kind == CallKind.Video) "ビデオ通話を発信しますか" else "音声通話を発信しますか",
                message = conversation.title + " の端末が鳴ります。",
                confirmLabel = "発信する",
                onConfirm = {
                    askCall = null
                    haptics.perform(HapticToken.SoftConfirm)
                    if (controller != null && target != null) {
                        callPermissions.then(kind) { controller.place(conversation.id, target, kind) }
                    }
                },
                onDismiss = { askCall = null },
            )
        }

        picker?.let { state ->
            val target = messages.firstOrNull { it.id == state.messageId }
            ReactionPickerOverlay(
                state = state,
                backdrop = backdrop,
                onSelect = { index -> closePicker(index) },
                // 自分のもので、まだ残っているものだけ。他人のメッセージにはリアクションだけ。
                onRetract = if (
                    target != null &&
                    target.isOutgoing &&
                    target.content !is MessageContent.Retracted
                ) {
                    {
                        closePicker(-1)
                        retracting = target
                    }
                } else {
                    null
                },
            )
        }
    }
}
