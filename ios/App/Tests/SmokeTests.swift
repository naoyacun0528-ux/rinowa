import XCTest
import RinowaCore
@testable import RinowaApp

/// アプリの側から見た確認。
///
/// `RinowaCore` 自身のテストは Windows でも通っている。ここで見たいのはそれとは別で、
/// **iOS のアプリに組み込んだ状態でも同じものが同じように動くか**。
/// 中身が違うわけではないが、繋ぎ方で壊れることはある。画面を積む前に知りたい。
///
/// あわせて、画面が使う組み立て（見本・送信・未読・反応・スワイプ）も見る。
/// 画面そのものは目で見るしかないが、**その下の判断は機械で押さえられる**。
final class SmokeTests: XCTestCase {

    // ---------------------------------------------------------------- 中核

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
    }

    /// **本文を持つ型が、iOS の上でも中身を吐かないこと。**
    func testMessageTextStillHidesItsBodyOnThisPlatform() {
        let secret = "口座の暗証番号は4649"
        let text = MessageText(secret)
        XCTAssertFalse("\(text)".contains(secret))
        XCTAssertFalse(text.debugDescription.contains(secret))
        XCTAssertFalse(String(reflecting: text).contains(secret))
    }

    func testCryptoIdShape() {
        XCTAssertEqual(CryptoIds.domain, "lowan.local")
        XCTAssertEqual(
            CryptoIds.matrixUser(UserId("K1mN4pQ7rS0tU3vW6xY9zA2bC5dE")),
            "@K1mN4pQ7rS0tU3vW6xY9zA2bC5dE:lowan.local"
        )
    }

    // ---------------------------------------------------------------- 画面の下

    /// 見本の会話が、画面が使える形になっていること。
    @MainActor
    func testSampleDataIsUsable() {
        let store = ConversationStore()
        XCTAssertFalse(store.conversations.isEmpty)
        for conversation in store.conversations {
            XCTAssertFalse(conversation.title.isEmpty)
            XCTAssertFalse(conversation.messages.isEmpty, conversation.title)
            // 一覧のプレビューが必ず何か返すこと。**空欄は「送られていない」に見える。**
            let preview = conversation.lastMessage?.preview ?? ""
            XCTAssertFalse(preview.isEmpty, conversation.title)
        }
    }

    /// 送ると増えること。状態が Sending から始まること。
    @MainActor
    func testSendingAppendsAMessage() {
        let store = ConversationStore()
        guard let id = store.conversations.first?.id else { return XCTFail("会話が無い") }
        let before = store.conversation(id: id)?.messages.count ?? 0
        store.send("てすと", to: id)
        let last = store.conversation(id: id)?.messages.last
        XCTAssertEqual(store.conversation(id: id)?.messages.count, before + 1)
        XCTAssertEqual(last?.status, .sending)
        XCTAssertEqual(last?.isMine, true)
    }

    /// 開いたら未読が消えること。
    @MainActor
    func testOpeningClearsUnread() {
        let store = ConversationStore()
        guard let unread = store.conversations.first(where: { $0.unreadCount > 0 }) else {
            return XCTFail("未読のある見本が無い")
        }
        store.markRead(unread.id)
        XCTAssertEqual(store.conversation(id: unread.id)?.unreadCount, 0)
    }

    /// 押すと付き、もう一度押すと外れること。
    @MainActor
    func testReactionTogglesBothWays() {
        let store = ConversationStore()
        guard let conversation = store.conversations.first,
              let message = conversation.messages.first(where: { $0.reactions.isEmpty })
        else { return XCTFail("反応の無いメッセージが無い") }

        store.toggleReaction(0, on: message.id, in: conversation.id)
        var got = store.conversation(id: conversation.id)?
            .messages.first(where: { $0.id == message.id })?.reactions
        XCTAssertEqual(got?.count, 1)
        XCTAssertEqual(got?.first?.mine, true)

        store.toggleReaction(0, on: message.id, in: conversation.id)
        got = store.conversation(id: conversation.id)?
            .messages.first(where: { $0.id == message.id })?.reactions
        XCTAssertEqual(got?.isEmpty, true)
    }

    // ---------------------------------------------------------------- 手触り

    /// **スワイプの抵抗。** 閾値を越えても指なりには動かない。
    func testSwipeResistanceIsAsymptotic() {
        XCTAssertEqual(RinowaSwipe.resist(40), 40, accuracy: 0.001, "閾値までは指なり")
        let far = RinowaSwipe.resist(400)
        XCTAssertLessThan(far, RinowaSwipe.maxDistance, "限界を越えない")
        XCTAssertGreaterThan(far, RinowaSwipe.threshold, "閾値より先へは進む")
    }

    /// 時刻の書き方が Android と同じ形であること。
    func testDurationNeverPadsTheMinute() {
        // 00:07 とは書かない。実際より長く見える。
        XCTAssertEqual(RinowaFormat.duration(ms: 7_000), "0:07")
        XCTAssertEqual(RinowaFormat.duration(ms: 83_000), "1:23")
        XCTAssertEqual(RinowaFormat.callDuration(seconds: 3_750), "1:02:30")
    }

    /// 色は明暗の2つとも揃っていること。片方だけ足すのを防ぐ。
    func testBothPalettesExist() {
        XCTAssertTrue(RinowaColors.light.isLight)
        XCTAssertFalse(RinowaColors.dark.isLight)
    }
}
