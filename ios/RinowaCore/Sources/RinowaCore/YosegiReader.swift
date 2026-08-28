import Foundation

/// Yosegi v1 の読む側。
///
/// **契約はひとつだけ。**
///
/// > 任意のバイト列に対し、正しいメッセージ配列を返すか `YosegiError` を投げるかの、
/// > どちらかしかしない。
///
/// してはならないこと:
///
/// 1. `YosegiError` 以外を投げる（境界検査の漏れを意味する）
/// 2. 上限なく確保する
/// 3. 上限なくループする
/// 4. **送られていないバイトからメッセージを組み立てる**
///
/// 4 が一番静かな失敗で、この契約が厳しい理由。切り詰められたフレームから
/// もっともらしいメッセージが生成され、例外もログも無しに、**人間が書いたものとして
/// 画面に出る**。Swift は範囲外を読むと落ちるので JavaScript ほど静かではないが、
/// 落ちるのも答えではない。境界は自分で見る。

private struct Reader {
    let bytes: [UInt8]
    var offset: Int
    /// この読み手が触ってよい終端。複合ブロックの中では、ブロックの終わりになる。
    /// **中の嘘が次のメッセージまで届いてはならない。**
    let limit: Int

    init(_ bytes: [UInt8], from: Int = 0, to: Int? = nil) {
        self.bytes = bytes
        self.offset = from
        self.limit = to ?? bytes.count
    }

    var remaining: Int { limit - offset }
    var atEnd: Bool { offset >= limit }

    mutating func byte() throws -> UInt8 {
        guard offset < limit else { throw YosegiError("バイトが足りない") }
        defer { offset += 1 }
        return bytes[offset]
    }

    mutating func take(_ n: Int) throws -> ArraySlice<UInt8> {
        guard n >= 0, remaining >= n else { throw YosegiError("\(n) バイト読めない（残り \(remaining)）") }
        defer { offset += n }
        return bytes[offset..<(offset + n)]
    }

    /// **最大8バイト。** 上限が無いと、継続ビットの連続が攻撃者の忍耐だけで
    /// 決まるループになる。8バイトで56ビットあり、倍精度が正確に表せる範囲を含む。
    mutating func varint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        for _ in 0..<8 {
            let b = try byte()
            result |= UInt64(b & 0x7F) << shift
            if b & 0x80 == 0 { return result }
            shift += 7
        }
        throw YosegiError("varint が長すぎる")
    }

    mutating func zigzag() throws -> Int64 {
        let raw = try varint()
        guard raw <= UInt64(Int64.max) else { throw YosegiError("時刻差が大きすぎる") }
        let v = Int64(raw)
        return (v % 2 == 0) ? v / 2 : -((v + 1) / 2)
    }

    mutating func string() throws -> String {
        let length = try varint()
        guard length <= UInt64(remaining) else {
            throw YosegiError("文字列の長さ \(length) が残り \(remaining) を超える")
        }
        let slice = try take(Int(length))
        guard let s = String(bytes: slice, encoding: .utf8) else {
            throw YosegiError("UTF-8 として読めない")
        }
        return s
    }

    /// 知らないフィールドは、ワイヤ型に従って読み飛ばして続行する。
    ///
    /// **これが v1 の最も重要な性質。** 読み飛ばせないと、将来フィールドを1つ
    /// 足しただけで旧クライアントは「新フィールドが見えない」のではなく
    /// **それ以降を一切読めなくなる**。メッセンジャーではそれは
    /// 「メッセージが黙って届かない」という形で現れる。
    mutating func skip(wireType: Int) throws {
        switch wireType {
        case 0: return                                   // フラグ。本体なし
        case 1: _ = try varint()
        case 2, 5:
            let n = try varint()
            guard n <= UInt64(remaining) else { throw YosegiError("読み飛ばす長さが残りを超える") }
            _ = try take(Int(n))
        case 3: _ = try take(15)
        default:
            // 4・6・7 は未定義。**長さが分からないので以降が読めない。**
            throw YosegiError("未定義のワイヤ型 \(wireType)")
        }
    }
}

extension Yosegi {

