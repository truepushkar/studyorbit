package dev.stillpoint.focus

import java.util.Locale

internal fun timerDigits(milliseconds: Long, stopwatch: Boolean): String {
    val seconds = if (stopwatch) milliseconds / 1_000 else (milliseconds + 999) / 1_000
    val hours = if (stopwatch) seconds / 3_600 else 0
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
}

internal fun timerTickDelay(milliseconds: Long, stopwatch: Boolean): Long {
    val fraction = milliseconds % 1_000
    return when {
        stopwatch -> 1_000 - fraction
        fraction == 0L -> 1_000
        else -> fraction
    }
}
