package blog.nextlab.echo.core.analytics

/**
 * The only way a non-numeric value can reach analytics.
 *
 * This interface is **sealed**, so it can only be implemented inside this module.
 * App code therefore cannot invent a new "enum" whose wire name is, say, the body of a
 * message — there is no path from arbitrary text to an analytics parameter.
 *
 * See docs/PRIVACY_PRINCIPLES.md ("Enforcement by construction").
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
 * What sort of sticker was used.
 *
 * Note what is deliberately absent: there is no parameter anywhere that carries a
 * sticker id. A custom sticker's id points at an asset one person made, so reporting it
 * would let the analytics pipeline reconstruct who created what and sent it to whom.
 * Kind and counts are enough to improve the feature.
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
 * A parameter value.
 *
 * There is deliberately no `Text` case. Adding one would be the single change that
 * breaks the privacy guarantee of this module, so it must never be added.
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
 * Message lengths are reported exactly up to 500 and bucketed above it.
 *
 * An exact length of 4 823 characters is a fingerprint; "5000+" is not.
 */
internal fun bucketCharacterCount(count: Int): Int = when {
    count < 500 -> count
    count < 1_000 -> 500
    count < 2_000 -> 1_000
    count < 5_000 -> 2_000
    else -> 5_000
}

/** Unread counts are reported as coarse buckets for the same reason. */
internal fun bucketUnread(count: Int): Int = when {
    count <= 0 -> 0
    count == 1 -> 1
    count <= 5 -> 2
    count <= 20 -> 3
    else -> 4
}
