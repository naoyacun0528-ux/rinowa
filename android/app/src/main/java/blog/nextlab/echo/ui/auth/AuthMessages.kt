package blog.nextlab.echo.ui.auth

import blog.nextlab.echo.auth.AuthFailure
import blog.nextlab.echo.auth.AuthNotice

/**
 * それぞれの結果を、その人に何と言うか。
 *
 * 文言を決める規則が2つ:
 *
 *  - アドレスが登録済みかどうかを絶対に明かさない。「そのアドレスは登録されていません」は、
 *    サインインのフォームを、誰が Rinowa を使っているか調べる道具に変える。
 *  - 次に何をすればよいかを言う。「エラーが発生しました」は誰にも何も伝えない。
 */
internal fun AuthNotice.text(): String = when (this) {
    is AuthNotice.Failed -> failure.text()
    AuthNotice.VerificationSent -> "確認メールを送りました。"
    AuthNotice.ResetSent -> "パスワード再設定のメールを送りました。"
    AuthNotice.StillUnverified -> "まだ確認できていません。メール内のリンクを開いてから、もう一度お試しください。"
}

internal fun AuthNotice.isError(): Boolean = this is AuthNotice.Failed

private fun AuthFailure.text(): String = when (this) {
    AuthFailure.NoNetwork -> "通信できませんでした。接続を確認してください。"
    AuthFailure.InvalidEmail -> "メールアドレスの形式が正しくありません。"
    AuthFailure.WeakPassword -> "パスワードは6文字以上にしてください。"
    AuthFailure.EmailAlreadyUsed -> "このメールアドレスは既に使われています。ログインをお試しください。"
    // 「そのアカウントが無い」と「パスワードが違う」を、意図的に同じ文言にする。
    AuthFailure.WrongCredentials -> "メールアドレスまたはパスワードが違います。"
    AuthFailure.TooManyAttempts -> "試行が続いたため一時的に制限されています。しばらく待ってからお試しください。"
    AuthFailure.NeedsRecentLogin -> "確認のため、もう一度ログインしてください。"
    AuthFailure.NoGoogleAccount -> "この端末にGoogleアカウントが見つかりませんでした。"
    AuthFailure.Cancelled -> "中止しました。"
    AuthFailure.Unknown -> "うまくいきませんでした。もう一度お試しください。"
}
