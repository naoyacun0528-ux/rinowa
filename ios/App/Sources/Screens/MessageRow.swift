import SwiftUI
import UIKit
import RinowaCore

/// 1通ぶんの行。
///
/// `ui/chat/MessageRow.kt` の Swift 側。
///
/// 返信スワイプの手触りがここに入っている。**閾値でだけ触覚が鳴る。**
/// 指が動いている間ずっと鳴らすと安っぽくなるし、意味も伝わらない。
struct MessageRow: View {

    let message: ChatMessage
    let showSender: Bool
    let onReply: (ChatMessage) -> Void
    let onReact: (Int) -> Void
    /// 写真を大きく開く。行は開き方を知らない——**開く場所は会話画面が持つ**ので、
    /// 拡大中にスレッドが下へスクロールしても、写真は動かない。
    var onOpenPhoto: (UIImage?) -> Void = { _ in }

    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var drag: CGFloat = 0
    /// 閾値を越えた瞬間だけ鳴らすための記憶。**跨ぐたびに1回。**
    @State private var pastThreshold = false
    @State private var showReactions = false

    private var alignment: HorizontalAlignment { message.isMine ? .trailing : .leading }

    var body: some View {
        HStack(spacing: 0) {
            if message.isMine { Spacer(minLength: 56) }

            VStack(alignment: alignment, spacing: 3) {
                if showSender && !message.isMine {
                    Text(message.senderName)
                        .rinowaType(RinowaType.labelSmall)
                        .foregroundStyle(colors.textTertiary)
                        .padding(.leading, 4)
                }

                bubble

                if !message.reactions.isEmpty {
                    ReactionRow(reactions: message.reactions, onTap: onReact)
                        .padding(.horizontal, 2)
                }

                meta
            }

            if !message.isMine { Spacer(minLength: 56) }
        }
        .padding(.horizontal, RinowaDimens.screenPadding)
        .offset(x: drag)
        .overlay(alignment: message.isMine ? .trailing : .leading) {
            replyHint
        }
        .gesture(swipe)
        .onLongPressGesture(minimumDuration: 0.35) {
            haptics.fire(.threshold)
            showReactions = true
        }
        .sheet(isPresented: $showReactions) {
            // iOS 16.0 で動く形。popover の小型表示は 16.4 からなので使わない。
            // **使える機能のために切り捨てる端末を増やさない。**
            ReactionPicker { index in
                haptics.fire(.reaction)
                onReact(index)
                showReactions = false
            }
            .padding(.vertical, RinowaDimens.gapLarge)
            .presentationDetents([.height(120)])
        }
    }

    // ---------------------------------------------------------------- 吹き出し

    /// **スタンプと写真と動画には吹き出しを付けない。**
    ///
    /// スタンプに枠を付けると「画像を送った」に見える。写真も同じで、
    /// 写真そのものがメッセージであって、枠は場所を奪うだけ。
    private var bare: Bool {
        switch message.content {
        case .sticker, .image, .video: return true
        default: return false
        }
    }

