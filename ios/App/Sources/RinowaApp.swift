import SwiftUI
import RinowaCore

/// iOS 版の入口。
///
/// 明暗は端末の設定に従う。**Android と同じ2つの組**を持っていて、
/// 値は `RinowaColors.kt` から1つも変えていない。
@main
struct RinowaApp: App {

    @StateObject private var store = ConversationStore()
    @StateObject private var haptics = HapticEngine()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environment(\.haptics, haptics)
        }
    }
}

struct RootView: View {

    @EnvironmentObject private var store: ConversationStore
    @Environment(\.colorScheme) private var scheme

    private var colors: RinowaColors {
        scheme == .dark ? .dark : .light
    }

    var body: some View {
        Group {
            if store.signedIn {
                NavigationStack {
                    ChatListScreen()
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                NavigationLink {
                                    ProfileScreen()
                                } label: {
                                    Image(systemName: "person.crop.circle")
                                }
                            }
                        }
                }
            } else {
                SignInScreen()
            }
        }
        .environment(\.rinowaColors, colors)
        .tint(colors.accent)
    }
}
