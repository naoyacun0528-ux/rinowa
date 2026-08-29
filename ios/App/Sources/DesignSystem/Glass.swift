import SwiftUI

/// ガラスの面。
///
/// `core/designsystem/RinowaGlass.kt` の Swift 側。**値は1つも変えていない。**
///
/// 最初 iOS 側は `.regularMaterial`（Apple 既定のぼかし）で似せようとしていた。
/// あれは iOS では自然な選択だが、**Android と並べると別のアプリに見える**。
/// 地の色から作る縦のグラデーションと、上下で濃さの違う縁——この2つが
/// Rinowa の面の作りで、既定のぼかしはそのどちらも持っていない。
///
/// 押したときの膨らみ方と、指の場所が光ることも含めて移してある。
enum RinowaGlassTone {
    /// 大きい面。会話のカードやパネル。
    case panel
    /// 小さい丸い操作。入力欄のボタン。
    case control

    /// 縮むのではなくふくらむ。縮む操作は押しのけられたように読め、
    /// ふくらむ操作は応えたように読める。
    var pressedScale: CGFloat {
        switch self {
        // カードは大きいので、割合が小さくても実際の移動量は大きい。
        case .panel: return 1.018
        case .control: return 1.075
        }
    }

    var elevation: CGFloat {
        switch self {
        case .panel: return 5
        case .control: return 3
        }
    }
}

extension RinowaDimens {
    static let glassCorner: CGFloat = 22
    static let glassCardMargin: CGFloat = 12
    static let glassCardGap: CGFloat = 7
}

// ------------------------------------------------------------------ 面だけ

/// 押したときの反応が無い、見た目だけのガラス。
///
/// ガラスではあるが触れてふくらむべきでない面のため。[GlassSurface] は
/// これに反応を足したもの。
private struct GlassFace<S: InsettableShape>: ViewModifier {
    let shape: S
    let elevation: CGFloat
    @Environment(\.rinowaColors) private var colors

    func body(content: Content) -> some View {
        content
            .background(
                LinearGradient(
                    colors: [colors.glassFaceHigh, colors.glassFace],
                    startPoint: .top,
                    endPoint: .bottom
                ),
                in: shape
            )
            .overlay(
                shape.strokeBorder(
                    LinearGradient(
                        colors: [colors.glassEdge, colors.glassEdgeLow],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    lineWidth: 1
                )
            )
            .clipShape(shape)
            // Compose の elevation は影の広がりと落ち方をまとめて表す。
            // radius はその 0.8 倍、y は 0.5 倍が実機で一番近かった。
            .shadow(color: colors.glassShadow, radius: elevation * 0.8, y: elevation * 0.5)
    }
}

extension View {
    func glassFace<S: InsettableShape>(
        shape: S,
        elevation: CGFloat = 3
    ) -> some View {
        modifier(GlassFace(shape: shape, elevation: elevation))
    }

    func glassFace(elevation: CGFloat = 3) -> some View {
        glassFace(
            shape: RoundedRectangle(cornerRadius: RinowaDimens.glassCorner, style: .continuous),
            elevation: elevation
        )
    }
}

// ------------------------------------------------------------------ 反応つき

/// 触れると応えるガラス。
///
/// 指のある場所を光らせる。全体を明るくすると状態の切り替えに見え、
/// **局所的に光ると材質が接触に反応したように見える。**
struct GlassSurface<S: InsettableShape, Content: View>: View {

    var shape: S
    var tone: RinowaGlassTone = .panel
    var onTap: (() -> Void)?
    @ViewBuilder var content: () -> Content

    @Environment(\.rinowaColors) private var colors
    @State private var pressed = false
    @State private var pressPoint: CGPoint?

    var body: some View {
        content()
            .glassFace(shape: shape, elevation: tone.elevation)
            .background {
                if let pressPoint, pressed {
                    GeometryReader { geo in
                        let reach = max(geo.size.width, geo.size.height) * 0.80
                        RadialGradient(
                            colors: [
                                colors.glassGlow.opacity(0.34),
                                colors.glassGlow.opacity(0.10),
                                .clear,
                            ],
                            center: .init(
                                x: pressPoint.x / max(geo.size.width, 1),
                                y: pressPoint.y / max(geo.size.height, 1)
                            ),
                            startRadius: 0,
                            endRadius: reach
                        )
                        .clipShape(shape)
                        .allowsHitTesting(false)
                    }
                }
            }
            .scaleEffect(pressed ? tone.pressedScale : 1)
            .animation(RinowaMotion.pop, value: pressed)
            .animation(RinowaMotion.commit, value: pressPoint != nil)
            .contentShape(shape)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !pressed {
                            pressPoint = value.startLocation
                            pressed = true
                        }
                    }
                    .onEnded { value in
                        pressed = false
                        // 指が面の外へ出ていたら、押したことにしない。
                        if let onTap, abs(value.translation.width) < 12,
                           abs(value.translation.height) < 12 {
                            onTap()
                        }
                        pressPoint = nil
                    },
                including: onTap == nil ? .subviews : .all
            )
    }
}

extension GlassSurface where S == RoundedRectangle {
    init(
        tone: RinowaGlassTone = .panel,
        onTap: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.init(
            shape: RoundedRectangle(
                cornerRadius: RinowaDimens.glassCorner,
                style: .continuous
            ),
            tone: tone,
            onTap: onTap,
            content: content
        )
    }
}

// ------------------------------------------------------------------ ＋の印

/// ＋。
///
/// SF Symbols を使わない。**あれは Apple の書体で、端の丸みも太さも
/// Android 側と揃わない。** Android は Canvas で線を2本引いていて、
/// 太さ 2.4、端は丸、24 の枠に対して 0.22〜0.78。同じものをここでも引く。
///
/// 開くと 45度 回って閉じる印になる。新しい操作が現れるのではなく、
/// 同じ操作のもう一方の状態。
struct PlusMark: View {
    var open: Bool
    var tint: Color
    var size: CGFloat = 24

    var body: some View {
        Canvas { context, canvasSize in
            let w = canvasSize.width
            let h = canvasSize.height
            var path = Path()
            path.move(to: CGPoint(x: w * 0.5, y: h * 0.22))
            path.addLine(to: CGPoint(x: w * 0.5, y: h * 0.78))
            path.move(to: CGPoint(x: w * 0.22, y: h * 0.5))
            path.addLine(to: CGPoint(x: w * 0.78, y: h * 0.5))
            context.stroke(
                path,
                with: .color(tint),
                style: StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round)
            )
        }
        .frame(width: size, height: size)
        .rotationEffect(.degrees(open ? 45 : 0))
        .animation(RinowaMotion.pop, value: open)
    }
}
