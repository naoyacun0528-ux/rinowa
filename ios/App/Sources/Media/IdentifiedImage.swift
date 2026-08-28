import SwiftUI
import UIKit

/// `fullScreenCover(item:)` に渡すための包み。
///
/// UIImage は `Identifiable` ではないので、そのままでは渡せない。
/// 中身を differentiate する必要は無い——**開いているのは常に1枚**なので、
/// id は開くたびに新しくてよい。
struct IdentifiedImage: Identifiable {
    let id = UUID()
    let value: UIImage?
}
