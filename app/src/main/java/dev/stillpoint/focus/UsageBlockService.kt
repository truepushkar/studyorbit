package dev.stillpoint.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Whole-app protection. Only foreground package events are read. */
class UsageBlockService : Service() {
    private val store by lazy { FocusStore(this) }
    private val apps by lazy { DeviceApps(this) }
    private val systemUsage by lazy { getSystemService(UsageStatsManager::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private var monitor: Job? = null
    private var planDirty = true
    private var resetCursor = true
    private var cover: LinearLayout? = null
    private var covered: Pair<String, String>? = null
    private val windowManager get() = getSystemService(WindowManager::class.java)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "study_schedules" || key == ENABLED ||
            key.startsWith("focus:") || key.startsWith("limit:") || isSessionPreference(key)) {
            planDirty = true
            changes.trySend(Unit)
        }
    }
    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            resetCursor = true
            planDirty = true
            if (intent.action == Intent.ACTION_SCREEN_OFF) clearCover()
            changes.trySend(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(this, screenChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            store.prefs.edit().putBoolean(ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (monitor == null) {
            startForeground(42, notification())
            if (!canProtect()) { stopSelf(); return START_NOT_STICKY }
            running = true
            monitor = scope.launch { protect() }
        }
        return START_STICKY
    }

    private fun canProtect() = store.prefs.getBoolean(ENABLED, false) &&
        Settings.canDrawOverlays(this) && apps.hasUsageAccess()

    private suspend fun protect() {
        var rules = emptyMap<String, AppRule>()
        var schedules = emptyList<StudySchedule>()
        var schedule: ScheduledFocus? = null
        var foreground: String? = null
        var cursor = 0L
        var usage = emptyMap<String, Long>()
        var measuredAt = 0L
        var day: LocalDate? = null
        while (currentCoroutineContext().isActive) {
            val (permitted, screenReady) = withContext(Dispatchers.IO) { canProtect() to apps.screenReady() }
            if (!permitted) { stopSelf(); return }
            if (planDirty) {
                planDirty = false
                rules = store.configuredRules().filterValues { it.focusOnly || it.dailyMinutes > 0 }
                schedules = readSchedules(store.prefs)
                schedule = null
            }
            if (resetCursor) {
                resetCursor = false
                cursor = 0L
                foreground = null
                measuredAt = 0L
            }
            val now = System.currentTimeMillis()
            val session = store.session()
            val localDay = LocalDate.now()
            if (localDay != day) { day = localDay; measuredAt = 0L; usage = emptyMap() }
            val focusRules = rules.values.any { it.focusOnly }
            if (focusRules && (schedule == null || schedule.nextChange?.toEpochMilli()?.let { it <= now } == true)) {
                schedule = scheduledFocus(schedules, ZonedDateTime.now())
            }
            val studying = (session?.running == true && session.phase == Phase.FOCUS) || schedule?.active == true
            if (!screenReady || rules.values.none { it.dailyMinutes > 0 || (it.focusOnly && studying) }) {
                clearCover()
                cursor = 0L
                foreground = null
                val timeline = session?.takeIf { it.running && it.mode != TimerMode.STOPWATCH }?.displayTime()
                val scheduled = schedule?.nextChange?.toEpochMilli()?.minus(now)
                val next = listOfNotNull(timeline, scheduled).minOrNull()
                if (next == null) changes.receive() else withTimeoutOrNull(next.coerceAtLeast(100)) { changes.receive() }
                continue
            }

            val current = withContext(Dispatchers.IO) {
                try {
                    // Include a short overlap for events Android delivers just after a query.
                    readForeground(if (cursor == 0L) now - 2 * 60 * MINUTE else cursor - 2_000, now + 1, foreground)
                } catch (_: SecurityException) { null }
            }
            foreground = current
            cursor = now + 1
            val rule = current?.let(rules::get)
            if (current != null && rule != null && withContext(Dispatchers.IO) { apps.canBlock(current) }) {
                val limit = rule.dailyMinutes * MINUTE
                val untilLimit = limit - usage.getOrDefault(current, 0)
                if (limit > 0 && (measuredAt == 0L || now - measuredAt >= untilLimit.coerceIn(2_000L, 30_000L))) {
                    usage = withContext(Dispatchers.IO) {
                        try { apps.usageSince(localDay).orEmpty() } catch (_: SecurityException) { emptyMap() }
                    }
                    measuredAt = now
                }
                val reason = when {
                    rule.focusOnly && studying -> "Blocked during focus"
                    limit > 0 && usage.getOrDefault(current, 0) >= limit -> "Daily limit reached"
                    else -> null
                }
                if (reason == null) clearCover()
                else if (covered != (current to reason)) {
                    val label = withContext(Dispatchers.IO) { apps.label(current) }
                    if (!resetCursor && !planDirty) showCover(current, label, reason)
                }
            } else clearCover()
            withTimeoutOrNull(850) { changes.receive() }
        }
    }

    @Suppress("DEPRECATION")
    private fun readForeground(start: Long, end: Long, previous: String?): String? {
        val events = systemUsage.queryEvents(start.coerceAtMost(end), end) ?: return null
        val event = UsageEvents.Event()
        var active = previous
        while (events.hasNextEvent() && events.getNextEvent(event)) {
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> active = event.packageName
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (active == event.packageName) active = null
            }
        }
        return active
    }

    private fun showCover(pkg: String, label: String, reason: String) {
        clearCover()
        val panel = blockCoverView(this, label, reason, store.session(), advanced = false,
            primaryLabel = "Back to Focus", primaryAction = {
                val open = Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.SHOW_FOCUS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                runCatching { startActivity(open) }
            })
        val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE)
        try {
            windowManager.addView(panel, params)
            cover = panel
            covered = pkg to reason
        } catch (_: RuntimeException) {
            // Permission may change between checking and attaching the window.
        }
    }

    private fun clearCover() {
        cover?.let { windowManager.removeView(it) }
        cover = null
        covered = null
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app_protection", "App protection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 42, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 43, Intent(this, UsageBlockService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "app_protection").setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("StudyOrbit app protection").setContentText("Your selected app rules are active.")
            .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop protection", stop).build()).build()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        changes.close()
        clearCover()
        store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
        unregisterReceiver(screenChanges)
        super.onDestroy()
    }

    companion object {
        const val ENABLED = "usage_blocking_enabled"
        private const val STOP = "dev.stillpoint.focus.STOP_APP_PROTECTION"
        internal var running by mutableStateOf(false)
            private set
    }
}
