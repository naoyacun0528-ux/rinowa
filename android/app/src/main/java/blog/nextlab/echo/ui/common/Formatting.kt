package blog.nextlab.echo.ui.common

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/** Clock time shown under a bubble. */
fun formatClock(timestampMs: Long): String =
    SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(timestampMs))

/** Compact relative time for the conversation list. */
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

/** Date separator between days in a thread. */
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
