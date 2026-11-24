package dev.stillpoint.focus

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.LruCache
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DistractionService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: FocusStore
    private lateinit var apps: DeviceApps
    private var shade: LinearLayout? = null
    private var covered: String? = null
    private var coveredReason: String? = null
    private var videoBackAt = 0L
    private var videoBackPackage: String? = null
    private var usageReadAt = -1L
    private var usageDay: LocalDate? = null
    private var usage: Map<String, Long>? = null
    private var usageJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var focusPackages = emptySet<String>()
    private var dailyLimits = emptyMap<String, Int>()
    private var videoModes = emptyMap<VideoTarget, Int>()
    private var schedules = emptyList<StudySchedule>()
    private var scheduleState: ScheduledFocus? = null
    private var content = ContentRules()
    private var coverSmallWindows = true
    private var topologyDirty = true
    private var hasSeparateWindows = false
    private var scanJob: Job? = null
    private var scanPending = false
    private var scanRevision = 0L
    private var lastForegroundEvent: Int? = null
    private var lastStudying = false
    private var contentWatch = emptySet<String>()
    private var hasConfiguredRules = false
    private val adultResults = LruCache<String, Boolean>(16)
    private val adultLookups = mutableMapOf<String, Job>()
    private var rulesDirty = true
    private var screenActive = false
    private var nextCheckAt = Long.MAX_VALUE
    private var nextWakeDelay = Long.MAX_VALUE
    private var receiverRegistered = false
    private val windowCovers = mutableMapOf<Int, WindowCover>()
    private val adultDomains by lazy { AdultDomains(this) }
    private val manager get() = getSystemService(WindowManager::class.java)

    private val checkNow = Runnable {
        nextCheckAt = Long.MAX_VALUE
        nextWakeDelay = Long.MAX_VALUE
        Trace.beginSection("StudyOrbit.blocker.enforce")
        try {
            enforce()
        } finally {
            Trace.endSection()
        }
        if (nextWakeDelay != Long.MAX_VALUE) requestCheck(nextWakeDelay)
    }
    private val settingsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (isRulePreference(key)) {
            rulesDirty = true
            scanRevision++
            requestCheck()
        } else if (hasConfiguredRules && isSessionPreference(key)) {
            scanRevision++
            requestCheck()
        }
    }
    private val systemChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> stopChecks()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenActive = true
                    topologyDirty = true
                    scanRevision++
                    requestCheck()
                }
                else -> {
                    scanRevision++
                    scheduleState = null
                    usageReadAt = -1
                    usage = null
                    usageJob?.cancel()
                    usageJob = null
                    requestCheck()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (::store.isInitialized) store.prefs.unregisterOnSharedPreferenceChangeListener(settingsChanged)
        store = FocusStore(this)
        apps = DeviceApps(this)
        store.prefs.registerOnSharedPreferenceChangeListener(settingsChanged)
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            }
            ContextCompat.registerReceiver(this, systemChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        rulesDirty = true
        topologyDirty = true
        scanRevision++
        screenActive = true
        requestCheck()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!screenActive || event == null) return
        if (!hasConfiguredRules && !rulesDirty) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (event.packageName?.toString() in contentWatch) requestCheck(150)
        } else {
            topologyDirty = true
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.windowId != lastForegroundEvent) {
                lastForegroundEvent = event.windowId
                scanRevision++
            }
            requestCheck(90)
        }
    }

    override fun onInterrupt() {
        scanRevision++
        clearShields()
        if (hasConfiguredRules) requestCheck(150)
    }

    private fun clearShields() {
        hideShield()
        clearWindowCovers()
    }

    override fun onDestroy() {
        stopChecks()
        scope.cancel()
        if (receiverRegistered) unregisterReceiver(systemChanges)
        if (::store.isInitialized) store.prefs.unregisterOnSharedPreferenceChangeListener(settingsChanged)
        super.onDestroy()
    }

    private fun stopChecks() {
        screenActive = false
        scanRevision++
        scanJob?.cancel()
        scanJob = null
        scanPending = false
        lastForegroundEvent = null
        handler.removeCallbacks(checkNow)
        nextCheckAt = Long.MAX_VALUE
        nextWakeDelay = Long.MAX_VALUE
        usageJob?.cancel()
        usageJob = null
        usageReadAt = -1
        usage = null
        adultLookups.values.forEach { it.cancel() }
        adultLookups.clear()
        adultResults.evictAll()
        updateContentWatch(emptySet())
        videoBackPackage = null
        clearShields()
    }

    private fun requestCheck(delay: Long = 0L) {
        if (!screenActive) return
        val whenToCheck = SystemClock.uptimeMillis() + delay.coerceAtLeast(0L)
        if (whenToCheck >= nextCheckAt) return
        handler.removeCallbacks(checkNow)
        nextCheckAt = whenToCheck
        handler.postAtTime(checkNow, whenToCheck)
    }

    private fun recheckIn(delay: Long) {
        nextWakeDelay = minOf(nextWakeDelay, delay.coerceAtLeast(1))
    }

    private fun isRulePreference(key: String?): Boolean = key == null || key.startsWith("focus:") ||
        key.startsWith("limit:") || key in rulePreferences

    private fun reloadRules() {
        rulesDirty = false
        val prefs = store.prefs
        val all = prefs.all
        focusPackages = all.keys.filter { it.startsWith("focus:") && all[it] == true }
            .mapTo(mutableSetOf()) { it.substringAfter(':') }
        dailyLimits = all.mapNotNull { (key, value) ->
            if (key.startsWith("limit:") && value is Int && value > 0) key.substringAfter(':') to value else null
        }.toMap()
        videoModes = videoTargets.associateWith { it.mode(prefs) }.filterValues { it != 0 }
        content = readContentRules(prefs)
        schedules = readSchedules(prefs).filter { it.block }
        scheduleState = null
        coverSmallWindows = prefs.getBoolean("cover_small_windows", true)
        topologyDirty = true
        hasConfiguredRules = focusPackages.isNotEmpty() || dailyLimits.isNotEmpty() || videoModes.isNotEmpty() ||
            content.browserStudy || content.youtubeStudy || content.adultFilter || content.blockedHosts.isNotEmpty()
    }

    private fun updateContentWatch(packages: Set<String>) {
        val changeEventTypes = contentWatch.isEmpty() != packages.isEmpty()
        contentWatch = packages
        if (!changeEventTypes) return
        val config = serviceInfo ?: return
        config.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (packages.isNotEmpty()) config.eventTypes = config.eventTypes or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        serviceInfo = config
    }

    private fun enforce() {
        if (!::store.isInitialized) return
        if (rulesDirty) reloadRules()
        if (!hasConfiguredRules) {
            updateContentWatch(emptySet())
            clearShields()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val session = store.session()
        val focusRules = focusPackages.isNotEmpty() || videoModes.containsValue(1) || content.browserStudy || content.youtubeStudy
        if (focusRules && session?.running == true && session.mode != TimerMode.STOPWATCH) {
            recheckIn(session.displayTime(now))
        }
        var scheduled = false
        if (focusRules && schedules.isNotEmpty()) {
            val wall = System.currentTimeMillis()
            val cached = scheduleState
            val schedule = if (cached == null || cached.nextChange?.toEpochMilli()?.let { it <= wall } == true) {
                scheduledFocus(schedules, ZonedDateTime.now()).also { scheduleState = it }
            } else cached
            scheduled = schedule.active
            schedule.nextChange?.let { recheckIn(it.toEpochMilli() - wall) }
        }
        val studying = scheduled || (session?.running == true && session.phase == Phase.FOCUS)
        if (studying != lastStudying) {
            lastStudying = studying
            scanRevision++
        }
        updateContentWatch(buildSet {
            if (content.adultFilter || content.blockedHosts.isNotEmpty() || (studying && content.browserStudy)) {
                addAll(supportedBrowserPackages)
            }
            if (studying && content.youtubeStudy) add(YOUTUBE)
            for ((target, mode) in videoModes) if (videoRuleApplies(mode, studying)) add(target.pkg)
        })
        val hasRules = dailyLimits.isNotEmpty() || videoModes.containsValue(2) ||
            content.adultFilter || content.blockedHosts.isNotEmpty() || (studying && focusRules)
        if (!hasRules) {
            videoBackPackage = null
            clearShields()
            return
        }
        val probeWindows = coverSmallWindows && (topologyDirty || hasSeparateWindows)
        if (scanJob != null) {
            scanPending = true
            return
        }
        val policy = ScanPolicy(studying, content, contentWatch, videoModes, focusPackages, dailyLimits.keys)
        val revision = scanRevision
        topologyDirty = false
        scanJob = scope.launch {
            val screen = withContext(Dispatchers.IO) {
                Trace.beginSection("StudyOrbit.blocker.scan")
                try {
                    captureScreen(policy, probeWindows)
                } catch (_: SecurityException) {
                    ScreenSnapshot(ready = false)
                } catch (_: IllegalStateException) {
                    // Android may revoke the accessibility connection during a scan.
                    ScreenSnapshot(ready = false)
                } finally {
                    Trace.endSection()
                }
            }
            scanJob = null
            if (!screenActive) return@launch
            if (revision == scanRevision) {
                Trace.beginSection("StudyOrbit.blocker.apply")
                try {
                    applyScreen(screen, policy)
                } finally {
                    Trace.endSection()
                }
            } else if (probeWindows) topologyDirty = true
            if (scanPending || revision != scanRevision) {
                scanPending = false
                requestCheck(40)
            }
        }
    }

    private fun applyScreen(screen: ScreenSnapshot, policy: ScanPolicy) {
        if (!screen.ready) {
            videoBackPackage = null
            clearShields()
            return
        }
        hasSeparateWindows = screen.windows != null
        val now = SystemClock.elapsedRealtime()
        val separate = screen.windows
        if (separate != null) {
            val seen = mutableSetOf<Int>()
            for (window in separate) {
                val bounds = window.bounds ?: continue
                val block = blockFor(window, policy, now) ?: continue
                seen += window.id
                showWindowCover(window.id, bounds, block.reason)
            }
            (windowCovers.keys - seen).forEach(::removeWindowCover)
            videoBackPackage = null
            hideShield()
            return
        }
        clearWindowCovers()
        val active = screen.foreground ?: return
        val foreground = active.pkg
        val block = blockFor(active, policy, now)
        if (block == null) {
            videoBackPackage = null
            hideShield()
        } else if (!block.exitPlayer) {
            videoBackPackage = null
            showShield(foreground, active.label, block.reason, block.allowBack)
        } else if (videoBackPackage != foreground) {
            // Try leaving the player before covering the app.
            hideShield()
            videoBackPackage = foreground
            videoBackAt = now
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) showShield(foreground, active.label, block.reason, allowBack = true)
            else requestCheck(700)
        } else if (now - videoBackAt >= 700) {
            showShield(foreground, active.label, block.reason, allowBack = true)
        } else {
            requestCheck(700 - (now - videoBackAt))
        }
    }

    private fun blockFor(window: ScreenWindow, policy: ScanPolicy, now: Long): Block? {
        if (!window.eligible) return null
        val foreground = window.pkg
        if (policy.studying && foreground in policy.focusPackages) return Block("Blocked during this focus session")
        val limit = dailyLimits[foreground]
        if (limit != null && overLimit(foreground, limit, now)) {
            return Block("Daily limit reached · $limit min")
        }
        if (window.contentBlock != null) return window.contentBlock
        return window.host?.let { policy.content.hostBlock(it, policy.studying, ::lookupAdultDomain) }
            ?.let { Block(it, allowBack = true) }
    }

    private fun observeWindow(root: AccessibilityNodeInfo, id: Int, bounds: Rect?, policy: ScanPolicy): ScreenWindow? {
        val foreground = root.packageName?.toString() ?: return null
        val relevant = (policy.studying && foreground in policy.focusPackages) ||
            foreground in policy.dailyPackages || foreground in policy.contentWatch
        if (!relevant || !apps.canBlock(foreground)) return ScreenWindow(id, bounds, foreground)
        val label = appLabels.get(foreground) ?: apps.label(foreground).also { appLabels.put(foreground, it) }
        val window = ScreenWindow(id, bounds, foreground, eligible = true, label = label)
        if (foreground !in policy.contentWatch || (policy.studying && foreground in policy.focusPackages)) return window
        val video = videoTargets.firstOrNull { it.pkg == foreground }
        val youtubeStudy = foreground == YOUTUBE && policy.studying && policy.content.youtubeStudy
        val videoEnabled = video != null && (youtubeStudy || videoRuleApplies(policy.videoModes[video] ?: 0, policy.studying))
        val checkWeb = hasBrowserSupport(foreground)
        val labels = when {
            checkWeb -> browserLabelIds(foreground)
            youtubeStudy -> youtubeLabelIds
            else -> emptySet()
        }
        val neededIds = if (youtubeStudy) youtubeStudyIds else labels
        val playerIds = if (videoEnabled) video?.ids.orEmpty() else emptySet()
        val views = if (videoEnabled || youtubeStudy || checkWeb) {
            visibleScreen(root, neededIds, labels, playerIds)
        } else emptyList()
        if (videoEnabled && views.any { it.id in playerIds }) {
            return window.copy(contentBlock = Block("${video.label} blocked", exitPlayer = true, allowBack = true))
        }
        if (checkWeb) return window.copy(host = browserHost(foreground, views))
        val reason = if (youtubeStudy) youtubeStudyBlock(views, policy.content.channels) else null
        return window.copy(contentBlock = reason?.let { Block(it, allowBack = true) })
    }

    private fun lookupAdultDomain(host: String): Boolean {
        adultResults.get(host)?.let { return it }
        adultDomains.cachedMatch(host)?.let { return it }
        if (host !in adultLookups && adultLookups.size < 4) {
            adultLookups[host] = scope.launch {
                val blocked = withContext(Dispatchers.IO) { adultDomains.loadAndMatch(host) }
                adultLookups.remove(host)
                if (screenActive) {
                    adultResults.put(host, blocked)
                    requestCheck()
                }
            }
        }
        return false
    }

    private fun captureScreen(policy: ScanPolicy, probeWindows: Boolean): ScreenSnapshot {
        if (!apps.screenReady()) return ScreenSnapshot(ready = false)
        if (probeWindows) {
            val applicationWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val pictureInPicture = applicationWindows.any { it.isInPictureInPictureMode }
            if (applicationWindows.size >= 2 || pictureInPicture) {
                val visible = applicationWindows.mapNotNull { window ->
                    val root = window.root ?: return@mapNotNull null
                    observeWindow(root, window.id, Rect().also(window::getBoundsInScreen), policy)
                }
                if (pictureInPicture || visible.map { it.pkg }.distinct().size >= 2) {
                    return ScreenSnapshot(windows = visible.filter { it.bounds?.isEmpty == false })
                }
            }
        }
        val root = rootInActiveWindow
        return ScreenSnapshot(foreground = root?.let { observeWindow(it, it.windowId, null, policy) })
    }

    private fun showWindowCover(id: Int, bounds: Rect, reason: String) {
        val existing = windowCovers[id]
        if (existing?.reason == reason && existing.bounds == bounds) return
        removeWindowCover(id)
        val view = shieldText(reason, 16f, "#E1D9F4").apply {
            setBackgroundColor(Color.rgb(35, 32, 55))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
        }
        val params = WindowManager.LayoutParams(
            bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply {
            @Suppress("RtlHardcoded")
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left
            y = bounds.top
        }
        try {
            manager.addView(view, params)
            windowCovers[id] = WindowCover(view, bounds, reason)
        } catch (_: WindowManager.BadTokenException) {
            // The user can revoke the overlay token.
        }
    }

    private fun removeWindowCover(id: Int) {
        windowCovers.remove(id)?.let { manager.removeView(it.view) }
    }

    private fun clearWindowCovers() = windowCovers.keys.toList().forEach(::removeWindowCover)

    private data class Block(val reason: String, val allowBack: Boolean = false, val exitPlayer: Boolean = false)
    private data class WindowCover(val view: TextView, val bounds: Rect, val reason: String)
    private data class ScanPolicy(
        val studying: Boolean, val content: ContentRules, val contentWatch: Set<String>,
        val videoModes: Map<VideoTarget, Int>, val focusPackages: Set<String>, val dailyPackages: Set<String>
    )
    private data class ScreenWindow(
        val id: Int, val bounds: Rect?, val pkg: String, val eligible: Boolean = false,
        val label: String = pkg, val host: String? = null, val contentBlock: Block? = null
    )
    private data class ScreenSnapshot(
        val ready: Boolean = true, val foreground: ScreenWindow? = null, val windows: List<ScreenWindow>? = null
    )
    private val appLabels = LruCache<String, String>(32)

    private fun videoRuleApplies(mode: Int, studying: Boolean) = mode == 2 || (mode == 1 && studying)

    private fun overLimit(pkg: String, minutes: Int, now: Long): Boolean {
        val day = LocalDate.now()
        if (usageDay != day) {
            usageDay = day
            usageReadAt = -1
            usage = null
        }
        val used = usage?.get(pkg) ?: 0L
        val remaining = minutes * MINUTE - used
        val betweenReads = when {
            usage == null -> 15_000L
            remaining <= 0 -> 60_000L
            else -> remaining.coerceIn(2_000L, 30_000L)
        }
        val age = now - usageReadAt
        if ((usageReadAt < 0 || age >= betweenReads) && usageJob == null) {
            usageJob = scope.launch {
                val snapshot = withContext(Dispatchers.IO) { runCatching { apps.usageSince(day) }.getOrNull() }
                usageJob = null
                if (day == usageDay && screenActive) {
                    usage = snapshot
                    usageReadAt = SystemClock.elapsedRealtime()
                }
                requestCheck()
            }
        }
        requestCheck(nextMidnight() - System.currentTimeMillis())
        if (usageJob == null) requestCheck(betweenReads - age)
        return remaining <= 0
    }

    private companion object {
        val rulePreferences = setOf("study_schedules", "cover_small_windows", "blocked_hosts", "allowed_hosts",
            "adult_filter", "browser_study", "youtube_study", "study_channels") + videoTargets.map { it.key }
    }

    private fun showShield(pkg: String, label: String, reason: String, allowBack: Boolean = false) {
        if (covered == pkg && coveredReason == reason) return
        hideShield()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(32), dp(36), dp(32), dp(36))
            setBackgroundColor(Color.rgb(35, 32, 55))
        }
        panel.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_studyorbit)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(68), dp(68)).apply { bottomMargin = dp(12) })
        panel.addView(shieldText(label, 32f, "#FFFFFF").apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
        })
        panel.addView(shieldText(reason, 16f, "#E1D9F4").apply { setPadding(0, dp(8), 0, dp(24)) })
        panel.addView(Button(this).apply {
            text = if (allowBack) "Go back" else "Go to Home"
            isAllCaps = false
            setTextColor(Color.rgb(35, 32, 55))
            background = GradientDrawable().apply {
                setColor(Color.rgb(218, 200, 255))
                cornerRadius = dp(22).toFloat()
            }
            setPadding(dp(30), 0, dp(30), 0)
            setOnClickListener {
                if (performGlobalAction(if (allowBack) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME)) hideShield()
            }
        }, LinearLayout.LayoutParams(dp(190), dp(52)))
        panel.addView(Button(this).apply {
            text = getString(R.string.open_app)
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                startActivity(Intent(this@DistractionService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                hideShield()
            }
        }, LinearLayout.LayoutParams(dp(190), dp(48)))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.OPAQUE
        )
        try {
            manager.addView(panel, params)
            shade = panel
            covered = pkg
            coveredReason = reason
        } catch (_: WindowManager.BadTokenException) {
            // Android can revoke the overlay token as the user disables the service.
        }
    }

    private fun shieldText(value: String, size: Float, color: String) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(color))
        gravity = Gravity.CENTER
    }

    private fun hideShield() {
        shade?.let { manager.removeView(it) }
        shade = null
        covered = null
        coveredReason = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

}
