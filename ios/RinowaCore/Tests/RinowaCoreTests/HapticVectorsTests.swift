import XCTest
@testable import RinowaCore

/// research/vectors/haptics.json と突き合わせる。
///
/// `Haptics.swift` はこのファイルから起こしたものなので、普通は一致する。
/// **一致しなくなるのは、Android 側で触り心地を直したのに起こし直していないとき。**
/// そのとき赤くなるのがこのテストの仕事で、赤くなったら `node tools/gen-haptics.mjs`。
///
/// 起こし直すのを忘れると、症状は「同じアプリなのに iPhone だと送信の手応えが弱い」。
/// 誰も原因に辿り着けない種類の壊れ方なので、機械に見張らせる。
final class HapticVectorsTests: XCTestCase {

    private func load() throws -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("research/vectors/haptics.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                let data = try Data(contentsOf: candidate)
                return try JSONSerialization.jsonObject(with: data) as! [String: Any]
            }
            dir = dir.deletingLastPathComponent()
        }
        throw YosegiError("research/vectors/haptics.json が見つからない")
    }

    func testEveryValueMatchesTheTuningTable() throws {
        let tokens = try load()["tokens"] as! [String: [String: Any]]

        // 語彙そのものが揃っていること。片方に増えたら、そこが穴になる。
        XCTAssertEqual(Set(tokens.keys), Set(HapticToken.allCases.map(\.rawValue)),
                       "触覚の語彙が食い違っている")

        for token in HapticToken.allCases {
            let spec = HapticTokens[token]
            let want = tokens[token.rawValue]!
            let name = token.rawValue

            XCTAssertEqual(spec.minIntervalMs, want["minIntervalMs"] as! Int, "\(name): 再発火の間隔")
            XCTAssertEqual(spec.fallback.rawValue, want["predefined"] as! String, "\(name): 代替")

            let env = want["envelope"] as! [String: Any]
            XCTAssertEqual(Double(spec.initialSharpness), env["initialSharpness"] as! Double,
                           accuracy: 1e-6, "\(name): 立ち上がりの硬さ")

            let points = env["points"] as! [[String: Any]]
            XCTAssertEqual(spec.points.count, points.count, "\(name): 制御点の数")
            for (i, p) in points.enumerated() {
                XCTAssertEqual(Double(spec.points[i].intensity), p["intensity"] as! Double,
                               accuracy: 1e-6, "\(name)[\(i)]: 強さ")
                XCTAssertEqual(Double(spec.points[i].sharpness), p["sharpness"] as! Double,
                               accuracy: 1e-6, "\(name)[\(i)]: 硬さ")
                XCTAssertEqual(spec.points[i].durationMs, p["durationMs"] as! Int,
                               "\(name)[\(i)]: 時間")
            }
        }
    }

    /// **エンベロープは必ず0で終わる。**
    ///
    /// 終わらないと OS に拒否される。Android は組み立て時に投げるので気づくが、
    /// iOS は別の実装なので、ここで見ないと「その触覚だけ鳴らない」に化ける。
    func testEveryEnvelopeLandsOnZero() {
        for token in HapticToken.allCases {
            let points = HapticTokens[token].points
            XCTAssertFalse(points.isEmpty, "\(token.rawValue): 制御点が無い")
            XCTAssertEqual(points.last!.intensity, 0, "\(token.rawValue): 最後の制御点が0でない")
        }
    }

    /// 読まれた通知だけは、指が原因ではない。**だから一番強く間引く。**
    func testTheHapticTheFingerDidNotAskForIsTheMostThrottled() {
        let readReceipt = HapticTokens[.readReceipt]
        for token in HapticToken.allCases where token != .readReceipt {
            XCTAssertGreaterThan(readReceipt.minIntervalMs, HapticTokens[token].minIntervalMs,
                                 "\(token.rawValue) のほうが間引きが強い")
        }
    }

    /// 指を追い続けるものが、一覧で一番弱いこと。連射されるものが強いと安っぽくなる。
    func testTheHapticThatFiresWhileTheFingerMovesIsTheWeakest() {
        let selection = HapticTokens[.selection].points.map(\.intensity).max()!
        for token in HapticToken.allCases where token != .selection {
            let other = HapticTokens[token].points.map(\.intensity).max()!
            XCTAssertGreaterThanOrEqual(other, selection, "\(token.rawValue) が Selection より弱い")
        }
    }

    /// 硬さは利用者の強度設定で増減させない。だから 0..1 に収まっていること。
    /// 範囲外は CoreHaptics に弾かれる。
    func testValuesStayInRange() {
        for token in HapticToken.allCases {
            let spec = HapticTokens[token]
            XCTAssertTrue((0...1).contains(spec.initialSharpness), "\(token.rawValue): 立ち上がり")
            for (i, p) in spec.points.enumerated() {
                XCTAssertTrue((0...1).contains(p.intensity), "\(token.rawValue)[\(i)]: 強さ")
                XCTAssertTrue((0...1).contains(p.sharpness), "\(token.rawValue)[\(i)]: 硬さ")
                XCTAssertGreaterThan(p.durationMs, 0, "\(token.rawValue)[\(i)]: 時間が0以下")
            }
        }
    }
}
