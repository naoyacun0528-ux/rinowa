import SwiftUI

/// 動きの定義。
///
/// `RinowaMotion.kt` の Swift 側。
///
/// **触覚はこれに合わせて鳴らすので、時間を画面ごとの判断にしない。**
/// ここの時間を変えれば、対応する触覚の感じも一緒に変わる。その連動が狙い。
///
/// Compose のばねは (減衰比, 剛性)、SwiftUI のばねは (応答, 減衰) で書く。
/// 同じ物理なので変換できる:
///
///     response = 2π / √stiffness
///     dampingFraction = dampingRatio
enum RinowaMotion {

    private static func spring(dampingRatio: Double, stiffness: Double) -> Animation {
        .spring(response: 2 * .pi / stiffness.squareRoot(), dampingFraction: dampingRatio)
    }

    /// 指に追従して、揺り戻さずに落ち着く動き。
    /// 返信スワイプと吹き出しのドラッグで使う。
    static let follow = spring(dampingRatio: 1.0, stiffness: 1400)

    /// 決然と見えるべき確定。速く、ごくわずかに行き過ぎる。
    static let commit = spring(dampingRatio: 0.72, stiffness: 900)

    /// 指の下に現れるもの。リアクションの選択、文脈のシート。
    static let pop = spring(dampingRatio: 0.68, stiffness: 700)

    /// 大きい面が落ち着く動き。ゆっくり、跳ねない。
    static let settle = spring(dampingRatio: 1.0, stiffness: 400)

    /// フェードや色の変化の標準。
    static func standard(_ ms: Int = durationStandard) -> Animation {
        .timingCurve(0.2, 0, 0, 1, duration: Double(ms) / 1000)
    }

    /// 画面から出ていくもの用。速く始まり、名残を残さない。
    static func exit(_ ms: Int = durationQuick) -> Animation {
        .timingCurve(0.4, 0, 1, 1, duration: Double(ms) / 1000)
    }

    static let durationInstant = 90
    static let durationQuick = 160
    static let durationStandard = 260
    static let durationSlow = 420
}

/// 返信スワイプの寸法。**ドラッグ中に触覚が鳴るのは閾値だけ。**
enum RinowaSwipe {
    /// 返信が成立する距離。
    static let threshold: CGFloat = 72

    /// 閾値を越えると次第に重くなる。限界が見えるだけでなく感じられるように。
    static let maxDistance: CGFloat = 104

    /// これ未満はスワイプではなくスクロールとして扱う。
    static let startSlop: CGFloat = 10

    /// 指はいつでも動かせるが、吹き出しは追いつかなくなる。
    static func resist(_ raw: CGFloat, threshold: CGFloat = threshold, max maxPx: CGFloat = maxDistance) -> CGFloat {
        if raw <= threshold { return raw }
        let overshoot = raw - threshold
        let room = Swift.max(maxPx - threshold, 1)
        // 漸近的に。
        return threshold + room * (1 - 1 / (1 + overshoot / room))
    }
}
