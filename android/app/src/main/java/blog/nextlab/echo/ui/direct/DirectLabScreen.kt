package blog.nextlab.echo.ui.direct

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import blog.nextlab.echo.core.designsystem.RinowaDimens
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.core.designsystem.glassFace
import blog.nextlab.echo.core.haptics.HapticToken
import blog.nextlab.echo.core.haptics.LocalRinowaHaptics
import blog.nextlab.echo.direct.DirectPreference
import blog.nextlab.echo.direct.DirectTier

/**
 * Rinowa Direct — Direct-1 の検証画面。
 *
 * 製品ではなく開発用。Direct-1 の全部は「Android 2台が、間にサーバーを置かずに
 * 文字列を渡せる」という主張で、それが示されるか示されないかがここで決まる。
 * docs/ROADMAP.md は、ここに数字が出なければ Direct-2 を始めないと明記している。
 *
 * この画面のどれも、Rinowa Direct の最終的な振る舞いではない。段の切り替えがあるのは
 * *開発者*が段を比べる必要があるからで、製品は自分で選び、誰にもラジオボタンを見せない。
 */
@Composable
fun DirectLabScreen(onBack: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val haptics = LocalRinowaHaptics.current
    val context = LocalContext.current

    val viewModel: DirectLabViewModel = viewModel(
        factory = viewModelFactory { initializer { DirectLabViewModel(context) } },
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        viewModel.onPermissionResult(granted.values.all { it })
    }

    // ViewModel に聞き、ViewModel が端末に聞く。この一覧をここにもう1つ持ったせいで
    // 両者がずれ、We2 では起動すらできなくなった。
    val required = remember(viewModel) { viewModel.requiredPermissions() }

    // 自分で始まる。探索はこの画面が前面にある間アプリがやることで、利用者が
    // 頼むものではない（製品と同じ規則で、見つけてもらうために誰も何も押さない）。
    // 下のボタンは、測るあいだ止めて再開するためだけにある。
    LaunchedEffect(viewModel.tier) {
        if (viewModel.discovering) return@LaunchedEffect
        if (viewModel.tier == DirectTier.Nearby && !viewModel.hasPermissions()) {
            permissionLauncher.launch(required)
        } else {
            viewModel.start()
        }
    }

    // 無線は電池を食うので、探索の寿命はこの画面の寿命ちょうど。
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = RinowaDimens.screenPadding, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                text = "Rinowa Direct",
                style = type.screenTitle,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        haptics.perform(HapticToken.Navigation)
                        onBack()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("閉じる", style = type.label, color = colors.accent)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "サーバーを通さずに文字列を渡せるかだけを見る画面です。" +
                    "製品ではこの選択は自動で、利用者には見えません。",
                style = type.labelSmall,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TierChip(
                    label = "Nearby（圏外でも可）",
                    selected = viewModel.tier == DirectTier.Nearby,
                    onClick = {
                        haptics.perform(HapticToken.Selection)
                        viewModel.selectTier(DirectTier.Nearby)
                    },
                    modifier = Modifier.weight(1f),
                )
                TierChip(
                    label = "同一LAN（将来iPhone）",
                    selected = viewModel.tier == DirectTier.Lan,
                    onClick = {
                        haptics.perform(HapticToken.Selection)
                        viewModel.selectTier(DirectTier.Lan)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(10.dp))

            // 推測させず明示する。放っておくと Nearby は可能なときは Wi-Fi に上がるので、
            // 「繋がった」は回線が無くても動くことを何も言わない。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TierChip(
                    label = "速度優先",
                    selected = viewModel.preference == DirectPreference.Fastest,
                    onClick = {
                        haptics.perform(HapticToken.Selection)
                        viewModel.selectPreference(DirectPreference.Fastest)
                    },
                    modifier = Modifier.weight(1f),
                )
                TierChip(
                    label = "オフライン優先",
                    selected = viewModel.preference == DirectPreference.OfflineCapable,
                    onClick = {
                        haptics.perform(HapticToken.Selection)
                        viewModel.selectPreference(DirectPreference.OfflineCapable)
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = if (viewModel.preference == DirectPreference.OfflineCapable) {
                    "Bluetooth だけを使います。遅いですが、ネットワークが無くても繋がります。"
                } else {
                    "使える中で一番速い経路を選びます。同じWi-Fiにいると Wi-Fi になります。"
                },
                style = type.labelSmall,
                color = colors.textTertiary,
            )

            Spacer(Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .glassFace(shape = RoundedCornerShape(16.dp), elevation = 2.dp)
                    .padding(14.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("この端末の名前", style = type.labelSmall, color = colors.textTertiary)
                    Spacer(Modifier.height(2.dp))
                    Text(viewModel.myLabel, style = type.listName, color = colors.textPrimary)
                }
                ActionButton(
                    label = if (viewModel.discovering) "停止" else "探す",
                    onClick = {
                        haptics.perform(HapticToken.SoftConfirm)
                        if (viewModel.discovering) {
                            viewModel.stop()
                        } else if (viewModel.tier == DirectTier.Nearby) {
                            permissionLauncher.launch(required)
                        } else {
                            viewModel.start()
                        }
                    },
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        haptics.perform(HapticToken.Selection)
                        viewModel.toggleAutoConnect()
                    }
                    .padding(vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "見つけたら自動で接続",
                        style = type.label,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // ここでは切のほうが異常な設定。製品は誰にも接続を押させない。
                        // 探索と接続を別々に計るためだけにある。
                        text = if (viewModel.autoConnect) {
                            "製品と同じ挙動です。"
                        } else {
                            "手動。発見と接続の時間を別々に測るとき用。"
                        },
                        style = type.labelSmall,
                        color = colors.textTertiary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Toggle(on = viewModel.autoConnect)
            }

            if (viewModel.status != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = viewModel.status.orEmpty(),
                    style = type.listPreview,
                    color = colors.danger,
                )
            }

            Spacer(Modifier.height(18.dp))
            Text("見つかった端末", style = type.label, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))

            if (viewModel.peers.isEmpty()) {
                Text(
                    text = if (viewModel.discovering) {
                        "探しています…"
                    } else {
                        "「探す」を両方の端末で押してください。"
                    },
                    style = type.listPreview,
                    color = colors.textTertiary,
                )
            }

            viewModel.peers.forEach { peer ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .glassFace(shape = RoundedCornerShape(14.dp), elevation = 2.dp)
                        .padding(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = peer.advertisedLabel,
                            style = type.listName,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = listOfNotNull(
                                viewModel.stateOf(peer.endpointId),
                                viewModel.mediumOf(peer.endpointId),
                            ).joinToString(" ・ "),
                            style = type.labelSmall,
                            color = colors.textTertiary,
                        )
                    }
                    ActionButton(
                        label = if (viewModel.isConnected(peer.endpointId)) "Hello" else "接続",
                        onClick = {
                            haptics.perform(HapticToken.Send)
                            if (viewModel.isConnected(peer.endpointId)) {
                                viewModel.sendHello(peer)
                            } else {
                                viewModel.connect(peer)
                            }
                        },
                    )
                }
            }

            // Yosegi はデスクトップで取った数字から選んだ。圧縮率はそのまま端末にも
            // 移るが、時間は移らない。デスクトップのマイクロ秒を Android の数字として
            // 出すのは作り話になる。本物はここから出る（この画面を出している端末で、
            // 同梱のコーデックを走らせる）。
            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("通話できる回線か確認", style = type.label, color = colors.textSecondary)
                    Text(
                        text = "相手は要りません。回線ごとに1回ずつ実行して比べます",
                        style = type.labelSmall,
                        color = colors.textTertiary,
                    )
                }
                ActionButton(
                    label = "確認",
                    onClick = {
                        haptics.perform(HapticToken.SoftConfirm)
                        viewModel.probeNetwork()
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Yosegi v1 の実機計測", style = type.label, color = colors.textSecondary)
                    Text(
                        text = "encode / decode / 連続処理。1秒ほどかかります",
                        style = type.labelSmall,
                        color = colors.textTertiary,
                    )
                }
                ActionButton(
                    label = "計測",
                    onClick = {
                        haptics.perform(HapticToken.Send)
                        viewModel.runWireBenchmark()
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("E2EE の往復確認", style = type.label, color = colors.textSecondary)
                    Text(
                        text = "鍵を公開して暗号化・復号を1往復。相手は要りません",
                        style = type.labelSmall,
                        color = colors.textTertiary,
                    )
                }
                ActionButton(
                    label = "検証",
                    onClick = {
                        haptics.perform(HapticToken.Send)
                        viewModel.runCryptoProbe()
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("記録", style = type.label, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            viewModel.log.forEach { line ->
                Text(
                    text = line,
                    style = type.labelSmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }

            Spacer(Modifier.height(28.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun TierChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentSoft else colors.surfaceSunken)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = type.labelSmall,
            color = if (selected) colors.accent else colors.textSecondary,
        )
    }
}

@Composable
private fun Toggle(on: Boolean) {
    val colors = RinowaTheme.colors
    val progress by animateFloatAsState(if (on) 1f else 0f, label = "toggle")
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(lerp(colors.surfaceSunken, colors.accent, progress)),
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .offset(x = (progress * 20f).dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = type.label.copy(fontWeight = FontWeight.SemiBold),
            color = colors.onAccent,
        )
    }
}

