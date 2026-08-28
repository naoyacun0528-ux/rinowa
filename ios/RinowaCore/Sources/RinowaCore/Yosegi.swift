import Foundation

/// Yosegi v1 — Rinowa Original Format の Swift 側。
///
/// `Yosegi.kt` と同じバイト列を読み書きする。仕様は docs/YOSEGI_V1_SPEC.md（凍結）で、
/// 両方が research/vectors/yosegi.json に縛られる。
///
/// **互いのコードを見ずに、同じ1つのファイルに合わせる。** それが2つ目の実装を書く
/// 理由で、片方を見ながら写したら、間違いまで揃ってしまって何も確かめられない。
///
/// この形式が短いのは、**両端が既に知っていることを送らないから**。会話 ID も
/// 参加者一覧も流れない。送信者は参加者一覧への索引1バイトになる。
/// だから読む側は、書いた側と同じ `Context` を持っていなければならない。
/// 持っていなければ**送信者を取り違える**。

// ---------------------------------------------------------------- 契約

/// 復号が失敗する唯一の形。
///
/// > 任意のバイト列に対し、正しいメッセージ配列を返すか `YosegiError` を投げるかの、
/// > どちらかしかしない。
///
/// これ以外の例外が出たら、それは境界検査の漏れを意味する。
public struct YosegiError: Error, CustomStringConvertible {
    public let description: String
    init(_ description: String) { self.description = description }
}

// ---------------------------------------------------------------- 共有文脈

/// 送受信の前に両端が一致していなければならないもの。
///
/// `memberIds` の順序は会話の属性であって、変えてはならない。
/// メンバー追加は末尾に追記する。並べ替えると過去のフレームの意味が変わる。
public struct YosegiContext {
    public let conversationId: String
    public let memberIds: [String]
    public let stickerCatalogue: [String]

    private let memberIndex: [String: Int]
    private let stickerIndex: [String: Int]

    public init(conversationId: String, memberIds: [String], stickerCatalogue: [String] = []) {
        self.conversationId = conversationId
        self.memberIds = memberIds
        self.stickerCatalogue = stickerCatalogue
        var m: [String: Int] = [:]
        for (i, id) in memberIds.enumerated() where m[id] == nil { m[id] = i }
        self.memberIndex = m
        var s: [String: Int] = [:]
        for (i, id) in stickerCatalogue.enumerated() where s[id] == nil { s[id] = i }
        self.stickerIndex = s
    }

    func member(_ uid: String) -> Int? { memberIndex[uid] }
    func sticker(_ id: String) -> Int? { stickerIndex[id] }
}

// ---------------------------------------------------------------- 運ぶもの

public struct YosegiMessage: Equatable {
    public var id: String
    public var senderId: String
    public var timestampMs: Int64
    public var status: YosegiStatus
    public var text: String?
    public var stickerId: String?
    public var senderName: String?
    public var replyTo: Reply?
    public var reactions: [Reaction]
    public var retracted: Bool

    public init(
        id: String, senderId: String, timestampMs: Int64, status: YosegiStatus,
        text: String? = nil, stickerId: String? = nil, senderName: String? = nil,
        replyTo: Reply? = nil, reactions: [Reaction] = [], retracted: Bool = false
    ) {
        self.id = id; self.senderId = senderId; self.timestampMs = timestampMs
        self.status = status; self.text = text; self.stickerId = stickerId
        self.senderName = senderName; self.replyTo = replyTo
        self.reactions = reactions; self.retracted = retracted
    }

    public struct Reply: Equatable {
        public let messageId: String
        public let senderName: String
        public let excerpt: String
        public init(messageId: String, senderName: String, excerpt: String) {
            self.messageId = messageId; self.senderName = senderName; self.excerpt = excerpt
        }
    }

    public struct Reaction: Equatable {
        public let memberIndex: Int
        public let palette: Int
        public init(memberIndex: Int, palette: Int) {
            self.memberIndex = memberIndex; self.palette = palette
        }
    }
}

public enum YosegiStatus: UInt8, CaseIterable {
    case sending = 0, sent = 1, delivered = 2, read = 3, failed = 4
}

// ---------------------------------------------------------------- 数と id

internal let ALPHABET = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
internal let VALUE: [Character: UInt8] = {
    var m: [Character: UInt8] = [:]
    for (i, c) in ALPHABET.enumerated() { m[c] = UInt8(i) }
    return m
}()

