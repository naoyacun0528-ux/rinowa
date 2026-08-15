package blog.nextlab.echo.core.analytics

import android.util.Log

/**
 * The analytics sink.
 *
 * Note what this interface does *not* have: any way to pass free text. There is no
 * `log(name: String, params: Map<String, Any>)` overload, and adding one would defeat
 * the whole design. See docs/PRIVACY_PRINCIPLES.md.
 */
interface Analytics {

    /** When true, nothing is recorded except the opt-out change itself. */
    val optedOut: Boolean

    fun setOptedOut(optedOut: Boolean)

    fun log(event: AnalyticsEvent)

    fun setUserProperty(property: AnalyticsUserProperty)
}

/** Prototype 0 default: analytics exists as a contract but sends nothing anywhere. */
class NoOpAnalytics : Analytics {
    private var optOut = false
    override val optedOut: Boolean get() = optOut
    override fun setOptedOut(optedOut: Boolean) { optOut = optedOut }
    override fun log(event: AnalyticsEvent) = Unit
    override fun setUserProperty(property: AnalyticsUserProperty) = Unit
}

/**
 * Writes events to logcat during development.
 *
 * Safe by construction: the only things it can print are the event name, parameter names,
 * numbers and enum wire names. There is no code path by which a message body could reach
 * this logger, because no such value can be put into an [AnalyticsEvent].
 */
class DebugAnalytics(private val tag: String = "EchoAnalytics") : Analytics {

    private var optOut = false
    override val optedOut: Boolean get() = optOut

    override fun setOptedOut(optedOut: Boolean) {
        optOut = optedOut
        Log.d(tag, "opt_out=$optedOut")
    }

    override fun log(event: AnalyticsEvent) {
        if (optOut && event !is AnalyticsEvent.AnalyticsOptOutChanged) return
        Log.d(tag, "${event.name} ${render(event.parameters())}")
    }

    override fun setUserProperty(property: AnalyticsUserProperty) {
        if (optOut) return
        Log.d(tag, "property ${property.name}=${render(property.value)}")
    }

    private fun render(parameters: Map<String, AnalyticsValue>): String =
        parameters.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "$key=${render(value)}"
        }

    private fun render(value: AnalyticsValue): String = when (value) {
        is AnalyticsValue.Num -> value.value.toString()
        is AnalyticsValue.Real -> value.value.toString()
        is AnalyticsValue.Flag -> value.value.toString()
        is AnalyticsValue.Choice -> value.value.wireName
    }
}
