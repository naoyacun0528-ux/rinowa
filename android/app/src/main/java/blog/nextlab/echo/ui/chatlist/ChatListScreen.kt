package blog.nextlab.echo.ui.chatlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.analytics.AnalyticsEvent
import blog.nextlab.echo.core.designsystem.RinowaColors
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.RinowaTypography
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.GlassSurface
import blog.nextlab.echo.core.designsystem.GlassTone
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.designsystem.preferHighFrameRate
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.model.Conversation
import blog.nextlab.echo.ui.LocalAnalytics
import blog.nextlab.echo.ui.common.Avatar
import blog.nextlab.echo.ui.common.formatListTime

@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel?,
    onOpenConversation: (Conversation) -> Unit,
    onOpenHapticLab: () -> Unit,
    onNewConversation: () -> Unit,
    onNewGroup: () -> Unit,
    photos: blog.nextlab.echo.data.ProfilePhotos? = null,
    hasAccount: Boolean = false,
    onOpenAccount: () -> Unit = {},
) {
    val colors = RinowaTheme.colors
    val haptics = LocalRinowaHaptics.current
    val analytics = LocalAnalytics.current
    val conversations by (viewModel?.conversations ?: remember { MutableStateFlow(emptyList()) })
        .collectAsStateWithLifecycle()


    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ChatListTopBar(
            onOpenHapticLab = {
                haptics.perform(HapticToken.Navigation)
                onOpenHapticLab()
            },
            hasAccount = hasAccount,
            onOpenAccount = {
                haptics.perform(HapticToken.Navigation)
                onOpenAccount()
            },
            // 消すものがあるときだけ出す。何もしない操作は、無い操作より悪い
            // （押させておいて、何も起きなかったと言うことになる）。
            hasUnread = viewModel?.hasUnread == true,
            onMarkAllRead = {
                viewModel?.markAllRead { count ->
                    // SoftConfirm ではなく Success。全部のバッジを一度に消す、
                    // 切り替えより大きな操作なので、そう着地させる。
                    if (count > 0) haptics.perform(HapticToken.Success)
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .preferHighFrameRate(listState.isScrollInProgress),
                contentPadding = PaddingValues(bottom = 92.dp),
            ) {
                items(conversations, key = { it.id.value }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        photo = viewModel?.let { model ->
                            // revision を読むことで、描いたあとに届いた画像も出る。
                            // ProfilePhotos.revision を参照。
                            photos?.let {
                                it.revision
                                model.counterpart(conversation)
                                    ?.let { profile -> it.photo(profile.id, profile.photoHash) }
                            }
                        },
                        onClick = {
                            haptics.perform(HapticToken.Navigation)
                            analytics.log(AnalyticsEvent.ChatOpened(conversation.unreadCount))
                            onOpenConversation(conversation)
                        },
                    )
                }
            }

            // 空の一覧は新しいアカウントが始まる状態なので、次に何をするかを言う必要が
            // ある。真っ白な画面は、読み込みに失敗したように読める。
            if (conversations.isEmpty() && viewModel?.loading == false) {
                EmptyConversations(onNewConversation)
            }

            if (viewModel != null) {
                ComposeMenu(
                    onNewConversation = onNewConversation,
                    onNewGroup = onNewGroup,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = RinowaDimens.glassCardMargin, bottom = 16.dp),
                )
            }
        }

        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun EmptyConversations(onNewConversation: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "まだ会話がありません",
            style = type.listName,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "招待コードを交換すると、相手と話しはじめられます。",
            style = type.listPreview,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onNewConversation)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(text = "会話をはじめる", style = type.label, color = colors.accent)
        }
    }
}

/**
 * ＋と、その先。
 *
 * 「どちらでしたか」と尋ねる1画面ではなく、行き先を2つにする。人を追加するのと
 * グループを作るのは、ボタンを押す*前*に決まっている別々の意図なので、選択は
 * 押した瞬間にある。1段あとの、読んでみて初めて場所違いと分かる画面ではない。
 *
 * ＋は開くときに閉じる印へ変わる。新しい操作が上に現れるのではなく、同じ操作の
 * もう一方の状態。
 */
@Composable
private fun ComposeMenu(
    onNewConversation: () -> Unit,
    onNewGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RinowaTheme.colors
    val haptics = LocalRinowaHaptics.current
    var expanded by remember { mutableStateOf(false) }

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = RinowaMotion.popSpring(),
        label = "plusRotation",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(RinowaMotion.DURATION_QUICK)) +
                expandVertically(animationSpec = RinowaMotion.popSpring()),
            exit = fadeOut(tween(RinowaMotion.DURATION_INSTANT)) +
                shrinkVertically(animationSpec = RinowaMotion.settleSpring()),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                ComposeMenuItem("友達を追加") {
                    haptics.perform(HapticToken.Navigation)
                    expanded = false
                    onNewConversation()
                }
                Spacer(Modifier.height(8.dp))
                ComposeMenuItem("グループを作る") {
                    haptics.perform(HapticToken.Navigation)
                    expanded = false
                    onNewGroup()
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        GlassSurface(
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            tone = GlassTone.Control,
            onClick = {
                haptics.perform(HapticToken.SoftConfirm)
                expanded = !expanded
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = rotation },
            ) {
                val stroke = Stroke(
                    width = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.22f)
                    lineTo(w * 0.5f, h * 0.78f)
                    moveTo(w * 0.22f, h * 0.5f)
                    lineTo(w * 0.78f, h * 0.5f)
                }
                drawPath(path, colors.accent, style = stroke)
            }
        }
    }
}

