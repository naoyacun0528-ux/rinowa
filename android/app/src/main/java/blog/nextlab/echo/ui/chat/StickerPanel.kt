package blog.nextlab.echo.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import blog.nextlab.echo.core.designsystem.RinowaMotion
import blog.nextlab.echo.core.designsystem.RinowaTheme
import blog.nextlab.echo.data.LocalStickerStore
import blog.nextlab.echo.model.BuiltInStickers
import blog.nextlab.echo.model.StickerId

private val panelHeight = 268.dp

/**
 * スタンプの引き出し。
 *
 * 押したら選択ではなく即送信。スタンプは1回の表現で、2タップにするとフォームになる。
 */
@Composable
fun StickerPanel(
    store: LocalStickerStore,
    onSelect: (StickerId) -> Unit,
    onBrowsed: (visibleIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RinowaTheme.colors
    val type = RinowaTheme.type
    val gridState = rememberLazyGridState()
    val ids = remember { BuiltInStickers.pack.stickerIds }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(panelHeight)
            .background(colors.surfaceSunken),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = BuiltInStickers.pack.title,
                style = type.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) {
            items(ids, key = { it.value }) { id ->
                StickerCell(
                    store = store,
                    id = id,
                    onClick = {
                        onBrowsed(gridState.firstVisibleItemIndex)
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun StickerCell(
    store: LocalStickerStore,
    id: StickerId,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = RinowaMotion.commitSpring(),
        label = "stickerPress",
    )

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(6.dp)
            .pointerInput(id) {
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
        StickerImage(
            store = store,
            id = id,
            modifier = Modifier
                .fillMaxSize()
                .scale(scale),
        )
    }
}

/**
 * 端末の置き場からスタンプを描く。無ければ仮の表示。
 *
 * 無くても止めてはいけない。会話はスクロールし続け、あとから埋まる。
 * Prototype 0 では取得先が無いので、無いということは壊れたインストールを意味する。
 */
@Composable
fun StickerImage(
    store: LocalStickerStore,
    id: StickerId,
    modifier: Modifier = Modifier,
) {
    val colors = RinowaTheme.colors
    val bitmap = remember(id) { store.image(id) }
    // 読み上げには「画像」ではなくスタンプの言葉を渡す。
    val label = remember(id) { BuiltInStickers.entries.firstOrNull { it.id == id }?.label }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(colors.outlineSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "…", style = RinowaTheme.type.label, color = colors.textTertiary)
        }
    }
}
