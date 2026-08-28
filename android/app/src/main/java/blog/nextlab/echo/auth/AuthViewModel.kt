package blog.nextlab.echo.auth

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

enum class AuthMode { SignIn, SignUp }

/**
 * サインイン画面にいま入力されているもの。
 *
 * ViewModel に置くのは、回転で入力途中のアドレスが消えないため。どこにも永続化
 * しないのは意図的で、保存状態に入ったパスワードは画面より長生きし、そうする理由が無い。
 */
@Immutable
data class AuthForm(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
) {
    // 6文字は Firebase 側の最小値。ここで見るのは、明らかな間違いを往復せずに
    // 捕まえるためで、サーバーの規則を二重に持つためではない。
    val canSubmit: Boolean
        get() = !busy && email.isNotBlank() && password.length >= MIN_PASSWORD_LENGTH

    companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}

/** 画面が出すべきこと。日本語にするのは UI 側で、ここではない。 */
sealed interface AuthNotice {
    data class Failed(val failure: AuthFailure) : AuthNotice
    data object VerificationSent : AuthNotice
    data object ResetSent : AuthNotice

    /** 確認を実行したが、アドレスはまだ未確認だった。 */
    data object StillUnverified : AuthNotice
}

/**
 * サインインと確認の画面を動かす。
 *
 * 各操作は suspend で、成功したかどうかだけを返す。触覚は画面側で鳴らす。
 * 触覚は「どう感じるか」の一部で、指が触れたものの側にあるべきだから。
 * docs/HAPTIC_DESIGN.md。
 */
class AuthViewModel(
    private val repository: AuthRepository,
    private val google: GoogleCredentialClient,
) : ViewModel() {

    val state = repository.state

    var form by mutableStateOf(AuthForm())
        private set

    var notice by mutableStateOf<AuthNotice?>(null)
        private set

    /**
     * パスワード再設定の画面に入力されたアドレス。
     *
     * [form] とは別に持つ。入れない人は別のアドレスを試すことが多く、それが
     * ログイン欄を黙って書き換えると、戻ってきたときのフォームが変わってしまう。
     */
    var resetEmail by mutableStateOf("")
        private set

    /** 再設定のメールを送り終えたら true。要求の画面とは別の画面になる。 */
    var resetSent by mutableStateOf(false)
        private set

    fun setEmail(value: String) {
        form = form.copy(email = value)
        notice = null
    }

    fun setPassword(value: String) {
        form = form.copy(password = value)
        notice = null
    }

    fun setMode(mode: AuthMode) {
        form = form.copy(mode = mode)
        notice = null
    }

    suspend fun submit(): Boolean = attempt {
        val current = form
        when (current.mode) {
            AuthMode.SignIn -> repository.signInWithEmail(current.email, current.password)
            AuthMode.SignUp -> repository.signUpWithEmail(current.email, current.password)
        }
    }

    /** @param activityContext Activity。Credential Manager がシートを出すのに要る。 */
    suspend fun signInWithGoogle(activityContext: Context): Boolean = attempt {
        google.requestIdToken(activityContext).fold(
            onSuccess = { repository.signInWithGoogle(it) },
            onFailure = { Result.failure(it) },
        )
    }

    suspend fun resendVerification() {
        if (attempt { repository.resendVerification() }) notice = AuthNotice.VerificationSent
    }

    /** 入力済みの内容を持ち越して、再設定の画面を開く。 */
    fun beginPasswordReset() {
        resetEmail = form.email
        resetSent = false
        notice = null
    }

    // `setResetEmail` にはできない。プロパティのセッターがその JVM 名をすでに使っている。
    fun updateResetEmail(value: String) {
        resetEmail = value
        notice = null
    }

    fun leavePasswordReset() {
        resetSent = false
        notice = null
    }

    suspend fun sendPasswordReset() {
        if (resetEmail.isBlank()) {
            notice = AuthNotice.Failed(AuthFailure.InvalidEmail)
            return
        }
        // 登録の無いアドレスでも Firebase は何も言わない。それが正しい挙動で、
        // 区別する再設定フォームは「ここにアカウントがあるか」を調べる道具になる。
        // なので必ず同じ「送信しました」の画面で終わる。
        if (attempt { repository.sendPasswordReset(resetEmail) }) resetSent = true
    }

    /**
     * アドレスが確認済みになったかをサーバーに聞く。
     *
     * 確認はメールアプリの中で起きるので、こちらには何も届かない。終わった人が
     * これを押し、通るか「まだ届いていません」と言われるかのどちらかになる。
     * 押しても何も起きない画面の前に取り残さない。
     */
    suspend fun checkVerification() {
        if (!attempt { repository.refresh() }) return
        if (repository.state.value is AuthState.NeedsVerification) {
            notice = AuthNotice.StillUnverified
        }
    }

    fun signOut() {
        repository.signOut()
        form = AuthForm()
        notice = null
    }

    /** 削除画面で入れ直したパスワード。このオブジェクトの外へは出ない。 */
    var deletePassword by mutableStateOf("")
        private set

    fun updateDeletePassword(value: String) {
        deletePassword = value
        notice = null
    }

    fun beginDeleteAccount() {
        deletePassword = ""
        notice = null
    }

    /**
     * アカウントを削除する。Firebase が求めたときだけ、先に本人確認をやり直す。
     *
     * この再試行は回避策ではない。Firebase は削除に最近のサインインを要求する。
     * 言われたときだけ2回目を挟むことで、普通の場合は1タップのまま、放置された
     * セッションでは実行しない、という両方が成り立つ。
     */
    suspend fun deleteAccount(activityContext: Context): Boolean {
        if (attempt { repository.deleteAccount() }) return true
        if ((notice as? AuthNotice.Failed)?.failure != AuthFailure.NeedsRecentLogin) return false

        val user = (state.value as? AuthState.SignedIn)?.user ?: return false
        val provedIdentity = if (AuthProvider.Google in user.providers) {
            attempt {
                google.requestIdToken(activityContext).fold(
                    onSuccess = { repository.reauthenticateWithGoogle(it) },
                    onFailure = { Result.failure(it) },
                )
            }
        } else {
            attempt { repository.reauthenticateWithPassword(deletePassword) }
        }
        if (!provedIdentity) return false

        return attempt { repository.deleteAccount() }
    }

    private suspend fun attempt(block: suspend () -> Result<Unit>): Boolean {
        if (form.busy) return false
        form = form.copy(busy = true)
        notice = null
        val result = block()
        form = form.copy(busy = false)
        return result.fold(
            onSuccess = { true },
            onFailure = { error ->
                val failure = (error as? AuthException)?.failure ?: AuthFailure.Unknown
                // Google のシートを閉じたのは判断であって失敗ではない。赤い帯を出すのは、
                // その人に「何か間違えた」と伝えること。
                if (failure != AuthFailure.Cancelled) notice = AuthNotice.Failed(failure)
                false
            },
        )
    }
}
