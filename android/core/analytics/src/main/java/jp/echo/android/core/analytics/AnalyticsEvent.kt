package jp.echo.android.core.analytics

/**
 * Every analytics event Echo can emit.
 *
 * This file is the executable form of docs/ANALYTICS_SCHEMA.md. If the two disagree,
 * one of them is a bug.
 *
 * Constraint that must hold for every event here: **no property may be a String.**
 * Counts are computed on the client and sent as numbers; categories are [AnalyticsEnum].
 */
sealed class AnalyticsEvent {

    /** The wire name of the event. Defined here, never supplied by a caller. */
    abstract val name: String

    abstract fun parameters(): Map<String, AnalyticsValue>

    // ------------------------------------------------------------ lifecycle

    data class AppOpened(
        val coldStart: Boolean,
        val startupMs: Long,
    ) : AnalyticsEvent() {
        override val name = "app_opened"
        override fun parameters() = mapOf(
            "cold_start" to coldStart.param(),
            "startup_ms" to startupMs.param(),
        )
    }

    data class AppBackgrounded(
        val foregroundMs: Long,
    ) : AnalyticsEvent() {
        override val name = "app_backgrounded"
        override fun parameters() = mapOf("foreground_ms" to foregroundMs.param())
    }

    /**
     * Reported once per active period. Never a stream of individual interaction
     * timestamps — see docs/ANALYTICS_SCHEMA.md ("Active Time").
     */
    data class ActivePeriodEnded(
        val activeMs: Long,
        val foregroundMs: Long,
        val interactionCount: Int,
        val screen: ScreenId,
    ) : AnalyticsEvent() {
        override val name = "active_period_ended"
        override fun parameters() = mapOf(
            "active_ms" to activeMs.param(),
            "foreground_ms" to foregroundMs.param(),
            "interaction_count" to interactionCount.param(),
            "screen" to screen.param(),
        )
    }

    // ------------------------------------------------------------ messaging

    data class MessageSent(
        val characterCount: Int,
        val conversationType: ConversationType,
        val isReply: Boolean,
        val attachmentType: AttachmentType,
        val deliveryLatencyMs: Long,
        val sendSuccess: Boolean,
    ) : AnalyticsEvent() {
        override val name = "message_sent"
        override fun parameters() = mapOf(
            "character_count" to bucketCharacterCount(characterCount).param(),
            "conversation_type" to conversationType.param(),
            "is_reply" to isReply.param(),
            "attachment_type" to attachmentType.param(),
            "delivery_latency_ms" to deliveryLatencyMs.param(),
            "send_success" to sendSuccess.param(),
        )
    }

    data class MessageSendFailed(
        val failureReason: SendFailureReason,
        val retryCount: Int,
    ) : AnalyticsEvent() {
        override val name = "message_send_failed"
        override fun parameters() = mapOf(
            "failure_reason" to failureReason.param(),
            "retry_count" to retryCount.param(),
        )
    }

    data class MessageReceived(
        val conversationType: ConversationType,
        val attachmentType: AttachmentType,
    ) : AnalyticsEvent() {
        override val name = "message_received"
        override fun parameters() = mapOf(
            "conversation_type" to conversationType.param(),
            "attachment_type" to attachmentType.param(),
        )
    }

    /**
     * @param paletteIndex position in the fixed reaction palette. The emoji itself is
     *   never sent — see docs/ANALYTICS_SCHEMA.md.
     */
    data class ReactionAdded(
        val paletteIndex: Int,
        val conversationType: ConversationType,
    ) : AnalyticsEvent() {
        override val name = "reaction_added"
        override fun parameters() = mapOf(
            "palette_index" to paletteIndex.param(),
            "conversation_type" to conversationType.param(),
        )
    }

    data class ReplySent(
        val conversationType: ConversationType,
    ) : AnalyticsEvent() {
        override val name = "reply_sent"
        override fun parameters() = mapOf("conversation_type" to conversationType.param())
    }

    // ------------------------------------------------------------ UX

