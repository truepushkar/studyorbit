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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

internal val Paper = Color(0xFFF8F7F2)
internal val Ink = Color(0xFF26243A)
internal val Muted = Color(0xFF625E73)
internal val Purple = Color(0xFF5C4193)
internal val Lavender = Color(0xFFECE4FC)
internal val Lilac = Color(0xFFD9C6FF)
internal val Line = Color(0xFFE7E3EB)
internal val Mint = Color(0xFFE4EEE4)
internal val Forest = Color(0xFF305442)
internal val White = Color(0xFFFFFFFF)
internal val Night = Color(0xFF232037)
internal val CardShape = RoundedCornerShape(24.dp)

class MainActivity : ComponentActivity() {
    private var resumeCount by mutableIntStateOf(0)
    private var focusRequests by mutableIntStateOf(0)
    private var plannerRequests by mutableIntStateOf(0)
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
        if (savedInstanceState == null) handleRoute(intent)
        setContent { StudyOrbit(store, apps, resumeCount, focusRequests, plannerRequests, ::pin, ::releasePin) }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRoute(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("focus_pin", pinRequested)
        super.onSaveInstanceState(outState)
    }

    private fun handleRoute(intent: Intent) {
        when (intent.action) {
            SHOW_FOCUS -> focusRequests++
            SHOW_PLANNER -> plannerRequests++
        }
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

private enum class Destination(val label: String, val glyph: Glyph) {
    FOCUS("Focus", Glyph.TIMER), BLOCK("Block", Glyph.SHIELD), PLANNER("Planner", Glyph.CALENDAR), PROGRESS("Progress", Glyph.CHART)
}

@Composable
private fun StudyOrbit(
    store: FocusStore, apps: DeviceApps, resumes: Int, focusRequests: Int, plannerRequests: Int,
    onPin: () -> Unit, onReleasePin: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var destination by rememberSaveable { mutableStateOf(Destination.FOCUS) }
    var phase by rememberSaveable { mutableStateOf(Phase.FOCUS) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var endConfirmation by rememberSaveable { mutableStateOf(false) }
    var strictConfirmation by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf(store.session()) }
    var history by remember { mutableStateOf(store.history()) }
    var focusMinutes by remember { mutableIntStateOf(store.focusMinutes) }
    var breakMinutes by remember { mutableIntStateOf(store.breakMinutes) }
    var goal by remember { mutableIntStateOf(store.goalMinutes) }
    var subject by remember { mutableStateOf(store.subject) }
    var mode by rememberSaveable { mutableStateOf(TimerMode.entries.getOrElse(store.prefs.getInt("timer_mode", 0)) { TimerMode.TIMER }) }
    var strict by rememberSaveable { mutableStateOf(store.prefs.getBoolean("strict_preference", false)) }
    var theme by remember { mutableStateOf(TimerTheme.entries.getOrElse(store.prefs.getInt("timer_theme", 0)) { TimerTheme.ORBIT }) }
    var sound by remember { mutableStateOf(store.soundscape()) }
    var nudges by remember { mutableStateOf(store.prefs.getBoolean("focus_nudges", true)) }
    var schedules by remember { mutableStateOf(readSchedules(store.prefs)) }
    val planner = remember(store) { StudyPlanner(context, store.prefs) }
    val policy = remember(context) { context.getSystemService(DevicePolicyManager::class.java) }
    val admin = remember(context) { ComponentName(context, StudyAdmin::class.java) }
    var adminActive by remember(resumes) { mutableStateOf(policy?.isAdminActive(admin) == true) }
    val exactAlarms = remember(resumes) {
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val blockAccess = remember(resumes) { apps.hasBlockAccess() }
    val usageAccess = remember(resumes) { apps.hasUsageAccess() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) store.syncStudyNotification()
    }
    val asked = rememberSaveable { mutableStateOf(store.prefs.getBoolean("asked_notifications", false)) }

    fun refresh() {
        active = store.session()
        history = store.history()
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
        val targetMode = if (task == null) mode else TimerMode.TIMER
        val targetPhase = if (task == null && mode == TimerMode.TIMER) phase else Phase.FOCUS
        store.start(targetPhase, minutes, targetMode, task == null && strict && targetPhase == Phase.FOCUS, task ?: subject)
        destination = Destination.FOCUS
        refresh()
        requestNotifications()
        syncAudio()
    }

    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            destination = Destination.FOCUS
            settings = false
            disclosure = false
        }
    }

