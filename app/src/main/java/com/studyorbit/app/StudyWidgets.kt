package com.studyorbit.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class FocusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateFocusWidgets(context, manager, appWidgetIds, FocusStore(context).session())
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH_FOCUS) refresh(context) { FocusStore(context).session() }
    }

    override fun onDisabled(context: Context) {
        cancelFocusRefresh(context)
    }

    companion object {
        internal fun refresh(context: Context, session: () -> Session?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FocusWidgetProvider::class.java))
            if (ids.isNotEmpty()) updateFocusWidgets(context, manager, ids, session())
        }
    }
}

private fun updateFocusWidgets(context: Context, manager: AppWidgetManager, ids: IntArray, session: Session?) {
    manager.updateAppWidget(ids, focusWidget(context, session))

    // The daily goal restarts at midnight even when no session event arrives.
    val pending = PendingIntent.getBroadcast(context, 0, focusRefreshIntent(context), PendingIntent.FLAG_IMMUTABLE)
    context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, nextMidnight(), pending)
}

private fun focusWidget(context: Context, session: Session?): RemoteViews {
    val store = FocusStore(context)
    val today = LocalDate.now()
    val total = store.history().filter { it.day == today }.sumOf { it.minutes }
    val goal = store.goalMinutes.coerceAtLeast(1)
    val reached = total >= goal
    val mode = TimerMode.entries.getOrElse(store.prefs.getInt("timer_mode", 0)) { TimerMode.TIMER }
    val stopwatch = (session?.mode ?: mode) == TimerMode.STOPWATCH
    val running = session?.running == true
    val onBreak = session?.phase == Phase.BREAK

    val status = when {
        session == null -> R.string.widget_focus_idle
        !running -> R.string.mini_timer_paused
        stopwatch -> R.string.widget_stopwatch_running
        onBreak -> R.string.widget_break_running
        else -> R.string.widget_focus_running
    }
    val dot = when {
        session == null || onBreak && running -> R.drawable.widget_dot_ready
        !running -> R.drawable.widget_dot_paused
        else -> R.drawable.widget_dot_live
    }
    val goalText = if (reached) context.getString(R.string.widget_goal_reached)
        else context.getString(R.string.widget_goal_progress, total, goal)
    val goalSpoken = if (reached) context.getString(R.string.widget_goal_reached_cd, total)
        else context.getString(R.string.widget_goal_cd, total, goal)

    return RemoteViews(context.packageName, R.layout.widget_focus).apply {
        setOnClickPendingIntent(android.R.id.background, openTab(context, MainActivity.SHOW_FOCUS))
        setViewVisibility(R.id.widget_clock, if (running) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_value, if (running) View.GONE else View.VISIBLE)
        if (session != null && running) {
            val base = session.sampledAt + if (stopwatch) -session.remaining else session.remaining
            setChronometerCountDown(R.id.widget_clock, !stopwatch)
            setChronometer(R.id.widget_clock, base, null, true)
        } else {
            // Idle shows the length the next session will have; paused shows what is left.
            val shown = session?.remaining ?: if (stopwatch) 0L else store.focusMinutes * MINUTE
            setChronometer(R.id.widget_clock, SystemClock.elapsedRealtime(), null, false)
            setTextViewText(R.id.widget_value, timerDigits(shown, stopwatch))
            setTextColor(R.id.widget_value, context.getColor(
                if (session == null) R.color.widget_text else R.color.widget_accent
            ))
        }
        setImageViewResource(R.id.widget_dot, dot)
        setTextViewText(R.id.widget_status, context.getString(status))
        setProgressBar(R.id.widget_goal_bar, 100, (total * 100 / goal).coerceIn(0, 100), false)
        setTextViewText(R.id.widget_goal_text, goalText)
        setContentDescription(android.R.id.background, context.getString(status) + ". " + goalSpoken)
    }
}

private const val REFRESH_FOCUS = "com.studyorbit.app.REFRESH_FOCUS_WIDGET"

private fun focusRefreshIntent(context: Context) =
    Intent(context, FocusWidgetProvider::class.java).setAction(REFRESH_FOCUS)

