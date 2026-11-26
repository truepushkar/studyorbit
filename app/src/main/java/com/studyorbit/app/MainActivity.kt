package com.studyorbit.app

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.res.Configuration
import android.content.Context
import android.content.Intent
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
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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

internal enum class AppAppearance(val label: Int, val nightMode: Int) {
    SYSTEM(R.string.appearance_system, Configuration.UI_MODE_NIGHT_UNDEFINED),
    LIGHT(R.string.appearance_light, Configuration.UI_MODE_NIGHT_NO),
    DARK(R.string.appearance_dark, Configuration.UI_MODE_NIGHT_YES)
}

class MainActivity : ComponentActivity() {
    private val model by viewModels<StudyViewModel>()
    private var resumeCount by mutableIntStateOf(0)
    private val routeRequests = Channel<Destination>(Channel.CONFLATED)
    private val routes = routeRequests.receiveAsFlow()
    private var pinRequested = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val appearance = FocusStore(newBase).appearance
        if (appearance != AppAppearance.SYSTEM) {
            applyOverrideConfiguration(Configuration().apply { uiMode = appearance.nightMode })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        val apps = DeviceApps(this)
        pinRequested = savedInstanceState?.getBoolean("focus_pin") == true
        val initialDestination = routeDestination(intent) ?: Destination.FOCUS
        setContent { StudyOrbit(model, apps, resumeCount, initialDestination, routes, ::pin, ::releasePin, ::recreate) }
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
        const val SHOW_FOCUS = "com.studyorbit.app.SHOW_FOCUS"
        const val SHOW_PLANNER = "com.studyorbit.app.SHOW_PLANNER"
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
    model: StudyViewModel, apps: DeviceApps, resumes: Int, initialDestination: Destination, routes: Flow<Destination>,
    onPin: () -> Unit, onReleasePin: () -> Unit, onAppearanceChanged: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var destination by rememberSaveable { mutableStateOf(initialDestination) }
    val store = model.store
    val ui by model.state.collectAsStateWithLifecycle()
    val active = ui.active
    val history = ui.history
    val today = ui.today
    val focusMinutes = ui.focusMinutes
    val breakMinutes = ui.breakMinutes
    val goal = ui.goal
    val subject = ui.subject
    val mode = ui.mode
    val strict = ui.strict
    val theme = ui.theme
    val sound = ui.sound
    val nudges = ui.nudges
    val result = ui.result
    val resultTime = ui.resultTime
    val resultKind = ui.resultKind
    val tabState = rememberSaveableStateHolder()
    var phase by rememberSaveable { mutableStateOf(Phase.FOCUS) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var endConfirmation by rememberSaveable { mutableStateOf(false) }
    var strictConfirmation by rememberSaveable { mutableStateOf(false) }
    var blockSubpage by rememberSaveable { mutableStateOf(false) }
    val secondaryPage = destination == Destination.BLOCK && blockSubpage
    var scheduleRevision by remember { mutableIntStateOf(0) }
    val planner = remember(store) { StudyPlanner(context, store.prefs) }
    var protectionSetup by rememberSaveable { mutableStateOf(false) }
    val protectionRunning = UsageBlockService.running
    val protectionEnabled = store.prefs.getBoolean(UsageBlockService.ENABLED, false)
    val needsProtectionStatus = destination == Destination.BLOCK || protectionSetup || protectionEnabled
    val needsUsageStatus = needsProtectionStatus || destination == Destination.PROGRESS
    val usageAccess = remember(resumes, needsUsageStatus) { needsUsageStatus && apps.hasUsageAccess() }
    val overlayAccess = remember(resumes, needsProtectionStatus) {
        needsProtectionStatus && Settings.canDrawOverlays(context)
    }
    fun showFloatingTimer() {
        if (store.session()?.strict != false) return
        runCatching { context.startForegroundService(Intent(context, FloatingTimerService::class.java)) }
            .onFailure { Toast.makeText(context, "Unable to show the floating timer.", Toast.LENGTH_SHORT).show() }
    }
    val timerOverlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) showFloatingTimer()
        else if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            context.startActivity(Intent(context, MiniTimerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) store.syncStudyNotification()
    }
    val asked = rememberSaveable { mutableStateOf(store.prefs.getBoolean("asked_notifications", false)) }

    val view = androidx.compose.ui.platform.LocalView.current
    fun pulse() { view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }

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
        val session = store.session()
        if (store.soundscape() == Soundscape.OFF || session == null) context.stopService(intent)
        else if (session.running && session.phase == Phase.FOCUS) runCatching { context.startForegroundService(intent) }
    }