    data object ReplySwipeStarted : AnalyticsEvent() {
        override val name = "reply_swipe_started"
        override fun parameters() = emptyMap<String, AnalyticsValue>()
    }

    data class ReplySwipeThresholdReached(
        val timeToThresholdMs: Long,
    ) : AnalyticsEvent() {
        override val name = "reply_swipe_threshold_reached"
        override fun parameters() = mapOf("time_to_threshold_ms" to timeToThresholdMs.param())
    }

    /** @param maxDragRatio 0-100, how far the finger got relative to the threshold. */
    data class ReplySwipeCancelled(
        val maxDragRatio: Int,
        val passedThreshold: Boolean,
    ) : AnalyticsEvent() {
        override val name = "reply_swipe_cancelled"
        override fun parameters() = mapOf(
            "max_drag_ratio" to maxDragRatio.coerceIn(0, 100).param(),
            "passed_threshold" to passedThreshold.param(),
        )
    }

    data class ReplySwipeCompleted(
        val durationMs: Long,
    ) : AnalyticsEvent() {
        override val name = "reply_swipe_completed"
        override fun parameters() = mapOf("duration_ms" to durationMs.param())
    }

    data class MessageLongPressed(
        val holdMs: Long,
    ) : AnalyticsEvent() {
        override val name = "message_long_pressed"
        override fun parameters() = mapOf("hold_ms" to holdMs.param())
    }

    data class LongPressCancelled(
        val holdMs: Long,
    ) : AnalyticsEvent() {
        override val name = "long_press_cancelled"
        override fun parameters() = mapOf("hold_ms" to holdMs.param())
    }

    data object ReactionPickerOpened : AnalyticsEvent() {
        override val name = "reaction_picker_opened"
        override fun parameters() = emptyMap<String, AnalyticsValue>()
    }

    data class ReactionPickerDismissed(
        val openMs: Long,
        val hoveredCount: Int,
    ) : AnalyticsEvent() {
        override val name = "reaction_picker_dismissed"
        override fun parameters() = mapOf(
            "open_ms" to openMs.param(),
            "hovered_count" to hoveredCount.param(),
        )
    }

    data class ReactionSelected(
        val paletteIndex: Int,
        val openMs: Long,
    ) : AnalyticsEvent() {
        override val name = "reaction_selected"
        override fun parameters() = mapOf(
            "palette_index" to paletteIndex.param(),
            "open_ms" to openMs.param(),
        )
    }

    data object ComposerOpened : AnalyticsEvent() {
        override val name = "composer_opened"
        override fun parameters() = emptyMap<String, AnalyticsValue>()
    }

    /** Text was typed but never sent. Only its length is reported. */
    data class ComposerAbandoned(
        val characterCount: Int,
        val composeMs: Long,
    ) : AnalyticsEvent() {
        override val name = "composer_abandoned"
        override fun parameters() = mapOf(
            "character_count" to bucketCharacterCount(characterCount).param(),
            "compose_ms" to composeMs.param(),
        )
    }

    data object AttachmentPickerOpened : AnalyticsEvent() {
        override val name = "attachment_picker_opened"
        override fun parameters() = emptyMap<String, AnalyticsValue>()
    }

    data class ChatOpened(
        val unreadCount: Int,
    ) : AnalyticsEvent() {
        override val name = "chat_opened"
        override fun parameters() = mapOf("unread_bucket" to bucketUnread(unreadCount).param())
    }

    data class ScrollBurst(
        val distanceDp: Int,
        val flingCount: Int,
    ) : AnalyticsEvent() {
        override val name = "scroll_burst"
        override fun parameters() = mapOf(
            "distance_dp" to distanceDp.param(),
            "fling_count" to flingCount.param(),
        )
    }

    // ------------------------------------------------------------ haptics

