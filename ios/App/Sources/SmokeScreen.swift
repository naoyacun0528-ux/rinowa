import SwiftUI
import RinowaCore

/// 動いていることを、画面から見て確かめるためだけの1枚。
///
/// スクリーンショットに写るので、CI の結果を人が見て判断できる。
/// 「ビルドが通った」だけでは、**中身が動いているかは分からない**。
struct SmokeScreen: View {

    /// 固定のフレームを、その場で読み解く。
    /// ここが緑なら、Yosegi が iOS の上で本当に動いている。
    private var decoded: Result<[YosegiMessage], Error> {
        let context = YosegiContext(
            conversationId: "aB3dEf6hIj9lMn2pQr5t",
            memberIds: ["K1mN4pQ7rS0tU3vW6xY9zA2bC5dE"],
            stickerCatalogue: []
        )
        return Result {
            let frame = try Yosegi.encode([
                YosegiMessage(
                    id: "Msg0000000000000001A",
                    senderId: "K1mN4pQ7rS0tU3vW6xY9zA2bC5dE",
                    timestampMs: 1_755_390_600_000,
                    status: .sent,
                    text: "おはよう"
                )
            ], context: context)
            return try Yosegi.decode(frame, context: context)
        }
    }

    var body: some View {
        ZStack {
            Color(red: 0.027, green: 0.031, blue: 0.047).ignoresSafeArea()

            VStack(spacing: 28) {
                VStack(spacing: 6) {
                    Text("RINOWA")
                        .font(.system(size: 12, weight: .bold))
                        .tracking(6)
                        .foregroundStyle(Color(red: 0.71, green: 0.83, blue: 0.95))
                    Text("iOS")
                        .font(.system(size: 44, weight: .heavy))
                        .foregroundStyle(.white)
                }

                VStack(alignment: .leading, spacing: 14) {
                    row("Yosegi", yosegiStatus)
                    row("触覚の語彙", "\(HapticToken.allCases.count) 種")
                    row("同梱スタンプ", "\(BuiltInStickers.entries.count) 個")
                    row("暗号の識別子", CryptoIds.domain)
                    row("本文の扱い", "\(MessageText("秘密の本文"))")
                }
                .padding(22)
                .background(Color(red: 0.067, green: 0.082, blue: 0.110))
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .padding(30)
        }
    }

    private var yosegiStatus: String {
        switch decoded {
        case .success(let messages):
            return messages.first?.text ?? "(本文なし)"
        case .failure(let error):
            return "失敗: \(error)"
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0.66, green: 0.71, blue: 0.77))
            Spacer(minLength: 16)
            Text(value)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
        }
    }
}

