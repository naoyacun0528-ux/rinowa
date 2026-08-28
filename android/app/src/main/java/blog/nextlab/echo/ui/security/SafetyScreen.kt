package blog.nextlab.echo.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberCoroutineScope
import blog.nextlab.echo.crypto.KnownDevices
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.crypto.CryptoEngine
import blog.nextlab.echo.crypto.DeviceFingerprint
import blog.nextlab.echo.model.UserId
import blog.nextlab.echo.ui.common.ScreenHeader
import blog.nextlab.echo.ui.common.formatDaySeparator

/**
 * 相手が本人か、利用者自身が確かめる画面。
 *
 * **RINOWA SIGIL に残っていた最後の穴。** 公開鍵はサーバーが配っていて、検証する
 * 第三者はいない。この設計自体は LINE も Signal も同じで、そこは変えられない。
 * 変えられるのは「**確かめたい人が確かめられるか**」だけ。
 *
 * ここで指紋を出し、別の経路（対面・電話）で読み合わせてもらう。
 *
 * **「確認済み」とは表示しない。** 押した記録を残す仕組み（V-5）がまだ無いのに
 * 済んだように見せると、検証機能そのものが飾りになる。docs/RINOWA_SIGIL.md。
 */
@Composable
fun SafetyScreen(
    peerName: String,
    peerId: UserId?,
    /** エンジンの取得は suspend。画面が開いてから待つ。 */
    engineOf: suspend () -> CryptoEngine?,
    known: KnownDevices,
    onBack: () -> Unit,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    var mine by remember { mutableStateOf<DeviceFingerprint?>(null) }
    var theirs by remember { mutableStateOf<List<DeviceFingerprint>?>(null) }
    var change by remember { mutableStateOf<KnownDevices.Change?>(null) }
    var engine by remember { mutableStateOf<CryptoEngine?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(peerId) {
        val found = engineOf()
        engine = found
        mine = found?.myFingerprint()
        val list = if (found != null && peerId != null) found.devicesOf(peerId) else emptyList()
        theirs = list

        // 比べてから覚え直す。**順番が逆だと警告が一度も出ない。**
        if (peerId != null) {
            change = known.compare(peerId, list)?.takeIf { !it.isEmpty() }
            known.remember(peerId, list)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        ScreenHeader(title = "安全性の確認", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "この番号が両方の端末で同じなら、あいだに誰もいません。",
                style = type.label,
                color = colors.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "画面を見せ合うか、電話で読み上げて確かめてください。" +
                    "Rinowa がこれを代わりに確かめることはできません。" +
                    "確かめられる仕組みを持っていたら、それは私たちが中身を読める設計です。",
                style = type.labelSmall,
                color = colors.textSecondary,
            )

            change?.let { ChangeWarning(it, peerName) }

            Spacer(Modifier.height(22.dp))
            SectionTitle("この端末")
            mine?.let { FingerprintCard(it, onVerifiedChange = null) }

            Spacer(Modifier.height(22.dp))
            SectionTitle(peerName + " の端末")

            when {
                theirs == null -> Hint("読み込んでいます…")

                theirs.orEmpty().isEmpty() -> Hint(
                    "端末が見つかりません。相手がまだ一度も送っていないか、" +
                        "この端末が相手の鍵をまだ取りに行っていません。",
                )

                else -> theirs.orEmpty().forEach { device ->
                    FingerprintCard(
                        device = device,
                        onVerifiedChange = if (peerId != null) {
                            { verified ->
                                scope.launch {
                                    engine?.setVerified(peerId, device.deviceId, verified)
                                    theirs = engine?.devicesOf(peerId) ?: theirs
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            // 端末が2台以上あるのは、機種変更や再インストールの跡。**隠さない。**
            // 見えない古い端末に鍵が配られるほうが、一覧が長いことより悪い。
            if (theirs.orEmpty().size > 1) {
                Spacer(Modifier.height(10.dp))
                Hint(
                    "端末が複数あります。機種変更や再インストールをすると、" +
                        "前の登録が残ることがあります。心当たりが無い端末があれば、" +
                        "相手に確かめてください。",
                )
            }

            Spacer(Modifier.height(26.dp))
            SectionTitle("この画面で分からないこと")
            Hint(
                "番号が変わったときに知らせる仕組みは、まだありません。" +
                    "誰と誰がいつ話したかは、サーバーから見えます。" +
                    "RINOWA SIGIL が守るのは中身で、関係ではありません。",
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = RinowaTheme.type.labelSmall,
        color = RinowaTheme.colors.textTertiary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = RinowaTheme.type.labelSmall, color = RinowaTheme.colors.textSecondary)
}

/**
 * 端末1台ぶん。
 *
 * 指紋は**等幅**で出す。`l` と `1`、`O` と `0` が同じ形だと、読み合わせが成立しない。
 */
@Composable
private fun FingerprintCard(
    device: DeviceFingerprint,
    onVerifiedChange: ((Boolean) -> Unit)?,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceSunken)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = device.deviceId, style = type.labelSmall, color = colors.textSecondary)
            if (device.firstSeenMs > 0) {
                Text(
                    text = formatDaySeparator(device.firstSeenMs) + " から",
                    style = type.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = device.readable(),
            style = type.label.copy(fontFamily = FontFamily.Monospace, lineHeight = type.label.fontSize * 1.6f),
            color = colors.textPrimary,
        )

        if (onVerifiedChange != null) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (device.locallyTrusted) "確かめました" else "まだ確かめていません",
                    style = type.labelSmall,
                    color = if (device.locallyTrusted) colors.accent else colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (device.locallyTrusted) "取り消す" else "同じでした",
                    style = type.labelSmall,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onVerifiedChange(!device.locallyTrusted) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 相手の端末が前と違うときの知らせ。
 *
 * **機種変更と乗っ取りは、この端末からは同じに見える。** 区別しようとせず、
 * 変わったことだけを言って判断を利用者に返す。断定すると、外れたときに嘘になる。
 */
@Composable
private fun ChangeWarning(change: KnownDevices.Change, peerName: String) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    // 指紋が変わったほうが重い。端末が増えるのは機種変更でも起きるが、
    // 同じ端末 ID で指紋だけ変わるのは、普通に使っていて起きることではない。
    val severe = change.changed.isNotEmpty()

    Spacer(Modifier.height(16.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (severe) Color(0x33E5484D) else colors.surfaceSunken)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = if (severe) "番号が変わりました" else peerName + " の端末が増えました",
            style = type.label,
            color = if (severe) Color(0xFFFF9AA0) else colors.textPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (severe) {
                "前に見たときと番号が違います。機種変更や再インストールでも起きますが、" +
                    "別の誰かに置き換わっている場合も同じに見えます。" +
                    "本人に直接、番号を読み合わせて確かめてください。"
            } else {
                "前に見たときには無かった端末があります。機種変更や再インストールで増えます。" +
                    "心当たりが無ければ、本人に確かめてください。"
            },
            style = type.labelSmall,
            color = colors.textSecondary,
        )
    }
}
