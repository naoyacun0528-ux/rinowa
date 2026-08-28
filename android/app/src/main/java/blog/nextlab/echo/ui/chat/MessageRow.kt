package blog.nextlab.echo.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.preferHighFrameRate
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.model.MediaId
import blog.nextlab.echo.model.Message
import blog.nextlab.echo.model.MessageContent
import blog.nextlab.echo.model.MessageStatus
import blog.nextlab.echo.model.previewText
import androidx.compose.ui.graphics.ImageBitmap
import blog.nextlab.echo.ui.LocalStickers
import blog.nextlab.echo.ui.common.Avatar
import blog.nextlab.echo.ui.common.formatClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val affordanceSize = 30.dp
private val affordanceGap = 12.dp

/** 閾値を越えたあと、離すと判定されるまでに戻る割合。 */
/** 一覧のアイコンより小さい。吹き出しの上ではなく横に並ぶため。 */
private val AVATAR_SIZE = 34.dp

private const val THRESHOLD_HYSTERESIS = 0.78f

class MessageRowCallbacks(
    val onReply: () -> Unit = {},
    val onSwipeStarted: () -> Unit = {},
    val onThresholdReached: (timeToThresholdMs: Long) -> Unit = {},
    val onSwipeCancelled: (maxDragPercent: Int, everPastThreshold: Boolean) -> Unit = { _, _ -> },
    val onSwipeCompleted: (durationMs: Long) -> Unit = {},
    val onLongPressStart: (localPosition: Offset, holdMs: Long) -> Unit = { _, _ -> },
    val onLongPressMove: (localPosition: Offset) -> Unit = {},
    val onLongPressFinish: () -> Unit = {},
    val onBoundsChanged: (Rect) -> Unit = {},
    /** 吹き出しの下のリアクションを押した。 */
    val onReactionChipClick: () -> Unit = {},
    /** 写真を押した。全画面で開く。 */
    val onPhotoClick: () -> Unit = {},
    val onVideoClick: () -> Unit = {},
)

/**
 * メッセージ1件。
 *
 * スワイプ量は行ごとに持つ。画面側で共有していたとき、別の吹き出しの戻りアニメーションが
 * 走っている最中にスワイプを始めると、同じ値を2箇所が書いて途中で戻された。
 */
