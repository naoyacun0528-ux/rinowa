import Foundation

/// `model/Models.kt` と `model/Sticker.kt` の Swift 側。
///
/// 画面を持たない部分だけを写す。Compose の `@Immutable` に当たるものは Swift には
/// 要らない（`struct` が既に値型なので）。
///
/// 写す意味があるのは**振る舞いを持つもの**だけ。ただの入れ物なら、2つ目の実装が
/// あっても何も確かめられない。ここに来ているのは、
/// 本文をログに出さない仕掛けと、一覧に出す短い文字列と、固定された並び。

// ---------------------------------------------------------------- 本文

/// メッセージの本文。
///
/// **説明に中身を出さない。** ログへの文字列展開は本文が端末の外へ出る一番ありがちな
/// 経路で、`print("sending \(text)")` が本文をそのまま吐く。
/// docs/PRIVACY_PRINCIPLES.md の防御層3。
///
/// Kotlin 側は `toString()` を潰している。Swift では `CustomStringConvertible` と
/// `CustomDebugStringConvertible` の**両方**を潰す必要がある。片方だけだと
/// `debugPrint` と `\(...)` のどちらかから漏れる。
public struct MessageText: Equatable, Hashable, CustomStringConvertible, CustomDebugStringConvertible {
    public let value: String

    public init(_ value: String) { self.value = value }

    public var length: Int { value.count }
    public var isBlank: Bool { value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    public var description: String { "MessageText(len=\(length))" }
    public var debugDescription: String { description }
}

// ---------------------------------------------------------------- 識別子

/// 素の `String` にしないのは、会話 id をメッセージ id の場所へ渡せないようにするため。
/// 中身は Firestore のドキュメント id で、解釈も並べ替えもしない。
public struct ConversationId: Equatable, Hashable {
    public let value: String
    public init(_ value: String) { self.value = value }
}

public struct MessageId: Equatable, Hashable {
    public let value: String
    public init(_ value: String) { self.value = value }
}

public struct UserId: Equatable, Hashable {
    public let value: String
    public init(_ value: String) { self.value = value }
}

public struct StickerId: Equatable, Hashable {
    public let value: String
    public init(_ value: String) { self.value = value }
}

public struct StickerPackId: Equatable, Hashable {
    public let value: String
    public init(_ value: String) { self.value = value }
}

public struct MediaId: Equatable, Hashable {
    public let value: String
    public init(_ value: String) { self.value = value }
}

// ---------------------------------------------------------------- 状態

public enum MessageStatus: String, CaseIterable {
    case sending = "Sending"
    case sent = "Sent"
    case delivered = "Delivered"
    case read = "Read"
    case failed = "Failed"
}

/// 通話がどう終わったか。スレッドに書くための粒度。
///
/// 実装内部の理由よりわざと粗い。経路が見つからなかったのか、話す前に切れたのかは、
/// 当事者にとっては同じ「通話できなかった」。
public enum CallOutcome: String, CaseIterable {
    /// 出た。通話の秒数が意味を持つのはこれだけ。
    case completed = "Completed"
    case missed = "Missed"
    case declined = "Declined"
    case failed = "Failed"
}

// ---------------------------------------------------------------- 反応

public struct Reaction: Equatable {
    public let paletteIndex: Int
    public let count: Int
    public let mine: Bool

    public init(paletteIndex: Int, count: Int, mine: Bool) {
        self.paletteIndex = paletteIndex; self.count = count; self.mine = mine
    }

    public var emoji: String { ReactionPalette.emoji(at: paletteIndex) }
}

/// **固定の並び。** 線の上を通るのは絵文字ではなく番号なので、
/// 並べ替えると過去の反応が別のものになる。
public enum ReactionPalette {
    public static let version = 1
    public static let emoji: [String] = ["❤️", "😂", "😮", "😢", "👍", "🔥"]

    public static func emoji(at index: Int) -> String {
        guard index >= 0, index < emoji.count else { return "" }
        return emoji[index]
    }
}

// ---------------------------------------------------------------- スタンプ

public enum StickerFormat: String, CaseIterable { case webp = "Webp", png = "Png" }

public enum StickerOrigin: String, CaseIterable {
    case builtIn = "BuiltIn", custom = "Custom", group = "Group"
}

/// `public` は無い。公開共有には、先に審査・削除対応・権利の扱いを設計する必要がある。
/// だから値を作って使わずに置くのではなく、**値そのものを作らない**。
public enum PackVisibility: String, CaseIterable {
    case privateOnly = "Private", group = "Group", shared = "Shared"
}

public struct StickerAsset: Equatable {
    public let id: StickerId
    public let packId: StickerPackId
    public let contentHash: String
    public let widthPx: Int
    public let heightPx: Int
    public let byteSize: Int
    public let format: StickerFormat
    public let origin: StickerOrigin

