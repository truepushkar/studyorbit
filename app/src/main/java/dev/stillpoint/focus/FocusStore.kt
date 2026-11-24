package dev.stillpoint.focus

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

const val MINUTE = 60_000L

enum class Phase(val label: String) {
    FOCUS("Focus"), BREAK("Break")
}

enum class TimerMode(val label: String) {
    TIMER("Timer"), STOPWATCH("Stopwatch"), POMODORO("Pomodoro")
}

data class Session(
    val phase: Phase,
    val duration: Long,
    val remaining: Long,
    val running: Boolean,
    val subject: String,
    val mode: TimerMode,
    val strict: Boolean,
    val round: Int,
    val rounds: Int,
    val sampledAt: Long = SystemClock.elapsedRealtime()
) {
    fun displayTime(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!running) return remaining
        val sinceSample = (now - sampledAt).coerceAtLeast(0)
        return if (mode == TimerMode.STOPWATCH) remaining + sinceSample else (remaining - sinceSample).coerceAtLeast(0)
    }
}

data class StudyLog(val endedAt: Long, val minutes: Int, val subject: String) {
    val day: LocalDate
        get() = Instant.ofEpochMilli(endedAt).atZone(ZoneId.systemDefault()).toLocalDate()
}

enum class AppTag(val label: String) {
    UNTAGGED("Untagged"), PRODUCTIVE("Productive"), DISTRACTING("Distracting")
}

data class AppRule(
    val focusOnly: Boolean = false,
    val dailyMinutes: Int = 0,
    val tag: AppTag = AppTag.UNTAGGED,
    val lockedUntil: Long = 0
) {
    val isLocked get() = lockedUntil > System.currentTimeMillis()
}

class FocusStore(private val context: Context) {
    internal val prefs = context.getSharedPreferences("stillpoint", Context.MODE_PRIVATE)

    var focusMinutes: Int
        get() = prefs.getInt("focus_minutes", 25)
        set(value) { prefs.edit().putInt("focus_minutes", value).apply() }
    var breakMinutes: Int
        get() = prefs.getInt("break_minutes", 5)
        set(value) { prefs.edit().putInt("break_minutes", value).apply() }
    var goalMinutes: Int
        get() = prefs.getInt("goal_minutes", 60)
        set(value) { prefs.edit().putInt("goal_minutes", value).apply() }
    var longBreakMinutes: Int
        get() = prefs.getInt("long_break_minutes", 15)
        set(value) { prefs.edit().putInt("long_break_minutes", value).apply() }
    var rounds: Int
        get() = prefs.getInt("pomodoro_rounds", 4)
        set(value) { prefs.edit().putInt("pomodoro_rounds", value).apply() }
    var subject: String
        get() = prefs.getString("subject", "").orEmpty()
        set(value) { prefs.edit().putString("subject", value.trim().take(70)).apply() }

