package blog.nextlab.echo.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 指では取り消せないことの前に出す、はい／いいえの問い。
 *
 * Material の `AlertDialog` は使わない。別の設計体系の部品を Rinowa の上に落とすことに
 * なるうえ、それが出る瞬間こそ、アプリが一番アプリらしく見えるべきとき。カードは
 * 他の場所と同じ、浮いた面。
 *
 * @param destructive 確定側の操作を危険の色で塗る。取り消せない操作にだけ使うこと。
 *   ただ面倒なだけの操作に使うと、人はこの色を無視するようになる。
 */
@Composable
fun RinowaConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "キャンセル",
    destructive: Boolean = false,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassFace(shape = RoundedCornerShape(24.dp), elevation = 8.dp)
                .padding(horizontal = 22.dp, vertical = 20.dp),
        ) {
            Text(
                text = title,
                style = type.screenTitle,
                color = colors.textPrimary,
            )
            // 見出しだけで足りるときは空にする。ダイアログの形を埋めるためだけの本文は、
            // 誰も頼んでいない文章。
            if (message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = type.listPreview,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogButton(
                    label = cancelLabel,
                    fill = null,
                    contentColor = colors.textSecondary,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                DialogButton(
                    label = confirmLabel,
                    fill = if (destructive) colors.danger else colors.accent,
                    contentColor = colors.onAccent,
                    onClick = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    fill: Color?,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    var pressed by remember { mutableStateOf(false) }
    val swell by animateFloatAsState(
        targetValue = if (pressed) 1.05f else 1f,
        animationSpec = RinowaMotion.popSpring(),
        label = "dialogButton",
    )

    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .scale(swell)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (fill != null) {
                    // 他の塗りつぶしの操作と同じく不透明に。半透明を影の上に置くと、
                    // 影のシルエットが透けて見える。
                    Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(fill, androidx.compose.ui.graphics.lerp(fill, Color.Black, 0.08f)),
                            ),
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(colors.glassEdge.copy(alpha = 0.35f), Color.Transparent),
                            ),
                            shape = RoundedCornerShape(14.dp),
                        )
                } else {
                    Modifier
                },
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.label.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
        )
    }
}
