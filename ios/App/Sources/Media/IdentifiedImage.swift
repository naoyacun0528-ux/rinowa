import Foundation

/// `fullScreenCover(item:)` に渡すための包み。
///
/// 開くときに要るのは「どれを押したか」だけ。画像そのものは会話が持っていて、
/// **横へめくるには全部が要る**ので、ここでは位置だけを運ぶ。
struct OpenedPhoto: Identifiable {
    let id = UUID()
    let index: Int
}
