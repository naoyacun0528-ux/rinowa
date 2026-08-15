package blog.nextlab.echo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import blog.nextlab.echo.ui.EchoApp

class MainActivity : ComponentActivity() {

    private val app: EchoApplication get() = application as EchoApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            EchoApp(
                haptics = app.haptics,
                analytics = app.analytics,
                stickers = app.stickers,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        app.haptics.setAppInForeground(true)
    }

    override fun onPause() {
        // Haptics never fire while the app is not in front.
        app.haptics.setAppInForeground(false)
        super.onPause()
    }
}
