package blog.nextlab.echo.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * いまの回線にどれだけ余裕があるか。
 *
 * これがあるのは衛星のため。Starlink Direct to Cell（日本では au Starlink Direct）は、
 * 改造していない普通の端末に衛星回線をつなぐ。**アプリに衛星の SDK は要らない**
 * （普通のセルラーとして見え、ソケットも普通に動く）。
 *
 * ただし普通のようには振る舞わない。衛星の足元にいる全員で1本を分け合うので、
 * 1台あたりの予算は**メガではなくキロビット**、往復も長い。これを遅い4Gとして扱うものは
 * 単に失敗する。ゆっくりでもなく穏やかでもなく、握手を終えられない Firestore の
 * ストリームは何ひとつ配達しない。
 *
 * だからアプリが*知る*必要があり、そのためのクラス。
 *
 * 見分け方: Android 15 がまさにこのために
 * [NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED] を足した。
 * **これが無いこと**が、プラットフォームが強く制約されていると見なす回線の印で、
 * 衛星接続はそう報告する。それより古い版にはこの信号が無いので、推測せず [Unknown] を
 * 返す。自信のある方向に間違えると、動いている接続を理由なく文字だけに落とす。
 *
 * Rinowa Direct で2回誤診した教訓と同じ。**API のレベルは端末の能力ではない**し、
 * 観測できない能力は仮定しない。
 *
 * docs/SATELLITE.md。
 */
enum class LinkClass {
    /** 普通の Wi-Fi かセルラー。いつもどおりでよい。 */
    Normal,

    /** 強く制約されている。衛星か、プラットフォームが極端に細いと判断した回線。 */
    Constrained,

    /** 使える回線が無い。 */
    Offline,

    /** この Android では判断できない。[Normal] として扱う。上の注記を参照。 */
    Unknown,
}

object LinkClassifier {

    fun current(context: Context): LinkClass {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return LinkClass.Unknown
        val network = manager.activeNetwork ?: return LinkClass.Offline
        val capabilities = manager.getNetworkCapabilities(network) ?: return LinkClass.Offline

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return LinkClass.Offline
        }

        // VANILLA_ICE_CREAM は Android 15。この capability が入った版。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return LinkClass.Unknown

        val roomy = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED,
        )
        return if (roomy) LinkClass.Normal else LinkClass.Constrained
    }

    /**
     * この回線で何をしてよいか。
     *
     * `if` を散らさずに方針オブジェクトとして書く。ここを間違えたときの失敗は
     * 分かりにくい（先読み1つ消し忘れただけで、文字メッセージに必要だった衛星の
     * 予算を食い潰す）。
     */
    fun policy(link: LinkClass): LinkPolicy = when (link) {
        LinkClass.Constrained -> LinkPolicy(
            allowPhotos = false,
            allowStickerFetch = false,
            allowProfilePhotoFetch = false,
            allowReadReceipts = false,
            allowPresence = false,
            requireCompactWire = true,
            maxTextLength = 500,
        )

        // Unknown を Normal 扱いにするのは意図的。プラットフォームが答えてくれないという
        // 理由で、動いている接続を文字だけに落とすほうが、細かった回線に写真を送るより悪い。
        else -> LinkPolicy(
            allowPhotos = true,
            allowStickerFetch = true,
            allowProfilePhotoFetch = true,
            allowReadReceipts = true,
            allowPresence = true,
            requireCompactWire = false,
            maxTextLength = 4000,
        )
    }
}

data class LinkPolicy(
    val allowPhotos: Boolean,
    val allowStickerFetch: Boolean,
    val allowProfilePhotoFetch: Boolean,
    /**
     * 既読は、読んだメッセージ1件につき1回の書き込み。
     *
     * 普通の回線ではただ同然。衛星ではメッセージ本体と同じ費用を、見たと伝えるために使う。
     */
    val allowReadReceipts: Boolean,
    val allowPresence: Boolean,
    /** 普通のドキュメント書き込みではなく、Yosegi と同梱の辞書を使う。 */
    val requireCompactWire: Boolean,
    val maxTextLength: Int,
)
