import Foundation
import XCTest
@testable import RinowaCore

/// Android のテストが走らせるのと同じベクタ。
///
/// このパッケージをこんなに早く作った理由がこれ。1つの形式に実装が2つあると黙って
/// ずれ、そのずれは他人の端末の、送り直せないメッセージの上で表に出る。ここでなら、
/// 食い違いはノートPCの上の赤いテストで済む。
///
/// ファイルは Android 側の `FormatVectorsTest` が生成してコミットする。ここからは
/// 書かない。答えを作り直せる2つ目の実装は、自分自身と黙って一致することもできてしまう。
final class FormatVectorsTests: XCTestCase {

    private var vectors: [String: Any]!

    override func setUpWithError() throws {
        // #filePath はファイル自身を含むので、最初の1段でそのディレクトリに着く。
        // ファイルではなくディレクトリから数え始めたせいで1段足りず、
        // リポジトリの根にあるものを ios/research の中に探しに行っていた。
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // …/Tests/RinowaCoreTests
            .deletingLastPathComponent()   // …/Tests
            .deletingLastPathComponent()   // …/RinowaCore
            .deletingLastPathComponent()   // …/ios
            .deletingLastPathComponent()   // リポジトリの根
            .appendingPathComponent("research/vectors/formats.json")

        let data = try Data(contentsOf: url)
        vectors = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
    }

    func testEnvelopeVectors() throws {
        let envelopes = try XCTUnwrap(vectors["envelopes"] as? [[String: Any]])
        XCTAssertFalse(envelopes.isEmpty)

        for vector in envelopes {
            let name = vector["name"] as? String ?? "?"
            let plaintext = try XCTUnwrap(vector["plaintext"] as? String, name)
            let opened = try XCTUnwrap(MessageEnvelope.open(plaintext), name)

            switch vector["kind"] as? String {
            case "text":
                guard case let .text(body) = opened else {
                    return XCTFail(name + ": expected text")
                }
                XCTAssertEqual(body, vector["body"] as? String, name)

            case "image":
                guard case let .image(image) = opened else {
                    return XCTFail(name + ": expected an image")
                }
                XCTAssertEqual(image.mediaId, vector["mediaId"] as? String, name)
                XCTAssertEqual(image.width, (vector["width"] as? NSNumber)?.intValue, name)
                XCTAssertEqual(image.height, (vector["height"] as? NSNumber)?.intValue, name)
                XCTAssertEqual(image.byteCount, (vector["bytes"] as? NSNumber)?.intValue, name)
                XCTAssertEqual(
                    image.mediaKey?.base64EncodedString(),
                    vector["mediaKeyBase64"] as? String,
                    name + ": media key"
                )
                XCTAssertEqual(
                    image.thumbnail.base64EncodedString(),
                    vector["thumbnailBase64"] as? String,
                    name + ": thumbnail"
                )

            case "video":
                guard case let .video(video) = opened else {
                    return XCTFail(name + ": expected a video")
                }
                XCTAssertEqual(video.mediaId, vector["mediaId"] as? String, name)
                XCTAssertEqual(
                    video.durationMs,
                    (vector["durationMs"] as? NSNumber)?.int64Value,
                    name
                )
                XCTAssertEqual(
                    video.sealedBytes,
                    (vector["sealedBytes"] as? NSNumber)?.int64Value,
                    name
                )

            case "call":
                guard case let .call(call) = opened else {
                    return XCTFail(name + ": expected a call")
                }
                XCTAssertEqual(call.video, vector["video"] as? Bool, name)
                XCTAssertEqual(call.outcome.rawValue, vector["outcome"] as? String, name)
                XCTAssertEqual(call.seconds, (vector["seconds"] as? NSNumber)?.intValue, name)

            default:
                XCTFail(name + ": unknown kind in the vectors")
            }
        }
    }

    /// 実装が読み違えたときに送り直せないほう。
    func testBackupVectors() throws {
        let backups = try XCTUnwrap(vectors["backups"] as? [[String: Any]])
        XCTAssertFalse(backups.isEmpty)

        for vector in backups {
            let name = vector["name"] as? String ?? "?"
            let sealed = try XCTUnwrap(
                Data(base64Encoded: try XCTUnwrap(vector["sealedBase64"] as? String, name)),
                name
            )
            let secret = try XCTUnwrap(vector["secret"] as? String, name)

            let opened = try XCTUnwrap(
                BackupCipher.open(sealed, secret: secret),
                name + ": did not open"
            )
            XCTAssertEqual(String(data: opened, encoding: .utf8), vector["plaintext"] as? String, name)
        }

        XCTAssertEqual(
            BackupCipher.iterations,
            (vectors["backupIterations"] as? NSNumber)?.intValue,
            "work factor"
        )
    }

    /// ここで封じてここで開く。上のベクタと組み合わせて初めて、自分の中では
    /// 辻褄が合っている間違った書き手を捕まえられる。だから両方のテストがある。
    func testBackupRoundTrip() throws {
        let plaintext = Data("ぬ".utf8)
        let sealed = try BackupCipher.seal(plaintext, secret: "483920")

        XCTAssertEqual(BackupCipher.open(sealed, secret: "483920"), plaintext)
        XCTAssertNil(BackupCipher.open(sealed, secret: "483921"))

        var edited = sealed
        edited[edited.count - 1] ^= 1
        XCTAssertNil(BackupCipher.open(edited, secret: "483920"), "an edited file must not open")

        var weakened = sealed
        weakened[8] = 0
        weakened[9] = 0
        weakened[10] = 0
        weakened[11] = 1
        XCTAssertNil(
            BackupCipher.open(weakened, secret: "483920"),
            "the work factor must not be editable down"
        )
    }

    func testEnvelopeRoundTrip() throws {
        let image = MessageContent.Image(
            mediaId: String(repeating: "a", count: 64),
            width: 1440,
            height: 1920,
            byteCount: 812_345,
            thumbnail: Data([1, 2, 3]),
            mediaKey: Data(repeating: 7, count: 32)
        )

        XCTAssertEqual(MessageEnvelope.open(MessageEnvelope.seal(.image(image))), .image(image))
        XCTAssertEqual(
            MessageEnvelope.open(MessageEnvelope.seal(.text("こんばんは 🌙"))),
            .text("こんばんは 🌙")
        )
        // 封ができる前に書かれたもの。本文そのもの。
        XCTAssertEqual(MessageEnvelope.open("E2EE-final-1552"), .text("E2EE-final-1552"))
    }
}