    public static func decode(_ data: Data, context: YosegiContext) throws -> [YosegiMessage] {
        let bytes = [UInt8](data)
        var r = Reader(bytes)

        let v = try r.byte()
        guard v == version else { throw YosegiError("版が違う: \(v)") }

        let count = try r.varint()
        guard count <= UInt64(maxCount) else { throw YosegiError("1フレーム \(maxCount) 通まで（\(count)）") }

        // **確保の前に検証する。** 長さフィールドは他人からの約束であって、事実ではない。
        let base = try r.varint()
        guard Int(count) * minMessageBytes <= r.remaining else {
            throw YosegiError("\(count) 通は残り \(r.remaining) バイトに入らない")
        }
        guard base <= UInt64(Int64.max) else { throw YosegiError("基準時刻が大きすぎる") }

        var messages: [YosegiMessage] = []
        messages.reserveCapacity(Int(count))
        var previous = Int64(base)

        for _ in 0..<Int(count) {
            let id = unpackId(try r.take(15), length: 20)

            let senderByte = try r.byte()
            let senderId: String
            if senderByte == 0xFF {
                senderId = unpackId(try r.take(21), length: 28)
            } else {
                // **索引が範囲外なら拒否。** 誰のものでもないメッセージを作らない。
                guard Int(senderByte) < context.memberIds.count else {
                    throw YosegiError("送信者の索引 \(senderByte) が参加者数 \(context.memberIds.count) を超える")
                }
                senderId = context.memberIds[Int(senderByte)]
            }

            let delta = try r.zigzag()
            let (timestamp, overflow) = previous.addingReportingOverflow(delta)
            guard !overflow else { throw YosegiError("時刻があふれた") }
            previous = timestamp

            let statusByte = try r.byte()
            guard let status = YosegiStatus(rawValue: statusByte) else {
                throw YosegiError("状態 \(statusByte) は範囲外")
            }

            var m = YosegiMessage(id: id, senderId: senderId, timestampMs: timestamp, status: status)

            while true {
                let tag = try r.byte()
                if tag == 0 { break }                    // END
                let field = Int(tag) >> 3
                let wire = Int(tag) & 0x07

                switch (field, wire) {
                case (1, 2):
                    m.text = try r.string()

                case (2, 1):
                    let idx = try r.varint()
                    guard idx < UInt64(context.stickerCatalogue.count) else {
                        throw YosegiError("スタンプの索引 \(idx) がカタログを超える")
                    }
                    m.stickerId = context.stickerCatalogue[Int(idx)]

                case (2, 2):
                    m.stickerId = try r.string()

                case (3, 5):
                    let length = try r.varint()
                    guard length <= UInt64(r.remaining) else { throw YosegiError("返信ブロックが残りを超える") }
                    let end = r.offset + Int(length)
                    var inner = Reader(bytes, from: r.offset, to: end)
                    let mid = unpackId(try inner.take(15), length: 20)
                    let name = try inner.string()
                    let excerpt = try inner.string()
                    m.replyTo = YosegiMessage.Reply(messageId: mid, senderName: name, excerpt: excerpt)
                    r.offset = end                        // ブロック長で閉じる

                case (4, 5):
                    let length = try r.varint()
                    guard length <= UInt64(r.remaining) else { throw YosegiError("反応ブロックが残りを超える") }
                    let end = r.offset + Int(length)
                    var inner = Reader(bytes, from: r.offset, to: end)
                    let n = try inner.varint()
                    guard n * 2 <= UInt64(inner.remaining) else {
                        throw YosegiError("反応 \(n) 件はブロックに入らない")
                    }
                    var list: [YosegiMessage.Reaction] = []
                    list.reserveCapacity(Int(n))
                    for _ in 0..<Int(n) {
                        let member = Int(try inner.byte())
                        let palette = Int(try inner.byte())
                        guard palette < 6 else { throw YosegiError("色の索引 \(palette) は範囲外") }
                        list.append(YosegiMessage.Reaction(memberIndex: member, palette: palette))
                    }
                    m.reactions = list
                    r.offset = end

                case (5, 2):
                    m.senderName = try r.string()

                case (6, 0):
                    m.retracted = true

                default:
                    // 知らないフィールド。読み飛ばして続ける。
                    try r.skip(wireType: wire)
                }
            }

            messages.append(m)
        }

        return messages
    }
}
