package blog.nextlab.echo.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics

/**
 * メッセージの文字と、開けるリンク。
 *
 * 探すのはウェブのアドレスだけ。コマンドもメンションも書式も、アプリが本文を
 * 解釈する理由になるものは何も探さない。本文は運ぶものであって読むものではない
 * （docs/PRIVACY_PRINCIPLES.md）。ここだけ例外なのは、誰もたどれないリンクは
 * 失敗したリンクだから。
 *
 * 開くのは本人が選んだブラウザ。`ACTION_VIEW` を投げて Android に渡す。Rinowa に
 * アプリ内ブラウザは無いし、作るべきでもない。メッセンジャーの中で開いたページは、
 * 読む人にアドレス欄が見えないページ。
 */
@Composable
fun MessageBody(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalRinowaHaptics.current
    val accentOnBubble = RinowaTheme.colors.onAccent

    val annotated = remember(text, color) { annotateLinks(text, color) }

    if (annotated.getStringAnnotations(LINK_TAG, 0, annotated.length).isEmpty()) {
        androidx.compose.material3.Text(
            text = text,
            style = style,
            color = color,
            modifier = modifier,
        )
        return
    }

    @Suppress("DEPRECATION")
    ClickableText(
        text = annotated,
        style = style.copy(color = color),
        modifier = modifier,
        onClick = { position ->
            annotated.getStringAnnotations(LINK_TAG, position, position)
                .firstOrNull()
                ?.let { annotation ->
                    haptics.perform(HapticToken.Navigation)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // 扱えるものが入っていないか、アドレスが壊れている。
                    // どちらも会話を落とすほどのことではない。
                    runCatching { context.startActivity(intent) }
                        .onFailure { if (it !is ActivityNotFoundException) throw it }
                }
        },
    )
}

/**
 * ウェブのアドレスらしきものに下線を引く。
 *
 * わざと控えめに、`http://` か `https://` だけ。`example.com` をリンクだと
 * 決めることは、`10.30` はリンクでないと決めることでもある。普通の文の断片を
 * 押せる的に変えるメッセンジャーは、いくつか取りこぼすものより悪い。
 */
private fun annotateLinks(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    val matches = LINK_PATTERN.findAll(text)

    for (match in matches) {
        if (match.range.first > index) append(text.substring(index, match.range.first))

        val url = match.value.trimEnd('.', ',', '、', '。', ')', '）')
        pushStringAnnotation(LINK_TAG, url)
        withStyle(
            SpanStyle(
                color = color,
                textDecoration = TextDecoration.Underline,
            ),
        ) {
            append(url)
        }
        pop()

        // アドレスの末尾から外した句読点は、文のほうに残す。
        val trailing = match.value.length - url.length
        if (trailing > 0) append(match.value.takeLast(trailing))

        index = match.range.last + 1
    }

    if (index < text.length) append(text.substring(index))
}

private const val LINK_TAG = "url"

private val LINK_PATTERN = Regex("""https?://[^\s　]+""")
