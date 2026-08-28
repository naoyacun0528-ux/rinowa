package blog.nextlab.echo.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * 認証。アプリの他の部分から見えるかたち。
 *
 * 入口は2つ（docs/FIREBASE_SETUP.md で決めた）。Google サインインと、
 * パスワード＋確認済みアドレスのメール。どちらもあるのは、端末を失うことが
 * アカウントを失うことにならないため。docs/SYNC_AND_BACKUP.md の
 * 「端末は替えられる。アカウントは続く」。匿名認証は意図的に入れていない
 * （復旧の手段がまったく無い）。
 */
class AuthRepository(private val auth: FirebaseAuth) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        auth.addAuthStateListener { publish(it.currentUser) }
        publish(auth.currentUser)
    }

    suspend fun signInWithGoogle(idToken: String): Result<Unit> = attempt {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).await()
        // Google 側がすでにそのアドレスの持ち主であることを確かめているので、
        // こちらから確認することは残っていない。
        publish(auth.currentUser)
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = attempt {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
        auth.currentUser?.sendEmailVerification()?.await()
        publish(auth.currentUser)
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = attempt {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        publish(auth.currentUser)
    }

    suspend fun resendVerification(): Result<Unit> = attempt {
        auth.currentUser?.sendEmailVerification()?.await()
    }

    /**
     * アカウントをサーバーから読み直す。
     *
     * 確認はメールアプリの中で起き、このアプリには何も届かない。こちらから
     * 聞きに戻る必要がある。
     */
    suspend fun refresh(): Result<Unit> = attempt {
        auth.currentUser?.reload()?.await()
        publish(auth.currentUser)
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = attempt {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    fun signOut() {
        auth.signOut()
        publish(null)
    }

    /**
     * アカウントを消す。
     *
     * サインインが最近でないと Firebase は拒否する。それは意図的で、机に置いた
     * 解錠済みの端末だけで、何時間もあとに他人のアカウントを終わらせられては困る。
     * 呼び出し側は [AuthFailure.NeedsRecentLogin] を受けたら、`reauthenticate` の
     * どれかで本人確認をやり直して再試行する。
     */
    suspend fun deleteAccount(): Result<Unit> = attempt {
        val user = auth.currentUser ?: throw AuthException(AuthFailure.Unknown)
        user.delete().await()
        publish(null)
    }

    suspend fun reauthenticateWithGoogle(idToken: String): Result<Unit> = attempt {
        val user = auth.currentUser ?: throw AuthException(AuthFailure.Unknown)
        user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    suspend fun reauthenticateWithPassword(password: String): Result<Unit> = attempt {
        val user = auth.currentUser ?: throw AuthException(AuthFailure.Unknown)
        val email = user.email ?: throw AuthException(AuthFailure.Unknown)
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
    }

    private fun publish(user: FirebaseUser?) {
        _state.value = when {
            user == null -> AuthState.SignedOut
            else -> {
                val echoUser = user.toRinowaUser()
                // アドレスを確認していないパスワードのアカウントは、サインイン手前で
                // 止める。確認するまで、そのアドレスが登録した本人のものである証拠が
                // 何も無い。パスワード再設定はそのアドレスへ届くので、持ち主がまだ定まっていない。
                if (echoUser.emailVerified || AuthProvider.Google in echoUser.providers) {
                    AuthState.SignedIn(echoUser)
                } else {
                    AuthState.NeedsVerification(echoUser)
                }
            }
        }
    }

    private inline fun attempt(block: () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(AuthException(e.toFailure()))
    }
}

private fun FirebaseUser.toRinowaUser(): RinowaUser = RinowaUser(
    uid = uid,
    email = email,
    displayName = displayName,
    emailVerified = isEmailVerified,
    providers = providerData.mapNotNull { info ->
        when (info.providerId) {
            GoogleAuthProvider.PROVIDER_ID -> AuthProvider.Google
            "password" -> AuthProvider.Password
            else -> null
        }
    }.toSet(),
)

/**
 * Firebase の例外は英語の技術的な文言を持ち、アドレスが登録済みかどうかを
 * そのまま言うものもある。生のまま UI に出さないよう、ここで対応付ける。
 */
private fun Exception.toFailure(): AuthFailure = when (this) {
    is AuthException -> failure
    is FirebaseNetworkException -> AuthFailure.NoNetwork
    // FirebaseAuthInvalidUserException より先に見る。どちらも FirebaseAuthException の
    // 子で、「もう一度サインインしてくれ」と「アカウントが使えない」は別のこと。
    is FirebaseAuthRecentLoginRequiredException -> AuthFailure.NeedsRecentLogin
    is FirebaseAuthWeakPasswordException -> AuthFailure.WeakPassword
    is FirebaseAuthUserCollisionException -> AuthFailure.EmailAlreadyUsed
    is FirebaseTooManyRequestsException -> AuthFailure.TooManyAttempts
    is FirebaseAuthInvalidUserException -> AuthFailure.WrongCredentials
    is FirebaseAuthInvalidCredentialsException ->
        if (message?.contains("email", ignoreCase = true) == true) {
            AuthFailure.InvalidEmail
        } else {
            AuthFailure.WrongCredentials
        }
    else -> AuthFailure.Unknown
}
