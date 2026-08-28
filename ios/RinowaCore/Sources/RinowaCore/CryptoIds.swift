import Foundation

/// Rinowa の id と、暗号ライブラリが要求する id の変換。
///
/// `crypto/CryptoIds.kt` の Swift 側。
///
/// 暗号エンジンは Matrix のもので、Matrix の id には形がある（利用者は `@name:server`、
/// 部屋は `!id:server`）。エンジンはそれを解析し、署名し、署名済みの鍵の束の中に入れる。
/// つまり省ける飾りではない。
///
/// Rinowa にサーバーは無く、連合もしない。だからここのドメインは**何も意味しない定数**で、
/// id が解析できるように置いてあるだけ。解決もしないし、接続もしないし、DNS も無い。
///
/// **ここが Android と1文字でも違うと、iPhone と Android の間で鍵が噛み合わない。**
/// しかも症状は「鍵が無い」ではなく**「署名が一致しない」**で、
/// 移行ではなく攻撃に見える。research/vectors/crypto-ids.json で縛ってある。
public enum CryptoIds {

    /// わざと `.local`。
    ///
    /// 登録できない予約された接尾辞なので、誰か他人の持つ本物のホストに
    /// うっかりなることがない。
    ///
    /// **アプリが Rinowa に改名しても、ここは動かさない。** この文字列は全端末の
    /// 識別子と全会話の部屋 id に焼き付いていて、公開済みの鍵にも入っている。
    /// 書き換えると、既に鍵を交換した相手と**二度と噛み合わなくなる**。
    public static let domain = "lowan.local"

    /// `@<firebase uid>:lowan.local`
    public static func matrixUser(_ user: UserId) -> String { "@\(user.value):\(domain)" }

    /// `!<conversation id>:lowan.local`
    public static func matrixRoom(_ conversation: ConversationId) -> String {
        "!\(conversation.value):\(domain)"
    }

    /// Firebase の uid に戻す。
    ///
    /// 形が違えば推測せず nil を返す。解析できない id は想定外のところから来たもので、
    /// **そこから uid をでっち上げると、その推測が指した人のメッセージということになる。**
    public static func userFromMatrix(_ matrix: String) -> UserId? {
        guard matrix.hasPrefix("@") else { return nil }
        guard let colon = matrix.firstIndex(of: ":") else { return nil }
        // Kotlin 側は `colon <= 1` で弾く。つまり `@:x` のような空の uid は通さない。
        let start = matrix.index(after: matrix.startIndex)
        guard colon > start else { return nil }
        return UserId(String(matrix[start..<colon]))
    }
}
