package blog.nextlab.echo.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace

/**
 * Rinowa 自身の言葉で書いた1行入力欄。
 *
 * Material の `TextField` ではなく [BasicTextField] と [glassFace] で作る。理由は
 * アプリの他の場所と同じで、枠線の箱に浮く見出しが付くと、他所のアプリに見える。
 */
@Composable
internal fun RinowaField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RinowaDimens.composerMinHeight + 6.dp)
            .glassFace(shape = RoundedCornerShape(16.dp), elevation = 2.dp)
            .padding(start = 16.dp, end = 6.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                // クロスフェードする。ログインと新規登録で文言が変わるので、
                // 瞬時に差し替えると変わったことに誰も気付かない。
                Crossfade(targetState = placeholder, label = "placeholder") { hint ->
                    Text(hint, style = type.composer, color = colors.textTertiary)
                }
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = type.composer.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = visualTransformation,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

/**
 * その画面が求めている唯一の操作。
 *
 * 入力欄の送信ボタンと同じ作り。無効のときはアルファで薄くせず、紙の色へ混ぜた
 * 不透明の塗りにする。半透明の塗りだと、影のシルエットが透けて多角形に見えるため。
 * RinowaGlass.kt を参照。
 */
@Composable
internal fun PrimaryButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    label: @Composable (Color) -> Unit,
) {
    val colors = RinowaTheme.colors
    val base = tint ?: colors.accent

    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled) 1.03f else 1f,
        animationSpec = RinowaMotion.popSpring(),
        label = "primaryPress",
    )
    val fill by animateFloatAsState(
        // 0.30 は実機で測った値で、明るい背景では*有効*に見えた（温かい紙の上に
        // 濃いオレンジの30%は、まだ自信たっぷりのボタンに見える）。暗い方では
        // 見えなくなっていて、両方で見る必要があるのはまさにそのため。
        targetValue = if (enabled) 1f else 0.18f,
        animationSpec = RinowaMotion.commitSpring(),
        label = "primaryFill",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(pressScale)
            .shadow(3.dp, RoundedCornerShape(16.dp), clip = false, spotColor = colors.glassShadow)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(colors.background, base, fill),
                        lerp(lerp(colors.background, base, fill), Color.Black, 0.07f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(colors.glassEdge.copy(alpha = 0.40f), Color.Transparent),
                ),
                shape = RoundedCornerShape(16.dp),
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        label(lerp(colors.textTertiary, colors.onAccent, fill))
    }
}

/**
 * 中身を少し縦に動かしながら入れ替える。状態が変わったことを*見せる*ため。
 *
 * ログインと新規登録は同じ場所の数語しか違わない。瞬時に置き換えると、何かが
 * 起きた痕跡が残らず、次に見たときに文面が違うだけになる。2つが別の場所だと
 * 感じさせるのは動き。
 *
 * [forward] は進む向き。状態が変わったあとに読むので、true は「2つのうち遠いほうに
 * 着いた」という意味。
 */
@Composable
internal fun <T> SwapContent(
    target: T,
    forward: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            val rise = if (forward) 1 else -1
            val enter = slideInVertically(
                animationSpec = tween(
                    RinowaMotion.DURATION_QUICK,
                    easing = RinowaMotion.standardEasing,
                ),
            ) { height -> rise * height / 2 } + fadeIn(tween(RinowaMotion.DURATION_QUICK))
            val exit = slideOutVertically(
                animationSpec = tween(
                    RinowaMotion.DURATION_QUICK,
                    easing = RinowaMotion.exitEasing,
                ),
            ) { height -> -rise * height / 2 } + fadeOut(tween(RinowaMotion.DURATION_INSTANT))
            // 切り抜かない。2つのラベルは幅が違い、滑りに合わせて入れ物まで伸縮すると、
            // 移動ではなく文字が押しつぶされたように読める。
            enter togetherWith exit using SizeTransform(clip = false)
        },
        label = "swap",
    ) { value -> content(value) }
}

/** [PrimaryButton] の中の文字。ボタンが自分のラベルを動かせるように分けてある。 */
@Composable
internal fun PrimaryButtonLabel(text: String, color: Color) {
    Text(
        text = text,
        style = RinowaTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
        color = color,
    )
}

/** 主操作の隣に置く、控えめな操作。 */
@Composable
internal fun QuietButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (Color) -> Unit,
) {
    val colors = RinowaTheme.colors

    Box(
        modifier = modifier
            .height(RinowaDimens.touchTarget)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        label(if (enabled) colors.accent else colors.textTertiary)
    }
}

/** [QuietButton] の中の文字。ボタンが自分のラベルを動かせるように分けてある。 */
@Composable
internal fun QuietButtonLabel(text: String, color: Color) {
    Text(text = text, style = RinowaTheme.type.label, color = color)
}

/**
 * フォームの下に出る1行。
 *
 * 言うことがあるときだけ場所を取り、あらかじめ空けておく余白は作らない。
 * 将来の悪い知らせのために空いた枠は、何かが欠けているように読める。
 */
@Composable
internal fun NoticeBanner(text: String?, isError: Boolean) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val shown = remember(text) { text }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isError) colors.accentSoft else colors.surfaceSunken)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isError) colors.danger else colors.success),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = shown.orEmpty(),
                style = type.listPreview,
                color = colors.textPrimary,
            )
        }
    }
}
