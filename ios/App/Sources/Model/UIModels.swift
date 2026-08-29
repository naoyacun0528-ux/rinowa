import Foundation
import UIKit
import RinowaCore

/// 画面が扱う形。
///
/// `RinowaCore` の型は**線の上を通る形**で、こちらは**画面に出す形**。分けてある理由は、
/// 一覧に出す「3分前」や「未読2件」は送られてこないから。端末が組み立てるもの。
///
/// Firebase はまだ繋いでいない。繋ぐときは `ConversationStore` の後ろだけ差し替える。
/// 画面はどこから来たかを知らない。

struct ChatMessage: Identifiable, Equatable {
    let id: String
    let senderId: String
    let senderName: String
    let timestampMs: Int64
    let status: MessageStatus
    let content: Content
    var reactions: [Reaction]
    var replyTo: Quote?
    var isMine: Bool
    /// 端末に既に届いているもの。**まだなら nil。**
    /// nil でも幅と高さは `content` が持っているので、届く前から場所を空けられる。
    var media: MediaAttachment? = nil

    enum Content: Equatable {
        case text(String)
        case sticker(String)
        case image(width: Int, height: Int)
        case video(seconds: Int)
        case call(video: Bool, outcome: CallOutcome, seconds: Int)
        /// 封を開けられなかったもの。**空欄にはしない。**
        /// 空だと「送られていない」と受け取られる。
        case locked
        /// 読まれたあとに取り消したもの。空の本文とは別の状態。
        case retracted
    }

    /// 写真や動画の本体。
    ///
    /// 本文と同じで、**端末の外にある間は封がしてある**。ここに現れるのは
    /// 開いたあとのものだけなので、`RinowaCore` の型には無い。
    struct MediaAttachment: Equatable {
        var url: URL? = nil
        var thumbnail: UIImage? = nil
    }

    struct Quote: Equatable {
        let senderName: String
        let excerpt: String
    }

    /// 一覧や引用に出す短い代わり。**判断は RinowaCore に任せる。**
    /// ここで書き直すと Android とずれる。
    var preview: String {
        switch content {
        case .text(let body): return body
        case .sticker: return MessagePreview.text(for: .sticker("")).value
        case .image: return MessagePreview.text(for: .image(.init(
            mediaId: "", width: 0, height: 0, byteCount: 0, thumbnail: Data(),
            mediaKey: nil, originalId: nil, originalKey: nil,
            originalBytes: nil, originalMime: nil))).value
        case .video: return MessagePreview.text(for: .video(.init(
            mediaId: "", width: 0, height: 0, durationMs: 0, byteCount: 0,
            sealedBytes: 0, thumbnail: Data(), mediaKey: nil))).value
        case .call(let video, _, _):
            return video ? "ビデオ通話" : "音声通話"
        case .locked: return MessagePreview.locked.value
        case .retracted: return MessagePreview.retracted.value
        }
    }
}

struct Conversation: Identifiable, Equatable {
    let id: String
    let title: String
    /// アイコンの色を決めるためだけの数。名前から作るので、端末が変わっても同じ色。
    let seed: Int
    var messages: [ChatMessage]
    var unreadCount: Int
    var isGroup: Bool

    var lastMessage: ChatMessage? { messages.last }

    var lastActivityMs: Int64 { messages.last?.timestampMs ?? 0 }
}

/// 会話をどこから取るか。
///
/// いまは端末の中の見本。Firebase を繋ぐときは、この後ろだけ差し替える。
/// **画面は出どころを知らない。**
@MainActor
final class ConversationStore: ObservableObject {
    @Published private(set) var conversations: [Conversation]

    /// 自分の招待コード。**これを渡した相手だけが自分を見つけられる。**
    /// 本物はサーバーが持つ。ここは画面を組み立てるための見本。
    let myInviteCode: String? = "K7QM3XPD"

    /// 自分の名前とアドレス。本物は Firebase が持つ。ここは画面のための見本。
    let myName: String? = "みなと"
    let myEmail: String? = "minato@example.com"
    @Published var signedIn: Bool = true

