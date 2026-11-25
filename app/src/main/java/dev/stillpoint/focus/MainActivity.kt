package dev.stillpoint.focus

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var resumeCount by mutableIntStateOf(0)
    private val routeRequests = Channel<Destination>(Channel.CONFLATED)
    private val routes = routeRequests.receiveAsFlow()
    private var pinRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        val store = FocusStore(this)
        val apps = DeviceApps(this)
        pinRequested = savedInstanceState?.getBoolean("focus_pin") == true
        val initialDestination = routeDestination(intent) ?: Destination.FOCUS
        setContent { StudyOrbit(store, apps, resumeCount, initialDestination, routes, ::pin, ::releasePin) }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeDestination(intent)?.let { routeRequests.trySend(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("focus_pin", pinRequested)
        super.onSaveInstanceState(outState)
    }

    private fun routeDestination(intent: Intent): Destination? = when (intent.action) {
        SHOW_FOCUS -> Destination.FOCUS
        SHOW_PLANNER -> Destination.PLANNER
        else -> null
    }

    private fun pin() {
        runCatching {
            startLockTask()
            pinRequested = true
        }.onFailure { Toast.makeText(this, "Screen pinning is unavailable on this device.", Toast.LENGTH_LONG).show() }
    }

    private fun releasePin() {
        if (!pinRequested) return
        if (getSystemService(ActivityManager::class.java).lockTaskModeState == ActivityManager.LOCK_TASK_MODE_PINNED) {
            runCatching { stopLockTask() }
        }
        pinRequested = false
    }

    companion object {
        const val SHOW_FOCUS = "dev.stillpoint.focus.SHOW_FOCUS"
        const val SHOW_PLANNER = "dev.stillpoint.focus.SHOW_PLANNER"
    }
}

private enum class Destination(val label: String, val icon: Int, val selectedIcon: Int) {
    FOCUS("Focus", R.drawable.fig_focus, R.drawable.fig_focus_active),
    BLOCK("Block", R.drawable.fig_block, R.drawable.fig_block_active),
    PLANNER("Planner", R.drawable.fig_planner, R.drawable.fig_planner_active),
    PROGRESS("Progress", R.drawable.fig_progress, R.drawable.fig_progress_active)
}

