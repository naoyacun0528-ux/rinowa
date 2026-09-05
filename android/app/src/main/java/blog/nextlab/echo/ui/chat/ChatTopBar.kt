package blog.nextlab.echo.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.model.Conversation
import blog.nextlab.echo.ui.common.Avatar

@Composable
internal fun ChatTopBar(
    conversation: Conversation,
    onBack: () -> Unit,
    onCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    /** 名前と顔を押したとき。指紋の読み合わせへ。 */
    onOpenSafety: (() -> Unit)? = null,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    // 自前の背景は持たない。FrostedBar が敷く。
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(RinowaDimens.touchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val stroke = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                    val path = Path().apply {
                        moveTo(size.width * 0.62f, size.height * 0.16f)
                        lineTo(size.width * 0.30f, size.height * 0.5f)
                        lineTo(size.width * 0.62f, size.height * 0.84f)
                    }
                    drawPath(path, colors.textPrimary, style = stroke)
                }
            }
            Spacer(Modifier.width(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .then(
                        if (onOpenSafety != null) {
                            Modifier.clickable(onClick = onOpenSafety)
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 4.dp),
            ) {
            Avatar(title = conversation.title, seed = conversation.avatarSeed, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = conversation.title,
                style = type.screenTitle,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            }
            // 相手が1人に決まる会話だけ。グループ通話は別の機能で、たまに何もしない
            // ボタンを置くより出さないほうがよい。
            if (onVideoCall != null) {
                Box(
                    modifier = Modifier
                        .size(RinowaDimens.touchTarget)
                        .clip(CircleShape)
                        .clickable(onClick = onVideoCall),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(21.dp)) {
                        val stroke = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        )
                        val w = size.width
                        val h = size.height
                        // レンズが飛び出した本体。誰でも「ビデオ」と読む形を自前で描く。
                        val body = Path().apply {
                            moveTo(w * 0.10f, h * 0.30f)
                            lineTo(w * 0.62f, h * 0.30f)
                            lineTo(w * 0.62f, h * 0.70f)
                            lineTo(w * 0.10f, h * 0.70f)
                            close()
                        }
                        drawPath(body, colors.textPrimary, style = stroke)
                        val lens = Path().apply {
                            moveTo(w * 0.68f, h * 0.42f)
                            lineTo(w * 0.90f, h * 0.30f)
                            lineTo(w * 0.90f, h * 0.70f)
                            lineTo(w * 0.68f, h * 0.58f)
                            close()
                        }
                        drawPath(lens, colors.textPrimary, style = stroke)
                    }
                }
            }
            if (onCall != null) {
                Box(
                    modifier = Modifier
                        .size(RinowaDimens.touchTarget)
                        .clip(CircleShape)
                        .clickable(onClick = onCall),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(21.dp)) {
                        val stroke = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        )
                        val w = size.width
                        val h = size.height
                        val handset = Path().apply {
                            moveTo(w * 0.22f, h * 0.16f)
                            lineTo(w * 0.38f, h * 0.16f)
                            lineTo(w * 0.46f, h * 0.36f)
                            lineTo(w * 0.34f, h * 0.46f)
                            quadraticTo(w * 0.5f, h * 0.72f, w * 0.56f, h * 0.68f)
                            lineTo(w * 0.66f, h * 0.56f)
                            lineTo(w * 0.86f, h * 0.66f)
                            lineTo(w * 0.86f, h * 0.82f)
                            quadraticTo(w * 0.5f, h * 0.98f, w * 0.22f, h * 0.16f)
                        }
                        drawPath(handset, colors.textPrimary, style = stroke)
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

/**
 * 招待の段階では、入力欄の代わりにこれを出す。
 *
 * スレッドの上に被せない。誰が何を送ってきたのか読んでから決められるべきで、
 * 隠したら中身を見ずに決めることになる。
 */
@Composable
internal fun AcceptInvitation(name: String, isGroup: Boolean, onAccept: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            // グループに入るのは友達になることではない。家族のグループで「友達に追加」と
            // 書くのは、起きていないことを画面が説明していることになる。
            text = if (isGroup) "「$name」に招待されています" else "$name さんからのメッセージです",
            style = type.label,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isGroup) {
                "参加すると発言できます。"
            } else {
                "友達に追加すると返信できます。"
            },
            style = type.labelSmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.accent)
                .clickable(onClick = onAccept)
                .padding(horizontal = 28.dp, vertical = 13.dp),
        ) {
            Text(
                text = if (isGroup) "参加する" else "友達に追加",
                style = type.label.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onAccent,
            )
        }
    }
}