    public init(id: StickerId, packId: StickerPackId, contentHash: String,
                widthPx: Int, heightPx: Int, byteSize: Int,
                format: StickerFormat, origin: StickerOrigin) {
        self.id = id; self.packId = packId; self.contentHash = contentHash
        self.widthPx = widthPx; self.heightPx = heightPx; self.byteSize = byteSize
        self.format = format; self.origin = origin
    }
}

public struct StickerPack: Equatable {
    public let id: StickerPackId
    /// 同梱セットは誰のものでもないので nil。
    public let ownerId: String?
    public let title: String
    public let visibility: PackVisibility
    /// 単調増加。クライアントが変わったぶんだけ取れるように。
    public let version: Int
    public let stickerIds: [StickerId]

    public init(id: StickerPackId, ownerId: String?, title: String,
                visibility: PackVisibility, version: Int, stickerIds: [StickerId]) {
        self.id = id; self.ownerId = ownerId; self.title = title
        self.visibility = visibility; self.version = version; self.stickerIds = stickerIds
    }
}

/// 保管の乱用が、そもそも起こりえないように。
public enum StickerLimits {
    public static let maxDimensionPx = 512
    public static let maxBytes = 200 * 1024
}

/// アプリに同梱したセット。
public enum BuiltInStickers {
    public static let packId = StickerPackId("pack_builtin_v1")

    public struct Entry: Equatable {
        public let id: StickerId
        public let fileName: String
        public let label: String
    }

    public static let entries: [Entry] = [
        Entry(id: StickerId("st_iine"), fileName: "st_iine.png", label: "いいね"),
        Entry(id: StickerId("st_arigato"), fileName: "st_arigato.png", label: "ありがと"),
        Entry(id: StickerId("st_ok"), fileName: "st_ok.png", label: "OK!"),
        Entry(id: StickerId("st_ukeru"), fileName: "st_ukeru.png", label: "うける"),
        Entry(id: StickerId("st_gomen"), fileName: "st_gomen.png", label: "ごめん"),
        Entry(id: StickerId("st_tasukaru"), fileName: "st_tasukaru.png", label: "たすかる"),
        Entry(id: StickerId("st_otsukare"), fileName: "st_otsukare.png", label: "おつかれ"),
        Entry(id: StickerId("st_matteru"), fileName: "st_matteru.png", label: "まってる"),
    ]

    public static let pack = StickerPack(
        id: packId, ownerId: nil, title: "Rinowa",
        visibility: .privateOnly, version: 1,
        stickerIds: entries.map(\.id)
    )
}

// ---------------------------------------------------------------- 一覧に出す文字列

/// メッセージがどう見えるか。本文そのものではなく、**一覧や引用に出す短い代わり**。
///
/// 両方の実装がここでずれると、同じ会話が端末によって違う説明になる。
/// research/vectors/model.json で縛ってある。
public enum MessagePreview {

    public static func text(for content: MessageContent) -> MessageText {
        switch content {
        case .text(let body):
            return MessageText(body)
        case .sticker:
            return MessageText("スタンプ")
        case .image:
            return MessageText("写真")
        case .video:
            return MessageText("動画")
        case .call(let call):
            // 一覧はどちら側か知らないので中立にする。発信・着信の言い分けはスレッドの仕事。
            return MessageText(call.video ? "ビデオ通話" : "音声通話")
        }
    }

    /// 封を開けられなかったとき。
    ///
    /// 一覧はサーバーが読める項目から作るので、暗号化された本文は出せない。
    /// 最後に読めたメッセージを出して「それ以降なにも無い」ように見せるより、こう言う。
    public static let locked = MessageText("🔒 暗号化されたメッセージ")

    /// 読まれたあとに取り消したもの。
    ///
    /// 空の本文ではなく別の状態にする。中身は無くなり、会話はそう言う、という別の事実。
    /// 未読のものは削除されるので、これにはならない。
    public static let retracted = MessageText("送信を取り消しました")
}