    init(conversations: [Conversation] = SampleData.conversations) {
        self.conversations = conversations
    }

    func conversation(id: String) -> Conversation? {
        conversations.first { $0.id == id }
    }

    func send(_ text: String, to conversationId: String) {
        guard let index = conversations.firstIndex(where: { $0.id == conversationId }) else { return }
        let message = ChatMessage(
            id: UUID().uuidString,
            senderId: SampleData.me,
            senderName: "自分",
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            status: .sending,
            content: .text(text),
            reactions: [],
            replyTo: nil,
            isMine: true
        )
        conversations[index].messages.append(message)

        // 送信の状態が進むところ。**本物の送信はまだ繋いでいない。**
        // 繋ぐまでの間、画面の側だけ先に正しく作れるようにしてある。
        Task {
            try? await Task.sleep(nanoseconds: 400_000_000)
            await MainActor.run { self.advance(message.id, in: conversationId, to: .sent) }
        }
    }

    func markRead(_ conversationId: String) {
        guard let index = conversations.firstIndex(where: { $0.id == conversationId }) else { return }
        conversations[index].unreadCount = 0
    }

    func toggleReaction(_ paletteIndex: Int, on messageId: String, in conversationId: String) {
        guard let ci = conversations.firstIndex(where: { $0.id == conversationId }),
              let mi = conversations[ci].messages.firstIndex(where: { $0.id == messageId })
        else { return }

        var reactions = conversations[ci].messages[mi].reactions
        if let ri = reactions.firstIndex(where: { $0.paletteIndex == paletteIndex && $0.mine }) {
            let old = reactions[ri]
            if old.count <= 1 {
                reactions.remove(at: ri)
            } else {
                reactions[ri] = Reaction(paletteIndex: paletteIndex, count: old.count - 1, mine: false)
            }
        } else if let ri = reactions.firstIndex(where: { $0.paletteIndex == paletteIndex }) {
            let old = reactions[ri]
            reactions[ri] = Reaction(paletteIndex: paletteIndex, count: old.count + 1, mine: true)
        } else {
            reactions.append(Reaction(paletteIndex: paletteIndex, count: 1, mine: true))
        }
        conversations[ci].messages[mi].reactions = reactions
    }

    private func advance(_ messageId: String, in conversationId: String, to status: MessageStatus) {
        guard let ci = conversations.firstIndex(where: { $0.id == conversationId }),
              let mi = conversations[ci].messages.firstIndex(where: { $0.id == messageId })
        else { return }
        let old = conversations[ci].messages[mi]
        conversations[ci].messages[mi] = ChatMessage(
            id: old.id, senderId: old.senderId, senderName: old.senderName,
            timestampMs: old.timestampMs, status: status, content: old.content,
            reactions: old.reactions, replyTo: old.replyTo, isMine: old.isMine
        )
    }
}

#if DEBUG
extension ConversationStore {
    /// 撮影のときだけ、写真の本体を後から入れる。
    ///
    /// 見本データ自身には持たせない——**まだ届いていない状態も撮りたい**し、
    /// 「本体が無くても形は分かる」という約束を試験で押さえているため。
    func attachSampleMedia(_ image: UIImage) {
        for (ci, conversation) in conversations.enumerated() {
            for (mi, message) in conversation.messages.enumerated() {
                if case .image = message.content {
                    conversations[ci].messages[mi].media = .init(thumbnail: image)
                }
            }
        }
    }
}
#endif


/// 話したことがある人。
///
/// **友達一覧という別の入れ物は無い。** 話したことがあることが知っていること、
/// という Android 側の決めごとをそのまま持ってくる。
struct Contact: Identifiable, Equatable {
    let id: String
    let displayName: String
    let seed: Int
}

extension ConversationStore {
    /// グループに入れられる相手。1対1で話したことのある人だけ。
    var contacts: [Contact] {
        conversations
            .filter { !$0.isGroup }
            .map { Contact(id: $0.id, displayName: $0.title, seed: $0.seed) }
    }
}