    LaunchedEffect(plannerRequests) {
        if (plannerRequests > 0) {
            destination = Destination.PLANNER
            settings = false
            disclosure = false
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

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Purple, onPrimary = White, primaryContainer = Lavender, onPrimaryContainer = Ink,
            background = Paper, onBackground = Ink, surface = White, onSurface = Ink,
            surfaceVariant = Lavender, onSurfaceVariant = Muted, outline = Color(0xFF9A92A8)
        ),
        typography = Typography()
    ) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 580.dp).fillMaxWidth().weight(1f)) {
                    AppHeader { settings = true }
                    when (destination) {
                        Destination.FOCUS -> FocusPage(
                            active = active,
                            phase = active?.phase ?: if (mode == TimerMode.TIMER) phase else Phase.FOCUS,
                            minutes = if (mode != TimerMode.TIMER || phase == Phase.FOCUS) focusMinutes else breakMinutes,
                            mode = active?.mode ?: mode, strict = active?.strict ?: strict, theme = theme,
                            streak = if (nudges) remember(history, today) { studyStreak(history, today) } else null,
                            subject = subject,
                            goal = goal,
                            total = remember(history, today) { history.filter { it.day == today }.sumOf { it.minutes } },
                            today = today,
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
                            onGoal = { destination = Destination.PROGRESS },
                            shortcuts = { StudyShortcuts(readContentRules(store.prefs)) }
                        )
                        Destination.BLOCK -> BlockPage(
                            apps, store, blockAccess, usageAccess, active?.strict == true, today, resumes,
                            onBlockAccess = { disclosure = true },
                            onUsageAccess = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
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
                        Destination.PLANNER -> PlannerPage(
                            schedules, today, active != null, active?.strict == true, exactAlarms,
                            onExactAlarms = {
                                openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, data = Uri.parse("package:${context.packageName}"))
                            },
                            onSave = { updated, reminder ->
                                planner.save(updated)
                                schedules = readSchedules(store.prefs)
                                if (reminder) requestNotifications()
                            },
                            onStart = { launchSession(it.minutes, it.name) }
                        )
                        Destination.PROGRESS -> ProgressPage(
                            history, today, goal, apps, store, usageAccess,
                            onUsageAccess = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
                        )
                    }
                }
                BottomTabs(destination) { destination = it }
            }
        }

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
        if (strictConfirmation) AlertDialog(
            onDismissRequest = { strictConfirmation = false },
            title = { Text("Start in Strict Mode?") },
            text = { Text("You won’t be able to pause, end early, or edit blocking rules in StudyOrbit until this ${if (mode == TimerMode.POMODORO) "cycle" else "timer"} ends.") },
            confirmButton = { TextButton(onClick = { strictConfirmation = false; launchSession(focusMinutes) }) { Text("Start strict session") } },
            dismissButton = { TextButton(onClick = { strictConfirmation = false }) { Text("Cancel") } }
        )
        if (disclosure) AlertDialog(
            onDismissRequest = { disclosure = false },
            title = { Text("Enable app blocking") },
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
        if (endConfirmation) AlertDialog(
            onDismissRequest = { endConfirmation = false },
            title = { Text("End this session?") },
            text = { Text(if (active?.mode == TimerMode.STOPWATCH) "Full minutes on the stopwatch count toward your goal."
                else "Only completed focus rounds count toward your goal.") },
            confirmButton = {
                TextButton(onClick = { endConfirmation = false; store.stop(); refresh(); syncAudio() }) { Text("End session") }
            },
            dismissButton = { TextButton(onClick = { endConfirmation = false }) { Text("Keep going") } }
        )
    }
}

@Composable
private fun AppHeader(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 23.dp, end = 18.dp, top = 11.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.ic_studyorbit), contentDescription = null, modifier = Modifier.size(39.dp))
        Text(stringResource(R.string.app_name), Modifier.padding(start = 9.dp).weight(1f), fontSize = 23.sp,
            color = Ink, fontWeight = FontWeight.Bold)
        IconButton(
            onClick = onSettings,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "Settings" }
        ) {
            Box(Modifier.size(39.dp).border(1.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
                Mark(Glyph.SETTINGS, Ink, 19)
            }
        }
    }
}