    fun launchSession(minutes: Int, task: String? = null) {
        model.reset()
        val targetMode = if (task == null) mode else TimerMode.TIMER
        val targetPhase = if (task == null && mode == TimerMode.TIMER) phase else Phase.FOCUS
        pulse()
        store.start(targetPhase, minutes, targetMode, task == null && strict && targetPhase == Phase.FOCUS, task ?: store.subject)
        destination = Destination.FOCUS
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
            model.observe()
        }
    }

    LaunchedEffect(active?.phase, active?.running, sound) { syncAudio() }
    var lastResult by rememberSaveable { mutableIntStateOf(result) }
    LaunchedEffect(result) {
        if (lastResult < 0 && result >= 0 && resultKind != FocusResult.ENDED) pulse()
        lastResult = result
    }

    val background = if (destination == Destination.FOCUS && !settings) theme.pageColor else Paper
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme(
            primary = Accent, onPrimary = OnAccent, primaryContainer = AccentSurface, onPrimaryContainer = Ink,
            background = Paper, onBackground = Ink, surface = OrbitSurface, onSurface = Ink,
            surfaceVariant = Night, onSurfaceVariant = Muted, outline = Line, outlineVariant = Line,
            surfaceContainerLowest = OrbitSurface, surfaceContainerLow = OrbitSurface, surfaceContainer = OrbitSurface,
            surfaceContainerHigh = OrbitSurface, surfaceContainerHighest = Night
        ) else lightColorScheme(
            primary = Accent, onPrimary = OnAccent, primaryContainer = AccentSurface, onPrimaryContainer = Accent,
            background = Paper, onBackground = Ink, surface = OrbitSurface, onSurface = Ink,
            surfaceVariant = Night, onSurfaceVariant = Muted, outline = Line, outlineVariant = Line,
            surfaceContainerLowest = OrbitSurface, surfaceContainerLow = OrbitSurface, surfaceContainer = OrbitSurface,
            surfaceContainerHigh = OrbitSurface, surfaceContainerHighest = Night
        ),
        typography = orbitTypography, shapes = orbitShapes
    ) {
        Surface(Modifier.fillMaxSize(), color = background, contentColor = Ink) {
            Column(Modifier.fillMaxSize().orbitInsets(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 580.dp).fillMaxWidth().weight(1f)) {
                    if (!secondaryPage) AppHeader(destination, active,
                        onTimer = { destination = Destination.FOCUS }, onSettings = { settings = true })
                    Box(Modifier.weight(1f)) {
                        tabState.SaveableStateProvider(destination.name) {
                            when (destination) {
                                Destination.FOCUS -> FocusPage(
                                    state = FocusUiState(
                                        active = active, result = result, resultTime = resultTime, resultKind = resultKind,
                                        restMinutes = breakMinutes, rounds = store.rounds, longBreak = store.longBreakMinutes,
                                        phase = active?.phase ?: if (mode == TimerMode.TIMER) phase else Phase.FOCUS,
                                        minutes = if (mode != TimerMode.TIMER || phase == Phase.FOCUS) focusMinutes else breakMinutes,
                                        mode = active?.mode ?: mode, strict = active?.strict ?: strict, theme = theme,
                                        sound = sound,
                                        streak = if (nudges) remember(history, today) { studyStreak(history, today) } else null,
                                        subject = subject,
                                        goal = goal,
                                        total = remember(history, today) { history.filter { it.day == today }.sumOf { it.minutes } },
                                        recentSubjects = remember(history) { history.asReversed().map { it.subject }.filter { it.isNotBlank() }.distinct().take(4) },
                                        lastSession = history.lastOrNull()
                                    ),
                                    onReset = { model.reset() },
                                    onMode = { store.prefs.edit().putInt("timer_mode", it.ordinal).apply() },
                                    onStrict = { store.prefs.edit().putBoolean("strict_preference", it).apply() },
                                    onPin = onPin,
                                    onPhase = { phase = it },
                                    onMinutes = {
                                        if (phase == Phase.FOCUS || mode != TimerMode.TIMER) { store.focusMinutes = it }
                                        else { store.breakMinutes = it }
                                    },
                                    onSubject = {
                                        store.subject = it.take(70)
                                    },
                                    onStart = {
                                        if (strict && mode != TimerMode.STOPWATCH && (mode != TimerMode.TIMER || phase == Phase.FOCUS)) {
                                            strictConfirmation = true
                                        } else launchSession(if (mode == TimerMode.TIMER && phase == Phase.BREAK) breakMinutes else focusMinutes)
                                    },
                                    onPause = { pulse(); store.pauseOrResume() },
                                    onEnd = { endConfirmation = true },
                                    onTheme = {
                                        store.prefs.edit().putInt("timer_theme", it.ordinal).apply()
                                    },
                                    onSound = {
                                        store.prefs.edit().putString("music_track", it.name).apply()
                                        syncAudio()
                                    },
                                    onMiniTimer = {
                                        if (Settings.canDrawOverlays(context)) showFloatingTimer()
                                        else runCatching { timerOverlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:" + context.packageName))) }.onFailure {
                                            Toast.makeText(context, "Open Android Settings and allow display over other apps.", Toast.LENGTH_LONG).show()
                                        }
                                    },
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
                                    onFocus = { model.reset(); destination = Destination.FOCUS },
                                    onUsageAccess = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
                                )
                            }
                        }
                        if (!secondaryPage) Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(8.dp).background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(background.copy(alpha = 0f), background))))
                    }
                }
                if (!secondaryPage) BottomTabs(destination) { destination = it }
            }
        }
        if (protectionSetup) AlertDialog(
            onDismissRequest = { protectionSetup = false },
            title = { Text(stringResource(R.string.whole_app_protection)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.block_selected_apps_during_focus_or_when))
                    PermissionStep(stringResource(R.string.usage_access), usageAccess)
                    PermissionStep(stringResource(R.string.display_over_other_apps), overlayAccess)
                    val advanced = remember(resumes) { apps.hasBlockAccess() }
                    PermissionStep(stringResource(R.string.accessibility_optional), advanced)
                    if (usageAccess && overlayAccess && !advanced) TextButton(onClick = {
                        protectionSetup = false
                        disclosure = true
                    }) { Text(stringResource(R.string.set_up_advanced_filters)) }
                }
            },
            confirmButton = {
                TextButton(enabled = active?.strict != true,
                    onClick = {
                        if (!protectionRunning && !usageAccess) {
                            openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            return@TextButton
                        }
                        if (!protectionRunning && !overlayAccess) {
                            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, data = Uri.parse("package:${context.packageName}"))
                            return@TextButton
                        }
                        store.prefs.edit().putBoolean(UsageBlockService.ENABLED, !protectionRunning).apply()
                        if (protectionRunning) context.stopService(Intent(context, UsageBlockService::class.java))
                        else {
                            startAppProtection()
                            requestNotifications()
                        }
                        protectionSetup = false
                    }) { Text(when { protectionRunning -> stringResource(R.string.stop_protection); !usageAccess -> stringResource(R.string.allow_usage_access); !overlayAccess -> stringResource(R.string.allow_overlay); else -> stringResource(R.string.start_protection) }) }
            },
            dismissButton = { TextButton(onClick = { protectionSetup = false }) { Text(stringResource(R.string.close)) } }
        )

        if (settings) SettingsDialog(
            store, focusMinutes, breakMinutes, goal, theme, sound, nudges,
            onDismiss = { settings = false },
            onNotifications = {
                openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Bundle().apply {
                    putString(Settings.EXTRA_APP_PACKAGE, context.packageName)
                })
            },
            onSave = { focus, rest, target, nextTheme, nextSound, nextNudges, appearance ->
                store.focusMinutes = focus
                store.breakMinutes = rest
                store.goalMinutes = target
                store.prefs.edit().putInt("timer_theme", nextTheme.ordinal).putString("music_track", nextSound.name)
                    .putBoolean("focus_nudges", nextNudges).apply()
                syncAudio()
                settings = false
                if (appearance != store.appearance) {
                    store.appearance = appearance
                    onAppearanceChanged()
                }
            }
        )
        if (strictConfirmation) OrbitSheet({ strictConfirmation = false }) {
            OrbitHeading(stringResource(R.string.stay_in_your_orbit), level = HeadingLevel.Sheet)
            Text(stringResource(R.string.strict_mode_hides_pause_resume_end_and), color = Muted)
            Text("$focusMinutes-minute focus" + if (subject.isBlank()) "" else " · $subject",
                color = Accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Gap.xs))
            OrbitButton(stringResource(R.string.start_strict_session), { strictConfirmation = false; launchSession(focusMinutes) }, Modifier.fillMaxWidth())
            OrbitButton(stringResource(R.string.not_now), { strictConfirmation = false }, Modifier.fillMaxWidth(), primary = false)
        }
        if (disclosure) AlertDialog(
            onDismissRequest = { disclosure = false },
            title = { Text(stringResource(R.string.enable_advanced_filters)) },
            text = {
                Text(stringResource(R.string.blocking_disclosure))
            },
            confirmButton = {
                TextButton(onClick = {
                    disclosure = false
                    openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }) { Text(stringResource(R.string.agree_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text(stringResource(R.string.cancel)) } }
        )
        if (endConfirmation) OrbitSheet({ endConfirmation = false }) {
            val stopwatch = active?.mode == TimerMode.STOPWATCH
            OrbitHeading(if (stopwatch) "Finish this stopwatch session?" else "End this ${active?.phase?.label?.lowercase() ?: "focus"} session?", level = HeadingLevel.Sheet)
            Text(if (stopwatch) "Full minutes on the stopwatch count toward your goal."
                else "Unfinished countdown rounds don’t add study minutes. A completed round is always saved.",
                color = Muted)
            Spacer(Modifier.height(Gap.xs))
            OrbitButton(stringResource(R.string.keep_going), { endConfirmation = false }, Modifier.fillMaxWidth(), primary = !stopwatch)
            OrbitButton(if (stopwatch) "Save & finish" else "End session", {
                endConfirmation = false
                model.finish()
                syncAudio()
            }, Modifier.fillMaxWidth(), primary = stopwatch)
        }
    }
}

