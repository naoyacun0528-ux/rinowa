import XCTest
import UIKit
import RinowaCore
@testable import RinowaApp

/// 写真・動画・通話。
///
/// 通話の試験に**繋ぐ処理は要らない**。壊れるのはたいてい遷移の方で、
/// それは相手がいなくても試せる。ここで押さえるのは
/// 「二重にかからない」「出ていないものに出られない」「切ったら必ず終わる」の三つ。
@MainActor
final class MediaCallTests: XCTestCase {

    // ------------------------------------------------------------ 通話

    func testDialingTwiceDoesNotRestartTheCall() {
        let call = CallController()
        call.dial(to: "みなと", video: false)
        XCTAssertEqual(call.state, .dialing)

        // 二度目は無視される。名前も動画かどうかも書き換わらない。
        call.dial(to: "べつのひと", video: true)
        XCTAssertEqual(call.state, .dialing)
        XCTAssertEqual(call.peerName, "みなと")
        XCTAssertFalse(call.video)
    }

    func testACallThatConnectsAndHangsUpEndsAsCompleted() {
        let call = CallController()
        call.dial(to: "みなと", video: false)
        call.connected()
        guard case .active = call.state else {
            return XCTFail("繋がったのに active になっていない: \(call.state)")
        }
        call.hangUp()
        XCTAssertEqual(call.state, .ended(.completed))
    }

    func testHangingUpWhileStillDialingIsAMissedCall() {
        let call = CallController()
        call.dial(to: "みなと", video: false)
        call.hangUp()
        // **繋がっていないので completed ではない。** 履歴に残る言葉が変わる。
        XCTAssertEqual(call.state, .ended(.missed))
    }

    func testDecliningAnIncomingCall() {
        let call = CallController()
        call.incoming(from: "みなと", video: true)
        XCTAssertEqual(call.state, .ringing)
        XCTAssertTrue(call.video)
        call.decline()
        XCTAssertEqual(call.state, .ended(.declined))
    }

    func testAnsweringWhenNothingIsRingingDoesNothing() {
        let call = CallController()
        call.answer()
        XCTAssertEqual(call.state, .idle)
        call.connected()
        XCTAssertEqual(call.state, .idle)
        call.hangUp()
        XCTAssertEqual(call.state, .idle)
    }

    func testResetClearsEverythingIncludingTheSwitches() {
        let call = CallController()
        call.incoming(from: "みなと", video: false)
        call.answer()
        call.muted = true
        call.speaker = true
        call.hangUp()
        call.reset()

        XCTAssertEqual(call.state, .idle)
        XCTAssertEqual(call.peerName, "")
        XCTAssertEqual(call.elapsed, 0)
        // **次の通話に前の設定を持ち越さない。** 黙ったまま始まるのが一番困る。
        XCTAssertFalse(call.muted)
        XCTAssertFalse(call.speaker)
    }

    func testDurationTextStartsAtZero() {
        let call = CallController()
        call.incoming(from: "みなと", video: false)
        call.answer()
        XCTAssertEqual(call.durationText, "0:00")
    }

    // ------------------------------------------------------------ 写真

    func testAWideImageIsScaledDownAndKeepsItsShape() throws {
        let source = Self.solidImage(width: 3000, height: 2000)
        let prepared = try XCTUnwrap(MediaStore().prepare(image: source))

        XCTAssertEqual(prepared.width, Int(MediaStore.maxDimension))
        // 3:2 のまま。1600 / 3000 * 2000 = 1066.67 なので、端数の丸め方は問わない。
        XCTAssertTrue((1060...1070).contains(prepared.height),
                      "縦横比が崩れている: \(prepared.width)x\(prepared.height)")
        XCTAssertFalse(prepared.body.isEmpty)
        XCTAssertFalse(prepared.thumbnail.isEmpty)
        // 小さい方が必ず軽い。逆なら縮小がどこかで効いていない。
        XCTAssertLessThan(prepared.thumbnail.count, prepared.body.count)
    }

    func testASmallImageIsNotBlownUp() throws {
        let source = Self.solidImage(width: 240, height: 240)
        let prepared = try XCTUnwrap(MediaStore().prepare(image: source))
        // **拡大はしない。** 引き伸ばして送っても、荒くなるだけで何も増えない。
        XCTAssertEqual(prepared.width, 240)
        XCTAssertEqual(prepared.height, 240)
    }

    func testATallImageIsLimitedByItsHeight() throws {
        let source = Self.solidImage(width: 1000, height: 4000)
        let prepared = try XCTUnwrap(MediaStore().prepare(image: source))
        XCTAssertEqual(prepared.height, Int(MediaStore.maxDimension))
        XCTAssertTrue((398...402).contains(prepared.width),
                      "縦長でも長辺で決まるべき: \(prepared.width)x\(prepared.height)")
    }

    // ------------------------------------------------------------ 行への繋ぎ

    func testAMessageWithoutItsBodyStillKnowsItsShape() throws {
        let message = try XCTUnwrap(
            SampleData.conversations
                .flatMap(\.messages)
                .first { if case .image = $0.content { return true } else { return false } },
            "見本に写真のメッセージが一つも無い"
        )
        guard case .image(let width, let height) = message.content else {
            return XCTFail("写真ではない")
        }
        // 見本には本体を持たせていない。**それでも幅と高さは分かる**ので、
        // 届く前から場所を空けられる。行はここで置き換えを描き分ける。
        XCTAssertNil(message.media)
        XCTAssertGreaterThan(width, 0)
        XCTAssertGreaterThan(height, 0)
    }

    // ------------------------------------------------------------ 道具

    /// 一色で塗っただけの絵。中身は問わない——見ているのは寸法だけ。
    private static func solidImage(width: Int, height: Int) -> UIImage {
        let size = CGSize(width: width, height: height)
        return UIGraphicsImageRenderer(size: size).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
    }
}
