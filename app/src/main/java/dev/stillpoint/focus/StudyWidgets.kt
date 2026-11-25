package dev.stillpoint.focus

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
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.time.ZonedDateTime
import java.util.Date

class FocusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val session = FocusStore(context).session()
        manager.updateAppWidget(appWidgetIds, focusWidget(context, session))
    }

    companion object {
        internal fun refresh(context: Context, session: () -> Session?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FocusWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, focusWidget(context, session()))
        }
    }
}

private fun focusWidget(context: Context, session: Session?) =
    RemoteViews(context.packageName, R.layout.widget_focus).apply {
        setOnClickPendingIntent(android.R.id.background, openTab(context, MainActivity.SHOW_FOCUS))
        val stopwatch = session?.mode == TimerMode.STOPWATCH
        val running = session?.running == true
        setViewVisibility(R.id.widget_clock, if (running) View.VISIBLE else View.GONE)
        setViewVisibility(R.id.widget_value, if (running) View.GONE else View.VISIBLE)
        if (session != null && running) {
            val base = session.sampledAt + if (stopwatch) -session.remaining else session.remaining
            setChronometerCountDown(R.id.widget_clock, !stopwatch)
            setChronometer(R.id.widget_clock, base, null, true)
        } else {
            setChronometer(R.id.widget_clock, SystemClock.elapsedRealtime(), null, false)
            setTextViewTextSize(R.id.widget_value, TypedValue.COMPLEX_UNIT_SP, if (session == null) 20f else 30f)
            setTextViewText(R.id.widget_value, session?.let { timerDigits(it.remaining, stopwatch) }
                ?: context.getString(R.string.widget_focus_idle))
        }
        setTextViewText(R.id.widget_detail, context.getString(when {
            session == null -> R.string.widget_open_focus
            !running -> R.string.mini_timer_paused
            stopwatch -> R.string.widget_stopwatch_running
            session.phase == Phase.BREAK -> R.string.widget_break_running
            else -> R.string.widget_focus_running
        }))
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
            setTextViewText(R.id.widget_value, context.getString(R.string.widget_planner_empty))
            setTextViewText(R.id.widget_detail, context.getString(R.string.widget_open_planner))
        } else {
            val (schedule, start) = next
            val date = Date.from(start)
            val duration = context.resources.getQuantityString(
                R.plurals.widget_duration, schedule.minutes, schedule.minutes
            )
            setTextViewText(R.id.widget_value, schedule.name)
            setTextViewText(R.id.widget_detail, context.getString(
                R.string.widget_planner_schedule,
                DateFormat.getMediumDateFormat(context).format(date),
                DateFormat.getTimeFormat(context).format(date),
                duration
            ))
        }
    }
    manager.updateAppWidget(ids, views)

    // The next session can change even when reminders are off.
    val refreshAt = next?.second
    if (refreshAt == null) {
        cancelPlannerRefresh(context)
    } else {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 0, plannerRefreshIntent(context), PendingIntent.FLAG_IMMUTABLE)
        alarm.set(AlarmManager.RTC, refreshAt.toEpochMilli(), pending)
    }
}

private const val REFRESH_PLANNER = "dev.stillpoint.focus.REFRESH_PLANNER_WIDGET"

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
