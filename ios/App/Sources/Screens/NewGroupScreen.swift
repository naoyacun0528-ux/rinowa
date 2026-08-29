import SwiftUI

/// グループを作る。
///
/// `ui/chatlist/NewGroupScreen.kt` の Swift 側。文言はそのまま写した。
///
/// 候補に出るのは、このアカウントがすでに会話したことのある人。
/// **先に追加する友達一覧は別に無い。** Rinowa では、話したことがあることが
/// 知っていること。一覧に居ない人へは招待コードで1回届けば、次からは居る。
struct NewGroupScreen: View {

    let onBack: () -> Void
    var onCreated: (String) -> Void = { _ in }

    @EnvironmentObject private var store: ConversationStore
    @Environment(\.rinowaColors) private var colors
    @Environment(\.haptics) private var haptics

    @State private var title = ""
    @State private var busy = false
    @State private var notice: String?
    @State private var selected: Set<String> = []

    private var contacts: [Contact] { store.contacts }

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "グループを作る", onBack: onBack)

            VStack(alignment: .leading, spacing: 0) {
                RinowaField(
                    value: Binding(
                        get: { title },
                        set: { title = String($0.prefix(60)) }
                    ),
                    placeholder: "グループ名（例: 家族）",
                    enabled: !busy
                )
                Spacer().frame(height: 16)
                Text(selected.isEmpty ? "追加する人を選んでください" : "\(selected.count)人を追加します")
                    .rinowaType(RinowaType.label)
                    .foregroundStyle(colors.textSecondary)
                Spacer().frame(height: 8)
            }
            .padding(.horizontal, 24)

            if contacts.isEmpty {
                empty
            } else {
                list
            }

            VStack(spacing: 0) {
                if notice != nil { Spacer().frame(height: 12) }
                NoticeBanner(text: notice)
                Spacer().frame(height: 12)
                PrimaryButton(
                    enabled: !busy && !selected.isEmpty
                        && !title.trimmingCharacters(in: .whitespaces).isEmpty,
                    action: create
                ) { tint in
                    PrimaryButtonLabel(text: busy ? "作成しています" : "グループを作る", color: tint)
                }
                Spacer().frame(height: 20)
            }
            .padding(.horizontal, 24)
        }
        .background(colors.background.ignoresSafeArea())
        .navigationBarBackButtonHidden()
        .toolbar(.hidden, for: .navigationBar)
    }

    private var empty: some View {
        VStack(spacing: 8) {
            Text("まだ誰とも話していません")
                .rinowaType(RinowaType.listName)
                .foregroundStyle(colors.textPrimary)
            Text("先に招待コードで1対1の会話をはじめると、その相手をグループに入れられます。")
                .rinowaType(RinowaType.listPreview)
                .foregroundStyle(colors.textSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var list: some View {
        ScrollView {
            LazyVStack(spacing: RinowaDimens.glassCardGap) {
                ForEach(contacts) { contact in
                    let isSelected = selected.contains(contact.id)
                    HStack(spacing: 12) {
                        Avatar(title: contact.displayName, seed: contact.seed)
                        Text(contact.displayName)
                            .rinowaType(RinowaType.listName)
                            .foregroundStyle(colors.textPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        SelectionMark(selected: isSelected)
                    }
                    .padding(RinowaDimens.rowPadding)
                    .frame(maxWidth: .infinity)
                    .glassFace()
                    .padding(.horizontal, RinowaDimens.glassCardMargin)
                    .contentShape(Rectangle())
                    .onTapGesture {
                        haptics.fire(.selection)
                        if isSelected { selected.remove(contact.id) }
                        else { selected.insert(contact.id) }
                    }
                }
            }
            .padding(.vertical, RinowaDimens.glassCardGap)
        }
        .frame(maxHeight: .infinity)
    }

    private func create() {
        guard !busy else { return }
        busy = true
        notice = nil
        // 作る仕組みは iOS にまだ無い。失敗と同じ形で返す。
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            busy = false
            haptics.fire(.error)
            notice = "作成できませんでした。通信を確認してください。"
        }
    }
}

/// 選んだ印。
///
/// 塗りつぶした丸に、白い鉤。**枠だけのチェックボックスにしない。**
/// 遠目に「入っているか」が読めるのは、線ではなく面。
private struct SelectionMark: View {
    let selected: Bool
    @Environment(\.rinowaColors) private var colors

    var body: some View {
        ZStack {
            Circle().fill(selected ? colors.accent : colors.surfaceSunken)
            if selected {
                Canvas { context, size in
                    let w = size.width, h = size.height
                    var path = Path()
                    path.move(to: CGPoint(x: w * 0.16, y: h * 0.54))
                    path.addLine(to: CGPoint(x: w * 0.42, y: h * 0.78))
                    path.addLine(to: CGPoint(x: w * 0.84, y: h * 0.24))
                    context.stroke(
                        path,
                        with: .color(colors.onAccent),
                        style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
                    )
                }
                .frame(width: 14, height: 14)
            }
        }
        .frame(width: 24, height: 24)
        .animation(RinowaMotion.pop, value: selected)
    }
}
