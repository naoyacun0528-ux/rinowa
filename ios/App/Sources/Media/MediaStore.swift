import AVFoundation
import Photos
import SwiftUI
import UIKit
import RinowaCore

/// 写真と動画を、送れる形にするところ。
///
/// `media/` の Swift 側。Android は 1,017行あり、縮小・サムネイル・分割暗号・
/// 送出までを抱えている。ここは**端末側の仕事だけ**——選ぶ、縮める、
/// サムネイルを作る——を持つ。封をするのは `RinowaCore` の仕事で、
/// 上げるのはまだ繋いでいない。
///
/// **サムネイルも封の中に入る。** 一覧に並ぶ小さな写真は小さいが、写真には違いない。
/// 説明文だけ暗号化して写真を平文で送るのは、暗号化ではない。
@MainActor
final class MediaStore: ObservableObject {

    /// 送る前に縮める上限。
    ///
    /// **元の大きさのまま送らない。** 相手の端末が受け取るのは、相手の画面に
    /// 収まればいいもの。4000px の写真を送っても、見えるのは同じで、
    /// 通信量と電池だけが増える。
    static let maxDimension: CGFloat = 1600

    /// 一覧と吹き出しに出す小さいほう。封筒の中に入るので、小さくないと入らない。
    static let thumbnailDimension: CGFloat = 256

    /// 画像を、送れる形に整える。
    ///
    /// 返すのは (縮めた本体, サムネイル, 元の形)。形を先に返すのは、
    /// **本体が届く前から場所を正しく空けられる**ようにするため。
    func prepare(image: UIImage) -> PreparedImage? {
        guard let scaled = Self.scaled(image, to: Self.maxDimension),
              let thumb = Self.scaled(image, to: Self.thumbnailDimension),
              let body = scaled.jpegData(compressionQuality: 0.82),
              let thumbData = thumb.jpegData(compressionQuality: 0.7)
        else { return nil }

        return PreparedImage(
            body: body,
            thumbnail: thumbData,
            width: Int(scaled.size.width),
            height: Int(scaled.size.height)
        )
    }

    /// 動画から、1枚目のこまと長さを取る。
    ///
    /// 動画そのものは送る前に縮める必要があるが、それは端末の外に出す段で。
    /// ここでは**吹き出しに出すために要るものだけ**取る。
    func probe(video url: URL) async -> PreparedVideo? {
        let asset = AVURLAsset(url: url)
        do {
            let duration = try await asset.load(.duration)
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: Self.thumbnailDimension,
                                           height: Self.thumbnailDimension)
            // 0秒ちょうどは黒い場合がある。少し進めた位置から取る。
            let time = CMTime(seconds: min(0.3, duration.seconds / 2), preferredTimescale: 600)
            let cgImage = try await generator.image(at: time).image
            let thumb = UIImage(cgImage: cgImage)
            guard let data = thumb.jpegData(compressionQuality: 0.7) else { return nil }

            let track = try await asset.loadTracks(withMediaType: .video).first
            let size = try await track?.load(.naturalSize) ?? .zero

            return PreparedVideo(
                thumbnail: data,
                durationMs: Int64(duration.seconds * 1000),
                width: Int(abs(size.width)),
                height: Int(abs(size.height))
            )
        } catch {
            return nil
        }
    }

    /// 長辺を上限に合わせて縮める。**縦横比は変えない。**
    private static func scaled(_ image: UIImage, to limit: CGFloat) -> UIImage? {
        let longest = max(image.size.width, image.size.height)
        guard longest > 0 else { return nil }
        let ratio = min(1, limit / longest)
        if ratio >= 1 { return image }

        let target = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1   // 画面の倍率ではなく実寸で。送るのは画素であって見え方ではない。
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }
}

struct PreparedImage {
    let body: Data
    let thumbnail: Data
    let width: Int
    let height: Int
}

struct PreparedVideo {
    let thumbnail: Data
    let durationMs: Int64
    let width: Int
    let height: Int
}
