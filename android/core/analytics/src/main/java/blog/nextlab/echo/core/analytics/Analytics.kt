package blog.nextlab.echo.core.analytics

import android.util.Log

/**
 * 計測の出口。
 *
 * この interface に*無い*ものに注意。自由な文字列を渡す手段がまったく無い。
 * `log(name: String, params: Map<String, Any>)` のようなものは存在せず、
 * 足せば設計全体が台無しになる。docs/PRIVACY_PRINCIPLES.md。
 */
interface Analytics {

    /** true の間、記録されるのは「止めた」という変更そのものだけ。 */
    val optedOut: Boolean

    fun setOptedOut(optedOut: Boolean)

    fun log(event: AnalyticsEvent)

    fun setUserProperty(property: AnalyticsUserProperty)
}

/** Prototype 0 の既定。契約としては存在するが、どこにも送らない。 */
class NoOpAnalytics : Analytics {
    private var optOut = false
    override val optedOut: Boolean get() = optOut
    override fun setOptedOut(optedOut: Boolean) { optOut = optedOut }
    override fun log(event: AnalyticsEvent) = Unit
    override fun setUserProperty(property: AnalyticsUserProperty) = Unit
}

/**
 * 開発中に logcat へイベントを書く。
 *
 * 構造上安全。出せるのはイベント名、項目名、数値、enum の名前だけ。
 * メッセージの本文がここへ届く経路は無い。そういう値を [AnalyticsEvent] に
 * 入れられないから。
 */
class DebugAnalytics(private val tag: String = "RinowaAnalytics") : Analytics {

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
