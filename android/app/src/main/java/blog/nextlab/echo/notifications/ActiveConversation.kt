package blog.nextlab.echo.notifications

/**
 * いま画面に出ている会話。無ければ null。
 *
 * すでに読んでいる会話への通知は雑音。届くのを本人が見ている。しかも、目を向けて
 * いる当のものについて、手の中の端末を震わせることになる。
 *
 * push サーバーにはこれを知りようが無い。知るには、受信者全員がどの画面を見ているかを
 * 継続的に伝える必要があり、通知1つを省くために集めてよい情報ではない。だから判断は
 * ここ、答えを無料で持っている端末の側でやる。
 *
 * 書くのはチャット画面、読むのは [RinowaMessagingService] で、走るスレッドが違う。
 * だから `@Volatile`。
 */
object ActiveConversation {

    @Volatile
    private var openId: String? = null

    /** [conversationId] のチャット画面が前面にある間、入っている。 */
    fun enter(conversationId: String) {
        openId = conversationId
    }

    fun leave(conversationId: String) {
        // 消す前に比べる。すでに別の会話に置き換わった画面を離れるとき、
        // 新しいほうの主張まで消してしまわないように。
        if (openId == conversationId) openId = null
    }

    /** Rinowa が背面に回ったら消す。ホーム画面の裏のチャットは読まれていない。 */
    fun clear() {
        openId = null
    }

    fun isOpen(conversationId: String): Boolean = openId == conversationId
}
