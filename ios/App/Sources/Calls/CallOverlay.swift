import SwiftUI
import RinowaCore

/// 通話中の画面。
///
/// `ui/calls/CallOverlay.kt` の Swift 側。
///
/// **画面の上に重ねる**。別の画面へ移らないので、通話しながら会話も読める。
/// 通話が「今いる場所を奪うもの」ではなく「今いる場所の上にあるもの」になる。
struct CallOverlay: View {

    @ObservedObject var call: CallController
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    var body: some View {
        ZStack {
            colors.scrim.ignoresSafeArea()

            VStack(spacing: RinowaDimens.gapHuge) {
                Spacer()

                VStack(spacing: RinowaDimens.gapSmall) {
                    Avatar(title: call.peerName, seed: call.peerName.count, size: 96)
                    Text(call.peerName)
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(colors.textPrimary)
                    Text(statusText)
                        .rinowaType(RinowaType.label)
                        .foregroundStyle(colors.textSecondary)
                        // 数字が動くたびに幅が変わるのを止める。
                        .monospacedDigit()
                }

                Spacer()

                controls
            }
            .padding(RinowaDimens.gapHuge)
        }
        .background(.regularMaterial)
    }

    private var statusText: String {
        switch call.state {
        case .idle: return ""
        case .dialing: return "呼び出しています"
        case .ringing: return call.video ? "ビデオ通話の着信" : "着信"
        case .active: return call.durationText
        case .ended(let outcome):
            switch outcome {
            case .completed: return "終了 " + call.durationText
            case .missed: return "応答なし"
            case .declined: return "断りました"
            case .failed: return "つながりませんでした"
            }
        }
    }

    @ViewBuilder
    private var controls: some View {
        switch call.state {
        case .ringing:
            // 出るのと断るのは**離して置く**。押し間違えたときの取り返しが効かない。
            HStack(spacing: RinowaDimens.gapHuge * 2) {
                circle("phone.down.fill", tint: colors.danger) {
                    haptics.fire(.destructive)
                    call.decline()
                }
                circle("phone.fill", tint: colors.success) {
                    haptics.fire(.success)
                    call.answer()
                }
            }

        case .active, .dialing:
            VStack(spacing: RinowaDimens.gapLarge) {
                HStack(spacing: RinowaDimens.gapLarge) {
                    toggle("mic.slash.fill", "mic.fill", on: call.muted) {
                        haptics.fire(.selection)
                        call.muted.toggle()
                    }
                    toggle("speaker.wave.2.fill", "speaker.fill", on: call.speaker) {
                        haptics.fire(.selection)
                        call.speaker.toggle()
                    }
                    if call.video {
                        toggle("camera.rotate.fill", "camera.rotate", on: call.cameraFront) {
                            haptics.fire(.selection)
                            call.cameraFront.toggle()
                        }
                    }
                }
                circle("phone.down.fill", tint: colors.danger) {
                    haptics.fire(.destructive)
                    call.hangUp()
                }
            }

        case .ended, .idle:
            Button {
                haptics.fire(.navigation)
                call.reset()
            } label: {
                Text("閉じる")
                    .rinowaType(RinowaType.label)
                    .foregroundStyle(colors.textPrimary)
                    .frame(minWidth: 120, minHeight: RinowaDimens.touchTarget)
                    .background(Capsule().fill(colors.surfaceRaised))
            }
            .buttonStyle(.plain)
        }
    }

    private func circle(_ symbol: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 24))
                .foregroundStyle(.white)
                .frame(width: 68, height: 68)
                .background(Circle().fill(tint))
        }
        .buttonStyle(.plain)
    }

    private func toggle(_ onSymbol: String, _ offSymbol: String,
                        on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: on ? onSymbol : offSymbol)
                .font(.system(size: 20))
                .foregroundStyle(on ? colors.onAccent : colors.textPrimary)
                .frame(width: 54, height: 54)
                .background(Circle().fill(on ? colors.accent : colors.surfaceRaised))
        }
        .buttonStyle(.plain)
    }
}
