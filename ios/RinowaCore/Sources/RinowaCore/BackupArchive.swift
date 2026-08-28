import Foundation

/// バックアップに何が入るか。`BackupArchive.kt` の Swift 側。
///
/// メッセージと、写真に届く鍵。写真のバイト列は入れない。写真は保管庫から取り直せるが、
/// メッセージは開く鍵が消えたらどこからも戻らない。docs/WIRE_FORMATS.md §2.2。
public enum BackupArchive {

    public static let version = 1

    public struct Entry: Equatable {
        public let conversationId: String
        public let messageId: String
        public let senderId: String
        public let sentAtMs: Int64
        public let content: MessageContent

        public init(
            conversationId: String,
            messageId: String,
            senderId: String,
            sentAtMs: Int64,
            content: MessageContent
        ) {
            self.conversationId = conversationId
            self.messageId = messageId
            self.senderId = senderId
            self.sentAtMs = sentAtMs
            self.content = content
        }
    }

    public struct Parsed: Equatable {
        public let version: Int
        public let owner: String
        public let createdAtMs: Int64
        public let entries: [Entry]
    }

    public static func write(owner: String, createdAtMs: Int64, entries: [Entry]) -> String {
        let messages: [[String: Any]] = entries.map { entry in
            [
                "c": entry.conversationId,
                "m": entry.messageId,
                "s": entry.senderId,
                "t": entry.sentAtMs,
                // 封の中身をそのまま。メッセージが存在する2箇所で符号化器を1つにする。
                "e": MessageEnvelope.seal(entry.content)
            ]
        }

        let root: [String: Any] = [
            "v": version,
            "owner": owner,
            "at": createdAtMs,
            "messages": messages
        ]

        guard
            let data = try? JSONSerialization.data(withJSONObject: root),
            let text = String(data: data, encoding: .utf8)
        else {
            return ""
        }
        return text
    }

    /// 書庫を読む。このビルドが解せるものでなければ nil。
    ///
    /// 新しいビルドが書いたファイルは、半分読まずに拒否する。ばらばらに戻ってくる会話は、
    /// 戻ってこない会話より悪い。うまくいったように見えるから。
    public static func read(_ json: String) -> Parsed? {
        guard
            let data = json.data(using: .utf8),
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let rawVersion = (root["v"] as? NSNumber)?.intValue,
            rawVersion > 0, rawVersion <= version,
            let owner = root["owner"] as? String, !owner.isEmpty
        else {
            return nil
        }

        let messages = root["messages"] as? [[String: Any]] ?? []
        let entries: [Entry] = messages.compactMap { item in
            guard
                let conversation = item["c"] as? String, !conversation.isEmpty,
                let message = item["m"] as? String, !message.isEmpty,
                let sender = item["s"] as? String, !sender.isEmpty,
                let envelope = item["e"] as? String,
                let content = MessageEnvelope.open(envelope)
            else {
                return nil
            }

            return Entry(
                conversationId: conversation,
                messageId: message,
                senderId: sender,
                sentAtMs: Int64((item["t"] as? NSNumber)?.int64Value ?? 0),
                content: content
            )
        }

        return Parsed(
            version: rawVersion,
            owner: owner,
            createdAtMs: Int64((root["at"] as? NSNumber)?.int64Value ?? 0),
            entries: entries
        )
    }
}
