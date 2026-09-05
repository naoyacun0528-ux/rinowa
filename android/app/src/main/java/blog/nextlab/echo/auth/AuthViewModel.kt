package blog.nextlab.echo.auth

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import blog.nextlab.echo.core.model.UserId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * この端末を、そのアカウントの端末一覧から外すもの。
     *
     * ここが関数1つなのは、認証がリポジトリの袋（RinowaServices）を知らずに済むから。
     * 知る必要があるのは「出ていく前に呼ぶものがある」ことだけで、それが Firestore
     * なのかどうかは、この画面の関心ではない。
     *
     * 既定を必須の引数にしないのは、Firebase の設定が無いビルドを巻き込まないため。
     * ただし既定は黙らない。**渡されていないと登録は消えないまま、症状は
     * 「サインアウトしたのに前のアカウント宛の通知が届く」という遠いところに出る。**
     * 何も言わずに通る既定は、この穴をもう一度掘る。
     */
    private val forgetPushDevice: suspend (UserId) -> Unit = {
        android.util.Log.w(
            LOG,
            "端末の push 登録を消す手立てが渡されていない。この端末は登録されたまま出ていく",
        )
    },
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

    /**
     * サインアウトする。Firebase を切る**前に**、この端末の push 登録を消す。
     *
     * 順番がこの関数の中身のほとんど。`users/{uid}/devices` は本人しか書けない
     * （firestore.rules）ので、先に auth を切ると、消しに行った時点で自分の端末を
     * 消す資格が無い。残ったトークンは server/push.php が端末一覧をなめて拾い、
     * FCM は生きているトークンに 200 を返すので、掃除もされない。
     * つまり一度置き去りにすると、アプリを消すまで前のアカウント宛の通知が届き続ける。
     * deviceId は1インストールに1つなので、端末を人に譲れば届く先はその人になる。
     *
     * 呼び出し側は `() -> Unit` として持っている（AccountScreen の `::signOut`）ので
     * suspend にはできない。コルーチンはここで開く。
     */
    fun signOut() {
        val leaving = currentUserId()
        form = AuthForm()
        notice = null
        viewModelScope.launch {
            try {
                if (leaving != null) forgetThisDevice(leaving)
            } finally {
                // 何があってもサインアウトはする。アカウントは端末を出るべきで、
                // 登録が消せないことを理由に居座らせるほうが害が大きい。
                // finally なのは、この ViewModel が先に片付いた場合でも
                // 「押したのにサインアウトしていない」を作らないため。
                repository.signOut()
            }
        }
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
     *
     * サインアウトと同じ理由で、端末を外すのが先。しかもこちらは取り返しがつかない。
     * Firestore は下のコレクションを連鎖して消さないので、`users/{uid}/devices` は
     * アカウントが消えたあとも残り、それを消せる人はもうどこにもいない。
     * server/push.php は会話の memberIds をなめるだけでアカウントの生死を見ないから、
     * 退会したはずの端末にグループの通知が届き続ける。
     */
    suspend fun deleteAccount(activityContext: Context): Boolean {
        // 外すのを attempt の中に入れてあるのは、その間 busy でいるため。外に出すと
        // 待っている数秒のあいだボタンが生きていて、削除を2回始められる。
        //
        // 削除が失敗して、本人確認をやり直す途中でやめた場合、この端末は登録の無いまま
        // サインインしたまま残る。通知は次の起動で戻る（ChatListViewModel.bind）。
        // 数時間通知が来ないのと、退会したのに届き続けるのとでは、後者だけが直せない。
        val deleted = attempt {
            currentUserId()?.let { forgetThisDevice(it) }
            repository.deleteAccount()
        }
        if (deleted) return true
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

    /**
     * この端末を [owner] の端末一覧から外す。失敗しても投げない。
     *
     * 待つのに上限を置いてあるのは、圏外だと Firestore の削除がいつまでも返らないから
     * （ローカルには積まれるが、`await` が終わるのはサーバーが受け取ったとき）。
     * 上限が無いと、サインアウトを押した人が動かない画面の前に取り残される。
     * それは「消せなかったらサインアウトを止める」を、待ち時間のかたちでやっているのと同じ。
     */
    private suspend fun forgetThisDevice(owner: UserId) {
        val finished = withTimeoutOrNull(FORGET_DEVICE_TIMEOUT_MS) {
            runCatching { forgetPushDevice(owner) }
                .onFailure { android.util.Log.w(LOG, "端末の push 登録を消せなかった", it) }
        }
        if (finished == null) {
            // 積まれた削除は、繋がった時点でもう認証が切れていて拒まれる。
            // つまり圏外でのサインアウトは登録を置いていく。分かっていて進む。
            android.util.Log.w(LOG, "端末の push 登録の削除が返らなかった。登録は残る")
        }
    }

    /**
     * いまこの端末に居るアカウント。
     *
     * 確認待ちも含める。[AuthState.NeedsVerification] は Firebase から見れば
     * サインイン済みで、その画面にも「別のアカウントを使う」がある。
     */
    private fun currentUserId(): UserId? = when (val current = state.value) {
        is AuthState.SignedIn -> UserId(current.user.uid)
        is AuthState.NeedsVerification -> UserId(current.user.uid)
        else -> null
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

    private companion object {
        const val LOG = "Rinowa/auth"

        /**
         * 端末を外すのに待つ上限。
         *
         * 繋がっていれば1往復で、ふつうはこの何分の1かで終わる。長くしても
         * 圏外のときに画面が止まる時間が伸びるだけで、消える見込みは増えない。
         */
        const val FORGET_DEVICE_TIMEOUT_MS = 3_000L
    }
}
