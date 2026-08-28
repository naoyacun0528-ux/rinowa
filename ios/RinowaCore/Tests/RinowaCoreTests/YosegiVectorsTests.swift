import XCTest
@testable import RinowaCore

/// research/vectors/yosegi.json と突き合わせる。
///
/// **このテストが通ることが、Swift 実装が正しいことの唯一の根拠。**
/// Kotlin のコードを見ながら書いたのではなく、同じ1つのファイルに合わせている。
/// だから、両方が同じ間違いをしていない限り、一致は仕様の一致を意味する。
final class YosegiVectorsTests: XCTestCase {

    struct Vectors: Decodable {
        let version: Int
        let conversationId: String
        let memberIds: [String]
        let stickerCatalogue: [String]
        let cases: [Case]

        struct Case: Decodable {
            let name: String
            let note: String
            let messages: [Message]
            let hex: String
            let bytes: Int
        }

        struct Message: Decodable {
            let id: String
            let senderId: String
            let timestampMs: Int64
            let status: String
            let text: String?
            let stickerId: String?
            let replyTo: Reply?

            struct Reply: Decodable {
                let messageId: String
                let senderName: String
                let excerpt: String
            }
        }
    }

    /// リポジトリの中を探す。テストの作業ディレクトリは実行のしかたで変わるので、
    /// 決め打ちにしない。
    private func loadVectors() throws -> Vectors {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("research/vectors/yosegi.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: candidate))
            }
            dir = dir.deletingLastPathComponent()
        }
        throw YosegiError("research/vectors/yosegi.json が見つからない")
    }

    private func context(_ v: Vectors) -> YosegiContext {
        YosegiContext(conversationId: v.conversationId, memberIds: v.memberIds,
                      stickerCatalogue: v.stickerCatalogue)
    }

    private func status(_ s: String) -> YosegiStatus {
        switch s {
        case "Sending": return .sending
        case "Delivered": return .delivered
        case "Read": return .read
        case "Failed": return .failed
        default: return .sent
        }
    }

    private func expected(_ m: Vectors.Message) -> YosegiMessage {
        YosegiMessage(
            id: m.id, senderId: m.senderId, timestampMs: m.timestampMs, status: status(m.status),
            text: m.text, stickerId: m.stickerId,
            replyTo: m.replyTo.map {
                YosegiMessage.Reply(messageId: $0.messageId, senderName: $0.senderName, excerpt: $0.excerpt)
            }
        )
    }

    private func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private func bytes(_ hex: String) -> Data {
        var out = Data(capacity: hex.count / 2)
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            out.append(UInt8(hex[i..<j], radix: 16)!)
            i = j
        }
        return out
    }

    // ---------------------------------------------------------------- 書く

    func testEncodeMatchesTheFixedBytes() throws {
        let v = try loadVectors()
        let ctx = context(v)
        for c in v.cases {
            let frame = try Yosegi.encode(c.messages.map(expected), context: ctx)
            XCTAssertEqual(hex(frame), c.hex, "\(c.name): バイト列が違う — \(c.note)")
            XCTAssertEqual(frame.count, c.bytes, "\(c.name): 長さが違う")
        }
    }

    // ---------------------------------------------------------------- 読む

    func testDecodeMatchesTheFixedBytes() throws {
        let v = try loadVectors()
        let ctx = context(v)
        for c in v.cases {
            let got = try Yosegi.decode(bytes(c.hex), context: ctx)
            let want = c.messages.map(expected)
            XCTAssertEqual(got.count, want.count, "\(c.name): 通数が違う")
            for (g, w) in zip(got, want) {
                XCTAssertEqual(g.id, w.id, "\(c.name): id")
                XCTAssertEqual(g.senderId, w.senderId, "\(c.name): 送信者")
                XCTAssertEqual(g.timestampMs, w.timestampMs, "\(c.name): 時刻")
                XCTAssertEqual(g.status, w.status, "\(c.name): 状態")
                XCTAssertEqual(g.text, w.text, "\(c.name): 本文")
                XCTAssertEqual(g.stickerId, w.stickerId, "\(c.name): スタンプ")
                XCTAssertEqual(g.replyTo, w.replyTo, "\(c.name): 返信")
            }
        }
    }

    // ---------------------------------------------------------------- 契約

    /// **切り詰められたフレームから、もっともらしいメッセージを作らない。**
    ///
    /// 1バイトずつ削って、どの長さでも「落ちる」のではなく「拒否する」こと。
    func testTruncatedFramesAreRejectedNotInvented() throws {
        let v = try loadVectors()
        let ctx = context(v)
        for c in v.cases {
            let full = bytes(c.hex)
            for cut in 1..<full.count {
                let short = full.prefix(cut)
                do {
                    let got = try Yosegi.decode(short, context: ctx)
                    // 通ってしまったなら、少なくとも**でっち上げていない**こと。
                    XCTAssertLessThanOrEqual(got.count, c.messages.count,
                                             "\(c.name) を \(cut) バイトに切ったら、元より多い通数が出てきた")
                } catch is YosegiError {
                    // これが正しい
                } catch {
                    XCTFail("\(c.name) を \(cut) バイトに切ったら YosegiError 以外が出た: \(error)")
                }
            }
        }
    }

    /// 版が違うフレームは読まない。
    func testWrongVersionIsRejected() throws {
        let v = try loadVectors()
        let ctx = context(v)
        var frame = bytes(v.cases[0].hex)
        frame[0] = 2
        XCTAssertThrowsError(try Yosegi.decode(frame, context: ctx))
    }

    /// **通数の宣言を信じない。** 4096通と言われても、バイトが無ければ確保しない。
    func testCountIsCheckedBeforeAllocating() throws {
        let v = try loadVectors()
        let ctx = context(v)
        // version(1) + count(varint 4096) + baseTimestamp(1) だけ
        let frame = Data([1, 0x80, 0x20, 0])
        XCTAssertThrowsError(try Yosegi.decode(frame, context: ctx)) { error in
            XCTAssertTrue(error is YosegiError, "YosegiError であること")
        }
    }

    /// 何を投げ込んでも、落ちずに拒否する。
    func testRandomBytesNeverEscapeTheContract() throws {
        let v = try loadVectors()
        let ctx = context(v)
        var seed: UInt64 = 0x5EED
        func next() -> UInt8 {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return UInt8((seed >> 33) & 0xFF)
        }
        for _ in 0..<4000 {
            let n = Int(next()) % 64
            var frame = Data([1])
            for _ in 0..<n { frame.append(next()) }
            do {
                _ = try Yosegi.decode(frame, context: ctx)
            } catch is YosegiError {
                // これが正しい
            } catch {
                XCTFail("YosegiError 以外が出た: \(error) — \(hex(frame))")
            }
        }
    }
}