@Composable
private fun BottomTabs(current: Destination, onSelect: (Destination) -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 580.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = Night
    ) {
        Row(Modifier.selectableGroup().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Destination.entries.forEach { tab ->
                val selected = tab == current
                val color = if (selected) Night else Color(0xFFE4DDF0)
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(22.dp))
                        .background(if (selected) Lilac else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                        .padding(horizontal = 3.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                ) {
                    Mark(tab.glyph, color, 21)
                    Text(tab.label, color = color, fontSize = 12.sp, textAlign = TextAlign.Center,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun PageTitle(kicker: String, title: String, subtitle: String) {
    Text(kicker, color = Purple, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.9.sp)
    Text(title, Modifier.padding(top = 5.dp).semantics { heading() }, color = Ink, fontFamily = FontFamily.Serif,
        fontSize = 41.sp, lineHeight = 44.sp, letterSpacing = (-1.6).sp)
    Text(subtitle, Modifier.padding(top = 7.dp), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
}

@Composable
internal fun GoalCard(total: Int, goal: Int, onClick: (() -> Unit)? = null) {
    val card = if (onClick == null) Modifier else Modifier.clickable(onClickLabel = "View study progress", onClick = onClick)
    Column(Modifier.fillMaxWidth().clip(CardShape).background(Mint).then(card).padding(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(39.dp).clip(RoundedCornerShape(12.dp)).background(White.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center) { Mark(Glyph.TARGET, Forest, 21) }
            Column(Modifier.padding(start = 11.dp).weight(1f)) {
                Text("Daily study goal", color = Forest, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("$total of $goal min", color = Forest, fontSize = 12.sp)
            }
            if (onClick != null) Mark(Glyph.ARROW, Forest, 19)
        }
        Spacer(Modifier.height(11.dp))
        LinearProgressIndicator(
            progress = { (total.toFloat() / goal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            trackColor = Color(0xFFCBDDCD), color = Forest, drawStopIndicator = {}
        )
    }
}

@Composable
internal fun NumberDialog(title: String, label: String, initial: Int, range: IntRange, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial.toString()) }
    val parsed = value.toIntOrNull()?.takeIf { it in range }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { NumberField(value, { value = it }, label, range) },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun NumberField(value: String, onChange: (String) -> Unit, label: String, range: IntRange) {
    OutlinedTextField(
        value, { onChange(it.filter(Char::isDigit).take(3)) }, Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true,
        supportingText = { Text("${range.first}–${range.last} min") },
        isError = value.isNotEmpty() && value.toIntOrNull() !in range,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@OptIn(ExperimentalLayoutApi::class)
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
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Study settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(focusText, { focusText = it }, "Focus duration", 1..180)
                NumberField(restText, { restText = it }, "Short break duration", 1..180)
                NumberField(longBreak, { longBreak = it }, "Long break duration", 1..180)
                NumberField(rounds, { rounds = it }, "Pomodoro rounds", 2..12)
                NumberField(goalText, { goalText = it }, "Daily goal", 1..720)
                Text("Timer theme", color = Ink, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TimerTheme.entries.forEach {
                        FilterChip(selected = selectedTheme == it, onClick = { selectedTheme = it }, label = { Text(it.label) })
                    }
                }
                Text("Focus sounds", color = Ink, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Soundscape.entries.forEach {
                        FilterChip(selected = selectedSound == it, onClick = { selectedSound = it }, label = { Text(it.label) })
                    }
                }
                ToggleRule("Companion nudges", "Show study suggestions and streaks on the Focus tab.", showNudges) { showNudges = it }
                TextButton(onClick = onNotifications) { Text("Notification settings") }
            }
        },
        confirmButton = { TextButton(enabled = a in 1..180 && b in 1..180 && c in 1..720 && d in 1..180 && e in 2..12,
            onClick = {
                store.longBreakMinutes = d
                store.rounds = e
                onSave(a, b, c, selectedTheme, selectedSound, showNudges)
            }) { Text("Save settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
