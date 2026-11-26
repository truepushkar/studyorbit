package com.studyorbit.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WidgetFocusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = FocusStore(this)
        if (savedInstanceState == null && !store.prefs.getBoolean("strict_preference", false)) {
            store.start(Phase.FOCUS, store.focusMinutes)
        }
        startActivity(screenIntent(this, MainActivity.SHOW_FOCUS))
        finish()
    }
}

class FocusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val store = FocusStore(context)
        refresh(context, store::sessionSnapshot)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH_FOCUS) {
            val store = FocusStore(context)
            refresh(context, store::sessionSnapshot)
        }
    }

    override fun onDisabled(context: Context) {
        focusRefreshIntent(context, PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
        }
    }

    companion object {
        internal fun refresh(context: Context, session: () -> Session?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, FocusWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val today = LocalDate.now()
            val store = FocusStore(context)
            val minutes = store.history().filter { it.day == today }.sumOf { it.minutes }
            val canStart = session() == null && !store.prefs.getBoolean("strict_preference", false)
            val open = openScreen(context, MainActivity.SHOW_FOCUS)
            val start = PendingIntent.getActivity(context, 0,
                Intent(context, WidgetFocusActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val views = RemoteViews(context.packageName, R.layout.widget_focus).apply {
                setTextViewText(R.id.widget_value, usageLabel(minutes * MINUTE))
                setTextViewText(R.id.widget_action, context.getString(
                    if (canStart) R.string.widget_start_focus else R.string.widget_open_focus
                ))
                setOnClickPendingIntent(android.R.id.background, open)
                setOnClickPendingIntent(R.id.widget_action, if (canStart) start else open)
            }
            manager.updateAppWidget(ids, views)
            val alarm = context.getSystemService(AlarmManager::class.java)
            focusRefreshIntent(context, 0)?.let { alarm.set(AlarmManager.RTC, nextMidnight(), it) }
        }
    }
}

private const val REFRESH_FOCUS = "com.studyorbit.app.REFRESH_FOCUS_WIDGET"

private fun focusRefreshIntent(context: Context, flags: Int): PendingIntent? =
    PendingIntent.getBroadcast(context, 0,
        Intent(context, FocusWidgetProvider::class.java).setAction(REFRESH_FOCUS),
        flags or PendingIntent.FLAG_IMMUTABLE)

class UnlocksWidgetProvider : UsageWidgetProvider()
class UsageRingWidgetProvider : UsageWidgetProvider()
class UsageDetailsWidgetProvider : UsageWidgetProvider()

abstract class UsageWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH_USAGE) update(context)
    }

    private fun update(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updates.withLock { updateUsageWidgets(app) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val REFRESH_USAGE = "com.studyorbit.app.REFRESH_USAGE_WIDGETS"
        private val updates = Mutex()

        internal fun requestRefresh(context: Context) {
            context.sendBroadcast(Intent(context, UsageRingWidgetProvider::class.java).setAction(REFRESH_USAGE))
        }
    }
}

private data class UsageCategory(val tag: AppTag, val color: Int, val label: Int)
private val usageCategories = listOf(
    UsageCategory(AppTag.PRODUCTIVE, R.color.widget_mint, R.string.widget_productive),
    UsageCategory(AppTag.DISTRACTING, R.color.widget_coral, R.string.widget_distracting),
    UsageCategory(AppTag.UNTAGGED, R.color.widget_lavender, R.string.widget_other)
)

private suspend fun updateUsageWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    val unlocks = manager.getAppWidgetIds(ComponentName(context, UnlocksWidgetProvider::class.java))
    val ring = manager.getAppWidgetIds(ComponentName(context, UsageRingWidgetProvider::class.java))
    val details = manager.getAppWidgetIds(ComponentName(context, UsageDetailsWidgetProvider::class.java))
    if (unlocks.isEmpty() && ring.isEmpty() && details.isEmpty()) return

    val apps = DeviceApps(context)
    val report = try {
        apps.usageReportSince(LocalDate.now())
    } catch (_: SecurityException) {
        null
    }
    val open = openScreen(context, MainActivity.SHOW_PROGRESS)
    if (unlocks.isNotEmpty()) {
        manager.updateAppWidget(unlocks, RemoteViews(context.packageName, R.layout.widget_unlocks).apply {
            setTextViewText(R.id.widget_value, report?.unlocks?.toString() ?: context.getString(R.string.widget_unavailable))
            setTextViewText(R.id.widget_caption, context.getString(
                if (report == null) R.string.widget_allow_usage else R.string.widget_unlocks_caption
            ))
            setOnClickPendingIntent(android.R.id.background, open)
        })
    }

    if (ring.isEmpty() && details.isEmpty()) return
    val store = FocusStore(context)
    val installed = if (report != null) apps.installed().mapTo(mutableSetOf()) { it.id } else emptySet()
    val byTag = report?.appTimes.orEmpty().entries.filter { it.key in installed }
        .groupingBy { store.rule(it.key).tag }
        .fold(0L) { sum, entry -> sum + entry.value }
    val amounts = usageCategories.map { byTag[it.tag] ?: 0L }
    val chart = usageRing(context, amounts)
    val total = amounts.sum()
    val value = if (report == null) context.getString(R.string.widget_unavailable) else usageLabel(total)
    val caption = context.getString(if (report == null) R.string.widget_allow_usage else R.string.widget_today_usage)

    for ((ids, layout) in listOf(ring to R.layout.widget_usage_ring, details to R.layout.widget_usage_details)) {
        if (ids.isEmpty()) continue
        manager.updateAppWidget(ids, RemoteViews(context.packageName, layout).apply {
            setOnClickPendingIntent(android.R.id.background, open)
            setImageViewBitmap(R.id.widget_ring, chart)
            setTextViewText(R.id.widget_value, value)
            setTextViewText(R.id.widget_caption, caption)
            if (layout == R.layout.widget_usage_details) {
                setViewVisibility(R.id.widget_legend, if (report == null) View.GONE else View.VISIBLE)
                listOf(R.id.widget_productive, R.id.widget_distracting, R.id.widget_other)
                    .forEachIndexed { index, id ->
                        setTextViewText(id, context.getString(R.string.widget_category_usage,
                            context.getString(usageCategories[index].label), usageLabel(amounts[index])))
                    }
            }
        })
    }
}

private fun usageRing(context: Context, amounts: List<Long>): Bitmap {
    val bitmap = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bounds = RectF(17f, 17f, 343f, 343f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 15f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        color = context.getColor(R.color.widget_track)
    }
    canvas.drawOval(bounds, paint)
    val total = amounts.sum().toFloat()
    val populated = amounts.count { it > 0L }
    var angle = -90f
    amounts.forEachIndexed { index, amount ->
        if (amount > 0L) {
            val sweep = 360f * amount / total
            val gap = if (populated > 1) minOf(9f, sweep / 2f) else 0f
            paint.color = context.getColor(usageCategories[index].color)
            canvas.drawArc(bounds, angle + gap / 2f, sweep - gap, false, paint)
            angle += sweep
        }
    }
    return bitmap
}

private fun screenIntent(context: Context, action: String) =
    Intent(context, MainActivity::class.java).setAction(action)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

private fun openScreen(context: Context, action: String) =
    PendingIntent.getActivity(context, 0, screenIntent(context, action), PendingIntent.FLAG_IMMUTABLE)
