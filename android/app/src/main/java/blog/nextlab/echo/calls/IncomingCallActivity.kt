package blog.nextlab.echo.calls

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import blog.nextlab.echo.MainActivity

/**
 * 着信が出る画面。ロックされた端末の上でも出る。
 *
 * アプリ内のオーバーレイは、アプリがすでに画面に出ているときしか出せない。ポケットの
 * 中に届いた着信は、Rinowa が動き出す前に、キーガードの上へ自前の画面を出す必要がある。
 * それができるのは全画面インテントで起こされた Activity だけで、これはその最小構成。
 *
 * `setShowWhenLocked` と `setTurnScreenOn` は、通話アプリが10年前から使ってきた
 * ウィンドウフラグの今の書き方。**端末のロックは解かない**し、この画面はキーガードの
 * 裏を何も見られない。名前と2つのボタンを出すだけで、標準の電話アプリも同じ場面で
 * それしか出さない。
 *
 * 応答すると、先にキーガードを解いてもらう。通話の裏にある会話は私的なもので、
 * ロックされた端末にそれを出すのはロックの迂回になるから。
 */
class IncomingCallActivity : ComponentActivity() {

    private lateinit var callerName: String
    private lateinit var callId: String
    private lateinit var conversationId: String
    private lateinit var kindLabel: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callerName = intent.getStringExtra(IncomingCallNotifier.EXTRA_CALLER_NAME) ?: "着信"
        callId = intent.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID).orEmpty()
        conversationId = intent.getStringExtra(IncomingCallNotifier.EXTRA_CONVERSATION_ID).orEmpty()
        kindLabel = intent.getStringExtra(IncomingCallNotifier.EXTRA_KIND_LABEL) ?: "着信"

        showOverLockScreen()

        // この画面ではなく通知から押された場合。何も描かずに処理して退く。
        when (intent.action) {
            IncomingCallNotifier.ACTION_DECLINE -> {
                decline()
                return
            }
            IncomingCallNotifier.ACTION_ANSWER -> {
                answer()
                return
            }
        }

        setContent { IncomingCallScreen(callerName, kindLabel, onAnswer = ::answer, onDecline = ::decline) }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private fun answer() {
        IncomingCallService.stop(this)
        PendingCall.answer(callId, conversationId)

        val open = {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(blog.nextlab.echo.notifications.RinowaMessagingService.EXTRA_CONVERSATION_ID, conversationId)
                },
            )
            finish()
        }

        // 会話は私的なもの。通話に出ることが、解錠せずに中を見る手段になってはいけない。
        // 先にキーガードに退いてもらう。
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = open()
                    override fun onDismissCancelled() = finish()
                    override fun onDismissError() = finish()
                },
            )
        } else {
            open()
        }
    }

    private fun decline() {
        IncomingCallService.stop(this)
        PendingCall.decline(callId, conversationId)
        finish()
    }
}

/**
 * 動き出したアプリが、その通話について何をすべきか。
 *
 * 通知は Rinowa がメモリ上に存在する前に押されうるので、その判断は、押されたあとに
 * アプリの残りを起こすプロセスをまたいで生き残る必要がある。ブロードキャストではなく
 * ただの入れ物にしてある。1ビットの状態を1回読むだけで、そのためにメッセージバスを
 * 用意するのは大げさ。
 */
object PendingCall {
    @Volatile var answeredCallId: String? = null
        private set

    @Volatile var declinedCallId: String? = null
        private set

    @Volatile var conversationId: String? = null
        private set

    fun answer(callId: String, conversation: String) {
        answeredCallId = callId
        conversationId = conversation
    }

    fun decline(callId: String, conversation: String) {
        declinedCallId = callId
        conversationId = conversation
    }

    fun consumeAnswer(): String? = answeredCallId.also { answeredCallId = null }
    fun consumeDecline(): String? = declinedCallId.also { declinedCallId = null }
}

@Composable
private fun IncomingCallScreen(
    name: String,
    kindLabel: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0C)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        ) {
            Text("Rinowa", color = Color(0xFF8A8A95), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(kindLabel, color = Color(0xFF9A9AA5), fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = name,
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(72.dp))

            // 標準の電話アプリと同じく大きく離す。この画面の最悪の形は、ポケットの中で
            // 間違って出てしまうこと。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LockScreenButton("拒否", Color(0xFFE5484D), onDecline)
                LockScreenButton("応答", Color(0xFF30A46C), onAnswer)
            }
        }
    }
}

@Composable
private fun LockScreenButton(label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(tint)
                .clickable(onClick = onClick),
        )
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color(0xFFC8C8D0), fontSize = 14.sp)
    }
}