    @ViewBuilder
    private var bubble: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let quote = message.replyTo {
                QuoteBlock(quote: quote, mine: message.isMine)
            }
            content
        }
        .padding(.horizontal, bare ? 0 : 13)
        .padding(.vertical, bare ? 0 : 9)
        .background {
            if !bare {
                RoundedRectangle(cornerRadius: RinowaDimens.bubbleRadius, style: .continuous)
                    .fill(bubbleColor)
                    .overlay(
                        RoundedRectangle(
                            cornerRadius: RinowaDimens.bubbleRadius,
                            style: .continuous
                        )
                        .strokeBorder(message.isMine ? .clear : colors.outlineSoft, lineWidth: 1)
                    )
            }
        }
    }

    private var bubbleColor: Color {
        message.isMine ? colors.bubbleOutgoing : colors.bubbleIncoming
    }

    private var onBubble: Color {
        message.isMine ? colors.onBubbleOutgoing : colors.onBubbleIncoming
    }

    @ViewBuilder
    private var content: some View {
        switch message.content {
        case .text(let body):
            Text(body)
                .rinowaType(RinowaType.messageBody)
                .foregroundStyle(onBubble)
                .textSelection(.enabled)

        case .sticker(let id):
            // 画像は端末が持つ。線の上を通るのは id だけ。
            StickerImage(id: id, size: stickerSize)

        case .image(let width, let height):
            let aspect = CGFloat(width) / CGFloat(max(height, 1))
            Button {
                haptics.fire(.softConfirm)
                onOpenPhoto(message.media?.thumbnail)
            } label: {
                if let thumbnail = message.media?.thumbnail {
                    Image(uiImage: thumbnail)
                        .resizable()
                        .scaledToFill()
                        .aspectRatio(aspect, contentMode: .fit)
                        .frame(maxWidth: 220)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                } else {
                    MediaPlaceholder(aspect: aspect, label: "写真")
                }
            }
            .buttonStyle(.plain)

        case .video(let seconds):
            if let url = message.media?.url {
                InlineVideo(url: url,
                            thumbnail: message.media?.thumbnail,
                            durationMs: Int64(seconds) * 1000,
                            aspect: 16.0 / 9.0)
            } else {
                // **本体がまだ無いなら、再生ボタンは出さない。**
                // 押しても何も起きないボタンは、壊れているのと見分けがつかない。
                MediaPlaceholder(aspect: 16.0 / 9.0,
                                 label: RinowaFormat.callDuration(seconds: seconds))
            }

        case .call(let video, let outcome, let seconds):
            CallNotice(video: video, outcome: outcome, seconds: seconds, tint: onBubble)

        case .locked:
            // **開けられなかったもの。空欄にはしない。**
            // 空だと「送られていない」と受け取られる。
            HStack(spacing: 6) {
                Image(systemName: "lock.fill").font(.system(size: 12))
                Text(MessagePreview.locked.value.replacingOccurrences(of: "🔒 ", with: ""))
                    .rinowaType(RinowaType.messageBody)
            }
            .foregroundStyle(message.isMine ? colors.bubbleOutgoingMeta : colors.textTertiary)

        case .retracted:
            Text(MessagePreview.retracted.value)
                .rinowaType(RinowaType.messageBody)
                .italic()
                .foregroundStyle(message.isMine ? colors.bubbleOutgoingMeta : colors.textTertiary)
        }
    }

    // ---------------------------------------------------------------- 時刻と状態

    private var meta: some View {
        HStack(spacing: 4) {
            Text(RinowaFormat.clockText(message.timestampMs))
            if message.isMine {
                Text(statusMark)
            }
        }
        .rinowaType(RinowaType.messageMeta)
        .foregroundStyle(colors.textTertiary)
        .padding(.horizontal, 4)
    }

    private var statusMark: String {
        switch message.status {
        case .sending: return "送信中"
        case .sent: return "済"
        case .delivered: return "届"
        case .read: return "既読"
        case .failed: return "！"
        }
    }

    // ---------------------------------------------------------------- 返信スワイプ

    private var replyHint: some View {
        Image(systemName: "arrowshape.turn.up.left.fill")
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(colors.accent)
            .opacity(Double(min(abs(drag) / RinowaSwipe.threshold, 1)))
            .scaleEffect(pastThreshold ? 1.0 : 0.85)
            .padding(.horizontal, 6)
            .animation(RinowaMotion.follow, value: pastThreshold)
    }

    private var swipe: some Gesture {
        DragGesture(minimumDistance: RinowaSwipe.startSlop)
            .onChanged { value in
                // 自分のものは左へ、相手のものは右へ。指が向かう先に返信の印が出る。
                let raw = message.isMine ? -value.translation.width : value.translation.width
                guard raw > 0 else { drag = 0; return }

                let resisted = RinowaSwipe.resist(raw)
                drag = message.isMine ? -resisted : resisted

                let crossed = raw >= RinowaSwipe.threshold
                if crossed != pastThreshold {
                    // **跨いだ瞬間だけ鳴らす。** 戻ったときは弱いほうで。
                    haptics.fire(crossed ? .threshold : .thresholdRelease)
                    pastThreshold = crossed
                }
            }
            .onEnded { _ in
                if pastThreshold { onReply(message) }
                pastThreshold = false
                withAnimation(RinowaMotion.follow) { drag = 0 }
            }
    }
}

