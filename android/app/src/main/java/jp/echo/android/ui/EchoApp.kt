package jp.echo.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import jp.echo.android.core.analytics.Analytics
import jp.echo.android.core.designsystem.EchoMotion
import jp.echo.android.core.designsystem.EchoTheme
import jp.echo.android.core.haptics.EchoHaptics
import jp.echo.android.core.haptics.LocalEchoHaptics
import jp.echo.android.model.Conversation
import jp.echo.android.ui.chat.ChatScreen
import jp.echo.android.ui.chatlist.ChatListScreen
import jp.echo.android.ui.lab.HapticLabScreen

private sealed interface Screen {
    data object ChatList : Screen
    data class Chat(val conversation: Conversation) : Screen
    data object HapticLab : Screen
}

@Composable
fun EchoApp(haptics: EchoHaptics, analytics: Analytics) {
    EchoTheme {
        CompositionLocalProvider(
            LocalEchoHaptics provides haptics,
            LocalAnalytics provides analytics,
        ) {
            val colors = EchoTheme.colors
            var screen by remember { mutableStateOf<Screen>(Screen.ChatList) }

            BackHandler(enabled = screen !is Screen.ChatList) {
                screen = Screen.ChatList
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.background),
            ) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val forward = targetState !is Screen.ChatList
                        val duration = EchoMotion.DURATION_STANDARD
                        if (forward) {
                            slideInHorizontally(
                                animationSpec = tween(duration, easing = EchoMotion.standardEasing),
                            ) { it / 3 } + fadeIn(tween(duration)) togetherWith
                                slideOutHorizontally(
                                    animationSpec = tween(duration, easing = EchoMotion.standardEasing),
                                ) { -it / 6 } + fadeOut(tween(EchoMotion.DURATION_QUICK))
                        } else {
                            slideInHorizontally(
                                animationSpec = tween(duration, easing = EchoMotion.standardEasing),
                            ) { -it / 6 } + fadeIn(tween(duration)) togetherWith
                                slideOutHorizontally(
                                    animationSpec = tween(duration, easing = EchoMotion.exitEasing),
                                ) { it / 3 } + fadeOut(tween(EchoMotion.DURATION_QUICK))
                        }
                    },
                    label = "screen",
                ) { current ->
                    when (current) {
                        Screen.ChatList -> ChatListScreen(
                            onOpenConversation = { screen = Screen.Chat(it) },
                            onOpenHapticLab = { screen = Screen.HapticLab },
                        )

                        is Screen.Chat -> ChatScreen(
                            conversation = current.conversation,
                            onBack = { screen = Screen.ChatList },
                        )

                        Screen.HapticLab -> HapticLabScreen(
                            onBack = { screen = Screen.ChatList },
                        )
                    }
                }
            }
        }
    }
}