    /**
     * One aggregated summary per session rather than an event per vibration.
     *
     * Per-fire events would both flood the pipeline and make an individual user's
     * interaction sequence reconstructible.
     */
    data class HapticSessionSummary(
        val hapticsEnabled: Boolean,
        val tierUsed: HapticTierId,
        val sendCount: Int,
        val selectionCount: Int,
        val thresholdCount: Int,
        val reactionCount: Int,
        val successCount: Int,
        val errorCount: Int,
        val destructiveCount: Int,
        val suppressedCount: Int,
    ) : AnalyticsEvent() {
        override val name = "haptic_session_summary"
        override fun parameters() = mapOf(
            "haptics_enabled" to hapticsEnabled.param(),
            "tier_used" to tierUsed.param(),
            "send_count" to sendCount.param(),
            "selection_count" to selectionCount.param(),
            "threshold_count" to thresholdCount.param(),
            "reaction_count" to reactionCount.param(),
            "success_count" to successCount.param(),
            "error_count" to errorCount.param(),
            "destructive_count" to destructiveCount.param(),
            "suppressed_count" to suppressedCount.param(),
        )
    }

    data class HapticsSettingChanged(
        val enabled: Boolean,
        val intensity: HapticIntensityId,
    ) : AnalyticsEvent() {
        override val name = "haptics_setting_changed"
        override fun parameters() = mapOf(
            "enabled" to enabled.param(),
            "intensity" to intensity.param(),
        )
    }

    // ------------------------------------------------------------ feedback

    /**
     * @param bodyLength length only. The feedback text itself goes to the feedback
     *   backend because the user wrote it *for* the developer; it never goes to analytics.
     */
    data class FeedbackSubmitted(
        val category: FeedbackCategory,
        val bodyLength: Int,
        val hasScreenshot: Boolean,
    ) : AnalyticsEvent() {
        override val name = "feedback_submitted"
        override fun parameters() = mapOf(
            "category" to category.param(),
            "body_length" to bucketCharacterCount(bodyLength).param(),
            "has_screenshot" to hasScreenshot.param(),
        )
    }

    data class FeedbackVoted(
        val direction: VoteDirection,
    ) : AnalyticsEvent() {
        override val name = "feedback_voted"
        override fun parameters() = mapOf("direction" to direction.param())
    }

    // ------------------------------------------------------------ privacy

    data object PrivacyScreenOpened : AnalyticsEvent() {
        override val name = "privacy_screen_opened"
        override fun parameters() = emptyMap<String, AnalyticsValue>()
    }

    data class AnalyticsOptOutChanged(
        val optedOut: Boolean,
    ) : AnalyticsEvent() {
        override val name = "analytics_opt_out_changed"
        override fun parameters() = mapOf("opted_out" to optedOut.param())
    }
}

/** Device- and configuration-level attributes. Never anything identifying a person. */
sealed class AnalyticsUserProperty {
    abstract val name: String
    abstract val value: AnalyticsValue

    data class AppVersionCode(val versionCode: Int) : AnalyticsUserProperty() {
        override val name = "app_version_code"
        override val value = versionCode.param()
    }

    data class OsApiLevel(val apiLevel: Int) : AnalyticsUserProperty() {
        override val name = "os_api_level"
        override val value = apiLevel.param()
    }

    /** Category only. The exact model can single out a person on a rare device. */
    data class Device(val category: DeviceCategory) : AnalyticsUserProperty() {
        override val name = "device_category"
        override val value = category.param()
    }

    data class HapticTierProperty(val tier: HapticTierId) : AnalyticsUserProperty() {
        override val name = "haptic_tier"
        override val value = tier.param()
    }

    data class HapticsIntensity(val intensity: HapticIntensityId) : AnalyticsUserProperty() {
        override val name = "haptics_intensity"
        override val value = intensity.param()
    }

    data class Theme(val mode: ThemeMode) : AnalyticsUserProperty() {
        override val name = "theme_mode"
        override val value = mode.param()
    }

    data class ReactionPaletteVersion(val version: Int) : AnalyticsUserProperty() {
        override val name = "reaction_palette_version"
        override val value = version.param()
    }
}
