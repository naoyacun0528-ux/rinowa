import SwiftUI
import RinowaCore

/// 入力欄。
///
/// `ui/chat/Composer.kt` の Swift 側。
///
/// 送信の丸は、**書いてあるときだけ**アクセント色になる。押せないものが
/// 押せそうに見えるのが一番よくないので、色そのものが状態を運ぶ。
struct Composer: View {

    @Binding var draft: String
    @Binding var replyingTo: ChatMessage?
    let onSend: () -> Void
    var stickersOpen: Bool = false
    var onSticker: (String) -> Void = { _ in }

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics
    @FocusState private var focused: Bool

    @State private var drawerOpen: Bool

    init(
        draft: Binding<String>,
        replyingTo: Binding<ChatMessage?>,
        onSend: @escaping () -> Void,
        stickersOpen: Bool = false,
        onSticker: @escaping (String) -> Void = { _ in }
    ) {
        _draft = draft
        _replyingTo = replyingTo
        self.onSend = onSend
        self.stickersOpen = stickersOpen
        self.onSticker = onSticker
        _drawerOpen = State(initialValue: stickersOpen)
    }


    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(spacing: 0) {
            if let reply = replyingTo {
                replyBanner(reply)
            }

            HStack(alignment: .bottom, spacing: RinowaDimens.gapSmall) {
                attachButton

                TextField("メッセージ", text: $draft, axis: .vertical)
                    .rinowaType(RinowaType.composer)
                    .foregroundStyle(colors.textPrimary)
                    .focused($focused)
                    .lineLimit(1...6)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .fill(colors.surfaceSunken)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .strokeBorder(colors.outlineSoft, lineWidth: 1)
                    )

                sendButton
            }
            .padding(.horizontal, RinowaDimens.gap)
            .padding(.vertical, RinowaDimens.gapSmall)

            if drawerOpen {
                StickerPanel { id in
                    onSticker(id)
                    withAnimation(RinowaMotion.settle) { drawerOpen = false }
                }
                .transition(.move(edge: .bottom))
            }
        }
        .background(.regularMaterial)
        .overlay(alignment: .top) {
            Rectangle().fill(colors.outlineSoft).frame(height: 0.5)
        }
    }

    private func replyBanner(_ reply: ChatMessage) -> some View {
        HStack(spacing: RinowaDimens.gapSmall) {
            Rectangle().fill(colors.accent).frame(width: 2, height: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(reply.senderName)
                    .rinowaType(RinowaType.labelSmall)
                    .foregroundStyle(colors.accent)
                Text(reply.preview)
                    .rinowaType(RinowaType.quotedBody)
                    .foregroundStyle(colors.textSecondary)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                haptics.fire(.softConfirm)
                withAnimation(RinowaMotion.exit()) { replyingTo = nil }
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(colors.textTertiary)
                    .frame(width: RinowaDimens.touchTarget, height: 30)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, RinowaDimens.gap)
        .padding(.top, RinowaDimens.gapSmall)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    private var attachButton: some View {
        Button {
            haptics.fire(.selection)
            focused = false
            withAnimation(RinowaMotion.settle) { drawerOpen.toggle() }
        } label: {
            // 開いているあいだは色が変わる。何が出ているかを、
            // 引き出しを見なくてもボタン側で分かるように。
            PlusMark(open: drawerOpen, tint: drawerOpen ? colors.accent : colors.textSecondary)
                .frame(width: RinowaDimens.touchTarget, height: RinowaDimens.touchTarget)
        }
        .buttonStyle(.plain)
    }

    private var sendButton: some View {
        Button(action: onSend) {
            Image(systemName: "arrow.up")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(canSend ? colors.onAccent : colors.textTertiary)
                .frame(width: 36, height: 36)
                .background(
                    Circle().fill(canSend ? colors.accent : colors.surfaceSunken)
                )
        }
        .buttonStyle(.plain)
        .disabled(!canSend)
        .animation(RinowaMotion.standard(RinowaMotion.durationQuick), value: canSend)
        .padding(.bottom, 2)
    }
}