@Composable
private fun StudyOrbit(
    store: FocusStore, apps: DeviceApps, resumes: Int, initialDestination: Destination, routes: Flow<Destination>,
    onPin: () -> Unit, onReleasePin: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var destination by rememberSaveable { mutableStateOf(initialDestination) }
    val tabState = rememberSaveableStateHolder()
    var phase by rememberSaveable { mutableStateOf(Phase.FOCUS) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var endConfirmation by rememberSaveable { mutableStateOf(false) }
    var strictConfirmation by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf(store.session()) }
    var history by remember { mutableStateOf(store.history()) }
    var result by rememberSaveable { mutableIntStateOf(-1) }
    var resultTime by rememberSaveable { mutableLongStateOf(0L) }
    var resultKind by rememberSaveable { mutableStateOf(FocusResult.FOCUS) }
    var blockSubpage by rememberSaveable { mutableStateOf(false) }
    val secondaryPage = destination == Destination.BLOCK && blockSubpage
    var focusMinutes by remember { mutableIntStateOf(store.focusMinutes) }
    var breakMinutes by remember { mutableIntStateOf(store.breakMinutes) }
    var goal by remember { mutableIntStateOf(store.goalMinutes) }
    var subject by remember { mutableStateOf(store.subject) }
    var mode by rememberSaveable { mutableStateOf(TimerMode.entries.getOrElse(store.prefs.getInt("timer_mode", 0)) { TimerMode.TIMER }) }
    var strict by rememberSaveable { mutableStateOf(store.prefs.getBoolean("strict_preference", false)) }
    var theme by remember { mutableStateOf(TimerTheme.entries.getOrElse(store.prefs.getInt("timer_theme", 0)) { TimerTheme.ORBIT }) }
    var sound by remember { mutableStateOf(store.soundscape()) }
    var nudges by remember { mutableStateOf(store.prefs.getBoolean("focus_nudges", true)) }
    var scheduleRevision by remember { mutableIntStateOf(0) }
    val planner = remember(store) { StudyPlanner(context, store.prefs) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var protectionSetup by rememberSaveable { mutableStateOf(false) }
    val protectionRunning = UsageBlockService.running
    val protectionEnabled = store.prefs.getBoolean(UsageBlockService.ENABLED, false)
    val needsProtectionStatus = destination == Destination.BLOCK || protectionSetup || protectionEnabled
    val needsUsageStatus = needsProtectionStatus || destination == Destination.PROGRESS
    val usageAccess = remember(resumes, needsUsageStatus) { needsUsageStatus && apps.hasUsageAccess() }
    val overlayAccess = remember(resumes, needsProtectionStatus) {
        needsProtectionStatus && Settings.canDrawOverlays(context)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) store.syncStudyNotification()
    }
    val asked = rememberSaveable { mutableStateOf(store.prefs.getBoolean("asked_notifications", false)) }

    fun refresh() {
        val previous = active
        val next = store.session()
        val updatedHistory = store.history()
        if (previous != null && next == null) {
            val newLog = updatedHistory.lastOrNull()?.takeIf { it != history.lastOrNull() }
            when {
                newLog != null -> {
                    result = newLog.minutes
                    resultTime = result * MINUTE
                    resultKind = if (previous.mode == TimerMode.STOPWATCH) FocusResult.STOPWATCH else FocusResult.FOCUS
                }
                previous.mode == TimerMode.POMODORO && previous.phase == Phase.BREAK && previous.displayTime() == 0L -> {
                    result = updatedHistory.takeLast(previous.rounds).sumOf { it.minutes }
                    resultTime = result * MINUTE
                    resultKind = FocusResult.CYCLE
                }
                previous.phase == Phase.BREAK && previous.displayTime() == 0L -> {
                    result = 0
                    resultTime = previous.duration
                    resultKind = FocusResult.BREAK
                }
            }
        }
        active = next
        history = updatedHistory
        today = LocalDate.now()
    }

    fun openSettings(action: String, extras: Bundle? = null, data: Uri? = null) {
        try {
            context.startActivity(Intent(action).apply {
                this.data = data
                if (extras != null) putExtras(extras)
            })
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "Open Android Settings to grant access.", Toast.LENGTH_LONG).show()
        }
    }

    fun startAppProtection() {
        if (!usageAccess || !overlayAccess || UsageBlockService.running) return
        runCatching { context.startForegroundService(Intent(context, UsageBlockService::class.java)) }
            .onFailure { Toast.makeText(context, "Open StudyOrbit to start app protection.", Toast.LENGTH_LONG).show() }
    }

    LaunchedEffect(resumes) {
        if (resumes > 0 && protectionEnabled) {
            if (usageAccess && overlayAccess) startAppProtection()
            else context.stopService(Intent(context, UsageBlockService::class.java))
        }
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !asked.value &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            asked.value = true
            store.prefs.edit().putBoolean("asked_notifications", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun syncAudio() {
        val intent = Intent(context, FocusAudio::class.java)
        if (sound == Soundscape.OFF || active == null) context.stopService(intent)
        else if (active?.running == true && active?.phase == Phase.FOCUS) runCatching { context.startForegroundService(intent) }
    }

    fun launchSession(minutes: Int, task: String? = null) {
        result = -1
        val targetMode = if (task == null) mode else TimerMode.TIMER
        val targetPhase = if (task == null && mode == TimerMode.TIMER) phase else Phase.FOCUS
        store.start(targetPhase, minutes, targetMode, task == null && strict && targetPhase == Phase.FOCUS, task ?: subject)
        destination = Destination.FOCUS
        refresh()
        requestNotifications()
        syncAudio()
    }

    LaunchedEffect(lifecycle, routes) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            routes.collect {
                destination = it
                settings = false
                disclosure = false
                protectionSetup = false
                strictConfirmation = false
                endConfirmation = false
            }
        }
    }

    LaunchedEffect(active?.strict, resumes) {
        if (active?.strict != true) onReleasePin()
    }

    LaunchedEffect(lifecycle, store) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val updates = Channel<Unit>(Channel.CONFLATED)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (isSessionPreference(key) || key == "history") updates.trySend(Unit)
            }
            val clockChanges = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    updates.trySend(Unit)
                }
            }
            val clockFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            }
            store.prefs.registerOnSharedPreferenceChangeListener(listener)
            ContextCompat.registerReceiver(context, clockChanges, clockFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                store.restoreAlarm()
                planner.arm()
                refresh()
                store.syncStudyNotification()
                syncAudio()
                while (true) {
                    val midnight = (nextMidnight() - System.currentTimeMillis()).coerceAtLeast(1)
                    val countdown = active?.takeIf { it.running && it.mode != TimerMode.STOPWATCH }
                    val nextRefresh = minOf(midnight, countdown?.displayTime()?.coerceAtLeast(1) ?: Long.MAX_VALUE)
                    withTimeoutOrNull(nextRefresh) { updates.receive() }
                    refresh()
                }
            } finally {
                context.unregisterReceiver(clockChanges)
                store.prefs.unregisterOnSharedPreferenceChangeListener(listener)
                updates.close()
            }
        }
    }

    val background = if (destination == Destination.FOCUS && !settings) theme.pageColor else Paper
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent, onPrimary = OnAccent, primaryContainer = AccentSurface, onPrimaryContainer = Accent,
            background = Paper, onBackground = Ink, surface = OrbitSurface, onSurface = Ink,
            surfaceVariant = Night, onSurfaceVariant = Muted, outline = Line, outlineVariant = Line
        ),
        typography = orbitTypography
    ) {
        Surface(Modifier.fillMaxSize(), color = background, contentColor = Ink) {
            Column(Modifier.fillMaxSize().orbitInsets(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 580.dp).fillMaxWidth().weight(1f)) {
                    if (!secondaryPage) AppHeader(destination, active,
                        onTimer = { destination = Destination.FOCUS }, onSettings = { settings = true })
                    tabState.SaveableStateProvider(destination.name) {
                        when (destination) {
                            Destination.FOCUS -> FocusPage(
                                active = active, result = result, resultTime = resultTime, resultKind = resultKind,
                                restMinutes = breakMinutes, rounds = store.rounds, longBreak = store.longBreakMinutes,
                                onReset = { result = -1 },
                                phase = active?.phase ?: if (mode == TimerMode.TIMER) phase else Phase.FOCUS,
                                minutes = if (mode != TimerMode.TIMER || phase == Phase.FOCUS) focusMinutes else breakMinutes,
                                mode = active?.mode ?: mode, strict = active?.strict ?: strict, theme = theme,
                                sound = sound,
                                streak = if (nudges) remember(history, today) { studyStreak(history, today) } else null,
                                subject = subject,
                                goal = goal,
                                total = remember(history, today) { history.filter { it.day == today }.sumOf { it.minutes } },
                                onMode = { mode = it; store.prefs.edit().putInt("timer_mode", it.ordinal).apply() },
                                onStrict = { strict = it; store.prefs.edit().putBoolean("strict_preference", it).apply() },
                                onPin = onPin,
                                onPhase = { phase = it },
                                onMinutes = {
                                    if (phase == Phase.FOCUS || mode != TimerMode.TIMER) { focusMinutes = it; store.focusMinutes = it }
                                    else { breakMinutes = it; store.breakMinutes = it }
                                },
                                onSubject = {
                                    subject = it.take(70)
                                    store.subject = subject
                                },
                                onStart = {
                                    if (strict && mode != TimerMode.STOPWATCH && (mode != TimerMode.TIMER || phase == Phase.FOCUS)) {
                                        strictConfirmation = true
                                    } else launchSession(if (mode == TimerMode.TIMER && phase == Phase.BREAK) breakMinutes else focusMinutes)
                                },
                                onPause = { store.pauseOrResume(); refresh(); syncAudio() },
                                onEnd = { endConfirmation = true },
                                onTheme = {
                                    theme = it
                                    store.prefs.edit().putInt("timer_theme", it.ordinal).apply()
                                },
                                onSound = {
                                    sound = it
                                    store.prefs.edit().putInt("soundscape", it.ordinal).apply()
                                    syncAudio()
                                },
                                onMiniTimer = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) ({
                                    context.startActivity(Intent(context, MiniTimerActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }) else null,
                                onGoal = { destination = Destination.PROGRESS },
                                shortcuts = { StudyShortcuts(readContentRules(store.prefs)) }
                            )
                            Destination.BLOCK -> {
                                val policy = remember(context) { context.getSystemService(DevicePolicyManager::class.java) }
                                val admin = remember(context) { ComponentName(context, StudyAdmin::class.java) }
                                var adminActive by remember(resumes) { mutableStateOf(policy?.isAdminActive(admin) == true) }
                                val blockAccess = remember(resumes) { apps.hasBlockAccess() }
                                BlockPage(
                                    apps, store, blockAccess, usageAccess, active?.strict == true, today, resumes,
                                    appProtection = protectionRunning, protectionReady = usageAccess && overlayAccess,
                                    onSubpage = { blockSubpage = it },
                                    onAppProtection = { protectionSetup = true },
                                    onBlockAccess = { disclosure = true },
                                    adminActive = adminActive,
                                    onAdmin = {
                                        if (adminActive) {
                                            policy?.removeActiveAdmin(admin)
                                            adminActive = false
                                        } else openSettings(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, Bundle().apply {
                                            putParcelable(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                                            putString(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                                "Adds an extra confirmation step before uninstalling StudyOrbit. You can remove access from Android Settings.")
                                        })
                                    }
                                )
                            }
                            Destination.PLANNER -> {
                                val schedules = remember(planner, scheduleRevision) { planner.schedules() }
                                val exactAlarms = remember(resumes) {
                                    Build.VERSION.SDK_INT < 31 ||
                                        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
                                }
                                PlannerPage(
                                    schedules, today, active != null, active?.strict == true, exactAlarms,
                                    onExactAlarms = {
                                        openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, data = Uri.parse("package:${context.packageName}"))
                                    },
                                    onSave = { updated, reminder ->
                                        planner.save(updated)
                                        scheduleRevision++
                                        if (reminder) requestNotifications()
                                    },
                                    onStart = { launchSession(it.minutes, it.name) }
                                )
                            }
                            Destination.PROGRESS -> ProgressPage(
                                history, today, goal, apps, store, usageAccess,
                                onFocus = { result = -1; destination = Destination.FOCUS },
                                onUsageAccess = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
                            )
                        }
                    }
                }
                if (!secondaryPage) BottomTabs(destination) { destination = it }
            }
        }
        if (protectionSetup) AlertDialog(
            onDismissRequest = { protectionSetup = false },
            title = { Text("Whole-app protection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Block selected apps during focus or when they reach a daily limit. StudyOrbit checks which app is open and shows a blocking screen.")
                    if (!usageAccess) TextButton(onClick = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }) {
                        Text("Allow Usage Access")
                    }
                    if (!overlayAccess) TextButton(onClick = {
                        openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, data = Uri.parse("package:${context.packageName}"))
                    }) { Text("Allow display over other apps") }
                    if (usageAccess && overlayAccess) Text("Both permissions are ready.", color = Forest)
                }
            },
            confirmButton = {
                TextButton(enabled = active?.strict != true && (protectionRunning || (usageAccess && overlayAccess)),
                    onClick = {
                        store.prefs.edit().putBoolean(UsageBlockService.ENABLED, !protectionRunning).apply()
                        if (protectionRunning) context.stopService(Intent(context, UsageBlockService::class.java))
                        else {
                            startAppProtection()
                            requestNotifications()
                        }
                        protectionSetup = false
                    }) { Text(if (protectionRunning) "Stop protection" else "Start protection") }
            },
            dismissButton = { TextButton(onClick = { protectionSetup = false }) { Text("Close") } }
        )

        if (settings) SettingsDialog(
            store, focusMinutes, breakMinutes, goal, theme, sound, nudges,
            onDismiss = { settings = false },
            onNotifications = {
                openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Bundle().apply {
                    putString(Settings.EXTRA_APP_PACKAGE, context.packageName)
                })
            },
            onSave = { focus, rest, target, nextTheme, nextSound, nextNudges ->
                focusMinutes = focus
                breakMinutes = rest
                goal = target
                store.focusMinutes = focus
                store.breakMinutes = rest
                store.goalMinutes = target
                theme = nextTheme
                sound = nextSound
                nudges = nextNudges
                store.prefs.edit().putInt("timer_theme", nextTheme.ordinal).putInt("soundscape", nextSound.ordinal)
                    .putBoolean("focus_nudges", nextNudges).apply()
                syncAudio()
                settings = false
            }
        )
        if (strictConfirmation) OrbitSheet({ strictConfirmation = false }) {
            OrbitHeading("Stay in your orbit?", size = 26)
            Text("Strict Mode hides Pause, Resume, End, and Float timer. Your blocking rules cannot be changed until the session finishes.",
                color = Muted, fontSize = 16.sp, lineHeight = 23.sp)
            Spacer(Modifier.height(22.dp))
            Text("$focusMinutes-minute focus" + if (subject.isBlank()) "" else " · $subject",
                color = Accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            OrbitButton("Start strict session", { strictConfirmation = false; launchSession(focusMinutes) }, Modifier.fillMaxWidth())
            OrbitButton("Not now", { strictConfirmation = false }, Modifier.fillMaxWidth(), primary = false, rounded = false)
        }
        if (disclosure) AlertDialog(
            onDismissRequest = { disclosure = false },
            title = { Text("Enable advanced filters") },
            text = {
                Text(stringResource(R.string.blocking_disclosure))
            },
            confirmButton = {
                TextButton(onClick = {
                    disclosure = false
                    openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }) { Text("Agree & open settings") }
            },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Cancel") } }
        )
        if (endConfirmation) OrbitSheet({ endConfirmation = false }) {
            val stopwatch = active?.mode == TimerMode.STOPWATCH
            OrbitHeading(if (stopwatch) "Finish this stopwatch session?" else "End this ${active?.phase?.label?.lowercase() ?: "focus"} session?", size = 26)
            Text(if (stopwatch) "Full minutes on the stopwatch count toward your goal."
                else "Unfinished countdown rounds don’t add study minutes. A completed round is always saved.",
                color = Muted, fontSize = 16.sp, lineHeight = 23.sp)
            Spacer(Modifier.height(43.dp))
            OrbitButton("Keep going", { endConfirmation = false }, Modifier.fillMaxWidth(), primary = !stopwatch)
            OrbitButton(if (stopwatch) "Save & finish" else "End session", {
                endConfirmation = false
                val previous = active
                val time = previous?.displayTime() ?: 0L
                store.stop()
                refresh()
                if (previous != null && result < 0) {
                    result = 0
                    resultKind = if (stopwatch) FocusResult.STOPWATCH else FocusResult.ENDED
                }
                if (previous != null && resultKind in listOf(FocusResult.STOPWATCH, FocusResult.ENDED)) resultTime = time
                syncAudio()
            }, Modifier.fillMaxWidth(), primary = stopwatch, rounded = false)
        }
    }
}

