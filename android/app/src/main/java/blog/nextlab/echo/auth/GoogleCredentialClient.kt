package blog.nextlab.echo.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import blog.nextlab.echo.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Credential Manager から Google の ID トークンを取る。
 *
 * Credential Manager は非推奨になった旧 `GoogleSignIn` の後継。返ってきたトークンは
 * そのまま [AuthRepository.signInWithGoogle] へ渡す。アプリの他のどこも見ないし、
 * ログにも出さない。
 *
 * クライアント id は `R.string.default_web_client_id` から来る。google-services.json を
 * 元に Google Services プラグインが生成するもの。埋め込むと Firebase の設定を変える
 * たびにソースを直すことになるうえ、無視されるファイルにあるべき値を git に入れることになる。
 */
class GoogleCredentialClient(context: Context) {

    private val appContext = context.applicationContext
    private val credentialManager = CredentialManager.create(appContext)

    /**
     * @param activityContext Activity であること。Credential Manager は画面を出すので、
     *   application の context では動かせない。
     */
    suspend fun requestIdToken(activityContext: Context): Result<String> {
        // 自動の下シートではなく、明示的な「Google でログイン」の流れ。初回は
        // 以前に許可したアカウントが無く、自動のほうはそこで単に失敗する。
        // それはボタンが壊れているように見える。
        val option = GetSignInWithGoogleOption
            .Builder(appContext.getString(R.string.default_web_client_id))
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        return try {
            val response = credentialManager.getCredential(activityContext, request)
            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                Result.success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Result.failure(AuthException(AuthFailure.Unknown))
            }
        } catch (_: GetCredentialCancellationException) {
            // 閉じたのは失敗ではない。UI は黙るべきで、警告を出すべきではない。
            Result.failure(AuthException(AuthFailure.Cancelled))
        } catch (_: NoCredentialException) {
            Result.failure(AuthException(AuthFailure.NoGoogleAccount))
        } catch (_: GetCredentialException) {
            Result.failure(AuthException(AuthFailure.Unknown))
        }
    }
}
