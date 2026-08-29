import SwiftUI

/// 画面が共通で使う部品。
///
/// `ui/auth/AuthComponents.kt` と `ui/common/ScreenHeader.kt` の Swift 側。
/// **値は1つも変えていない。** Material や SF Symbols の既定に寄せると、
/// 二台並べたときに別のアプリに見える——一覧の＋で一度やった失敗。

// ------------------------------------------------------------------ 入力欄

/// Rinowa 自身の言葉で書いた1行入力欄。
///
/// 枠線の箱に浮く見出しが付く既定の入力欄を使わない。付くと他所のアプリに見える。
struct RinowaField<Trailing: View>: View {

    @Binding var value: String
    var placeholder: String
    var enabled: Bool = true
    var secure: Bool = false
    var keyboard: UIKeyboardType = .default
    var capitalization: TextInputAutocapitalization = .never
    var submitLabel: SubmitLabel = .done
    var onSubmit: () -> Void = {}
    @ViewBuilder var trailing: () -> Trailing

    @Environment(\.rinowaColors) private var colors

    var body: some View {
        HStack(spacing: 0) {
            ZStack(alignment: .leading) {
                if value.isEmpty {
                    Text(placeholder)
                        .rinowaType(RinowaType.composer)
                        .foregroundStyle(colors.textTertiary)
                        // 文言が変わることがあるので、瞬時に差し替えない。
                        .transition(.opacity)
                        .id(placeholder)
                }
                Group {
                    if secure {
                        SecureField("", text: $value)
                    } else {
                        TextField("", text: $value)
                    }
                }
                .rinowaType(RinowaType.composer)
                .foregroundStyle(colors.textPrimary)
                .tint(colors.accent)
                .disabled(!enabled)
                .keyboardType(keyboard)
                .textInputAutocapitalization(capitalization)
                .autocorrectionDisabled()
                .submitLabel(submitLabel)
                .onSubmit(onSubmit)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            trailing()
        }
        .padding(.leading, 16)
        .padding(.trailing, 6)
        .frame(minHeight: RinowaDimens.composerMinHeight + 6)
        .glassFace(shape: RoundedRectangle(cornerRadius: 16, style: .continuous), elevation: 2)
        .animation(RinowaMotion.standard(RinowaMotion.durationQuick), value: value.isEmpty)
    }
}

extension RinowaField where Trailing == EmptyView {
    init(
        value: Binding<String>,
        placeholder: String,
        enabled: Bool = true,
        secure: Bool = false,
        keyboard: UIKeyboardType = .default,
        capitalization: TextInputAutocapitalization = .never,
        submitLabel: SubmitLabel = .done,
        onSubmit: @escaping () -> Void = {}
    ) {
        self.init(
            value: value,
            placeholder: placeholder,
            enabled: enabled,
            secure: secure,
            keyboard: keyboard,
            capitalization: capitalization,
            submitLabel: submitLabel,
            onSubmit: onSubmit,
            trailing: { EmptyView() }
        )
    }
}

// ------------------------------------------------------------------ ボタン

/// その画面が求めている唯一の操作。
///
/// 無効のときはアルファで薄くせず、地の色へ混ぜた不透明の塗りにする。
/// 半透明の塗りだと、影のシルエットが透けて多角形に見えるため。
struct PrimaryButton<Label: View>: View {

    var enabled: Bool
    var action: () -> Void
    @ViewBuilder var label: (Color) -> Label

    @Environment(\.rinowaColors) private var colors
    @State private var pressed = false

    var body: some View {
        // 0.18 は実機で測った値。明るい背景ではアクセントの 30% がまだ
        // 自信のあるボタンに見えたが、暗いほうでは見えなくなっていた。
        let fill: CGFloat = enabled ? 1 : 0.18
        let base = colors.background.mixed(with: colors.accent, amount: fill)

        return ZStack {
            label(enabled ? colors.onAccent : colors.textTertiary)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 52)
        .background(
            LinearGradient(
                colors: [base, base.mixed(with: .black, amount: 0.07)],
                startPoint: .top,
                endPoint: .bottom
            ),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(
                    LinearGradient(
                        colors: [colors.glassEdge.opacity(0.40), .clear],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    lineWidth: 1
                )
        )
        .shadow(color: colors.glassShadow, radius: 3, y: 2)
        .scaleEffect(pressed && enabled ? 1.03 : 1)
        .animation(RinowaMotion.pop, value: pressed)
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in if enabled { pressed = true } }
                .onEnded { value in
                    pressed = false
                    guard enabled,
                          abs(value.translation.width) < 12,
                          abs(value.translation.height) < 12 else { return }
                    action()
                }
        )
        .allowsHitTesting(enabled)
    }
}

/// [PrimaryButton] の中の文字。ボタンが自分のラベルを動かせるように分けてある。
struct PrimaryButtonLabel: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text)
            .rinowaType(RinowaType.label)
            .foregroundStyle(color)
    }
}

/// 主でない操作。塗らない。
struct QuietButton<Label: View>: View {
    var enabled: Bool = true
    var action: () -> Void
    @ViewBuilder var label: (Color) -> Label

    @Environment(\.rinowaColors) private var colors

