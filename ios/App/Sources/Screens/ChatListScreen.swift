import SwiftUI
import RinowaCore

/// 会話の一覧。
///
/// `ui/chatlist/ChatListScreen.kt` の Swift 側。
///
/// ここに出るプレビューについて1つ。**サーバーに入っているのは「🔒 メッセージ」**で、
/// 本文ではない。画面に本文が出るのは、端末が最新の1通を復号し直して差し替えて
/// いるから。**サーバーには本文が無いまま、画面には出る。**
struct ChatListScreen: View {

    @EnvironmentObject private var store: ConversationStore
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var composeOpen: Bool

    /// 既定は閉じている。開いた状態を撮るときだけ true を渡す。
    init(composeOpen: Bool = false) {
        _composeOpen = State(initialValue: composeOpen)
    }

    private var visible: [Conversation] {
        store.conversations.sorted { $0.lastActivityMs > $1.lastActivityMs }
    }

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()

            // **1枚ずつ浮いたカード。区切り線で仕切った並びではない。**
            // Android と同じで、余白も角丸も影も同じ値を使う。
            ScrollView {
                LazyVStack(spacing: RinowaDimens.glassCardGap) {
                    ForEach(visible) { conversation in
                        NavigationLink(value: conversation.id) {
                            GlassSurface {
                                ChatListRow(conversation: conversation)
                            }
                        }
                        .buttonStyle(.plain)
                        .simultaneousGesture(TapGesture().onEnded {
                            haptics.fire(.navigation)
                        })
                        .padding(.horizontal, RinowaDimens.glassCardMargin)
                    }
                }
                .padding(.vertical, RinowaDimens.glassCardGap)
            }
            .scrollDismissesKeyboard(.immediately)

            if visible.isEmpty {
                emptyState
            }

            ComposeMenu(open: $composeOpen)
                .padding(RinowaDimens.gapLarge)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        }
        .navigationTitle("Rinowa")
        .navigationBarTitleDisplayMode(.large)
        .navigationDestination(for: String.self) { id in
            if let conversation = store.conversation(id: id) {
                ChatScreen(conversationId: conversation.id)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: RinowaDimens.gapSmall) {
            Text("まだ会話がありません")
                .rinowaType(RinowaType.listName)
                .foregroundStyle(colors.textSecondary)
            Text("右下の＋から始められます")
                .rinowaType(RinowaType.listPreview)
                .foregroundStyle(colors.textTertiary)
        }
    }
}

/// ＋を押すと、行き先が2つ出る。
///
/// **「どちらでしたか」と尋ねる1画面にしない。** 人を追加するのとグループを作るのは、
/// ボタンを押す*前*に決まっている別々の意図なので、選択は押した瞬間にある。
/// 1段あとの、読んでみて初めて場所違いと分かる画面ではない。
///
/// ＋は開くときに閉じる印へ変わる。新しい操作が上に現れるのではなく、
/// **同じ操作のもう一方の状態**。
private struct ComposeMenu: View {

    @Binding var open: Bool
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    var body: some View {
        VStack(alignment: .trailing, spacing: RinowaDimens.gapSmall) {
            if open {
                item("友達を追加")
                item("グループを作る")
                    .padding(.bottom, RinowaDimens.gapTiny)
            }

            GlassSurface(shape: Circle(), tone: .control) {
                haptics.fire(.softConfirm)
                open.toggle()
            } content: {
                PlusMark(open: open, tint: colors.accent)
                    .frame(width: 56, height: 56)
            }
        }
        .animation(RinowaMotion.pop, value: open)
    }

    private func item(_ label: String) -> some View {
        Button {
            haptics.fire(.navigation)
            withAnimation(RinowaMotion.settle) { open = false }
        } label: {
            Text(label)
                .rinowaType(RinowaType.label)
                .foregroundStyle(colors.textPrimary)
                .padding(.horizontal, RinowaDimens.gap)
                .frame(height: RinowaDimens.touchTarget)
                .glassFace(
                    shape: RoundedRectangle(cornerRadius: 14, style: .continuous),
                    elevation: 3
                )
        }
        .buttonStyle(.plain)
        // 上へ伸びて出る。横から滑らせると、別の場所から来たものに見える。
        .transition(.opacity.combined(with: .scale(scale: 0.9, anchor: .bottom)))
    }
}

private struct ChatListRow: View {

    let conversation: Conversation
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        HStack(alignment: .top, spacing: RinowaDimens.gap) {
            Avatar(title: conversation.title, seed: conversation.seed)

            VStack(alignment: .leading, spacing: 3) {
                HStack(alignment: .firstTextBaseline, spacing: RinowaDimens.gapSmall) {
                    Text(conversation.title)
                        .rinowaType(RinowaType.listName)
                        .foregroundStyle(colors.textPrimary)
                        .lineLimit(1)

                    Spacer(minLength: 4)

                    Text(RinowaFormat.listTime(conversation.lastActivityMs))
                        .rinowaType(RinowaType.labelSmall)
                        .foregroundStyle(conversation.unreadCount > 0
                                         ? colors.accent : colors.textTertiary)
                        .layoutPriority(1)
                }

                HStack(alignment: .top, spacing: RinowaDimens.gapSmall) {
                    Text(previewText)
                        .rinowaType(RinowaType.listPreview)
                        .foregroundStyle(conversation.unreadCount > 0
                                         ? colors.textPrimary : colors.textSecondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    if conversation.unreadCount > 0 {
                        UnreadBadge(count: conversation.unreadCount)
                    }
                }
            }
        }
        .padding(.horizontal, RinowaDimens.screenPadding)
        .padding(.vertical, RinowaDimens.rowPadding)
        .contentShape(Rectangle())
    }

    /// グループでは誰が言ったかが要る。1対1では要らない（相手は1人しかいない）。
    private var previewText: String {
        guard let last = conversation.lastMessage else { return "" }
        if last.isMine { return "自分: " + last.preview }
        if conversation.isGroup { return last.senderName + ": " + last.preview }
        return last.preview
    }
}

/// 未読の数。
///
/// **アクセント色を使う数少ない場所。** 指が触れていないのに出る数少ないものでもある。
private struct UnreadBadge: View {
    let count: Int
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        Text(count > 99 ? "99+" : "\(count)")
            .rinowaType(RinowaType.labelSmall)
            .foregroundStyle(colors.onAccent)
            .padding(.horizontal, 6)
            .frame(minWidth: 20, minHeight: 20)
            .background(colors.accent, in: Capsule())
    }
}
