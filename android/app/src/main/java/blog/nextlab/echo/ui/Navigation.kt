package blog.nextlab.echo.ui

import blog.nextlab.echo.core.model.Conversation

internal sealed interface Screen {
    data object ChatList : Screen
    data class Chat(val conversation: Conversation) : Screen
    data object NewConversation : Screen
    data object NewGroup : Screen
    data object HapticLab : Screen
    data object Account : Screen
    data object DeleteAccount : Screen
    data object Profile : Screen
    data object Feedback : Screen
    data object Privacy : Screen
    data object Backup : Screen

    /**
     * 指紋の読み合わせ。会話ごとに開く。
     *
     * 相手を `Conversation` ごと持つのは、名前と相手の id の両方が要るため。
     * id だけだと画面に出す名前が無く、名前だけだと鍵が引けない。
     */
    data class Safety(val conversation: Conversation) : Screen

    /** Direct-1 の開発用画面。製品の画面ではない（DirectLabScreen を参照）。 */
    data object DirectLab : Screen
}

/** 各画面から戻る先。1箇所にまとめて、画面ごとに勝手な答えを作らせない。 */
internal fun Screen.parent(): Screen = when (this) {
    Screen.ChatList -> Screen.ChatList
    Screen.DeleteAccount -> Screen.Account
    Screen.Privacy -> Screen.Account
    Screen.Backup -> Screen.Account
    Screen.Feedback -> Screen.Account
    is Screen.Safety -> Screen.Chat(this.conversation)
    Screen.DirectLab -> Screen.Account
    Screen.Profile -> Screen.Account
    // どちらも一覧の＋から入るので、戻り先も一覧。
    else -> Screen.ChatList
}

/**
 * 一覧から何階層めか。左右どちらへ動かすかを決めるのに使う。
 *
 * 以前は「行き先が一覧でなければ進む」と見ていた。設定の子画面から設定へ戻るときも
 * 行き先は一覧ではないので、**戻っているのに進む向きの動き**になっていた。左から
 * 出てくるはずの画面が右から入ってくる。
 *
 * 深さは [parent] から数える。親子関係を2か所に書くと、片方だけ直した日に、向きだけが
 * 静かに狂う。
 */
internal fun Screen.depth(): Int {
    var here: Screen = this
    var steps = 0
    while (here != Screen.ChatList && steps < MAX_DEPTH) {
        here = here.parent()
        steps++
    }
    return steps
}

/** 階層の想定上限。parent が輪を作っても止まるようにするためのもので、意味は無い。 */
private const val MAX_DEPTH = 8

