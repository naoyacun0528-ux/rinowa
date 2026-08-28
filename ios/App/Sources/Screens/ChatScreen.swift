import SwiftUI
import UIKit
import RinowaCore

/// 会話の中身。
///
/// `ui/chat/ChatScreen.kt` の Swift 側。Android では 1,177行あり、
/// 写真の閲覧・動画の再生・通話の呼び出しまで抱えている。ここは**まず骨**。
/// 端末の SDK が要る部分は、それぞれ別の日に足す。
struct ChatScreen: View {

    let conversationId: String

    @EnvironmentObject private var store: ConversationStore
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var draft: String = ""
    @State private var replyingTo: ChatMessage?
    @State private var viewing: IdentifiedImage?
    @StateObject private var call = CallController()

    private var conversation: Conversation? { store.conversation(id: conversationId) }

    var body: some View {
        ZStack {
            colors.background.ignoresSafeArea()

            VStack(spacing: 0) {
                thread
                Composer(
                    draft: $draft,
                    replyingTo: $replyingTo,
                    onSend: send
                )
            }

            // **通話は画面を奪わない。** 上に重ねるだけなので、話しながら会話も読める。
            if call.state != .idle {
                CallOverlay(call: call)
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
        .animation(RinowaMotion.settle, value: call.state)
        .fullScreenCover(item: $viewing) { image in
            PhotoViewer(image: image.value) { viewing = nil }
        }
        .navigationTitle(conversation?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    haptics.fire(.navigation)
                    call.dial(to: conversation?.title ?? "", video: false)
                } label: {
                    Image(systemName: "phone")
                }
                Button {
                    haptics.fire(.navigation)
                    call.dial(to: conversation?.title ?? "", video: true)
                } label: {
                    Image(systemName: "video")
                }
                NavigationLink {
                    SafetyScreen(title: conversation?.title ?? "")
                } label: {
                    Image(systemName: "lock.shield")
                }
            }
        }
        .onAppear {
            // **開いたら未読が消える。** 通知も一緒に。
            store.markRead(conversationId)
        }
    }

    private var thread: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(rows, id: \.id) { row in
                        switch row.kind {
                        case .separator(let label):
                            DaySeparator(label: label)
                                .padding(.vertical, RinowaDimens.gapSmall)
                        case .message(let message, let showSender):
                            MessageRow(
                                message: message,
                                showSender: showSender,
                                onReply: { replyingTo = $0 },
                                onReact: { index in
                                    store.toggleReaction(index, on: message.id, in: conversationId)
                                },
                                onOpenPhoto: { viewing = IdentifiedImage(value: $0) }
                            )
                            .id(message.id)
                        }
                    }
                }
                .padding(.vertical, RinowaDimens.gap)
            }
            .scrollDismissesKeyboard(.interactively)
            .onAppear {
                if let last = conversation?.messages.last {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
            .onChange(of: conversation?.messages.count ?? 0) { _ in
                guard let last = conversation?.messages.last else { return }
                withAnimation(RinowaMotion.settle) {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        // **メッセージが端末を出た。** 短く、鋭く、尾を引かない。
        haptics.fire(.send)
        store.send(text, to: conversationId)
        draft = ""
        replyingTo = nil
    }

    // ---------------------------------------------------------------- 並べ方

    private struct Row {
        let id: String
        let kind: Kind
        enum Kind {
            case separator(String)
            case message(ChatMessage, showSender: Bool)
        }
    }

    /// 日付が変わる位置に区切りを挟み、同じ人の連投では名前を省く。
    private var rows: [Row] {
        guard let conversation else { return [] }
        var out: [Row] = []
        var previous: ChatMessage?

        for message in conversation.messages {
            if previous == nil || !RinowaFormat.isSameDay(previous!.timestampMs, message.timestampMs) {
                out.append(Row(id: "sep-\(message.id)",
                               kind: .separator(RinowaFormat.daySeparator(message.timestampMs))))
            }
            // 名前を出すのは、グループで、相手のもので、直前と送り主が違うときだけ。
            let showSender = conversation.isGroup
                && !message.isMine
                && previous?.senderId != message.senderId
            out.append(Row(id: message.id, kind: .message(message, showSender: showSender)))
            previous = message
        }
        return out
    }
}

private struct DaySeparator: View {
    let label: String
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        Text(label)
            .rinowaType(RinowaType.labelSmall)
            .foregroundStyle(colors.textTertiary)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(colors.surfaceSunken))
    }
}
