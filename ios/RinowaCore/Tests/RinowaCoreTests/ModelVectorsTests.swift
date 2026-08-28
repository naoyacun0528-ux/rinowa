import XCTest
@testable import RinowaCore

/// research/vectors/model.json と突き合わせる。
///
/// ここに入っているのは**画面に出る文字列と、凍結された並び**。
/// 実装ごとに違うと、同じ会話が端末によって違う説明になる。
/// 一覧に「写真」と出る端末と「画像」と出る端末があってはいけない。
final class ModelVectorsTests: XCTestCase {

    private func load() throws -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("research/vectors/model.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                let data = try Data(contentsOf: candidate)
                return try JSONSerialization.jsonObject(with: data) as! [String: Any]
            }
            dir = dir.deletingLastPathComponent()
        }
        throw YosegiError("research/vectors/model.json が見つからない")
    }

    // ---------------------------------------------------------------- 一覧の文字列

    func testPreviewStringsMatch() throws {
        let v = try load()
        let p = v["preview"] as! [String: Any]

        let image = MessageContent.Image(
            mediaId: "m", width: 1, height: 1, byteCount: 1, thumbnail: Data(),
            mediaKey: nil, originalId: nil, originalKey: nil,
            originalBytes: nil, originalMime: nil
        )
        XCTAssertEqual(MessagePreview.text(for: .image(image)).value, p["image"] as! String)
        XCTAssertEqual(MessagePreview.text(for: .sticker("st_ok")).value, p["sticker"] as! String)
        XCTAssertEqual(MessagePreview.locked.value, p["locked"] as! String)
        XCTAssertEqual(MessagePreview.retracted.value, p["retracted"] as! String)

        // 本文だけは中身がそのまま出る。代わりの文字列ではない。
        XCTAssertEqual(MessagePreview.text(for: .text("おはよう")).value, "おはよう")
    }

    // ---------------------------------------------------------------- 凍結された並び

    func testReactionPaletteIsFrozen() throws {
        let v = try load()
        let r = v["reactionPalette"] as! [String: Any]
        XCTAssertEqual(ReactionPalette.version, r["version"] as! Int)
        XCTAssertEqual(ReactionPalette.emoji, r["emoji"] as! [String],
                       "並べ替えると、過去の反応が別のものになる")
    }

    func testMessageStatusOrderIsFrozen() throws {
        let v = try load()
        let order = (v["messageStatus"] as! [String: Any])["order"] as! [String]
        XCTAssertEqual(MessageStatus.allCases.map(\.rawValue), order, "序数が線の上を通る")
    }

    func testCallOutcomeOrderIsFrozen() throws {
        let v = try load()
        let order = (v["callOutcome"] as! [String: Any])["order"] as! [String]
        XCTAssertEqual(CallOutcome.allCases.map(\.rawValue), order)
    }

    // ---------------------------------------------------------------- スタンプ

    func testStickerLimitsMatch() throws {
        let v = try load()
        let l = v["stickerLimits"] as! [String: Any]
        XCTAssertEqual(StickerLimits.maxDimensionPx, l["maxDimensionPx"] as! Int)
        XCTAssertEqual(StickerLimits.maxBytes, l["maxBytes"] as! Int)
    }

    func testBuiltInStickersMatch() throws {
        let v = try load()
        let b = v["builtInStickers"] as! [String: Any]
        XCTAssertEqual(BuiltInStickers.packId.value, b["packId"] as! String)
        XCTAssertEqual(BuiltInStickers.pack.title, b["title"] as! String)
        XCTAssertEqual(BuiltInStickers.pack.version, b["version"] as! Int)

        let want = b["entries"] as! [[String: String]]
        XCTAssertEqual(BuiltInStickers.entries.count, want.count)
        for (got, w) in zip(BuiltInStickers.entries, want) {
            XCTAssertEqual(got.id.value, w["id"])
            XCTAssertEqual(got.fileName, w["fileName"])
            XCTAssertEqual(got.label, w["label"])
        }
        XCTAssertEqual(BuiltInStickers.pack.stickerIds, BuiltInStickers.entries.map(\.id))
    }

    // ---------------------------------------------------------------- 本文を漏らさない

    /// **本文を持つ型は、説明に中身を出さない。**
    ///
    /// `print("送信: \(text)")` の1行が、本文を端末の外へ出す一番ありがちな経路。
    /// Swift は `CustomStringConvertible` と `CustomDebugStringConvertible` が別なので、
    /// 片方だけ潰すと `debugPrint` から漏れる。両方見る。
    func testMessageTextNeverPrintsItsBody() throws {
        let secret = "口座の暗証番号は4649"
        let text = MessageText(secret)

        XCTAssertFalse("\(text)".contains(secret), "文字列展開から漏れた")
        XCTAssertFalse(text.description.contains(secret), "description から漏れた")
        XCTAssertFalse(text.debugDescription.contains(secret), "debugDescription から漏れた")
        XCTAssertFalse(String(reflecting: text).contains(secret), "String(reflecting:) から漏れた")

        var dumped = ""
        debugPrint(text, terminator: "", to: &dumped)
        XCTAssertFalse(dumped.contains(secret), "debugPrint から漏れた")

        // 長さは出してよい。封の外からも見えるものなので、隠しても得がない。
        XCTAssertEqual(text.description, "MessageText(len=\(secret.count))")
    }

    func testMessageTextKnowsItsShape() {
        XCTAssertTrue(MessageText("   \n ").isBlank)
        XCTAssertFalse(MessageText("あ").isBlank)
        XCTAssertEqual(MessageText("おはよう").length, 4)
    }
}