@Composable
private fun ComposeMenuItem(label: String, onClick: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Box(
        modifier = Modifier
            .glassFace(shape = RoundedCornerShape(14.dp), elevation = 3.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = type.label, color = colors.textPrimary)
    }
}

@Composable
private fun ChatListTopBar(
    onOpenHapticLab: () -> Unit,
    hasAccount: Boolean,
    onOpenAccount: () -> Unit,
    hasUnread: Boolean,
    onMarkAllRead: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = RinowaDimens.screenPadding, end = 6.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                text = "RINOWA",
                style = type.screenTitle.copy(fontWeight = FontWeight.Bold),
                color = colors.textPrimary,
            )
            Spacer(Modifier.weight(1f))

            AnimatedVisibility(
                visible = hasUnread,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onMarkAllRead)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "すべて既読",
                        style = type.labelSmall,
                        color = colors.accent,
                    )
                }
            }

            // Prototype 0 限定。触覚 Lab は開発用の画面で、製品の機能ではない。
            Box(
                modifier = Modifier
                    .size(RinowaDimens.touchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenHapticLab),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val stroke = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                    val w = size.width
                    val h = size.height
                    // 3本の波紋。Rinowa の印を、そのまま Lab の入口として使う。
                    val path = Path().apply {
                        moveTo(w * 0.30f, h * 0.34f)
                        quadraticTo(w * 0.14f, h * 0.5f, w * 0.30f, h * 0.66f)
                        moveTo(w * 0.52f, h * 0.20f)
                        quadraticTo(w * 0.30f, h * 0.5f, w * 0.52f, h * 0.80f)
                        moveTo(w * 0.74f, h * 0.10f)
                        quadraticTo(w * 0.46f, h * 0.5f, w * 0.74f, h * 0.90f)
                    }
                    drawPath(path, colors.textSecondary, style = stroke)
                }
            }

            if (hasAccount) {
                Box(
                    modifier = Modifier
                        .size(RinowaDimens.touchTarget)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenAccount),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(22.dp)) {
                        val stroke = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        )
                        val w = size.width
                        val h = size.height
                        drawCircle(
                            color = colors.textSecondary,
                            radius = w * 0.20f,
                            center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.34f),
                            style = stroke,
                        )
                        // 丸ではなく肩。この大きさでは、弧の上に頭があると人に見え、
                        // 胴の輪郭だと塊に見える。
                        val shoulders = Path().apply {
                            moveTo(w * 0.18f, h * 0.86f)
                            quadraticTo(w * 0.5f, h * 0.56f, w * 0.82f, h * 0.86f)
                        }
                        drawPath(shoulders, colors.textSecondary, style = stroke)
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineSoft),
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    photo: ImageBitmap?,
    onClick: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val unread = conversation.unreadCount > 0

    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = RinowaDimens.glassCardMargin,
                vertical = RinowaDimens.glassCardGap / 2,
            ),
        onClick = onClick,
    ) {
        ConversationRowContent(conversation, colors, type, unread, photo)
    }
}

@Composable
private fun ConversationRowContent(
    conversation: Conversation,
    colors: RinowaColors,
    type: RinowaTypography,
    unread: Boolean,
    photo: ImageBitmap?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = RinowaDimens.listItemPadding,
                vertical = RinowaDimens.listItemPadding,
            ),
    ) {
        Avatar(title = conversation.title, seed = conversation.avatarSeed, photo = photo)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title,
                    style = type.listName,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatListTime(conversation.lastTimestampMs),
                    style = type.messageMeta,
                    color = if (unread) colors.accent else colors.textTertiary,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (conversation.previewIsOutgoing) {
                        "自分: ${conversation.preview.value}"
                    } else {
                        conversation.preview.value
                    },
                    style = type.listPreview,
                    color = if (unread) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 行を埋める。未読のバッジが、プレビューの長さで左右に動かず
                    // 常に右端に来る。
                    modifier = Modifier.weight(1f),
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    UnreadBadge(conversation.unreadCount)
                }
            }
        }
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Box(
        modifier = Modifier
            .height(19.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(colors.accent)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = type.labelSmall,
            color = colors.onAccent,
        )
    }
}
