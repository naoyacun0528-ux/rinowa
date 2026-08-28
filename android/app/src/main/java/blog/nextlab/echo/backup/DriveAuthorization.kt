package blog.nextlab.echo.backup

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * 本人のドライブに書く許可と、それを示すトークン。
 *
 * スコープは1つだけで、それが `drive.appdata`。アプリ専用の隠し領域にしか届かない
 * （書類にも、写真にも、今朝送られてきたファイルにも触れない）。Google はこれを
 * **非センシティブ**に分類していて、だから一人でも出荷できる。広いドライブの
 * スコープには説明動画と有料の第三者レビューが要る。仮定ではなく公開されている
 * スコープ表と突き合わせて確認した。docs/RESEARCH_E2EE.md §3.1。
 *
 * これ以上を求めるのは、機能の実態について不誠実でもある。
 *
 * 同意は本人のもので、必要になった瞬間に求める。サインイン時でも初回起動時でもない。
 * バックアップを入れない人には何も尋ねない。「いいえ」という答えを尊重する形は
 * それしかない。
 */
class DriveAuthorization(private val context: Context) {

    sealed interface Outcome {
        /** そのまま使える。 */
        class Granted(val token: String) : Outcome

        /** 同意画面を出す必要がある。出せる activity を持っているのは呼び出し側。 */
        class NeedsConsent(val intent: PendingIntent) : Outcome

        /** Play Services が断ったか、そもそも無い。文言は人が読むためのもの。 */
        class Failed(val message: String) : Outcome
    }

    suspend fun authorize(): Outcome {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .build()

        val result: AuthorizationResult = runCatching {
            Identity.getAuthorizationClient(context).authorize(request).await()
        }
            .getOrElse { failure ->
                // 握り潰さず報告する。でないと「バックアップが何もしなかった」と
                // 「バックアップが設定されていない」が区別できず、本人が動けるのは
                // 片方だけ。
                android.util.Log.w(TAG, "authorize failed", failure)
                return Outcome.Failed(
                    "Google の許可が取得できませんでした: " + (failure.message ?: "原因不明"),
                )
            }

        if (result.hasResolution()) {
            result.pendingIntent?.let { return Outcome.NeedsConsent(it) }
        }

        val token = result.accessToken
            ?: return Outcome.Failed("Google からアクセストークンが返りませんでした")
        return Outcome.Granted(token)
    }

    /**
     * トークン。まだ同意されていなければ null。
     *
     * これを [DriveAppData] に渡す。画面は出さない。寝ている間に同意ダイアログを
     * 出す裏のバックアップは、次にアプリを開くまで黙って待つものより悪い。
     */
    suspend fun tokenOrNull(): String? = (authorize() as? Outcome.Granted)?.token

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val TAG = "Rinowa/backup"
    }
}
