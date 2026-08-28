import Foundation

/// 触覚の調整表。
///
/// **このファイルは手で書かない。** research/vectors/haptics.json から起こしたもので、
/// 元は Android の `HapticTokens.kt`。数値がそのまま「感触」なので、写し間違えると
/// 「同じアプリなのに iPhone だと送信の手応えが弱い」という、誰も原因に辿り着けない
/// 壊れ方になる。作り直しは tools/gen-haptics.mjs。
///
/// 持っているのはエンベロープ段だけ。**両方の実装に共通して存在する唯一の段**だから。
/// Android のプリミティブ（TICK / CLICK / THUD）は Android の語彙で iOS に無く、
/// iOS の CoreHaptics は (強さ, 硬さ, 時間) で書くので、こちらとほぼそのまま対応する。
///
/// 鳴らすのは Apple の SDK が要るので、ここには入れない。ここは値だけ。

/// アプリの触覚の語彙。
///
/// 画面は「どう感じさせるか」を書かない。「何が起きたか」を書く。
/// 意味を波形に変えるのはこの層の仕事で、ここだけの仕事。
public enum HapticToken: String, CaseIterable {
    case selection = "Selection"
    case navigation = "Navigation"
    case softConfirm = "SoftConfirm"
    case send = "Send"
    case threshold = "Threshold"
    case thresholdRelease = "ThresholdRelease"
    case reaction = "Reaction"
    case readReceipt = "ReadReceipt"
    case success = "Success"
    case warning = "Warning"
    case error = "Error"
    case destructive = "Destructive"
}

/// エンベロープの制御点1つ。
///
/// - intensity: 0..1 の振幅。利用者の強度設定で増減する。
/// - sharpness: 0..1 の硬さ。**利用者設定では増減させない** — 強さではなく
///   その触覚の性格を運ぶため。
/// - durationMs: 前の点からここへ移るまでの時間。
public struct HapticEnvelopePoint: Equatable {
    public let intensity: Float
    public let sharpness: Float
    public let durationMs: Int

    public init(_ intensity: Float, _ sharpness: Float, _ durationMs: Int) {
        self.intensity = intensity; self.sharpness = sharpness; self.durationMs = durationMs
    }
}

public struct HapticSpec: Equatable {
    public let initialSharpness: Float
    public let points: [HapticEnvelopePoint]
    /// この時間内には再発火しない。触覚を安っぽくする一番の要因＝連射を防ぐ。
    public let minIntervalMs: Int
    /// 触覚エンジンが無い端末での代替。
    public let fallback: HapticFallback
}

/// 細かい制御ができないときの、粗い代わり。
public enum HapticFallback: String, CaseIterable {
    case tick = "Tick", click = "Click", doubleClick = "DoubleClick", heavyClick = "HeavyClick"
}

public enum HapticTokens {

    private static let selection = HapticSpec(
        initialSharpness: 0.72,
        points: [
            HapticEnvelopePoint(0.25, 0.72, 8),
            HapticEnvelopePoint(0.00, 0.72, 12),
        ],
        minIntervalMs: 40,
        fallback: .tick
    )

    private static let navigation = HapticSpec(
        initialSharpness: 0.58,
        points: [
            HapticEnvelopePoint(0.35, 0.58, 10),
            HapticEnvelopePoint(0.00, 0.55, 18),
        ],
        minIntervalMs: 100,
        fallback: .tick
    )

    private static let softConfirm = HapticSpec(
        initialSharpness: 0.65,
        points: [
            HapticEnvelopePoint(0.45, 0.65, 10),
            HapticEnvelopePoint(0.00, 0.65, 20),
        ],
        minIntervalMs: 60,
        fallback: .tick
    )

    private static let send = HapticSpec(
        initialSharpness: 0.90,
        points: [
            HapticEnvelopePoint(0.70, 0.93, 6),
            HapticEnvelopePoint(0.00, 0.79, 22),
        ],
        minIntervalMs: 120,
        fallback: .click
    )

    private static let threshold = HapticSpec(
        initialSharpness: 0.93,
        points: [
            HapticEnvelopePoint(0.90, 1.00, 5),
            HapticEnvelopePoint(0.00, 0.86, 16),
        ],
        minIntervalMs: 80,
        fallback: .click
    )

    private static let thresholdRelease = HapticSpec(
        initialSharpness: 0.79,
        points: [
            HapticEnvelopePoint(0.45, 0.79, 5),
            HapticEnvelopePoint(0.00, 0.72, 14),
        ],
        minIntervalMs: 80,
        fallback: .tick
    )

    private static let reaction = HapticSpec(
        initialSharpness: 0.51,
        points: [
            HapticEnvelopePoint(0.50, 0.62, 12),
            HapticEnvelopePoint(0.75, 0.79, 10),
            HapticEnvelopePoint(0.00, 0.72, 24),
        ],
        minIntervalMs: 60,
        fallback: .click
    )

    private static let readReceipt = HapticSpec(
        initialSharpness: 0.42,
        points: [
            HapticEnvelopePoint(0.26, 0.45, 8),
            HapticEnvelopePoint(0.00, 0.40, 20),
        ],
        minIntervalMs: 1000,
        fallback: .tick
    )

    private static let success = HapticSpec(
        initialSharpness: 0.65,
        points: [
            HapticEnvelopePoint(0.40, 0.65, 10),
            HapticEnvelopePoint(0.00, 0.65, 12),
            HapticEnvelopePoint(0.00, 0.65, 50),
            HapticEnvelopePoint(0.70, 0.79, 10),
            HapticEnvelopePoint(0.00, 0.72, 18),
        ],
        minIntervalMs: 150,
        fallback: .doubleClick
    )

    private static let warning = HapticSpec(
        initialSharpness: 0.79,
        points: [
            HapticEnvelopePoint(0.75, 0.79, 8),
            HapticEnvelopePoint(0.00, 0.72, 10),
            HapticEnvelopePoint(0.00, 0.72, 90),
            HapticEnvelopePoint(0.45, 0.65, 10),
            HapticEnvelopePoint(0.00, 0.65, 16),
        ],
        minIntervalMs: 150,
        fallback: .doubleClick
    )

    private static let error = HapticSpec(
        initialSharpness: 0.58,
        points: [
            HapticEnvelopePoint(0.80, 0.58, 8),
            HapticEnvelopePoint(0.00, 0.55, 8),
            HapticEnvelopePoint(0.00, 0.55, 42),
            HapticEnvelopePoint(0.80, 0.58, 8),
            HapticEnvelopePoint(0.00, 0.55, 8),
            HapticEnvelopePoint(0.00, 0.55, 42),
            HapticEnvelopePoint(0.60, 0.51, 10),
            HapticEnvelopePoint(0.00, 0.51, 14),
        ],
        minIntervalMs: 200,
        fallback: .doubleClick
    )

    private static let destructive = HapticSpec(
        initialSharpness: 0.41,
        points: [
            HapticEnvelopePoint(0.85, 0.44, 18),
            HapticEnvelopePoint(0.00, 0.41, 45),
        ],
        minIntervalMs: 200,
        fallback: .heavyClick
    )

    private static let table: [HapticToken: HapticSpec] = [
        .selection: selection,
        .navigation: navigation,
        .softConfirm: softConfirm,
        .send: send,
        .threshold: threshold,
        .thresholdRelease: thresholdRelease,
        .reaction: reaction,
        .readReceipt: readReceipt,
        .success: success,
        .warning: warning,
        .error: error,
        .destructive: destructive,
    ]

    public static subscript(token: HapticToken) -> HapticSpec { table[token]! }
}
