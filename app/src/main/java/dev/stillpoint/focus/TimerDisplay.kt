package dev.stillpoint.focus

internal fun timerDigits(milliseconds: Long, stopwatch: Boolean): String {
    val seconds = milliseconds / 1_000 + if (!stopwatch && milliseconds % 1_000 != 0L) 1 else 0
    val hours = if (stopwatch) seconds / 3_600 else 0
    val minutes = if (hours > 0) seconds / 60 % 60 else seconds / 60
    val remainder = seconds % 60
    return buildString {
        if (hours > 0) append(hours).append(':')
        if (minutes < 10) append('0')
        append(minutes).append(':')
        if (remainder < 10) append('0')
        append(remainder)
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
