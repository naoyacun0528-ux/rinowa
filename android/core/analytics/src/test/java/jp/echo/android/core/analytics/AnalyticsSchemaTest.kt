package jp.echo.android.core.analytics

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire format of the analytics schema.
 *
 * The "no message bodies" guarantee is structural — [AnalyticsValue] has no text case,
 * and [AnalyticsEnum] is sealed — so these tests check the things types cannot: naming
 * conventions and the bucketing that keeps long lengths from becoming fingerprints.
 */
class AnalyticsSchemaTest {

    private val allEvents: List<AnalyticsEvent> = listOf(
        AnalyticsEvent.AppOpened(coldStart = true, startupMs = 412),
        AnalyticsEvent.AppBackgrounded(foregroundMs = 90_000),
        AnalyticsEvent.ActivePeriodEnded(30_000, 90_000, 42, ScreenId.Chat),
        AnalyticsEvent.MessageSent(24, ConversationType.Direct, false, AttachmentType.None, 130, true),
        AnalyticsEvent.MessageSendFailed(SendFailureReason.Network, 2),
        AnalyticsEvent.MessageReceived(ConversationType.Group, AttachmentType.Image),
        AnalyticsEvent.ReactionAdded(3, ConversationType.Direct),
        AnalyticsEvent.ReplySent(ConversationType.Direct),
        AnalyticsEvent.ReplySwipeStarted,
        AnalyticsEvent.ReplySwipeThresholdReached(220),
        AnalyticsEvent.ReplySwipeCancelled(64, false),
        AnalyticsEvent.ReplySwipeCompleted(540),
        AnalyticsEvent.MessageLongPressed(380),
        AnalyticsEvent.LongPressCancelled(120),
        AnalyticsEvent.ReactionPickerOpened,
        AnalyticsEvent.ReactionPickerDismissed(1_400, 3),
        AnalyticsEvent.ReactionSelected(1, 900),
        AnalyticsEvent.ComposerOpened,
        AnalyticsEvent.ComposerAbandoned(18, 4_000),
        AnalyticsEvent.AttachmentPickerOpened,
        AnalyticsEvent.ChatOpened(7),
        AnalyticsEvent.ScrollBurst(1_200, 3),
        AnalyticsEvent.HapticSessionSummary(true, HapticTierId.Envelope, 5, 40, 3, 2, 1, 0, 0, 6),
        AnalyticsEvent.HapticsSettingChanged(true, HapticIntensityId.Normal),
        AnalyticsEvent.FeedbackSubmitted(FeedbackCategory.Haptic, 240, true),
        AnalyticsEvent.FeedbackVoted(VoteDirection.Up),
        AnalyticsEvent.PrivacyScreenOpened,
        AnalyticsEvent.AnalyticsOptOutChanged(true),
    )

    private val snakeCase = Regex("^[a-z][a-z0-9_]*$")

    @Test
    fun `event names are snake_case`() {
        allEvents.forEach { event ->
            assertTrue(
                "event name '${event.name}' is not snake_case",
                snakeCase.matches(event.name),
            )
        }
    }

    @Test
    fun `parameter names are snake_case`() {
        allEvents.forEach { event ->
            event.parameters().keys.forEach { key ->
                assertTrue(
                    "parameter '$key' on '${event.name}' is not snake_case",
                    snakeCase.matches(key),
                )
            }
        }
    }

    @Test
    fun `enum wire names are snake_case`() {
        allEvents.forEach { event ->
            event.parameters().forEach { (key, value) ->
                if (value is AnalyticsValue.Choice) {
                    assertTrue(
                        "enum wire name '${value.value.wireName}' on '${event.name}.$key' is not snake_case",
                        snakeCase.matches(value.value.wireName),
                    )
                }
            }
        }
    }

    @Test
    fun `character counts above 500 are bucketed`() {
        // Exact long lengths are a fingerprint; buckets are not.
        assertTrue(bucketCharacterCount(0) == 0)
        assertTrue(bucketCharacterCount(24) == 24)
        assertTrue(bucketCharacterCount(499) == 499)
        assertTrue(bucketCharacterCount(500) == 500)
        assertTrue(bucketCharacterCount(999) == 500)
        assertTrue(bucketCharacterCount(1_500) == 1_000)
        assertTrue(bucketCharacterCount(4_823) == 2_000)
        assertTrue(bucketCharacterCount(120_000) == 5_000)
    }

    @Test
    fun `unread counts are bucketed`() {
        assertTrue(bucketUnread(0) == 0)
        assertTrue(bucketUnread(1) == 1)
        assertTrue(bucketUnread(5) == 2)
        assertTrue(bucketUnread(20) == 3)
        assertTrue(bucketUnread(21) == 4)
    }

    @Test
    fun `opted out analytics records nothing except the opt-out itself`() {
        val recorded = mutableListOf<String>()
        val analytics = object : Analytics {
            private var out = false
            override val optedOut get() = out
            override fun setOptedOut(optedOut: Boolean) { out = optedOut }
            override fun log(event: AnalyticsEvent) {
                if (out && event !is AnalyticsEvent.AnalyticsOptOutChanged) return
                recorded += event.name
            }
            override fun setUserProperty(property: AnalyticsUserProperty) {
                if (out) return
                recorded += property.name
            }
        }

        analytics.setOptedOut(true)
        analytics.log(AnalyticsEvent.ComposerOpened)
        analytics.setUserProperty(AnalyticsUserProperty.OsApiLevel(37))
        analytics.log(AnalyticsEvent.AnalyticsOptOutChanged(optedOut = true))

        assertTrue("opted-out sink recorded $recorded", recorded == listOf("analytics_opt_out_changed"))
    }
}
