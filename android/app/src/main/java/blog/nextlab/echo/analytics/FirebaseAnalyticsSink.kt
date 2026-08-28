package blog.nextlab.echo.analytics

import android.content.Context
import android.os.Bundle
import blog.nextlab.echo.core.analytics.Analytics
import blog.nextlab.echo.core.analytics.AnalyticsEvent
import blog.nextlab.echo.core.analytics.AnalyticsUserProperty
import blog.nextlab.echo.core.analytics.AnalyticsValue
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * 型付きのスキーマを Firebase Analytics へ送る。
 *
 * このクラスが短くて退屈なのは、プライバシーの作業が全部よそで終わっているから。
 * `:core:analytics` には `String` を受ける API が無く、
 * [blog.nextlab.echo.core.analytics.AnalyticsEnum] はそのモジュールの中で sealed。
 * つまりここでは、本文になりうる値を書き表すことすらできない。だからこのクラスは
 * 何も濾さない。数値と enum の名前を [Bundle] に写して渡すだけ。
 *
 * それが設計の狙い。気を付ける必要のある出口は、いつか気を抜く。
 * docs/PRIVACY_PRINCIPLES.md の「構造で守る」。
 *
 * 切ってあるものと、その理由:
 *
 * - **広告 ID の収集**。マニフェストで off。Rinowa に広告は無い。
 * - **画面遷移の自動計測**。マニフェストで off。Firebase の自動 `screen_view` は
 *   Activity のクラス名を送る。Rinowa は自前の [ScreenId] を送るので、
 *   何を計っているかの説明はスキーマだけになる。
 * - **ユーザー ID**。設定しない。`setUserId` は安定したアカウント識別子を全イベントに
 *   付ける。docs/ANALYTICS_SCHEMA.md の規則2がはっきり禁じている。
 */
class FirebaseAnalyticsSink(
    context: Context,
    initiallyOptedOut: Boolean,
    private val onOptOutChanged: (Boolean) -> Unit,
) : Analytics {

    private val firebase = FirebaseAnalytics.getInstance(context)

    private var optOut: Boolean = initiallyOptedOut

    init {
        firebase.setAnalyticsCollectionEnabled(!initiallyOptedOut)
    }

    override val optedOut: Boolean get() = optOut

    override fun setOptedOut(optedOut: Boolean) {
        // 収集を止める前に、止めたこと自体は送る。割合が分かるように。
        // そのあとは何も送らない。docs/ANALYTICS_SCHEMA.md §7。
        if (optedOut && !optOut) {
            send(AnalyticsEvent.AnalyticsOptOutChanged(true))
        }
        optOut = optedOut
        firebase.setAnalyticsCollectionEnabled(!optedOut)
        if (!optedOut) send(AnalyticsEvent.AnalyticsOptOutChanged(false))
        onOptOutChanged(optedOut)
    }

    override fun log(event: AnalyticsEvent) {
        if (optOut) return
        send(event)
    }

    override fun setUserProperty(property: AnalyticsUserProperty) {
        if (optOut) return
        firebase.setUserProperty(property.name, property.value.wire())
    }

    private fun send(event: AnalyticsEvent) {
        val bundle = Bundle()
        event.parameters().forEach { (key, value) ->
            when (value) {
                is AnalyticsValue.Num -> bundle.putLong(key, value.value)
                is AnalyticsValue.Real -> bundle.putDouble(key, value.value)
                // Firebase に真偽値の型は無い。"true"/"false" ではなく 1/0 にして、
                // 最後まで数値のまま通す。
                is AnalyticsValue.Flag -> bundle.putLong(key, if (value.value) 1L else 0L)
                is AnalyticsValue.Choice -> bundle.putString(key, value.value.wireName)
            }
        }
        firebase.logEvent(event.name, bundle)
    }

    private fun AnalyticsValue.wire(): String = when (this) {
        is AnalyticsValue.Num -> value.toString()
        is AnalyticsValue.Real -> value.toString()
        is AnalyticsValue.Flag -> if (value) "true" else "false"
        is AnalyticsValue.Choice -> value.wireName
    }
}
