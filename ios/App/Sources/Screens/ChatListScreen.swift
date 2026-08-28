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

    @State private var query: String = ""

    private var visible: [Conversation] {
        let sorted = store.conversations.sorted { $0.lastActivityMs > $1.lastActivityMs }
        guard !query.isEmpty else { return sorted }
        return sorted.filter { $0.title.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()

            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(visible) { conversation in
                        NavigationLink(value: conversation.id) {
                            ChatListRow(conversation: conversation)
                        }
                        .buttonStyle(.plain)
                        .simultaneousGesture(TapGesture().onEnded {
                            haptics.fire(.navigation)
                        })

                        if conversation.id != visible.last?.id {
                            Divider()
                                .overlay(colors.outlineSoft)
                                .padding(.leading, RinowaDimens.screenPadding
                                         + RinowaDimens.avatarSize + RinowaDimens.gap)
                        }
                    }
                }
            }
            .scrollDismissesKeyboard(.immediately)

            if visible.isEmpty {
                emptyState
            }
        }
        .navigationTitle("Rinowa")
        .navigationBarTitleDisplayMode(.large)
        .searchable(text: $query, prompt: "会話を探す")
        .navigationDestination(for: String.self) { id in
            if let conversation = store.conversation(id: id) {
                ChatScreen(conversationId: conversation.id)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: RinowaDimens.gapSmall) {
            Text(query.isEmpty ? "まだ会話がありません" : "見つかりません")
                .rinowaType(RinowaType.listName)
                .foregroundStyle(colors.textSecondary)
            if query.isEmpty {
                Text("右上から始められます")
                    .rinowaType(RinowaType.listPreview)
                    .foregroundStyle(colors.textTertiary)
            }
        }
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
