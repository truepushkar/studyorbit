package com.studyorbit.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.Trace
import android.view.accessibility.AccessibilityManager
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

const val YOUTUBE = "com.google.android.youtube"

data class DeviceApp(val id: String, val name: String, val launcher: ComponentName)

class DeviceApps(private val context: Context) {
    private val pm get() = context.packageManager
    private val protectedApps by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val dial = Intent(Intent.ACTION_DIAL)
        val essential = pm.queryIntentActivities(home, 0) + pm.queryIntentActivities(dial, 0)
        essential.map { it.activityInfo.packageName }.toSet() +
            setOf(context.packageName, "com.android.settings", "com.android.systemui", "android")
    }

    fun canBlock(pkg: String) = pkg !in protectedApps

    suspend fun installed(): List<DeviceApp> {
        val job = currentCoroutineContext()
        return pm.queryIntentActivities(launcherIntent(), 0)
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull {
                job.ensureActive()
                appFromLauncher(it)
            }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun configuredApps(packages: Set<String>): List<DeviceApp> {
        val job = currentCoroutineContext()
        if (packages.isEmpty()) return emptyList()
        val intent = launcherIntent()
        if (packages.size == 1) intent.setPackage(packages.first())
        return pm.queryIntentActivities(intent, 0).asSequence()
            .filter { it.activityInfo.packageName in packages }
            .distinctBy { it.activityInfo.packageName }
            .mapNotNull {
                job.ensureActive()
                appFromLauncher(it)
            }.sortedBy { it.name.lowercase() }.toList()
    }

    fun loadIcon(app: DeviceApp): Drawable? =
        runCatching { pm.getActivityIcon(app.launcher) }.getOrNull()
            ?: runCatching { pm.getApplicationIcon(app.id) }.getOrNull()

    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun appFromLauncher(info: ResolveInfo): DeviceApp? {
        val activity = info.activityInfo
        val pkg = activity.packageName
        if (!canBlock(pkg)) return null
        return DeviceApp(pkg, info.loadLabel(pm).toString(), ComponentName(pkg, activity.name))
    }

    fun label(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun hasBlockAccess(): Boolean {
        val component = ComponentName(context, DistractionService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == component }
    }

    @Suppress("DEPRECATION")
    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun screenReady(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    @Suppress("DEPRECATION")
    suspend fun usageSince(firstDay: LocalDate): Map<String, Long>? {
        if (!hasUsageAccess()) return null
        val zone = ZoneId.systemDefault()
        val midnight = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterday = firstDay.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val stats = context.getSystemService(UsageStatsManager::class.java)
        Trace.beginSection("StudyOrbit.usage.query")
        val events = try {
            stats.queryRelevantEvents(yesterday, now, forScreenTime = true)
        } finally {
            Trace.endSection()
        } ?: return null
        val totals = mutableMapOf<String, Long>()
        var app: String? = null
        var screenOn = true
        var locked = false
        var since = yesterday

        fun accrue(until: Long) {
            val current = app
            val duration = (until - maxOf(since, midnight)).coerceAtLeast(0)
            if (current != null && screenOn && !locked && duration > 0) {
                totals[current] = (totals[current] ?: 0L) + duration
            }
            since = until
        }

        val event = UsageEvents.Event()
        val job = currentCoroutineContext()
        var processed = 0
        while (events.hasNextEvent() && events.getNextEvent(event)) {
            if (processed++ % 128 == 0) job.ensureActive()
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> { accrue(event.timeStamp); app = event.packageName }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (app == event.packageName) {
                    accrue(event.timeStamp)
                    app = null
                }
                UsageEvents.Event.SCREEN_INTERACTIVE -> { accrue(event.timeStamp); screenOn = true }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { accrue(event.timeStamp); screenOn = false }
                UsageEvents.Event.KEYGUARD_SHOWN -> { accrue(event.timeStamp); locked = true }
                UsageEvents.Event.KEYGUARD_HIDDEN -> { accrue(event.timeStamp); locked = false }
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> {
                    // A shutdown record can carry an old timestamp. Avoid counting through the reboot.
                    accrue(maxOf(event.timeStamp, since))
                    app = null
                }
            }
        }
        accrue(now)
        return totals
    }
}

internal fun UsageStatsManager.queryRelevantEvents(start: Long, end: Long, forScreenTime: Boolean = false): UsageEvents? {
    val begin = start.coerceAtLeast(0)
    if (begin >= end) return null
    if (Build.VERSION.SDK_INT < 35) return queryEvents(begin, end)
    val query = UsageEventsQuery.Builder(begin, end)
    if (forScreenTime) {
        query.setEventTypes(
            UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.SCREEN_INTERACTIVE, UsageEvents.Event.SCREEN_NON_INTERACTIVE,
            UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.KEYGUARD_HIDDEN,
            UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP
        )
    } else {
        query.setEventTypes(UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED)
    }
    return queryEvents(query.build())
}