/// Firestore の自動 ID は base62 の20文字。62^20 < 2^120 なので **15バイト**に収まる。
/// uid は28文字で 62^28 < 2^167、**21バイト**。
///
/// 「20文字の英数字」ではなく「119ビットの数」なので、文字として送るのは
/// 衣装を着せたまま運んでいるのと同じ。
internal func packId(_ s: String, width: Int) throws -> [UInt8] {
    var out = [UInt8](repeating: 0, count: width)
    for ch in s {
        guard let v = VALUE[ch] else { throw YosegiError("id に base62 でない文字: \(ch)") }
        // out = out * 62 + v を、桁上がりを見ながら下から。
        var carry = UInt32(v)
        var i = width - 1
        while i >= 0 {
            let t = UInt32(out[i]) &* 62 &+ carry
            out[i] = UInt8(t & 0xFF)
            carry = t >> 8
            i -= 1
        }
        if carry != 0 { throw YosegiError("id が \(width) バイトに収まらない") }
    }
    return out
}

internal func unpackId(_ bytes: ArraySlice<UInt8>, length: Int) -> String {
    var digits = [UInt8](repeating: 0, count: length)
    var work = Array(bytes)
    for i in stride(from: length - 1, through: 0, by: -1) {
        // work を 62 で割って、余りが1桁ぶん。
        var remainder: UInt32 = 0
        for j in 0..<work.count {
            let cur = (remainder << 8) | UInt32(work[j])
            work[j] = UInt8(cur / 62)
            remainder = cur % 62
        }
        digits[i] = UInt8(remainder)
    }
    return String(digits.map { ALPHABET[Int($0)] })
}

// ---------------------------------------------------------------- 書く

private struct Writer {
    var bytes: [UInt8] = []

    mutating func byte(_ b: UInt8) { bytes.append(b) }
    mutating func raw(_ b: [UInt8]) { bytes.append(contentsOf: b) }

    /// 7ビットずつ、下位から。継続ビットは最上位。
    mutating func varint(_ value: UInt64) {
        var v = value
        repeat {
            var b = UInt8(v & 0x7F)
            v >>= 7
            if v != 0 { b |= 0x80 }
            bytes.append(b)
        } while v != 0
    }

    /// 符号つき。メッセージは順不同で届くことがあり、**負の差分が10バイトに
    /// なってはいけない**。
    mutating func zigzag(_ value: Int64) {
        varint(value >= 0 ? UInt64(value) * 2 : UInt64(-value) * 2 - 1)
    }

    mutating func string(_ s: String) {
        let utf8 = Array(s.utf8)
        varint(UInt64(utf8.count))   // 長さは**バイト数**であって文字数ではない
        raw(utf8)
    }

    mutating func tag(_ field: Int, _ wire: Int) { byte(UInt8((field << 3) | wire)) }
}

public enum Yosegi {

    public static let version: UInt8 = 1
    static let maxCount = 4096
    static let minMessageBytes = 19   // id 15 + 送信者 1 + 時刻 1 + 状態 1 + 終端 1

    public static func encode(_ messages: [YosegiMessage], context: YosegiContext) throws -> Data {
        guard messages.count <= maxCount else { throw YosegiError("1フレーム \(maxCount) 通まで") }

        var w = Writer()
        w.byte(version)
        w.varint(UInt64(messages.count))

        let base = messages.first?.timestampMs ?? 0
        w.varint(UInt64(max(0, base)))

        var previous = base
        for m in messages {
            w.raw(try packId(m.id, width: 15))

            if let idx = context.member(m.senderId), idx < 0xFF {
                w.byte(UInt8(idx))
            } else {
                // 文脈に無い送信者。**索引をでっち上げず**、uid をそのまま送る。
                w.byte(0xFF)
                w.raw(try packId(m.senderId, width: 21))
            }

            w.zigzag(m.timestampMs - previous)
            previous = m.timestampMs
            w.byte(m.status.rawValue)

            if let text = m.text {
                w.tag(1, 2); w.string(text)
            }
            if let sticker = m.stickerId {
                if let idx = context.sticker(sticker), idx < 256 {
                    w.tag(2, 1); w.varint(UInt64(idx))
                } else {
                    w.tag(2, 2); w.string(sticker)
                }
            }
            if let reply = m.replyTo {
                var inner = Writer()
                inner.raw(try packId(reply.messageId, width: 15))
                inner.string(reply.senderName)
                inner.string(reply.excerpt)
                w.tag(3, 5); w.varint(UInt64(inner.bytes.count)); w.raw(inner.bytes)
            }
            if !m.reactions.isEmpty {
                var inner = Writer()
                inner.varint(UInt64(m.reactions.count))
                for r in m.reactions {
                    inner.byte(UInt8(truncatingIfNeeded: r.memberIndex))
                    inner.byte(UInt8(truncatingIfNeeded: r.palette))
                }
                w.tag(4, 5); w.varint(UInt64(inner.bytes.count)); w.raw(inner.bytes)
            }
            if let name = m.senderName {
                w.tag(5, 2); w.string(name)
            }
            if m.retracted {
                w.tag(6, 0)
            }

            w.byte(0)   // END
        }

        return Data(w.bytes)
    }
}