@Composable
fun MessageRow(
    message: Message,
    isFirstOfGroup: Boolean,
    isLastOfGroup: Boolean,
    dimmed: Boolean,
    raised: Boolean,
    thresholdPx: Float,
    maxPx: Float,
    callbacks: MessageRowCallbacks,
    modifier: Modifier = Modifier,
    /** 送信者の写真。null なら頭文字。 */
    senderPhoto: ImageBitmap? = null,
    senderName: String = "",
    /**
     * この行の写真。端末が持っていれば返る。
     *
     * 値ではなく関数なのは、画面に出ている数枚だけ要求するため。null は
     * 「取りに行け」の合図で、取得は呼び出し側がやる。
     */
    /** id と鍵の両方を渡す。片方だけでは取り出せない。 */
    mediaProvider: (MediaId, ByteArray?) -> ImageBitmap? = { _, _ -> null },
) {
    val colors = RinowaTheme.colors
    val haptics = LocalRinowaHaptics.current
    val scope = rememberCoroutineScope()
    val maxWidth = LocalConfiguration.current.screenWidthDp.dp * RinowaDimens.bubbleMaxWidthFraction

    // 取り消したものはもうメッセージではないので、吹き出しも左右も返信スワイプも付けない。
    // 日付の区切りと同じく、会話についての注記として中央に置く。
    if (message.content is MessageContent.Retracted) {
        RetractedNotice(
            // 誰のものだったか。グループで「誰かが取り消しました」だと疑問だけが残る。
            senderName = if (message.isOutgoing) {
                null
            } else {
                senderName.ifEmpty { message.senderName }
            },
            modifier = modifier,
        )
        return
    }

    (message.content as? MessageContent.Call)?.let { call ->
        CallNotice(
            call = call,
            isOutgoing = message.isOutgoing,
            timestampMs = message.timestampMs,
            modifier = modifier,
        )
        return
    }

    // callbacks は再コンポーズのたびに作り直されるが、ジェスチャー側は remember される。
    // 古い組を握り続けないように state 経由で読む。
    val cb by rememberUpdatedState(callbacks)

    var offsetPx by remember { mutableFloatStateOf(0f) }
    var pastThreshold by remember { mutableStateOf(false) }
    var swipeStartedAt by remember { mutableLongStateOf(0L) }
    var releaseJob by remember { mutableStateOf<Job?>(null) }

    val handlers = remember(message.id) {
        MessageGestureHandlers(
            onSwipeStart = {
                // 戻り中なら止める。同じ値を2つが書かないように。
                releaseJob?.cancel()
                releaseJob = null
                offsetPx = 0f
                pastThreshold = false
                swipeStartedAt = System.currentTimeMillis()
                cb.onSwipeStarted()
            },
            onSwipeUpdate = { px -> offsetPx = px },
            onThresholdEnter = {
                pastThreshold = true
                // ドラッグ中に鳴るのはこれだけ。
                haptics.perform(HapticToken.Threshold)
                cb.onThresholdReached(System.currentTimeMillis() - swipeStartedAt)
            },
            onThresholdExit = {
                pastThreshold = false
                haptics.perform(HapticToken.ThresholdRelease)
            },
            onSwipeFinish = { committed, maxRatio, everPast, durationMs ->
                if (committed) {
                    cb.onReply()
                    cb.onSwipeCompleted(durationMs)
                } else {
                    cb.onSwipeCancelled((maxRatio * 100).toInt(), everPast)
                }
                releaseJob = scope.launch {
                    animate(
                        initialValue = offsetPx,
                        targetValue = 0f,
                        animationSpec = RinowaMotion.commitSpring(),
                    ) { value, _ -> offsetPx = value }
                    pastThreshold = false
                    releaseJob = null
                }
            },
            onLongPressStart = { local, holdMs -> cb.onLongPressStart(local, holdMs) },
            onLongPressMove = { local -> cb.onLongPressMove(local) },
            onLongPressFinish = { cb.onLongPressFinish() },
        )
    }

    val dimAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.45f else 1f,
        animationSpec = RinowaMotion.settleSpring(),
        label = "dim",
    )
    val raiseScale by animateFloatAsState(
        targetValue = if (raised) 1.04f else 1f,
        animationSpec = RinowaMotion.popSpring(),
        label = "raise",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = if (isFirstOfGroup) RinowaDimens.bubbleGapNewSender else RinowaDimens.bubbleGapSameSender,
            )
            .alpha(dimAlpha),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // アイコンは連続した最初の1件にだけ出す。ただし場所は常に空けておく
        // （出したり出さなかったりで左右にずれると、別人が話しているように見える）。
        if (!message.isOutgoing) {
            Box(Modifier.size(AVATAR_SIZE)) {
                if (isFirstOfGroup) {
                    Avatar(
                        title = senderName.ifEmpty { message.senderName },
                        seed = message.senderId.value.hashCode(),
                        size = AVATAR_SIZE,
                        photo = senderPhoto,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        // この箱自体は動かさない。指の座標を固定の枠で扱うため。中の offset は配置時に効くので
        // 箱の大きさには影響しない。
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .onGloballyPositioned { cb.onBoundsChanged(it.boundsInRoot()) }
                .messageGestures(
                    key = message.id,
                    enabled = true,
                    thresholdPx = thresholdPx,
                    releaseThresholdPx = thresholdPx * THRESHOLD_HYSTERESIS,
                    maxPx = maxPx,
                    handlers = handlers,
                ),
        ) {
            Column(
                horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
                modifier = Modifier
                    // 実際に動いている間だけ高リフレッシュレートを要求する。
                    .preferHighFrameRate(offsetPx != 0f || raised)
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .scale(raiseScale),
            ) {
                // 時刻と状態は吹き出しの中ではなく横に置く。
                //
                // 中に入れると、1文字のメッセージでも「19:55 既読」の幅が要る。短い返事が
                // すべて同じ幅に伸びて、並びが機械的に見えた。外に出せば吹き出しは言った
                // ぶんの幅になり、列の端も揃ったままになる。
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.widthIn(max = maxWidth),
                ) {
                    // 時刻は連続した最後の1件だけ。同じ分に何行も同じ時刻が並ぶのは雑音。
                    // 送信中と失敗だけは例外で、黙っているほうが悪い。
                    val unsettled = message.isOutgoing &&
                        (message.status == MessageStatus.Sending || message.status == MessageStatus.Failed)
                    val showMeta = isLastOfGroup || unsettled
                    val meta = @Composable { MessageMeta(message, showClock = isLastOfGroup) }

                    if (message.isOutgoing && showMeta) {
                        meta()
                        Spacer(Modifier.width(META_GAP))
                    }

                    // fill = false。上限に当たるまでは中身なりの幅にする。
                    Box(Modifier.weight(1f, fill = false)) {
                        // スタンプに吹き出しは付けない。付けると「画像を送った」に見える。
                        if (message.content is MessageContent.Sticker) {
                            StickerImage(
                                store = LocalStickers.current,
                                id = message.content.stickerId,
                                modifier = Modifier.size(stickerSize),
                            )
                        } else if (message.content is MessageContent.Image) {
                            // 写真も同じ。写真そのものがメッセージで、枠は場所を奪うだけ。
                            PhotoMessage(
                                image = message.content,
                                isOutgoing = message.isOutgoing,
                                isFirstOfGroup = isFirstOfGroup,
                                full = mediaProvider(
                                    message.content.mediaId,
                                    message.content.mediaKey,
                                ),
                                onOpen = { cb.onPhotoClick() },
                            )
                        } else if (message.content is MessageContent.Video) {
                            VideoMessage(
                                video = message.content,
                                isOutgoing = message.isOutgoing,
                                isFirstOfGroup = isFirstOfGroup,
                                onOpen = { cb.onVideoClick() },
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(bubbleShape(message.isOutgoing, isFirstOfGroup))
                                    .background(
                                        if (message.isOutgoing) {
                                            colors.bubbleOutgoing
                                        } else {
                                            colors.bubbleIncoming
                                        },
                                    ),
                            ) {
                                BubbleContent(message)
                            }
                        }
                    }

                    if (!message.isOutgoing && showMeta) {
                        Spacer(Modifier.width(META_GAP))
                        meta()
                    }
                }

                if (message.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    ReactionChips(message, onClick = { cb.onReactionChipClick() })
                }
            }

            // 吹き出しが動いた分だけ、その後ろから出てくる。
            ReplyAffordance(
                progress = if (thresholdPx > 0f) offsetPx / thresholdPx else 0f,
                pastThreshold = pastThreshold,
                modifier = Modifier.offset {
                    IntOffset(
                        (offsetPx - (affordanceSize + affordanceGap).toPx()).roundToInt(),
                        0,
                    )
                },
            )
        }
    }
}

