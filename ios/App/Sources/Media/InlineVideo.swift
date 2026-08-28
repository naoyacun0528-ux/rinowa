import AVKit
import SwiftUI
import UIKit

/// スレッドの中で、そのまま再生する動画。
///
/// `ui/chat/InlineVideo.kt` の Swift 側。
///
/// **止めたら1枚目の絵に戻る。** 止まった最後のこまを残したままにすると、
/// スレッドを遡ったときに、どれが動画でどれが写真か分からなくなる。
struct InlineVideo: View {

    let url: URL?
    let thumbnail: UIImage?
    let durationMs: Int64
    let aspect: CGFloat

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var player: AVPlayer?
    @State private var playing = false
    @State private var progress: Double = 0
    @State private var observer: Any?

    var body: some View {
        ZStack {
            if let player, playing {
                VideoPlayer(player: player)
            } else {
                poster
            }

            if !playing {
                playButton
            }
        }
        .aspectRatio(aspect, contentMode: .fit)
        .frame(maxWidth: 260)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(alignment: .bottomTrailing) {
            if !playing {
                Text(RinowaFormat.duration(ms: durationMs))
                    .rinowaType(RinowaType.labelSmall)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(.black.opacity(0.55)))
                    .padding(6)
            }
        }
        .onDisappear(perform: stop)
    }

    @ViewBuilder
    private var poster: some View {
        if let thumbnail {
            Image(uiImage: thumbnail).resizable().scaledToFill()
        } else {
            colors.surfaceSunken
        }
    }

    private var playButton: some View {
        Button(action: play) {
            Image(systemName: "play.fill")
                .font(.system(size: 20))
                .foregroundStyle(.white)
                .frame(width: 52, height: 52)
                // 裏をぼかす。**下の絵が透けることで、何の動画かが分かったまま。**
                .background(Circle().fill(.ultraThinMaterial))
        }
        .buttonStyle(.plain)
    }

    private func play() {
        guard let url else { return }
        haptics.fire(.softConfirm)
        let player = AVPlayer(url: url)

        // 最後まで行ったら**必ず1枚目に戻る**。
        observer = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: player.currentItem,
            queue: .main
        ) { _ in
            stop()
        }

        self.player = player
        playing = true
        player.play()
    }

    private func stop() {
        player?.pause()
        player = nil
        playing = false
        progress = 0
        if let observer {
            NotificationCenter.default.removeObserver(observer)
            self.observer = nil
        }
    }
}
