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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class StudySchedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val date: LocalDate,
    val time: LocalTime,
    val minutes: Int,
    val repeat: Set<DayOfWeek> = emptySet(),
    val block: Boolean = false,
    val remind: Boolean = true
) {
    fun on(day: LocalDate): Boolean = if (repeat.isEmpty()) day == date else day >= date && day.dayOfWeek in repeat

    fun start(day: LocalDate, zone: ZoneId) = day.atTime(time).atZone(zone).toInstant()

    fun nextStart(now: ZonedDateTime): Instant? {
        val first = maxOf(date, now.toLocalDate())
        val after = now.toInstant()
        return (0L..7L).asSequence().map(first::plusDays).filter(::on)
            .map { start(it, now.zone) }.firstOrNull { it > after }
    }
}

internal fun readSchedules(prefs: SharedPreferences): List<StudySchedule> {
    val data = runCatching { JSONArray(prefs.getString("study_schedules", "[]")) }.getOrElse { JSONArray() }
    return (0 until data.length()).mapNotNull { index ->
        runCatching {
            val entry = data.getJSONObject(index)
            val days = entry.getInt("days")
            StudySchedule(
                id = entry.getString("id"), name = entry.getString("name"),
                date = LocalDate.parse(entry.getString("date")), time = LocalTime.parse(entry.getString("time")),
                minutes = entry.getInt("minutes"),
                repeat = DayOfWeek.entries.filter { days and (1 shl it.ordinal) != 0 }.toSet(),
                block = entry.getBoolean("block"), remind = entry.getBoolean("remind")
            )
        }.getOrNull()
    }
}

internal data class ScheduledFocus(val active: Boolean, val nextChange: Instant?)

internal fun scheduledFocus(schedules: List<StudySchedule>, now: ZonedDateTime): ScheduledFocus {
    val today = now.toLocalDate()
    val instant = now.toInstant()
    var active = false
    var nextChange: Instant? = null
    fun consider(time: Instant) {
        if (time > instant && (nextChange == null || time < nextChange)) nextChange = time
    }
    for (schedule in schedules) {
        if (!schedule.block) continue
        for (day in listOf(today.minusDays(1), today)) {
            if (!schedule.on(day)) continue
            val start = schedule.start(day, now.zone)
            val end = start.plusSeconds(schedule.minutes * 60L)
            if (instant >= start && instant < end) {
                active = true
                consider(end)
            } else consider(start)
        }
        val earliest = maxOf(schedule.date, today.plusDays(1))
        for (offset in 0L..6L) {
            val date = earliest.plusDays(offset)
            if (!schedule.on(date)) continue
            consider(schedule.start(date, now.zone))
            break
        }
    }
    return ScheduledFocus(active, nextChange)
}

internal class StudyPlanner(private val context: Context, private val prefs: SharedPreferences) {
    private val alarm get() = context.getSystemService(AlarmManager::class.java)

    fun save(schedules: List<StudySchedule>) {
        if (FocusStore(context).strictWindow()) return
        val array = JSONArray()
        schedules.forEach { schedule ->
            array.put(JSONObject().put("id", schedule.id).put("name", schedule.name)
                .put("date", schedule.date.toString()).put("time", schedule.time.toString())
                .put("minutes", schedule.minutes).put("days", schedule.repeat.sumOf { 1 shl it.ordinal })
                .put("block", schedule.block).put("remind", schedule.remind))
        }
        prefs.edit().putString("study_schedules", array.toString()).apply()
        arm()
        PlannerWidgetProvider.refresh(context, prefs)
    }

    fun arm() {
        val now = ZonedDateTime.now()
        val next = readSchedules(prefs).asSequence().filter { it.remind }
            .mapNotNull { it.nextStart(now) }.minOrNull()
        val intent = Intent(context, PlannerReceiver::class.java).setAction(REMIND)
        if (next != null) {
            val pending = PendingIntent.getBroadcast(context, 28, intent.putExtra("when", next.toEpochMilli()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            scheduleOrbitAlarm(alarm, AlarmManager.RTC_WAKEUP, next.toEpochMilli(), pending)
        } else {
            PendingIntent.getBroadcast(context, 28, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let(alarm::cancel)
        }
    }

    fun notifyAt(timestamp: Long) {
        val age = System.currentTimeMillis() - timestamp
        if (age !in 0..MINUTE * 60) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val start = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        val names = readSchedules(prefs).filter {
            it.remind && it.on(start.toLocalDate()) && it.start(start.toLocalDate(), start.zone).toEpochMilli() == timestamp
        }.map { it.name }
        if (names.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("study_plan", "Study reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 28, Intent(context, MainActivity::class.java)
            .setAction(MainActivity.SHOW_PLANNER).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        manager.notify(28, Notification.Builder(context, "study_plan").setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("Time to study").setContentText(names.joinToString(" · ")).setContentIntent(open)
            .setAutoCancel(true).build())
    }

    companion object {
        const val REMIND = "dev.stillpoint.focus.PLAN_REMINDER"
    }
}

internal fun scheduleOrbitAlarm(alarm: AlarmManager, type: Int, time: Long, intent: PendingIntent) {
    if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
        try {
            alarm.setExactAndAllowWhileIdle(type, time, intent)
            return
        } catch (_: SecurityException) {
            // Access can change while the app is running.
        }
    }
    alarm.setAndAllowWhileIdle(type, time, intent)
}

class PlannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = FocusStore(context)
        val planner = StudyPlanner(context, store.prefs)
        if (intent.action == StudyPlanner.REMIND) {
            planner.notifyAt(intent.getLongExtra("when", 0))
        } else {
            store.restoreAlarm()
            store.syncStudyNotification()
            FocusWidgetProvider.refresh(context, store::sessionSnapshot)
            PlannerWidgetProvider.refresh(context, store.prefs)
        }
        planner.arm()
    }
}
