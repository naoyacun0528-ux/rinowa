package blog.nextlab.echo.ui.common

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/** 吹き出しの下に出す時刻。 */
fun formatClock(timestampMs: Long): String =
    SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(timestampMs))

/**
 * 長さを 0:07、1:23、12:04 と書く。時刻ではなく経過時間。
 *
 * 動画の吹き出し、再生プレイヤー、通話の記録が同じ形で出す。以前は同じ数行が
 * 3か所に別々に置いてあり、片方だけ直せる状態だった。
 *
 * 00:07 とは書かない。実際より長く見える。
 */
fun formatDuration(ms: Long): String = formatSeconds((ms / 1000).coerceAtLeast(0))

/** 1時間を超えたら 1:02:30。通話は長くなりうる。 */
fun formatCallDuration(seconds: Int): String = formatSeconds(seconds.coerceAtLeast(0).toLong())

private fun formatSeconds(total: Long): String {
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    // 秒は必ず2桁。3:7 は読み違える。
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

/** 会話一覧用の、短い相対時刻。 */
fun formatListTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val delta = nowMs - timestampMs
    return when {
        delta < MINUTE -> "今"
        delta < HOUR -> "${delta / MINUTE}分"
        isSameDay(timestampMs, nowMs) -> formatClock(timestampMs)
        isYesterday(timestampMs, nowMs) -> "昨日"
        else -> SimpleDateFormat("M/d", Locale.getDefault()).format(Date(timestampMs))
    }
}

/** スレッドの中で日付が変わる位置に置く区切り。 */
fun formatDaySeparator(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String = when {
    isSameDay(timestampMs, nowMs) -> "今日"
    isYesterday(timestampMs, nowMs) -> "昨日"
    else -> SimpleDateFormat("M月d日(E)", Locale.getDefault()).format(Date(timestampMs))
}

fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(timestampMs: Long, nowMs: Long): Boolean {
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = nowMs
        add(Calendar.DAY_OF_YEAR, -1)
    }
    return isSameDay(timestampMs, yesterday.timeInMillis)
}
