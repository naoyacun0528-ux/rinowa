import Foundation
import RinowaCore

/// 画面を作るための見本。
///
/// **空の画面では何も分からない。** 文字の長さ、名前の長さ、時刻の並び、
/// 未読の数、返信の入れ子——実際に入るものが入っていないと、
/// 詰まっているのか空いているのかも見えない。
///
/// Firebase を繋いだらここは消える。それまでの間、画面だけ先に正しく作れる。
enum SampleData {

    static let me = "K1mN4pQ7rS0tU3vW6xY9zA2bC5dE"
    private static let yui = "F8gH1jK4lM7nP0qR3sT6uV9wX2yZ"
    private static let sota = "B5cD8eF1gH4jK7lM0nP3qR6sT9uV"

    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private static func ago(_ minutes: Int) -> Int64 { now - Int64(minutes) * 60_000 }

    static let conversations: [Conversation] = [
        Conversation(
            id: "conv-yui",
            title: "ゆい",
            seed: 3,
            messages: [
                message(id: "m1", from: yui, "ゆい", ago(184), .text("おはよう")),
                message(id: "m2", from: me, "自分", ago(180), .text("おはよ、今日って何時集合だっけ"), status: .read),
                message(id: "m3", from: yui, "ゆい", ago(176), .text("10時！駅の東口ね")),
                message(id: "m4", from: me, "自分", ago(170), .text("りょ"), status: .read),
                message(id: "m5", from: yui, "ゆい", ago(52), .image(width: 1200, height: 1600)),
                message(id: "m6", from: yui, "ゆい", ago(51), .text("これ昨日の"), reactions: [
                    Reaction(paletteIndex: 0, count: 1, mine: true),
                ]),
                message(
                    id: "m7", from: me, "自分", ago(48),
                    .text("いいじゃん"), status: .read,
                    reply: .init(senderName: "ゆい", excerpt: "これ昨日の")
                ),
                message(id: "m8", from: yui, "ゆい", ago(12), .sticker("st_ukeru")),
                message(id: "m9", from: me, "自分", ago(3), .text("今から出る"), status: .delivered),
            ],
            unreadCount: 0,
            isGroup: false
        ),
        Conversation(
            id: "conv-class",
            title: "3年2組",
            seed: 1,
            messages: [
                message(id: "g1", from: sota, "そうた", ago(300), .text("明日の持ち物わかる人")),
                message(id: "g2", from: yui, "ゆい", ago(295), .text("体育館履きって書いてあった気がする")),
                message(id: "g3", from: sota, "そうた", ago(290), .text("たすかる"), reactions: [
                    Reaction(paletteIndex: 4, count: 3, mine: false),
                ]),
                message(id: "g4", from: yui, "ゆい", ago(44), .video(seconds: 27)),
                message(id: "g5", from: sota, "そうた", ago(22), .text("これ体育祭の練習？")),
            ],
            unreadCount: 2,
            isGroup: true
        ),
        Conversation(
            id: "conv-locked",
            title: "はると",
            seed: 5,
            messages: [
                message(id: "l1", from: sota, "はると", ago(1500), .text("課題やった？")),
                // **開けられなかったもの。** この端末に鍵が無い状態。
                // 空欄にすると「送られていない」と読まれるので、そう言わない。
                message(id: "l2", from: sota, "はると", ago(1400), .locked),
                message(id: "l3", from: me, "自分", ago(1380), .text("まだ"), status: .read),
                message(id: "l4", from: sota, "はると", ago(1370), .retracted),
            ],
            unreadCount: 1,
            isGroup: false
        ),
        Conversation(
            id: "conv-call",
            title: "おかあさん",
            seed: 2,
            messages: [
                message(id: "c1", from: me, "自分", ago(2880),
                        .call(video: false, outcome: .completed, seconds: 214), status: .read),
                message(id: "c2", from: sota, "おかあさん", ago(2870), .text("気をつけてね")),
                message(id: "c3", from: sota, "おかあさん", ago(1440),
                        .call(video: true, outcome: .missed, seconds: 0)),
            ],
            unreadCount: 0,
            isGroup: false
        ),
    ]

    private static func message(
        id: String,
        from senderId: String,
        _ senderName: String,
        _ timestampMs: Int64,
        _ content: ChatMessage.Content,
        status: MessageStatus = .sent,
        reactions: [Reaction] = [],
        reply: ChatMessage.Quote? = nil
    ) -> ChatMessage {
        ChatMessage(
            id: id,
            senderId: senderId,
            senderName: senderName,
            timestampMs: timestampMs,
            status: status,
            content: content,
            reactions: reactions,
            replyTo: reply,
            isMine: senderId == me
        )
    }
}
