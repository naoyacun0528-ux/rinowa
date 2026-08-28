import CoreHaptics
import SwiftUI
import UIKit
import RinowaCore

/// 触覚を鳴らす側。
///
/// **数値は持たない。** 調整表は `RinowaCore` の `HapticTokens` にあり、そこは
/// Android の `HapticTokens.kt` から機械生成されている。ここがやるのは、
/// その (強さ, 硬さ, 時間) を CoreHaptics の言葉に置き換えることだけ。
///
/// Android 側と対応する段:
///
///   エンベロープ  →  CHHapticEvent（強さ・硬さ・時間がそのまま対応する）
///   既定効果      →  UIImpactFeedbackGenerator（触覚エンジンが無い端末）
///
/// 中間の段（プリミティブ）は写さない。TICK / CLICK / THUD は Android の語彙で、
/// iOS に対応するものが無い。**共通の言葉があるところだけを共通にする。**
@MainActor
final class HapticEngine: ObservableObject {

    private var engine: CHHapticEngine?
    private var lastFired: [HapticToken: Date] = [:]

    /// 触覚エンジンが無い端末（と Simulator）で使う代替。
    private let light = UIImpactFeedbackGenerator(style: .light)
    private let medium = UIImpactFeedbackGenerator(style: .medium)
    private let heavy = UIImpactFeedbackGenerator(style: .heavy)
    private let notice = UINotificationFeedbackGenerator()

    var isAvailable: Bool { CHHapticEngine.capabilitiesForHardware().supportsHaptics }

    init() {
        start()
    }

    private func start() {
        guard isAvailable else { return }
        do {
            let engine = try CHHapticEngine()
            // アプリが背面に回ると OS がエンジンを止める。戻ったときに黙って
            // 鳴らなくなるのを防ぐ。**「たまに触覚が来ない」は原因に辿り着けない。**
            engine.stoppedHandler = { [weak self] _ in
                Task { @MainActor in self?.engine = nil }
            }
            engine.resetHandler = { [weak self] in
                Task { @MainActor in try? self?.engine?.start() }
            }
            try engine.start()
            self.engine = engine
        } catch {
            // 鳴らないだけ。**アプリは止めない。** 触覚は届ける仕事の一部ではない。
            engine = nil
        }
    }

    /// 意味を渡す。波形はこちらが決める。
    ///
    /// 画面は「どう感じさせるか」を書かない。「何が起きたか」を書く。
    func fire(_ token: HapticToken) {
        let spec = HapticTokens[token]

        // **連射を防ぐ。** 触覚を安っぽくする一番の要因がこれ。
        let now = Date()
        if let last = lastFired[token],
           now.timeIntervalSince(last) * 1000 < Double(spec.minIntervalMs) {
            return
        }
        lastFired[token] = now

        if engine == nil { start() }
        guard let engine else { fallback(spec.fallback); return }

        do {
            try engine.start()
            let player = try engine.makePlayer(with: pattern(for: spec))
            try player.start(atTime: CHHapticTimeImmediate)
        } catch {
            fallback(spec.fallback)
        }
    }

    /// エンベロープの制御点を、時間の上に並べ直す。
    ///
    /// Android の制御点は「**前の点からここへ移るまでの時間**」を持つ。
    /// CoreHaptics は「その時刻に何が起きるか」で書くので、時間を足しながら進む。
    private func pattern(for spec: HapticSpec) throws -> CHHapticPattern {
        var events: [CHHapticEvent] = []
        var t: TimeInterval = 0
        var sharpness = spec.initialSharpness

        for point in spec.points {
            let duration = TimeInterval(point.durationMs) / 1000
            if point.intensity > 0 {
                events.append(
                    CHHapticEvent(
                        eventType: .hapticContinuous,
                        parameters: [
                            .init(parameterID: .hapticIntensity, value: point.intensity),
                            .init(parameterID: .hapticSharpness, value: sharpness),
                        ],
                        relativeTime: t,
                        duration: duration
                    )
                )
            }
            sharpness = point.sharpness
            t += duration
        }

        // 制御点が全部0だった場合。**無音のパターンは CoreHaptics に拒否される。**
        if events.isEmpty {
            events.append(
                CHHapticEvent(
                    eventType: .hapticTransient,
                    parameters: [
                        .init(parameterID: .hapticIntensity, value: 0.3),
                        .init(parameterID: .hapticSharpness, value: spec.initialSharpness),
                    ],
                    relativeTime: 0
                )
            )
        }

        return try CHHapticPattern(events: events, parameters: [])
    }

    /// 細かい制御ができない端末での粗い代わり。
    ///
    /// Android 側の「既定効果」と同じ考え方で、メーカーが実機に合わせて
    /// 調整したものに任せる。紛らわしくない場面ではそちらが勝つ。
    private func fallback(_ kind: HapticFallback) {
        switch kind {
        case .tick: light.impactOccurred()
        case .click: medium.impactOccurred()
        case .doubleClick: notice.notificationOccurred(.success)
        case .heavyClick: heavy.impactOccurred()
        }
    }
}

private struct HapticEngineKey: EnvironmentKey {
    @MainActor static let defaultValue = HapticEngine()
}

extension EnvironmentValues {
    var haptics: HapticEngine {
        get { self[HapticEngineKey.self] }
        set { self[HapticEngineKey.self] = newValue }
    }
}
