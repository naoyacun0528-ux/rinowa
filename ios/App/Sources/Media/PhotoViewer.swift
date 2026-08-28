import SwiftUI
import UIKit

/// 写真を1枚だけ、大きく見る。
///
/// `ui/chat/PhotoViewer.kt` の Swift 側。
///
/// 拡大と移動が入っている。**指を離したら必ず落ち着く。** 半端な倍率のまま
/// 放置されると、次に開いたときに何が映っているか分からない。
struct PhotoViewer: View {

    let image: UIImage?
    let onClose: () -> Void

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    /// 下へ引いて閉じる量。写真を掴んで下ろす感じにする。
    @State private var dismissDrag: CGFloat = 0

    private var zoomed: Bool { scale > 1.01 }

    var body: some View {
        ZStack {
            // 引くほど地が透ける。**閉じかけていることが、指を離す前に分かる。**
            Color.black
                .opacity(1 - min(dismissDrag / 300, 0.65))
                .ignoresSafeArea()

            content
                .scaleEffect(scale)
                .offset(x: offset.width, y: offset.height + dismissDrag)
                .gesture(magnify)
                .simultaneousGesture(pan)

            controls
        }
        .statusBarHidden()
    }

    @ViewBuilder
    private var content: some View {
        if let image {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
        } else {
            // 本体はまだ取りに行っていない。**形は分かっている**ので場所は空けられる。
            ProgressView().tint(.white)
        }
    }

    private var controls: some View {
        VStack {
            HStack {
                Button {
                    haptics.fire(.navigation)
                    onClose()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: RinowaDimens.touchTarget, height: RinowaDimens.touchTarget)
                        .background(Circle().fill(.ultraThinMaterial))
                }
                .buttonStyle(.plain)

                Spacer()

                // 保存と共有。**削除は置かない。** 相手が送ったものを、こちらの
                // 画面から消せるように見えるのは嘘になる。
                Button {
                    haptics.fire(.softConfirm)
                } label: {
                    Image(systemName: "square.and.arrow.down")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: RinowaDimens.touchTarget, height: RinowaDimens.touchTarget)
                        .background(Circle().fill(.ultraThinMaterial))
                }
                .buttonStyle(.plain)
            }
            .padding(RinowaDimens.gap)

            Spacer()
        }
        .opacity(zoomed ? 0 : 1)
        .animation(RinowaMotion.standard(RinowaMotion.durationQuick), value: zoomed)
    }

    // ---------------------------------------------------------------- 指

    private var magnify: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = max(1, min(lastScale * value, 6))
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= 1.01 {
                    // 等倍に戻ったら位置も戻す。**半端な場所に置いたままにしない。**
                    withAnimation(RinowaMotion.settle) {
                        scale = 1; lastScale = 1
                        offset = .zero; lastOffset = .zero
                    }
                }
            }
    }

    private var pan: some Gesture {
        DragGesture()
            .onChanged { value in
                if zoomed {
                    offset = CGSize(width: lastOffset.width + value.translation.width,
                                    height: lastOffset.height + value.translation.height)
                } else if value.translation.height > 0 {
                    // 等倍のときの下方向は「閉じる」。横は何もしない。
                    dismissDrag = value.translation.height
                }
            }
            .onEnded { value in
                if zoomed {
                    lastOffset = offset
                    return
                }
                if dismissDrag > 120 || value.predictedEndTranslation.height > 300 {
                    haptics.fire(.threshold)
                    onClose()
                } else {
                    withAnimation(RinowaMotion.settle) { dismissDrag = 0 }
                }
            }
    }
}