    // The process cannot survive a reboot.
    private val boot: Int by lazy { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
    private var historyJson: String? = null
    private var historyCache: List<StudyLog> = emptyList()
    private val alarm: AlarmManager get() = context.getSystemService(AlarmManager::class.java)
    private fun alarmIntent() = PendingIntent.getBroadcast(
        context, 1, Intent(context, TimerReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun rule(pkg: String): AppRule = AppRule(
        prefs.getBoolean("focus:$pkg", false), prefs.getInt("limit:$pkg", 0),
        AppTag.entries.getOrElse(prefs.getInt("tag:$pkg", 0)) { AppTag.UNTAGGED },
        prefs.getLong("lock:$pkg", 0).takeIf { it > System.currentTimeMillis() } ?: 0
    )

    internal fun configuredRules(): Map<String, AppRule> = prefs.all.keys.asSequence()
        .filter { it.startsWith("focus:") || it.startsWith("limit:") || it.startsWith("tag:") }
        .map { it.substringAfter(':') }
        .distinct()
        .map { it to rule(it) }
        .filter { (_, rule) -> rule != AppRule() }
        .toMap()

    fun saveRule(pkg: String, rule: AppRule): Boolean {
        if (strictWindow() || this.rule(pkg).isLocked) return false
        val edit = prefs.edit()
        if (rule.focusOnly) edit.putBoolean("focus:$pkg", true) else edit.remove("focus:$pkg")
        if (rule.dailyMinutes > 0) edit.putInt("limit:$pkg", rule.dailyMinutes) else edit.remove("limit:$pkg")
        if (rule.tag != AppTag.UNTAGGED) edit.putInt("tag:$pkg", rule.tag.ordinal) else edit.remove("tag:$pkg")
        if (rule.isLocked) edit.putLong("lock:$pkg", rule.lockedUntil) else edit.remove("lock:$pkg")
        edit.apply()
        return true
    }

    fun history(): List<StudyLog> {
        val stored = prefs.getString("history", "[]") ?: "[]"
        if (stored == historyJson) return historyCache
        val array = JSONArray(stored)
        val entries = List(array.length()) { index ->
            val item = array.getJSONObject(index)
            StudyLog(item.getLong("end"), item.getInt("minutes"), item.getString("subject"))
        }
        historyJson = stored
        historyCache = entries
        return entries
    }

    fun session(): Session? {
        finishIfDue()
        if (!prefs.contains("phase")) return null
        val duration = prefs.getLong("duration", 0)
        val running = prefs.getLong("end_wall", 0) != 0L
        val mode = sessionMode()
        val saved = prefs.getLong("paused", 0)
        val remaining = when {
            mode == TimerMode.STOPWATCH -> saved + if (running) (-timeLeft()).coerceAtLeast(0) else 0
            running -> timeLeft().coerceIn(0, duration)
            else -> saved
        }
        return Session(
            Phase.valueOf(prefs.getString("phase", Phase.FOCUS.name)!!),
            duration, remaining, running,
            prefs.getString("session_subject", "").orEmpty(), mode,
            prefs.getBoolean("session_strict", false),
            prefs.getInt("session_round", 1), prefs.getInt("session_rounds", 1)
        )
    }

    fun strictWindow(): Boolean = prefs.getBoolean("session_strict", false) &&
        prefs.getLong("end_wall", 0) != 0L && timeLeft() > 0

    private fun sessionMode() = TimerMode.entries.getOrElse(prefs.getInt("session_mode", 0)) { TimerMode.TIMER }

    private fun timeLeft(): Long {
        return if (boot >= 0 && prefs.getInt("boot", -2) == boot) {
            prefs.getLong("end_elapsed", 0) - SystemClock.elapsedRealtime()
        } else {
            prefs.getLong("end_wall", 0) - System.currentTimeMillis()
        }
    }

    fun start(phase: Phase, minutes: Int, mode: TimerMode = TimerMode.TIMER, strict: Boolean = false, task: String = subject) {
        finishIfDue()
        if (prefs.contains("phase")) return
        val initialPhase = if (mode == TimerMode.TIMER) phase else Phase.FOCUS
        val duration = minutes * MINUTE
        val edit = prefs.edit().putString("phase", initialPhase.name).putLong("duration", duration)
            .putInt("session_mode", mode.ordinal).putBoolean("session_strict", strict && mode != TimerMode.STOPWATCH)
            .putString("session_subject", if (initialPhase == Phase.FOCUS) task.trim().take(70) else "")
        if (mode == TimerMode.POMODORO) {
            edit.putInt("session_round", 1).putInt("session_rounds", rounds)
                .putInt("cycle_focus", minutes).putInt("cycle_break", breakMinutes).putInt("cycle_long", longBreakMinutes)
        }
        if (mode == TimerMode.STOPWATCH) runStopwatch(edit) else runTimer(edit, duration)
        context.getSystemService(NotificationManager::class.java).cancel(2)
        syncStudyNotification()
    }

    fun pauseOrResume() {
        val active = session() ?: return
        if (active.strict) return
        if (active.running) {
            prefs.edit().putLong("paused", active.remaining).remove("end_wall").remove("end_elapsed").apply()
            alarm.cancel(alarmIntent())
        } else if (active.mode == TimerMode.STOPWATCH) {
            runStopwatch(prefs.edit())
        } else {
            runTimer(prefs.edit(), active.remaining)
        }
        syncStudyNotification()
    }

    fun stop() {
        finishIfDue()
        val active = session() ?: return
        if (active.strict) return
        val edit = clearSession(prefs.edit())
        if (active.mode == TimerMode.STOPWATCH && active.remaining >= MINUTE) {
            appendLog(edit, System.currentTimeMillis(), (active.remaining / MINUTE).toInt(), active.subject)
        }
        edit.apply()
        alarm.cancel(alarmIntent())
        context.getSystemService(NotificationManager::class.java).cancel(STUDY_NOTIFICATION)
    }

    private fun runStopwatch(edit: android.content.SharedPreferences.Editor) {
        edit.putLong("end_wall", System.currentTimeMillis())
            .putLong("end_elapsed", SystemClock.elapsedRealtime()).putInt("boot", boot).apply()
    }

    private fun runTimer(edit: android.content.SharedPreferences.Editor, remaining: Long) {
        val elapsedEnd = SystemClock.elapsedRealtime() + remaining
        edit.putLong("end_wall", System.currentTimeMillis() + remaining)
            .putLong("end_elapsed", elapsedEnd).putInt("boot", boot).remove("paused").apply()
        scheduleOrbitAlarm(alarm, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedEnd, alarmIntent())
    }

    internal fun finishIfDue() {
        val end = prefs.getLong("end_wall", 0)
        if (end == 0L || sessionMode() == TimerMode.STOPWATCH || timeLeft() > 0L) return
        val phase = Phase.valueOf(prefs.getString("phase", Phase.FOCUS.name)!!)
        val edit = prefs.edit()
        val pomodoro = sessionMode() == TimerMode.POMODORO
        val round = prefs.getInt("session_round", 1)
        val finalRound = round >= prefs.getInt("session_rounds", 1)
        val continuing = pomodoro && (phase == Phase.FOCUS || !finalRound)
        if (phase == Phase.FOCUS) {
            val completedAt = if (boot >= 0 && prefs.getInt("boot", -2) == boot) {
                System.currentTimeMillis() + timeLeft()
            } else end
            appendLog(edit,
                completedAt, (prefs.getLong("duration", 0) / MINUTE).toInt(),
                prefs.getString("session_subject", "").orEmpty()
            )
        }
        if (continuing) {
            val next = if (phase == Phase.FOCUS) Phase.BREAK else Phase.FOCUS
            val minutes = when {
                next == Phase.FOCUS -> prefs.getInt("cycle_focus", 25)
                finalRound -> prefs.getInt("cycle_long", 15)
                else -> prefs.getInt("cycle_break", 5)
            }
            if (next == Phase.FOCUS) edit.putInt("session_round", round + 1)
            runTimer(edit.putString("phase", next.name).putLong("duration", minutes * MINUTE), minutes * MINUTE)
        } else {
            clearSession(edit).apply()
        }
        context.getSystemService(NotificationManager::class.java).cancel(STUDY_NOTIFICATION)
        notifyFinished(phase, continuing)
        if (continuing) syncStudyNotification()
    }

    internal fun restoreAlarm() {
        finishIfDue()
        if (sessionMode() == TimerMode.STOPWATCH || prefs.getLong("end_wall", 0) == 0L) return
        val remaining = timeLeft()
        if (remaining > 0) {
            scheduleOrbitAlarm(alarm, AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + remaining, alarmIntent())
        }
    }

    private fun appendLog(edit: android.content.SharedPreferences.Editor, end: Long, minutes: Int, task: String) {
        val entries = JSONArray()
        (history().takeLast(729) + StudyLog(end, minutes, task)).forEach {
            entries.put(JSONObject().put("end", it.endedAt).put("minutes", it.minutes).put("subject", it.subject))
        }
        edit.putString("history", entries.toString())
    }

    private fun clearSession(edit: android.content.SharedPreferences.Editor) =
        edit.remove("phase").remove("duration").remove("end_wall").remove("end_elapsed")
            .remove("boot").remove("paused").remove("session_subject").remove("session_mode").remove("session_strict")
            .remove("session_round").remove("session_rounds").remove("cycle_focus").remove("cycle_break").remove("cycle_long")

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun openTimer() = PendingIntent.getActivity(
        context, 2, Intent(context, MainActivity::class.java)
            .setAction(MainActivity.SHOW_FOCUS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE
    )

    fun syncStudyNotification() {
        if (!canNotify()) return
        val active = session()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (active == null || (active.phase != Phase.FOCUS && active.mode != TimerMode.POMODORO)) {
            manager.cancel(STUDY_NOTIFICATION)
            return
        }

        manager.createNotificationChannel(NotificationChannel(
            "study_countdown", "Study countdown", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) })
        val notification = Notification.Builder(context, "study_countdown")
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(when {
                !active.running -> "Study session paused"
                active.phase == Phase.BREAK -> "Pomodoro break"
                else -> "Study session in progress"
            })
            .setContentIntent(openTimer())
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (active.running) {
            val countdown = active.mode != TimerMode.STOPWATCH
            notification.setContentText(if (active.phase == Phase.BREAK) "Round ${active.round} of ${active.rounds}"
                else active.subject.ifBlank { "Tap to open your timer" })
                .setWhen(System.currentTimeMillis() + if (countdown) active.remaining else -active.remaining)
                .setUsesChronometer(true)
                .setChronometerCountDown(countdown)
                .setShowWhen(true)
            if (countdown) notification.setTimeoutAfter(active.remaining)
        } else {
            val seconds = (active.remaining + 999) / 1_000
            val label = if (active.mode == TimerMode.STOPWATCH) "elapsed" else "remaining"
            notification.setContentText(String.format(Locale.US, "%d:%02d %s", seconds / 60, seconds % 60, label))
                .setShowWhen(false)
        }
        manager.notify(STUDY_NOTIFICATION, notification.build())
    }

    private fun notifyFinished(phase: Phase, continuing: Boolean) {
        if (!canNotify()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("timer", "Timer finished", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = Notification.Builder(context, "timer")
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(if (phase == Phase.FOCUS) "Focus session complete" else "Break complete")
            .setContentText(when {
                continuing && phase == Phase.FOCUS -> "Your Pomodoro break has started."
                continuing -> "Your next focus round has started."
                phase == Phase.FOCUS -> "Take a breath. You earned a break."
                else -> "Ready for another study session?"
            })
            .setAutoCancel(true).setContentIntent(openTimer()).build()
        manager.notify(2, notification)
    }

    private companion object {
        const val STUDY_NOTIFICATION = 3
    }
}

private val sessionPreferences = setOf("phase", "duration", "end_wall", "end_elapsed", "paused", "boot")

internal fun isSessionPreference(key: String?): Boolean = key == null ||
    key in sessionPreferences ||
    key.startsWith("session_")

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = FocusStore(context).finishIfDue()
}
