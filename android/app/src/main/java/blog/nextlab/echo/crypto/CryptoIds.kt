package blog.nextlab.echo.crypto

import blog.nextlab.echo.core.model.ConversationId
import blog.nextlab.echo.core.model.UserId

/**
 * Rinowa の id と、暗号ライブラリが要求する id の変換。
 *
 * 暗号エンジンは Matrix のもので、Matrix の id には形がある（利用者は `@name:server`、
 * 部屋は `!id:server`）。エンジンはそれを解析し、署名し、署名済みの鍵の束の中に入れる。
 * つまり省ける飾りではない。
 *
 * Rinowa にサーバーは無く、連合もしない。だからここのドメインは**何も意味しない定数**で、
 * id が解析できるように置いてあるだけ。解決もしないし、接続もしないし、DNS も無い。
 *
 * 変えてはいけない理由: このドメインは署名済みの鍵素材の中に入る。あとで変えると、
 * 既存の端末の鍵が新しい id に対して検証できなくなる。「鍵が無い」ではなく
 * **「署名が一致しない」**で、移行ではなく攻撃に見える。
 *
 * > **`DOMAIN` は凍結。変える必要が出たら、それは改名ではなく全員の鍵の作り直し。**
 */
object CryptoIds {

    /**
     * わざと `.local`。
     *
     * 登録できない予約された接尾辞なので、誰か他人の持つ本物のホストに
     * うっかりなることがない。
     */
    const val DOMAIN = "lowan.local"
    // **アプリが Rinowa に改名しても、ここは動かさない。**
    // この文字列は全端末の識別子（`@uid:lowan.local`）と全会話の部屋 id に
    // 焼き付いていて、公開済みの鍵にも入っている。書き換えると、
    // 既に鍵を交換した相手と**二度と噛み合わなくなる**。

    /** `@<firebase uid>:lowan.local` */
    fun matrixUser(user: UserId): String = "@${user.value}:$DOMAIN"

    /** `!<conversation id>:lowan.local` */
    fun matrixRoom(conversation: ConversationId): String = "!${conversation.value}:$DOMAIN"

    /**
     * Firebase の uid に戻す。
     *
     * 形が違えば推測せず null を返す。解析できない id は想定外のところから来たもので、
     * そこから uid をでっち上げると、その推測が指した人のメッセージということになる。
     */
    fun userFromMatrix(matrix: String): UserId? {
        if (!matrix.startsWith("@")) return null
        val colon = matrix.indexOf(':')
        if (colon <= 1) return null
        return UserId(matrix.substring(1, colon))
    }
}
