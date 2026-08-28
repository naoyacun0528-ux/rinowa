import XCTest
import RinowaCore
@testable import RinowaApp

/// アプリの側から `RinowaCore` を触れることの確認。
///
/// `RinowaCore` 自身のテストは Windows でも通っている。ここで見たいのはそれとは別で、
/// **iOS のアプリに組み込んだ状態でも、同じものが同じように動くか**。
///
/// 中身が違うわけではないが、繋ぎ方（パッケージの解決、ビットコードの都合、
/// 最適化の設定）で壊れることはある。壊れるなら、画面を積む前に知りたい。
final class SmokeTests: XCTestCase {

    func testTheCoreIsReachableFromTheApp() throws {
        let context = YosegiContext(
            conversationId: "aB3dEf6hIj9lMn2pQr5t",
            memberIds: ["K1mN4pQ7rS0tU3vW6xY9zA2bC5dE"],
            stickerCatalogue: []
        )
        let sent = YosegiMessage(
            id: "Msg0000000000000001A",
            senderId: "K1mN4pQ7rS0tU3vW6xY9zA2bC5dE",
            timestampMs: 1_755_390_600_000,
            status: .sent,
            text: "おはよう"
        )

        let frame = try Yosegi.encode([sent], context: context)
        let back = try Yosegi.decode(frame, context: context)

        XCTAssertEqual(back.count, 1)
        XCTAssertEqual(back.first?.text, "おはよう")
        XCTAssertEqual(back.first?.id, sent.id)
        XCTAssertEqual(back.first?.senderId, sent.senderId)
    }

    /// **本文を持つ型が、iOS の上でも中身を吐かないこと。**
    ///
    /// ここは環境で変わりうる。文字列展開の実装はプラットフォームごとなので、
    /// Windows で塞がっていても iOS で開いている、が起こりうる。
    func testMessageTextStillHidesItsBodyOnThisPlatform() {
        let secret = "口座の暗証番号は4649"
        let text = MessageText(secret)
        XCTAssertFalse("\(text)".contains(secret))
        XCTAssertFalse(text.debugDescription.contains(secret))
        XCTAssertFalse(String(reflecting: text).contains(secret))
    }

    /// 触覚の語彙が、アプリ側から全部見えること。
    func testHapticVocabularyIsComplete() {
        XCTAssertEqual(HapticToken.allCases.count, 12)
        for token in HapticToken.allCases {
            let spec = HapticTokens[token]
            XCTAssertFalse(spec.points.isEmpty, "\(token.rawValue)")
            XCTAssertEqual(spec.points.last?.intensity, 0, "\(token.rawValue): 0で終わっていない")
        }
    }

    /// 暗号の識別子が Android と同じ形であること。ここがずれると鍵が噛み合わない。
    func testCryptoIdShape() {
        XCTAssertEqual(CryptoIds.domain, "lowan.local")
        XCTAssertEqual(
            CryptoIds.matrixUser(UserId("K1mN4pQ7rS0tU3vW6xY9zA2bC5dE")),
            "@K1mN4pQ7rS0tU3vW6xY9zA2bC5dE:lowan.local"
        )
    }
}