private fun cancelFocusRefresh(context: Context) {
    val pending = PendingIntent.getBroadcast(
        context, 0, focusRefreshIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
    ) ?: return
    context.getSystemService(AlarmManager::class.java).cancel(pending)
}

class PlannerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updatePlannerWidgets(context, manager, appWidgetIds, FocusStore(context).prefs)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH_PLANNER) refresh(context, FocusStore(context).prefs)
    }

    override fun onDisabled(context: Context) {
        cancelPlannerRefresh(context)
    }

    companion object {
        internal fun refresh(context: Context, prefs: SharedPreferences) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PlannerWidgetProvider::class.java))
            if (ids.isNotEmpty()) updatePlannerWidgets(context, manager, ids, prefs)
        }
    }
}

private fun updatePlannerWidgets(
    context: Context, manager: AppWidgetManager, ids: IntArray, prefs: SharedPreferences
) {
    val now = ZonedDateTime.now()
    val next = readSchedules(prefs).asSequence().mapNotNull { schedule ->
        schedule.nextStart(now)?.let { start -> schedule to start }
    }.minByOrNull { (_, start) -> start }
    val views = RemoteViews(context.packageName, R.layout.widget_planner).apply {
        setOnClickPendingIntent(android.R.id.background, openTab(context, MainActivity.SHOW_PLANNER))
        if (next == null) {
            val empty = context.getString(R.string.widget_planner_empty)
            val invitation = context.getString(R.string.plan_a_session)
            setTextViewText(R.id.widget_pill_text, context.getString(R.string.widget_planner_heading))
            setViewVisibility(R.id.widget_chip, View.GONE)
            setTextViewText(R.id.widget_hero, empty)
            setTextViewText(R.id.widget_value, invitation)
            setTextColor(R.id.widget_value, context.getColor(R.color.widget_planner_accent))
            setContentDescription(android.R.id.background, "$empty. $invitation")
        } else {
            val (schedule, start) = next
            val day = plannerDayLabel(context, start.atZone(now.zone), now)
            val time = DateFormat.getTimeFormat(context).format(Date.from(start))
            val duration = context.resources.getQuantityString(
                R.plurals.widget_duration, schedule.minutes, schedule.minutes
            )
            setTextViewText(R.id.widget_pill_text, day)
            setViewVisibility(R.id.widget_chip, View.VISIBLE)
            setTextViewText(R.id.widget_chip, duration)
            setTextViewText(R.id.widget_hero, time)
            setTextViewText(R.id.widget_value, schedule.name)
            setTextColor(R.id.widget_value, context.getColor(R.color.widget_text))
            setContentDescription(android.R.id.background,
                context.getString(R.string.widget_planner_cd, schedule.name, day, time, duration))
        }
    }
    manager.updateAppWidget(ids, views)

    // The next session can change even when reminders are off, and "Today" or
    // "Tomorrow" must roll over at midnight.
    val refreshAt = next?.second
    if (refreshAt == null) {
        cancelPlannerRefresh(context)
    } else {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 0, plannerRefreshIntent(context), PendingIntent.FLAG_IMMUTABLE)
        alarm.set(AlarmManager.RTC, minOf(refreshAt.toEpochMilli(), nextMidnight()), pending)
    }
}

private fun plannerDayLabel(context: Context, start: ZonedDateTime, now: ZonedDateTime): String {
    val today = now.toLocalDate()
    return when (start.toLocalDate()) {
        today -> context.getString(R.string.widget_day_today)
        today.plusDays(1) -> context.getString(R.string.widget_day_tomorrow)
        else -> DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), Locale.getDefault()
        ).format(start)
    }
}

private const val REFRESH_PLANNER = "com.studyorbit.app.REFRESH_PLANNER_WIDGET"

private fun plannerRefreshIntent(context: Context) =
    Intent(context, PlannerWidgetProvider::class.java).setAction(REFRESH_PLANNER)

private fun cancelPlannerRefresh(context: Context) {
    val pending = PendingIntent.getBroadcast(
        context, 0, plannerRefreshIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
    ) ?: return
    context.getSystemService(AlarmManager::class.java).cancel(pending)
}

private fun openTab(context: Context, action: String) = PendingIntent.getActivity(
    context, 0, Intent(context, MainActivity::class.java).setAction(action)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE
)
