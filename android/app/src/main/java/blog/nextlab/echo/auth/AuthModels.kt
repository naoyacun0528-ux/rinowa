package blog.nextlab.echo.auth

import androidx.compose.runtime.Immutable

/**
 * サインインしている人。このアプリが必要とする範囲で。
 *
 * Firebase の user のわざと薄い写し。ここから計測へ行くものは何も無い。
 * `:core:analytics` には文字列を受ける項目の型が無いので、メールアドレスも uid も
 * 事故でも報告できない。docs/PRIVACY_PRINCIPLES.md。
 */
@Immutable
data class RinowaUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val emailVerified: Boolean,
    val providers: Set<AuthProvider>,
)

enum class AuthProvider { Google, Password }

/**
 * サインインについて、その人がいまどこにいるか。
 *
 * [NeedsVerification] はユーザーのフラグではなく独立した状態にしてある。実際に
 * 留まる場所だから（Firebase から見ればサインイン済みで、まだ通していない）。
 * 真偽値にすると、どこかの画面で確認を忘れる。
 */
@Immutable
sealed interface AuthState {
    data object Loading : AuthState

    data object SignedOut : AuthState

    /** メールで登録済みだが、アドレスがまだ確認されていない。 */
    data class NeedsVerification(val user: RinowaUser) : AuthState

    data class SignedIn(val user: RinowaUser) : AuthState
}

/**
 * 何が起きたかを、UI が動ける言葉で。
 *
 * Firebase の文言は英語で技術的で、ときにアドレスが登録済みかどうかまで明かす。
 * そこを漏らさずに、本当で役に立つことを言えるようにこの分類を選んである。
 */
sealed interface AuthFailure {
    data object NoNetwork : AuthFailure
    data object InvalidEmail : AuthFailure
    data object WeakPassword : AuthFailure
    data object EmailAlreadyUsed : AuthFailure
    data object WrongCredentials : AuthFailure
    data object TooManyAttempts : AuthFailure

    /**
     * これだけ重い操作にはセッションが古すぎる。
     *
     * Firebase はアカウント削除の前に最近のサインインを求める。借りた解錠済みの端末で、
     * 何時間もあとにアカウントを壊せないように。
     */
    data object NeedsRecentLogin : AuthFailure
    data object Cancelled : AuthFailure
    data object NoGoogleAccount : AuthFailure
    data object Unknown : AuthFailure
}

class AuthException(val failure: AuthFailure) : Exception(failure::class.simpleName)