private val stickerSize = 148.dp

/** 吹き出しと横の時刻のあいだ。 */
private val META_GAP = 5.dp


/**
 * 時刻と状態。吹き出しの中ではなく横に、状態を上・時刻を下に積む。
 *
 * 吹き出しは折り返すと下へ伸びるので、親が下端で揃える（読み終わる位置が最後の行）。
 */
@Composable
private fun MessageMeta(message: Message, showClock: Boolean) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Column(
        horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
        // 吹き出しの最後の行は内側の余白のぶん上にあるので、同じだけ持ち上げて揃える。
        modifier = Modifier.padding(bottom = RinowaDimens.bubbleVerticalPadding),
    ) {
        if (message.isOutgoing) {
            StatusIndicator(message.status, colors.textTertiary)
        }
        if (showClock) {
            Text(
                text = formatClock(message.timestampMs),
                style = type.messageMeta,
                color = colors.textTertiary,
            )
        }
    }
}

/**
 * 取り消したあとに残るもの。
 *
 * 中央に、どちらの色でもない半透明で置く。送信者の色のままだと「この人が何か言った、
 * でも見せない」になって隠したように読める。
 */
@Composable
private fun RetractedNotice(senderName: String?, modifier: Modifier = Modifier) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (senderName.isNullOrEmpty()) {
                "メッセージの送信を取り消しました"
            } else {
                "${senderName}がメッセージの送信を取り消しました"
            },
            style = type.labelSmall,
            color = colors.textSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(
                    if (colors.isLight) {
                        Color.Black.copy(alpha = 0.06f)
                    } else {
                        Color.White.copy(alpha = 0.10f)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun BubbleContent(message: Message) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val onBubble = if (message.isOutgoing) colors.onBubbleOutgoing else colors.onBubbleIncoming

    Column(
        modifier = Modifier.padding(
            horizontal = RinowaDimens.bubbleHorizontalPadding,
            vertical = RinowaDimens.bubbleVerticalPadding,
        ),
    ) {
        message.replyTo?.let { reply ->
            Row(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(onBubble.copy(alpha = 0.10f))
                    .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(onBubble.copy(alpha = 0.55f)),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = reply.senderName,
                        style = type.labelSmall,
                        color = onBubble.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                    Text(
                        text = reply.excerpt.value,
                        style = type.quotedBody,
                        color = onBubble.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (message.content is MessageContent.Locked) {
            // 薄く、ただし空欄にはしない。空だと「送られていない」と受け取られる。
            // たいていは鍵が届いて次のスナップショットで開く。端末ができる前のものは
            // 永久に開かないが、どちらもここからは同じに見えるし、どちらも異常ではない。
            MessageBody(
                text = "🔒 まだ開けません",
                style = type.messageBody,
                color = onBubble.copy(alpha = 0.55f),
            )
        } else {
            MessageBody(
                text = message.content.previewText().value,
                style = type.messageBody,
                color = onBubble,
            )
        }
    }
}

/**
 * 送ったメッセージがどうなったか。
 *
 * 既読はチェック2本ではなく「既読」と書く。2本＝既読は教わらないと分からない決まりで、
 * 実機で試して通じなかった。出るときだけ小さく弾ませる（見ている前で無音でぬるっと
 * 現れると、最初からあったように見える）。
 */
@Composable
private fun StatusIndicator(status: MessageStatus, color: Color) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    if (status == MessageStatus.Read) {
        val bounce = remember { Animatable(0.55f) }
        LaunchedEffect(Unit) { bounce.animateTo(1f, RinowaMotion.popSpring()) }
        Text(
            text = "既読",
            style = type.messageMeta,
            color = color,
            modifier = Modifier.graphicsLayer {
                scaleX = bounce.value
                scaleY = bounce.value
                alpha = bounce.value.coerceIn(0f, 1f)
                // 端を基準に伸びる。時刻から外側へ広がって、横にずれない。
                transformOrigin = TransformOrigin(1f, 0.5f)
            },
        )
        return
    }

    val tint = if (status == MessageStatus.Failed) colors.danger else color

    Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun tick(offsetX: Float) {
            val path = Path().apply {
                moveTo(offsetX + size.width * 0.06f, size.height * 0.52f)
                lineTo(offsetX + size.width * 0.26f, size.height * 0.78f)
                lineTo(offsetX + size.width * 0.62f, size.height * 0.22f)
            }
            drawPath(path, tint, style = stroke)
        }

        when (status) {
            MessageStatus.Sending -> {
                drawCircle(
                    color = tint,
                    radius = size.height * 0.34f,
                    center = Offset(size.width * 0.4f, size.height * 0.5f),
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }
            MessageStatus.Failed -> {
                val path = Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.2f)
                    lineTo(size.width * 0.64f, size.height * 0.8f)
                    moveTo(size.width * 0.64f, size.height * 0.2f)
                    lineTo(size.width * 0.16f, size.height * 0.8f)
                }
                drawPath(path, tint, style = stroke)
            }
            // チェックは常に1本。2本目を「配信済み」に使っていたが、誰も読み取れなかった。
            MessageStatus.Sent, MessageStatus.Delivered -> tick(0f)
            MessageStatus.Read -> Unit // 上でテキストとして処理済み
        }
    }
}

/**
 * すでに付いているリアクション。
 *
 * 押すと選び直せる。最初は長押しから入るが、見えているものを押して変えられないと壊れて見える。
 */
@Composable
private fun ReactionChips(message: Message, onClick: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        message.reactions.forEach { reaction ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(RinowaDimens.reactionChipHeight)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (reaction.mine) colors.accentSoft else colors.surfaceSunken)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(horizontal = 8.dp),
            ) {
                Text(text = reaction.emoji, style = type.labelSmall)
                if (reaction.count > 1) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = reaction.count.toString(),
                        style = type.labelSmall,
                        color = if (reaction.mine) colors.accent else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** 返信の矢印。閾値の触覚とまったく同じ瞬間にアクセント色で満ちる。 */
@Composable
private fun ReplyAffordance(
    progress: Float,
    pastThreshold: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = RinowaTheme.colors
    val fill by animateColorAsState(
        targetValue = if (pastThreshold) colors.accent else colors.surfaceSunken,
        animationSpec = RinowaMotion.commitSpring(),
        label = "affordanceFill",
    )
    val glyph by animateColorAsState(
        targetValue = if (pastThreshold) colors.onAccent else colors.textTertiary,
        animationSpec = RinowaMotion.commitSpring(),
        label = "affordanceGlyph",
    )
    val pop by animateFloatAsState(
        targetValue = if (pastThreshold) 1.12f else 1f,
        animationSpec = RinowaMotion.commitSpring(),
        label = "affordancePop",
    )

    Canvas(
        modifier = modifier
            .size(affordanceSize)
            .alpha(progress.coerceIn(0f, 1f))
            .scale((0.5f + progress * 0.5f).coerceIn(0.5f, 1f) * pop),
    ) {
        val w = size.width
        val h = size.height
        drawCircle(color = fill, radius = w / 2f)

        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tail = Path().apply {
            moveTo(w * 0.74f, h * 0.70f)
            quadraticTo(w * 0.74f, h * 0.38f, w * 0.36f, h * 0.38f)
        }
        drawPath(tail, glyph, style = stroke)

        val head = Path().apply {
            moveTo(w * 0.50f, h * 0.24f)
            lineTo(w * 0.32f, h * 0.38f)
            lineTo(w * 0.50f, h * 0.52f)
        }
        drawPath(head, glyph, style = stroke)
    }
}

internal fun bubbleShape(isOutgoing: Boolean, isFirstOfGroup: Boolean): RoundedCornerShape {
    val large = RinowaDimens.bubbleCornerLarge
    val tail = RinowaDimens.bubbleCornerTail
    // 詰めるのは送信者側を向いた2つの角だけ。下は尻尾なので常に詰め、上は同じ人の
    // 吹き出しが続いているときだけ詰める。これで連続した発言がひとかたまりに見える。
    return if (isOutgoing) {
        RoundedCornerShape(
            topStart = large,
            topEnd = if (isFirstOfGroup) large else tail,
            bottomEnd = tail,
            bottomStart = large,
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstOfGroup) large else tail,
            topEnd = large,
            bottomEnd = large,
            bottomStart = tail,
        )
    }
}
