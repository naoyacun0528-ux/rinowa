import SwiftUI
import PhotosUI

/// プロフィール写真を正方形に切り抜く。
///
/// `ui/profile/PhotoCropScreen.kt` の Swift 側。
///
/// **写真は端末の中で切って縮めてから保存する。** 元の写真と、撮った場所などの
/// 情報は出さない。切り抜きを画面でやる理由もそこにあって、送ってから直すのでは
/// 一度は元のまま外へ出ることになる。
struct PhotoCropScreen: View {

    var source: UIImage?
    let onBack: () -> Void
    var onSave: (UIImage) -> Void = { _ in }

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var picked: PhotosPickerItem?
    @State private var image: UIImage?
    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero
    @State private var busy = false

    /// 拡大の上限。これ以上伸ばすと、保存したアイコンが目に見えて荒れる。
    private let maxZoom: CGFloat = 4

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "写真を選ぶ", onBack: onBack)

            if let image {
                cropper(image)

                VStack(spacing: RinowaDimens.gapSmall) {
                    Text("写真は端末の中で正方形に切り取って縮小してから保存します。")
                        .rinowaType(RinowaType.labelSmall)
                        .foregroundStyle(colors.textTertiary)
                    Text("元の写真と撮影場所などの情報は送られません。")
                        .rinowaType(RinowaType.labelSmall)
                        .foregroundStyle(colors.textTertiary)

                    PrimaryButton(enabled: !busy, action: { save(image) }) { tint in
                        PrimaryButtonLabel(text: busy ? "保存しています" : "保存", color: tint)
                    }
                    .padding(.top, RinowaDimens.gapSmall)

                    PhotosPicker(selection: $picked, matching: .images) {
                        Text("写真を選ぶ")
                            .rinowaType(RinowaType.label)
                            .foregroundStyle(colors.accent)
                            .frame(minHeight: RinowaDimens.touchTarget)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, RinowaDimens.gap)
            } else {
                chooser
            }
        }
        .background(colors.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { if image == nil { image = source } }
        .onChange(of: picked) { item in
            guard let item else { return }
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self),
                      let loaded = UIImage(data: data) else { return }
                await MainActor.run {
                    image = loaded
                    scale = 1; lastScale = 1
                    offset = .zero; lastOffset = .zero
                }
            }
        }
    }

    // ---------------------------------------------------------------- 切り抜き窓

    private func cropper(_ image: UIImage) -> some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            ZStack {
                Color.black
                Image(uiImage: image)
                    // **Fit にしない。** 正方形の窓に余白ごと収めると、
                    // 保存したアイコンの縁に背景が焼き付く。
                    .resizable()
                    .scaledToFill()
                    .scaleEffect(scale)
                    .offset(offset)
                    .frame(width: side, height: side)
                    .clipped()
            }
            .frame(width: side, height: side)
            .clipShape(Circle())
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .gesture(
                MagnificationGesture()
                    .onChanged { value in
                        scale = min(max(lastScale * value, 1), maxZoom)
                    }
                    .onEnded { _ in lastScale = scale }
            )
            .simultaneousGesture(
                DragGesture()
                    .onChanged { value in
                        offset = CGSize(
                            width: lastOffset.width + value.translation.width,
                            height: lastOffset.height + value.translation.height
                        )
                    }
                    .onEnded { _ in lastOffset = offset }
            )
        }
        .frame(maxHeight: .infinity)
        .padding(RinowaDimens.gap)
    }

    private var chooser: some View {
        VStack(spacing: RinowaDimens.gap) {
            Spacer()
            Text("写真を選んでください")
                .rinowaType(RinowaType.listName)
                .foregroundStyle(colors.textSecondary)
            PhotosPicker(selection: $picked, matching: .images) {
                Text("写真を選ぶ")
                    .rinowaType(RinowaType.label)
                    .foregroundStyle(colors.accent)
                    .padding(.horizontal, 20)
                    .frame(height: RinowaDimens.touchTarget)
                    .glassFace(
                        shape: RoundedRectangle(cornerRadius: 14, style: .continuous),
                        elevation: 3
                    )
            }
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    // ---------------------------------------------------------------- 保存

    private func save(_ image: UIImage) {
        busy = true
        haptics.fire(.softConfirm)

        // 画面で見えている通りに切る。窓は正方形で、倍率と位置はそのまま使う。
        let side: CGFloat = 512
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let out = UIGraphicsImageRenderer(size: .init(width: side, height: side), format: format)
            .image { _ in
                let ratio = max(side / image.size.width, side / image.size.height) * scale
                let w = image.size.width * ratio
                let h = image.size.height * ratio
                image.draw(in: CGRect(
                    x: (side - w) / 2 + offset.width,
                    y: (side - h) / 2 + offset.height,
                    width: w,
                    height: h
                ))
            }

        busy = false
        haptics.fire(.success)
        onSave(out)
        onBack()
    }
}
