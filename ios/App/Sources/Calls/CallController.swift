import AVFoundation
import Combine
import SwiftUI
import RinowaCore

/// 通話の状態。
///
/// `calls/` の Swift 側。Android は 2,760行あり、WebRTC・音声経路・
/// 画面の向き・端末の傾きまで抱えている。
///
/// **ここに WebRTC は入っていない。** 入れる前に、状態の遷移だけ先に正しくする。
/// 通話が壊れるときの原因は、たいてい繋ぐ処理ではなく**遷移の取りこぼし**
/// （出たのに鳴り続ける、切ったのに画面が残る、二重にかかる）で、
/// それは繋がなくても書けるし、繋がなくても試せる。
///
/// SDP と ICE は封筒に入れて渡す（`docs/RINOWA_SIGIL.md`）。
/// 経路の情報も本文と同じ扱いにする。
@MainActor
final class CallController: ObservableObject {

    enum State: Equatable {
        case idle
        /// こちらからかけている。相手が出るまで。
        case dialing
        /// 相手からかかってきている。まだ出ていない。
        case ringing
        /// 繋がった。秒数が意味を持つのはここから。
        case active(since: Date)
        /// 終わった。理由つき。
        case ended(CallOutcome)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var video: Bool = false
    @Published var muted = false
    @Published var speaker = false
    @Published var cameraFront = true

    private var ticker: AnyCancellable?
    @Published private(set) var elapsed: Int = 0

    var peerName: String = ""

    // ---------------------------------------------------------------- 操作

    func dial(to name: String, video: Bool) {
        guard state == .idle else { return }   // **二重にかけない。**
        peerName = name
        self.video = video
        state = .dialing
        // 音声の経路は繋ぐ前に決める。あとから変えると、最初の一言が消える。
        configureAudioSession()
    }

    func incoming(from name: String, video: Bool) {
        guard state == .idle else { return }
        peerName = name
        self.video = video
        state = .ringing
    }

    func answer() {
        guard state == .ringing else { return }
        configureAudioSession()
        begin()
    }

    func decline() {
        guard state == .ringing else { return }
        finish(.declined)
    }

    /// 相手が出た（こちらからかけていた場合）。
    func connected() {
        guard state == .dialing else { return }
        begin()
    }

    func hangUp() {
        switch state {
        case .active: finish(.completed)
        case .dialing: finish(.missed)
        case .ringing: finish(.declined)
        default: break
        }
    }

    /// 経路が見つからなかった、相手が出なかった、途中で切れた——
    /// **当事者にとっては同じ「通話できなかった」**なので、粗いまま。
    func failed() {
        guard state != .idle else { return }
        finish(.failed)
    }

    func reset() {
        state = .idle
        elapsed = 0
        muted = false
        speaker = false
        peerName = ""
    }

    // ---------------------------------------------------------------- 中

    private func begin() {
        state = .active(since: Date())
        elapsed = 0
        ticker = Timer.publish(every: 1, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                guard let self, case .active(let since) = self.state else { return }
                self.elapsed = Int(Date().timeIntervalSince(since))
            }
    }

    private func finish(_ outcome: CallOutcome) {
        ticker?.cancel()
        ticker = nil
        state = .ended(outcome)
        // 音声の占有は必ず返す。**返し忘れると、次に音楽が鳴らない。**
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
            try session.setActive(true)
        } catch {
            // 音が出ないだけ。**通話そのものは止めない。**
        }
    }

    var durationText: String { RinowaFormat.callDuration(seconds: elapsed) }
}