@Composable
private fun AppHeader(
    destination: Destination, session: Session?,
    onTimer: () -> Unit, onSettings: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = orbitTopPadding(), bottom = 19.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            OrbitHeading(destination.label)
            if (destination != Destination.FOCUS && session != null) {
                ActiveTimerLink(session, onTimer)
            }
        }
        IconButton(onClick = onSettings, modifier = Modifier.background(OrbitSurface, CircleShape)) {
            Image(painterResource(R.drawable.fig_sun),
                contentDescription = "Settings", modifier = Modifier.size(24.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Ink))
        }
    }
}

@Composable
private fun ActiveTimerLink(session: Session, onTimer: () -> Unit) {
    val time = rememberDisplayedTime(session)
    val stopwatch = session.mode == TimerMode.STOPWATCH
    val status = when {
        session.strict -> "Strict focus"
        !session.running -> "Paused"
        session.mode == TimerMode.POMODORO -> "Pomodoro"
        stopwatch -> "Stopwatch"
        session.phase == Phase.BREAK -> "Break running"
        else -> "Focus running"
    }
    Text("$status · ${timerDigits(time.value ?: session.remaining, stopwatch)}  → Return to timer",
        Modifier.clickable(onClick = onTimer, role = Role.Button), color = Accent, fontSize = 14.sp, lineHeight = 19.sp)
}