    var body: some View {
        Button(action: action) {
            label(enabled ? colors.accent : colors.textTertiary)
                .frame(minHeight: RinowaDimens.touchTarget)
                .padding(.horizontal, 12)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

struct QuietButtonLabel: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text).rinowaType(RinowaType.label).foregroundStyle(color)
    }
}

// ------------------------------------------------------------------ 一行の知らせ

/// フォームの下に出る1行。
///
/// 言うことがあるときだけ場所を取り、あらかじめ空けておく余白は作らない。
/// **将来の悪い知らせのために空いた枠は、何かが欠けているように読める。**
struct NoticeBanner: View {
    let text: String?
    var isError: Bool = true
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        Group {
            if let text {
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .fill(isError ? colors.danger : colors.success)
                        .frame(width: 3, height: 18)
                    Text(text)
                        .rinowaType(RinowaType.listPreview)
                        .foregroundStyle(colors.textPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
                .background(
                    (isError ? colors.accentSoft : colors.surfaceSunken),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )
                .transition(.opacity)
            }
        }
        .animation(RinowaMotion.standard(RinowaMotion.durationQuick), value: text)
    }
}

// ------------------------------------------------------------------ 画面の頭

/// 戻ると題名。
///
/// 8つの画面が同じ行を1つずつ持っていて、矢印の描画も8回書いてあった。
/// 位置が数ポイントずれていても誰も気づかないまま増えるので、1つにまとめる。
///
/// 触覚はここで鳴らす。**戻るは全画面で同じ手応えでなければならない。**
struct ScreenHeader<Trailing: View>: View {

    let title: String
    let onBack: () -> Void
    @ViewBuilder var trailing: () -> Trailing

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    var body: some View {
        HStack(spacing: 0) {
            Button {
                haptics.fire(.navigation)
                onBack()
            } label: {
                BackArrow()
                    // 見た目は 20 だが、押せるのは触れる大きさぶん。
                    // 左上は親指から遠いので、的を小さくしない。
                    .frame(width: RinowaDimens.touchTarget, height: RinowaDimens.touchTarget)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Text(title)
                .rinowaType(RinowaType.screenTitle)
                .foregroundStyle(colors.textPrimary)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)

            trailing()
        }
        .padding(.leading, 6)
        .padding(.trailing, RinowaDimens.screenPadding)
        .padding(.vertical, 6)
    }
}

extension ScreenHeader where Trailing == EmptyView {
    init(title: String, onBack: @escaping () -> Void) {
        self.init(title: title, onBack: onBack, trailing: { EmptyView() })
    }
}

private struct BackArrow: View {
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        Canvas { context, size in
            let w = size.width, h = size.height
            var path = Path()
            path.move(to: CGPoint(x: w * 0.62, y: h * 0.22))
            path.addLine(to: CGPoint(x: w * 0.34, y: h * 0.5))
            path.addLine(to: CGPoint(x: w * 0.62, y: h * 0.78))
            context.stroke(
                path,
                with: .color(colors.textPrimary),
                style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
            )
        }
        .frame(width: 20, height: 20)
    }
}

// ------------------------------------------------------------------ 招待コード

/// 招待コード。
///
/// `UserRepository` の同じ定数と同じ整え方。
///
/// 検索欄ではなくコードにしている理由: メールアドレスや電話番号を打って、
/// その人が使っているかどうかが見えるメッセンジャーは、構造として
/// **「この人はここにいますか」に誰にでも答えるサービス**を作っている。
/// その答えは、答えることに同意していない人についてのもの。
///
/// 本人が渡すと決めたコードなら逆になる。持ち主が明かすまで、何も見つからない。
enum InviteCode {
    /// 紛らわしい文字（I, O, 0, 1）を外してある。口で伝えても間違えないように。
    static let alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    static let length = 8

    static func normalise(_ code: String) -> String {
        code.uppercased().filter { alphabet.contains($0) }
    }

    /// `ABCD-EFGH`。読みやすさのための区切りで、この形では保存しない。
    static func format(_ code: String) -> String {
        guard code.count == length else { return code }
        let i = code.index(code.startIndex, offsetBy: 4)
        return code[code.startIndex..<i] + "-" + code[i...]
    }
}

// ------------------------------------------------------------------ 色を混ぜる

extension Color {
    /// Compose の `lerp` と同じ。無効なボタンの塗りに要る。
    func mixed(with other: Color, amount: CGFloat) -> Color {
        let a = UIColor(self), b = UIColor(other)
        var ar: CGFloat = 0, ag: CGFloat = 0, ab: CGFloat = 0, aa: CGFloat = 0
        var br: CGFloat = 0, bg: CGFloat = 0, bb: CGFloat = 0, ba: CGFloat = 0
        a.getRed(&ar, green: &ag, blue: &ab, alpha: &aa)
        b.getRed(&br, green: &bg, blue: &bb, alpha: &ba)
        let t = min(max(amount, 0), 1)
        return Color(
            red: Double(ar + (br - ar) * t),
            green: Double(ag + (bg - ag) * t),
            blue: Double(ab + (bb - ab) * t),
            opacity: Double(aa + (ba - aa) * t)
        )
    }
}