// ---------------------------------------------------------------- 部品

private struct QuoteBlock: View {
    let quote: ChatMessage.Quote
    let mine: Bool
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        HStack(spacing: 8) {
            Rectangle()
                .fill(mine ? colors.bubbleOutgoingMeta : colors.accent)
                .frame(width: 2)
            VStack(alignment: .leading, spacing: 1) {
                Text(quote.senderName)
                    .rinowaType(RinowaType.labelSmall)
                Text(quote.excerpt)
                    .rinowaType(RinowaType.quotedBody)
                    .lineLimit(2)
            }
            .foregroundStyle(mine ? colors.bubbleOutgoingMeta : colors.textSecondary)
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

private struct MediaPlaceholder: View {
    let aspect: CGFloat
    let label: String
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        // 本体はタップされてから取りに行く。**形は先に分かっている**ので、
        // 届く前から場所を正しく空けられる。
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(colors.surfaceSunken)
            .aspectRatio(aspect, contentMode: .fit)
            .frame(maxWidth: 220)
            .overlay(
                Text(label)
                    .rinowaType(RinowaType.labelSmall)
                    .foregroundStyle(colors.textTertiary)
            )
    }
}

private struct CallNotice: View {
    let video: Bool
    let outcome: CallOutcome
    let seconds: Int
    let tint: Color

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: video ? "video.fill" : "phone.fill")
                .font(.system(size: 13))
            VStack(alignment: .leading, spacing: 1) {
                Text(video ? "ビデオ通話" : "音声通話")
                    .rinowaType(RinowaType.messageBody)
                Text(detail)
                    .rinowaType(RinowaType.messageMeta)
                    .opacity(0.75)
            }
        }
        .foregroundStyle(tint)
    }

    /// 経路が見つからなかったのか、話す前に切れたのかは、当事者には同じ
    /// 「通話できなかった」。わざと粗い。
    private var detail: String {
        switch outcome {
        case .completed: return RinowaFormat.callDuration(seconds: seconds)
        case .missed: return "応答なし"
        case .declined: return "断りました"
        case .failed: return "つながりませんでした"
        }
    }
}

private struct ReactionRow: View {
    let reactions: [Reaction]
    let onTap: (Int) -> Void
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        HStack(spacing: 4) {
            ForEach(reactions, id: \.paletteIndex) { reaction in
                Button {
                    onTap(reaction.paletteIndex)
                } label: {
                    HStack(spacing: 3) {
                        Text(reaction.emoji).font(.system(size: 12))
                        if reaction.count > 1 {
                            Text("\(reaction.count)")
                                .rinowaType(RinowaType.labelSmall)
                                .foregroundStyle(colors.textSecondary)
                        }
                    }
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(
                        Capsule().fill(reaction.mine ? colors.accentSoft : colors.surfaceSunken)
                    )
                    .overlay(
                        Capsule().strokeBorder(reaction.mine ? colors.accent : .clear, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// スタンプの大きさ。Android と同じ 148。**吹き出しより大きい。**
/// スタンプは文章の飾りではなく、それ自体が1回の発言なので。
private let stickerSize: CGFloat = 148

struct ReactionPicker: View {
    let onPick: (Int) -> Void
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Array(ReactionPalette.emoji.enumerated()), id: \.offset) { index, emoji in
                Button {
                    onPick(index)
                } label: {
                    Text(emoji)
                        .font(.system(size: 26))
                        .frame(width: RinowaDimens.touchTarget, height: RinowaDimens.touchTarget)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(colors.surfaceRaised)
    }
}