@Composable
private fun AppHeader(
    destination: Destination, session: Session?,
    onTimer: () -> Unit, onSettings: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = orbitTopPadding(), bottom = Gap.lg),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            OrbitHeading(destination.label)
            if (destination != Destination.FOCUS && session != null) {
                ActiveTimerLink(session, onTimer)
            }
        }
        OrbitIconButton(R.drawable.fig_sun, stringResource(R.string.theme_settings), onSettings)
    }
}

@Composable
private fun ActiveTimerLink(session: Session, onTimer: () -> Unit) {
    val time = rememberDisplayedTime(session)
    val stopwatch = session.mode == TimerMode.STOPWATCH
    val status = when {
        session.strict -> "Strict focus"
        !session.running -> stringResource(R.string.focus_paused)
        session.mode == TimerMode.POMODORO -> "Pomodoro"
        stopwatch -> "Stopwatch"
        session.phase == Phase.BREAK -> "Break running"
        else -> "Focus running"
    }
    Row(
        Modifier.clickable(onClickLabel = stringResource(R.string.return_to_timer), role = Role.Button, onClick = onTimer)
            .padding(vertical = 4.dp).clip(CircleShape).background(AccentSurface)
            .heightIn(min = 40.dp).padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("$status · ${timerDigits(time.value ?: session.remaining, stopwatch)}", color = Accent, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
        OrbitIcon(R.drawable.ic_next, size = 16.dp)
    }
}

@Composable
private fun BottomTabs(current: Destination, onSelect: (Destination) -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth()
            .padding(start = Gap.lg, end = Gap.lg, top = 5.dp, bottom = 6.dp),
        shape = RoundedCornerShape(29.dp), color = NavContainer, shadowElevation = 4.dp,
        border = BorderStroke(1.dp, Line.copy(alpha = .5f))
    ) {
        Row(Modifier.selectableGroup().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Destination.entries.forEach { tab ->
                val selected = tab == current
                val pill by animateColorAsState(if (selected) NavPill else NavPill.copy(alpha = 0f), tween(180), label = "Tab highlight")
                val content = if (selected) NavPillContent else NavContent
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(24.dp))
                        .background(pill)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                        .padding(horizontal = 3.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
                ) {
                    Image(painterResource(if (selected) tab.selectedIcon else tab.icon), null, Modifier.size(22.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(content))
                    Text(tab.label, color = content, fontSize = 13.sp, lineHeight = 14.sp,
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
    onNotifications: () -> Unit, onSave: (Int, Int, Int, TimerTheme, Soundscape, Boolean, AppAppearance) -> Unit
) {
    var focusText by rememberSaveable { mutableStateOf(focus.toString()) }
    var restText by rememberSaveable { mutableStateOf(rest.toString()) }
    var goalText by rememberSaveable { mutableStateOf(goal.toString()) }
    var longBreak by rememberSaveable { mutableStateOf(store.longBreakMinutes.toString()) }
    var rounds by rememberSaveable { mutableStateOf(store.rounds.toString()) }
    var appearance by rememberSaveable { mutableStateOf(store.appearance) }
    var selectedTheme by rememberSaveable { mutableStateOf(theme) }
    var selectedSound by rememberSaveable { mutableStateOf(sound) }
    var showNudges by rememberSaveable { mutableStateOf(nudges) }
    val a = focusText.toIntOrNull() ?: 0
    val b = restText.toIntOrNull() ?: 0
    val c = goalText.toIntOrNull() ?: 0
    val d = longBreak.toIntOrNull() ?: 0
    val e = rounds.toIntOrNull() ?: 0
    OrbitScreen(stringResource(R.string.study_preferences), onDismiss) {
        Text(stringResource(R.string.saved_on_this_device_no_account_needed), color = Muted, fontSize = 13.sp)
        SectionTitle("Timer", Modifier.padding(top = Gap.sm))
        Column(verticalArrangement = Arrangement.spacedBy(Gap.sm)) {
            PreferenceNumber("Focus duration", focusText, { focusText = it }, "min", 1..180)
            PreferenceNumber("Short break", restText, { restText = it }, "min", 1..180)
            PreferenceNumber("Long break", longBreak, { longBreak = it }, "min", 1..180)
            PreferenceNumber("Pomodoro rounds", rounds, { rounds = it }, "rounds", 2..12)
            PreferenceNumber("Daily goal", goalText, { goalText = it }, "min", 1..720)
        }
        SectionTitle("Atmosphere", Modifier.padding(top = Gap.sm))
        MusicPicker(selectedSound) { selectedSound = it }
        Column(Modifier.fillMaxWidth().orbitField().padding(horizontal = Gap.lg, vertical = Gap.md),
            verticalArrangement = Arrangement.spacedBy(Gap.sm)) {
            Text(stringResource(R.string.app_appearance), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            OrbitSegmented(AppAppearance.entries.map { stringResource(it.label) }, appearance.ordinal,
                onSelect = { appearance = AppAppearance.entries[it] })
        }
        ThemePicker(selectedTheme, onSelect = { selectedTheme = it })
        SectionTitle("Reminders", Modifier.padding(top = Gap.sm))
        Panel(contentPadding = PaddingValues(horizontal = Gap.lg)) {
            ToggleRule(stringResource(R.string.companion_nudges), "", showNudges) { showNudges = it }
        }
        OrbitButton(stringResource(R.string.notification_settings), onNotifications, Modifier.fillMaxWidth(), primary = false,
            container = OrbitSurface)
        OrbitButton(stringResource(R.string.save_settings), {
            store.longBreakMinutes = d
            store.rounds = e
            onSave(a, b, c, selectedTheme, selectedSound, showNudges, appearance)
        }, Modifier.fillMaxWidth(), enabled = a in 1..180 && b in 1..180 && c in 1..720 && d in 1..180 && e in 2..12)
    }
}

@Composable
private fun PreferenceNumber(label: String, value: String, onChange: (String) -> Unit, suffix: String, range: IntRange) {
    OrbitField(label, value, { onChange(it.filter(Char::isDigit).take(3)) }, Modifier.fillMaxWidth(),
        suffix = suffix, numeric = true, inline = true, error = value.toIntOrNull() !in range,
        errorText = stringResource(R.string.error_range, range.first, range.last))
}

@Composable
private fun PermissionStep(label: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (granted) OrbitIcon(R.drawable.ic_orbit_check, stringResource(R.string.granted))
        else Box(Modifier.size(24.dp).padding(3.dp).border(1.5.dp, Muted, CircleShape))
        Text(label, color = Ink)
    }
}
