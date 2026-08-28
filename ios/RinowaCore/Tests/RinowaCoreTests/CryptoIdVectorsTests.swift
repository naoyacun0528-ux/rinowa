import XCTest
@testable import RinowaCore

/// research/vectors/crypto-ids.json と突き合わせる。
///
/// **ここが Android と1文字でも違うと、iPhone と Android の間で鍵が噛み合わない。**
/// 症状は「鍵が無い」ではなく「署名が一致しない」で、移行ではなく攻撃に見える。
final class CryptoIdVectorsTests: XCTestCase {

    private func load() throws -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let c = dir.appendingPathComponent("research/vectors/crypto-ids.json")
            if FileManager.default.fileExists(atPath: c.path) {
                return try JSONSerialization.jsonObject(with: Data(contentsOf: c)) as! [String: Any]
            }
            dir = dir.deletingLastPathComponent()
        }
        throw YosegiError("research/vectors/crypto-ids.json が見つからない")
    }

    func testDomainIsFrozen() throws {
        XCTAssertEqual(CryptoIds.domain, try load()["domain"] as! String,
                       "ドメインは凍結。変えるなら全員の鍵の作り直し")
    }

    func testUserIdsMatch() throws {
        for c in try load()["user"] as! [[String: String]] {
            XCTAssertEqual(CryptoIds.matrixUser(UserId(c["uid"]!)), c["matrix"]!)
        }
    }

    func testRoomIdsMatch() throws {
        for c in try load()["room"] as! [[String: String]] {
            XCTAssertEqual(CryptoIds.matrixRoom(ConversationId(c["conversationId"]!)), c["matrix"]!)
        }
    }

    func testParsingBack() throws {
        let p = try load()["parse"] as! [String: Any]
        for c in p["valid"] as! [[String: String]] {
            XCTAssertEqual(CryptoIds.userFromMatrix(c["matrix"]!)?.value, c["uid"]!,
                           "\(c["matrix"]!) が読めない")
        }
        for bad in p["invalid"] as! [String] {
            XCTAssertNil(CryptoIds.userFromMatrix(bad),
                         "\"\(bad)\" から uid をでっち上げた")
        }
    }

    /// 往復して同じものに戻ること。
    func testRoundTrip() {
        for uid in ["K1mN4pQ7rS0tU3vW6xY9zA2bC5dE", "x", "0123456789"] {
            let m = CryptoIds.matrixUser(UserId(uid))
            XCTAssertEqual(CryptoIds.userFromMatrix(m)?.value, uid)
        }
    }
}
