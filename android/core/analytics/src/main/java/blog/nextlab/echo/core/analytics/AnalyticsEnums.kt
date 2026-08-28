package blog.nextlab.echo.core.analytics

/**
 * 数値以外の値が計測に届く唯一の道。
 *
 * この interface は **sealed** なので、実装できるのはこのモジュールの中だけ。
 * アプリ側は、線の上の名前がたとえばメッセージの本文であるような「enum」を
 * 作れない。任意の文字列から計測の項目へ至る経路が存在しない。
 *
 * docs/PRIVACY_PRINCIPLES.md の「構造で守る」。
 */
sealed interface AnalyticsEnum {
    val wireName: String
}

enum class ConversationType(override val wireName: String) : AnalyticsEnum {
    Direct("direct"),
    Group("group"),
}

enum class AttachmentType(override val wireName: String) : AnalyticsEnum {
    None("none"),
    Image("image"),
    Video("video"),
    File("file"),
}

enum class SendFailureReason(override val wireName: String) : AnalyticsEnum {
    Network("network"),
    Auth("auth"),
    RateLimit("rate_limit"),
    Server("server"),
    Unknown("unknown"),
}

enum class MessageContentKind(override val wireName: String) : AnalyticsEnum {
    Text("text"),
    Sticker("sticker"),
}

/**
 * どの種類のスタンプが使われたか。
 *
 * わざと無いものに注意。スタンプの id を運ぶ項目はどこにも無い。自作スタンプの id は
 * 誰かが作った素材を指すので、報告すると、誰が何を作って誰に送ったかを計測側で
 * 復元できてしまう。機能を良くするには種類と回数で足りる。
 */
enum class StickerKind(override val wireName: String) : AnalyticsEnum {
    BuiltIn("built_in"),
    Custom("custom"),
    Group("group"),
}

enum class ScreenId(override val wireName: String) : AnalyticsEnum {
    ChatList("chat_list"),
    Chat("chat"),
    Settings("settings"),
    HapticLab("haptic_lab"),
}

enum class HapticTierId(override val wireName: String) : AnalyticsEnum {
    Envelope("t4_envelope"),
    PrimitiveRich("t3_primitive_rich"),
    Primitive("t2_primitive"),
    Predefined("t1_predefined"),
    Waveform("t0_waveform"),
    Legacy("t_minus_1_legacy"),
    None("none"),
}

enum class HapticIntensityId(override val wireName: String) : AnalyticsEnum {
    Off("off"),
    Subtle("subtle"),
    Normal("normal"),
    Strong("strong"),
}

enum class ThemeMode(override val wireName: String) : AnalyticsEnum {
    Light("light"),
    Dark("dark"),
    System("system"),
}

enum class DeviceCategory(override val wireName: String) : AnalyticsEnum {
    PhoneSmall("phone_small"),
    PhoneLarge("phone_large"),
    Foldable("foldable"),
    Tablet("tablet"),
}

enum class FeedbackCategory(override val wireName: String) : AnalyticsEnum {
    Bug("bug"),
    Feature("feature"),
    Ui("ui"),
    Haptic("haptic"),
    Other("other"),
}

enum class VoteDirection(override val wireName: String) : AnalyticsEnum {
    Up("up"),
    Down("down"),
    Unvote("unvote"),
}

/**
 * 項目の値。
 *
 * `Text` の場合をわざと作っていない。1つ足すだけで、このモジュールのプライバシーの
 * 保証が崩れる。絶対に足さないこと。
 */
sealed class AnalyticsValue {
    data class Num(val value: Long) : AnalyticsValue()
    data class Real(val value: Double) : AnalyticsValue()
    data class Flag(val value: Boolean) : AnalyticsValue()
    data class Choice(val value: AnalyticsEnum) : AnalyticsValue()
}

internal fun Int.param() = AnalyticsValue.Num(toLong())
internal fun Long.param() = AnalyticsValue.Num(this)
internal fun Double.param() = AnalyticsValue.Real(this)
internal fun Boolean.param() = AnalyticsValue.Flag(this)
internal fun AnalyticsEnum.param() = AnalyticsValue.Choice(this)

/**
 * メッセージの長さは500文字までは正確に、それより上はまとめて報告する。
 *
 * 4,823文字という正確な長さは指紋になるが、「5000以上」はならない。
 */
internal fun bucketCharacterCount(count: Int): Int = when {
    count < 500 -> count
    count < 1_000 -> 500
    count < 2_000 -> 1_000
    count < 5_000 -> 2_000
    else -> 5_000
}

/** 未読の件数も同じ理由で粗い区分にする。 */
internal fun bucketUnread(count: Int): Int = when {
    count <= 0 -> 0
    count == 1 -> 1
    count <= 5 -> 2
    count <= 20 -> 3
    else -> 4
}