@Composable
private fun BottomTabs(current: Destination, onSelect: (Destination) -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 5.dp, bottom = 6.dp),
        shape = RoundedCornerShape(29.dp), color = Ink, shadowElevation = 4.dp
    ) {
        Row(Modifier.selectableGroup().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Destination.entries.forEach { tab ->
                val selected = tab == current
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(24.dp))
                        .background(if (selected) Color(0xFFE6DEFF) else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                        .padding(horizontal = 3.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
                ) {
                    Image(painterResource(if (selected) tab.selectedIcon else tab.icon), null, Modifier.size(22.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (selected) Ink else Color(0xFFE1DEEE)))
                    Text(tab.label, color = if (selected) Ink else Color(0xFFE1DEEE), fontSize = 11.sp, lineHeight = 14.sp,
                        textAlign = TextAlign.Center, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    store: FocusStore, focus: Int, rest: Int, goal: Int,
    theme: TimerTheme, sound: Soundscape, nudges: Boolean, onDismiss: () -> Unit,
    onNotifications: () -> Unit, onSave: (Int, Int, Int, TimerTheme, Soundscape, Boolean) -> Unit
) {
    var focusText by rememberSaveable { mutableStateOf(focus.toString()) }
    var restText by rememberSaveable { mutableStateOf(rest.toString()) }
    var goalText by rememberSaveable { mutableStateOf(goal.toString()) }
    var longBreak by rememberSaveable { mutableStateOf(store.longBreakMinutes.toString()) }
    var rounds by rememberSaveable { mutableStateOf(store.rounds.toString()) }
    var selectedTheme by rememberSaveable { mutableStateOf(theme) }
    var selectedSound by rememberSaveable { mutableStateOf(sound) }
    var showNudges by rememberSaveable { mutableStateOf(nudges) }
    val a = focusText.toIntOrNull() ?: 0
    val b = restText.toIntOrNull() ?: 0
    val c = goalText.toIntOrNull() ?: 0
    val d = longBreak.toIntOrNull() ?: 0
    val e = rounds.toIntOrNull() ?: 0
    OrbitFullScreen(onDismiss) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = orbitTopPadding(), bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.offset(x = (-17).dp)) {
                Text("‹", Modifier.semantics { contentDescription = "Back" }, color = Accent, fontSize = 28.sp)
            }
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OrbitHeading("Study preferences", Modifier.weight(1f))
                Image(painterResource(R.drawable.ic_studyorbit), null, Modifier.size(38.dp))
            }
            Text("Saved on this device · no account needed", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(13.dp))
            PreferenceNumber("Focus duration", focusText, { focusText = it }, "min", 1..180)
            PreferenceNumber("Short break", restText, { restText = it }, "min", 1..180)
            PreferenceNumber("Long break", longBreak, { longBreak = it }, "min", 1..180)
            PreferenceNumber("Pomodoro rounds", rounds, { rounds = it }, " ·  2–12", 2..12)
            PreferenceNumber("Daily goal", goalText, { goalText = it }, "min  ·  1–720", 1..720)
            PreferenceChoice("Sound", selectedSound.label, Soundscape.entries, { it.label }, { selectedSound = it })
            PreferenceChoice("Theme", selectedTheme.label, TimerTheme.entries, { it.label }, { selectedTheme = it })
            Spacer(Modifier.height(7.dp))
            ToggleRule("Companion nudges", "", showNudges) { showNudges = it }
            TextButton(onClick = onNotifications, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 58.dp)) {
                Text("Notification settings  ↗", fontWeight = FontWeight.SemiBold)
            }
            OrbitButton("Save settings", {
                store.longBreakMinutes = d
                store.rounds = e
                onSave(a, b, c, selectedTheme, selectedSound, showNudges)
            }, Modifier.fillMaxWidth(), enabled = a in 1..180 && b in 1..180 && c in 1..720 && d in 1..180 && e in 2..12)
            OrbitButton("Cancel", onDismiss, Modifier.fillMaxWidth(), primary = false, rounded = false)
        }
    }
}

@Composable
private fun PreferenceNumber(label: String, value: String, onChange: (String) -> Unit, suffix: String, range: IntRange) {
    OrbitField(label, value, { onChange(it.filter(Char::isDigit).take(3)) }, Modifier.fillMaxWidth(),
        suffix = suffix, numeric = true, inline = true, error = value.toIntOrNull() !in range)
}

@Composable
private fun <T> PreferenceChoice(
    label: String, current: String, options: List<T>, title: (T) -> String, onChoice: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(FieldShape).background(OrbitSurface)
                .clickable(role = Role.Button) { expanded = true }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1.25f), color = Ink, fontSize = 14.sp)
            Text(current, Modifier.weight(1f), color = Accent, fontSize = 14.sp)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { item ->
                DropdownMenuItem(text = { Text(title(item)) }, onClick = { onChoice(item); expanded = false })
            }
        }
    }
}
