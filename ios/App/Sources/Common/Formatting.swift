import Foundation

/// 時刻の書き方。
///
/// `ui/common/Formatting.kt` の Swift 側。**1文字も変えない。**
/// 一覧に「3分」と出る端末と「3分前」と出る端末があってはいけない。
enum RinowaFormat {

    private static let minute: Int64 = 60_000
    private static let hour: Int64 = 60 * minute

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ja_JP")
        f.dateFormat = "H:mm"
        return f
    }()

    private static let shortDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ja_JP")
        f.dateFormat = "M/d"
        return f
    }()

    private static let daySeparator: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ja_JP")
        f.dateFormat = "M月d日(E)"
        return f
    }()

    private static func date(_ ms: Int64) -> Date {
        Date(timeIntervalSince1970: Double(ms) / 1000)
    }

    /// 吹き出しの下に出す時刻。
    static func clockText(_ ms: Int64) -> String { clock.string(from: date(ms)) }

    /// 長さを 0:07、1:23、12:04 と書く。時刻ではなく経過時間。
    ///
    /// **00:07 とは書かない。** 実際より長く見える。
    static func duration(ms: Int64) -> String { seconds(max(ms / 1000, 0)) }

    /// 1時間を超えたら 1:02:30。通話は長くなりうる。
    static func callDuration(seconds s: Int) -> String { seconds(Int64(max(s, 0))) }

    private static func seconds(_ total: Int64) -> String {
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        // 秒は必ず2桁。**3:7 は読み違える。**
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s)
                     : String(format: "%d:%02d", m, s)
    }

    /// 会話一覧用の、短い相対時刻。
    static func listTime(_ ms: Int64, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        let delta = now - ms
        if delta < minute { return "今" }
        if delta < hour { return "\(delta / minute)分" }
        if isSameDay(ms, now) { return clockText(ms) }
        if isYesterday(ms, now) { return "昨日" }
        return shortDate.string(from: date(ms))
    }

    /// スレッドの中で日付が変わる位置に置く区切り。
    static func daySeparator(_ ms: Int64, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> String {
        if isSameDay(ms, now) { return "今日" }
        if isYesterday(ms, now) { return "昨日" }
        return daySeparator.string(from: date(ms))
    }

    static func isSameDay(_ a: Int64, _ b: Int64) -> Bool {
        var cal = Calendar(identifier: .gregorian)
        cal.locale = Locale(identifier: "ja_JP")
        return cal.isDate(date(a), inSameDayAs: date(b))
    }

    private static func isYesterday(_ ms: Int64, _ now: Int64) -> Bool {
        var cal = Calendar(identifier: .gregorian)
        cal.locale = Locale(identifier: "ja_JP")
        guard let yesterday = cal.date(byAdding: .day, value: -1, to: date(now)) else { return false }
        return cal.isDate(date(ms), inSameDayAs: yesterday)
    }
}
