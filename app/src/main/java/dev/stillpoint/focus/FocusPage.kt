package dev.stillpoint.focus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusPage(
    active: Session?, result: Int, resultTime: Long, resultKind: FocusResult, phase: Phase, minutes: Int, subject: String, goal: Int, total: Int,
    restMinutes: Int, rounds: Int, longBreak: Int,
    mode: TimerMode, strict: Boolean, theme: TimerTheme, sound: Soundscape, streak: Int?,
    onMode: (TimerMode) -> Unit, onStrict: (Boolean) -> Unit, onPin: () -> Unit,
    onPhase: (Phase) -> Unit, onMinutes: (Int) -> Unit, onSubject: (String) -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onEnd: () -> Unit, onGoal: () -> Unit, onReset: () -> Unit,
    onTheme: (TimerTheme) -> Unit, onSound: (Soundscape) -> Unit, onMiniTimer: (() -> Unit)?,
    shortcuts: @Composable () -> Unit
) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = active == null && result < 0
    val completed = active == null && result >= 0
    val stopwatch = mode == TimerMode.STOPWATCH
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (ready) {
                FocusModeBar(mode, onMode)
                Spacer(Modifier.height(12.dp))
            } else {
                Spacer(Modifier.height(8.dp))
                val state = when {
                    completed -> "✓  SESSION COMPLETE"
                    active?.strict == true -> "◇  STRICT SESSION"
                    active?.running == false -> "Ⅱ  PAUSED"
                    mode == TimerMode.POMODORO -> "●  ${phase.label.uppercase()} · ROUND ${active?.round ?: 1} OF ${active?.rounds ?: rounds}"
                    stopwatch -> "●  STOPWATCH RUNNING"
                    phase == Phase.BREAK -> "●  BREAK IN PROGRESS"
                    else -> "●  FOCUS IN PROGRESS"
                }
                Text(state, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
            }

            SessionPoster(active, result, resultTime, resultKind, phase, mode, minutes,
                restMinutes, rounds, onEdit = if (ready && !stopwatch) ({ sheet = "duration" }) else null,
                onPhase = if (ready && mode == TimerMode.TIMER) onPhase else null,
                onStart = if (ready) onStart else null)

            if (completed) {
                Spacer(Modifier.height(11.dp))
                OrbitHeading(when (resultKind) {
                    FocusResult.BREAK -> "Break complete."
                    FocusResult.CYCLE -> "Pomodoro complete."
                    FocusResult.ENDED -> "A fresh start is always here."
                    else -> "Nice work."
                }, size = 24)
                Text(when (resultKind) {
                    FocusResult.BREAK -> "Ready for another study session?"
                    FocusResult.ENDED -> "Only completed countdown rounds count."
                    FocusResult.STOPWATCH -> "Full stopwatch minutes count toward your goal."
                    else -> "Focus time counts. Breaks stay separate."
                },
                    color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(43.dp))
                OrbitButton("View progress", onGoal, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OrbitButton("Back to Focus", onReset, Modifier.fillMaxWidth(), primary = false, rounded = false)
                Spacer(Modifier.height(19.dp))
                Text("Today’s goal · $total / $goal min", color = Accent, modifier = Modifier.padding(8.dp))
                return@Column
            }

            Spacer(Modifier.height(if (ready) 14.dp else 24.dp))
            if (ready) {
                if (!stopwatch) {
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val presets = if (phase == Phase.BREAK) listOf(5, 10, 15) else listOf(15, 25, 50)
                        presets.forEach { duration ->
                            val selected = minutes == duration
                            Box(Modifier.weight(1f).clip(CircleShape)
                                .background(if (selected) AccentSurface else OrbitSurface)
                                .border(1.dp, if (selected) Accent else Line, CircleShape)
                                .selectable(selected, role = Role.RadioButton) { onMinutes(duration) }
                                .heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                Text("$duration min", color = if (selected) Accent else Ink, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    Modifier.fillMaxWidth().clip(FieldShape).clickable(role = Role.Button) { sheet = "setup" }
                        .heightIn(min = 60.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Session options", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(when {
                            phase == Phase.BREAK -> "Break duration"
                            strict && !stopwatch -> "Strict mode on"
                            subject.isNotBlank() -> subject
                            stopwatch -> "Study task"
                            else -> "Duration, task and strict mode"
                        }, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("Edit", color = Accent, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                FocusGoal(total, goal, onGoal)
                val nudge = when {
                    mode == TimerMode.POMODORO -> "$rounds × $minutes min · Long break after round $rounds ($longBreak min)"
                    streak == null -> ""
                    streak >= 2 -> "✦  $streak study days in a row. Keep your orbit."
                    total >= goal -> "✦  You reached today’s goal."
                    else -> ""
                }
                if (nudge.isNotEmpty()) Text(nudge, Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            } else if (active != null) {
                if (active.subject.isNotBlank() && phase == Phase.FOCUS) Text(active.subject, Modifier.padding(bottom = 12.dp), color = Ink)
                if (active.strict) {
                    Text("Blocking rules locked until this session ends",
                        Modifier.fillMaxWidth().background(AccentSurface, FieldShape).padding(17.dp),
                        color = Accent, textAlign = TextAlign.Center, fontSize = 13.sp)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OrbitButton(if (active.running) "Pause" else "Resume", onPause, Modifier.weight(1f))
                        OrbitButton(if (mode == TimerMode.POMODORO) "End" else "Finish", onEnd,
                            Modifier.width(104.dp), primary = false)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (active.strict) OrbitButton("Pin screen", onPin, Modifier.weight(1f), primary = false, container = OrbitSurface)
                    OrbitButton("Sound & theme", { sheet = "atmosphere" }, Modifier.weight(1f), primary = false, container = OrbitSurface)
                    if (!active.strict && onMiniTimer != null) {
                        OrbitButton("Float timer", onMiniTimer, Modifier.weight(1f), primary = false, container = OrbitSurface)
                    }
                }
                if (active.strict) {
                    Text("Android screen pinning is optional", Modifier.padding(top = 15.dp), color = Muted, fontSize = 12.sp)
                } else {
                    TextButton({ sheet = "shortcuts" }, Modifier.align(Alignment.Start).padding(start = 20.dp, top = 4.dp)) {
                        Text("Study shortcuts → Open study list", color = Accent, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (sheet == "duration" && ready) {
        var length by rememberSaveable { mutableStateOf(minutes.toString()) }
        val parsed = length.toIntOrNull()
        OrbitSheet({ sheet = null }) {
            OrbitHeading(if (phase == Phase.BREAK) "Make time for a break" else "Make time for focus", size = 26)
            Spacer(Modifier.height(12.dp))
            OrbitField("${phase.label} duration", length, { length = it.filter(Char::isDigit).take(3) },
                Modifier.fillMaxWidth(), suffix = "min", numeric = true, error = parsed !in 1..180)
            Text("Choose a duration that feels achievable.", Modifier.padding(top = 9.dp), color = Muted)
            Spacer(Modifier.height(30.dp))
            OrbitButton("Save duration", { parsed?.let(onMinutes); sheet = null },
                Modifier.fillMaxWidth(), enabled = parsed in 1..180)
        }
    }
    if (sheet == "setup" && ready) {
        var length by rememberSaveable(mode, phase) { mutableStateOf(minutes.toString()) }
        var task by rememberSaveable { mutableStateOf(subject) }
        var strictSession by rememberSaveable { mutableStateOf(strict) }
        val selectedMinutes = length.toIntOrNull()
        OrbitSheet({ sheet = null }) {
            OrbitHeading("Set up your session", size = 26)

            Spacer(Modifier.height(12.dp))
            Text(when (mode) {
                TimerMode.TIMER -> "${phase.label} timer"
                TimerMode.POMODORO -> "Pomodoro · $rounds focus rounds"
                TimerMode.STOPWATCH -> "Stopwatch · Count up"
            },
                Modifier.fillMaxWidth().background(AccentSurface, FieldShape).padding(16.dp),
                color = Accent, fontWeight = FontWeight.SemiBold)
            if (!stopwatch) OrbitField(if (mode == TimerMode.POMODORO) "Focus duration" else "Duration", length, { length = it.filter(Char::isDigit).take(3) },
                Modifier.fillMaxWidth(), suffix = "min", numeric = true, error = selectedMinutes !in 1..180)
            if (phase == Phase.FOCUS) {
                OrbitField("Study task · optional", task, { task = it.take(70) }, Modifier.fillMaxWidth(),
                    placeholder = "What are you working on?")
                if (mode == TimerMode.POMODORO) {
                    Text("Short break  $restMinutes min    Long break  $longBreak min", color = Muted, fontSize = 13.sp)
                    Text("Long break follows round $rounds.", color = Muted, fontSize = 13.sp)
                }
                if (!stopwatch) ToggleRule("Strict Mode", "No pausing or early exit. Rules stay locked.", strictSession) {
                    strictSession = it
                }
            }
            Spacer(Modifier.height(22.dp))
            OrbitButton("Start session", {
                if (!stopwatch) selectedMinutes?.let(onMinutes)
                if (phase == Phase.FOCUS) {
                    onSubject(task.trim())
                    if (!stopwatch) onStrict(strictSession)
                }
                sheet = null
                onStart()
            }, Modifier.fillMaxWidth(), enabled = stopwatch || selectedMinutes in 1..180)
            OrbitButton("Cancel", { sheet = null }, Modifier.fillMaxWidth(), primary = false, rounded = false)
        }
    }
    if (sheet == "atmosphere" && active != null) OrbitSheet({ sheet = null }) {
        OrbitHeading("Set the atmosphere", size = 26)
        Text("Changes apply immediately to your session.", color = Muted)
        Text("SOUND", Modifier.padding(top = 8.dp), color = Muted, fontSize = 11.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Soundscape.entries.forEach { item ->
                val selected = item == sound
                Row(
                    Modifier.fillMaxWidth().clip(FieldShape).background(if (selected) AccentSurface else OrbitSurface)
                        .selectable(selected, onClick = { onSound(item) }, role = Role.RadioButton)
                        .heightIn(min = 48.dp).padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.label, Modifier.weight(1f), color = if (selected) Accent else Ink)
                    Text(if (selected) "●" else "○", color = Accent, fontSize = 17.sp)
                }
            }
        }
        Text("THEME", color = Muted, fontSize = 11.sp)
        TimerTheme.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    OrbitButton(item.label, { onTheme(item) }, Modifier.weight(1f), primary = false, rounded = false,
                        container = if (item == TimerTheme.ORBIT) AccentSurface else item.pageColor)
                }
            }
        }
        Text("${sound.label} · ${theme.label} theme selected", color = Muted, fontSize = 12.sp)
        OrbitButton("Done", { sheet = null }, Modifier.fillMaxWidth())
    }
    if (sheet == "shortcuts" && active != null) OrbitSheet({ sheet = null }) {
        OrbitHeading("Your study list", size = 26)
        Text("Saved sites and channels, ready when you are.", color = Muted)
        shortcuts()
        OrbitButton("Back to session", { sheet = null }, Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitSheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose, containerColor = Paper, contentColor = Ink,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content
        )
    }
}

@Composable
private fun FocusModeBar(mode: TimerMode, onMode: (TimerMode) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(FieldShape).background(OrbitSurface).selectableGroup().padding(3.dp)) {
        TimerMode.entries.forEach { option ->
            val selected = mode == option
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(FieldShape)
                .background(if (selected) Accent else OrbitSurface)
                .selectable(selected, role = Role.Tab) { onMode(option) }.padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(option.label, color = if (selected) OnAccent else Muted, fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.Center)
            }
        }
    }
}

internal enum class FocusResult { FOCUS, BREAK, CYCLE, STOPWATCH, ENDED }

@Composable
internal fun rememberDisplayedTime(active: Session?): State<Long?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val displayedTime = remember(active) { mutableStateOf(active?.displayTime()) }
    LaunchedEffect(active, lifecycle) {
        if (active?.running != true) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val stopwatch = active.mode == TimerMode.STOPWATCH
            while (true) {
                val next = active.displayTime()
                displayedTime.value = next
                if (!stopwatch && next == 0L) break
                delay(timerTickDelay(next, stopwatch))
            }
        }
    }
    return displayedTime
}

@Composable
private fun SessionPoster(
    active: Session?, result: Int, resultTime: Long, resultKind: FocusResult, phase: Phase,
    mode: TimerMode, minutes: Int, restMinutes: Int, rounds: Int,
    onEdit: (() -> Unit)?, onPhase: ((Phase) -> Unit)?, onStart: (() -> Unit)?
) {
    val time = rememberDisplayedTime(active)
    val stopwatch = mode == TimerMode.STOPWATCH
    val display = when {
        result >= 0 -> resultTime
        active != null -> time.value ?: active.remaining
        stopwatch -> 0L
        else -> minutes * MINUTE
    }
    val digits = timerDigits(display, if (result >= 0) resultKind == FocusResult.STOPWATCH else stopwatch)
    val white = androidx.compose.ui.graphics.Color.White
    AtlasPanel(R.drawable.atlas_focus) {
        Column(Modifier.fillMaxWidth().heightIn(min = 248.dp).padding(20.dp)) {
            if (onPhase != null) {
                Row(Modifier.selectableGroup().clip(CircleShape).background(white.copy(alpha = .14f))) {
                    Phase.entries.forEach { option ->
                        val selected = option == phase
                        Box(Modifier.clip(CircleShape).background(if (selected) white else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(selected, role = Role.Tab) { onPhase(option) }
                            .heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(option.label, color = if (selected) Ink else white, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.then(if (onEdit != null) Modifier.clickable(onClickLabel = "Edit duration", onClick = onEdit) else Modifier)) {
                Text(digits, color = white, fontSize = if (digits.length > 5) 44.sp else 60.sp,
                    lineHeight = 66.sp, letterSpacing = (-2).sp, fontWeight = FontWeight.SemiBold,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                Text(when {
                    result >= 0 -> if (resultKind == FocusResult.BREAK) "Break complete" else "$result study minutes saved"
                    active?.running == false -> "Session paused"
                    stopwatch -> "Elapsed time"
                    mode == TimerMode.POMODORO -> "$rounds rounds · $restMinutes min breaks"
                    onEdit != null -> "Edit duration"
                    else -> "Time remaining"
                }, color = white, fontSize = 14.sp)
            }
            if (onStart != null) {
                Spacer(Modifier.height(28.dp))
                Button(onClick = onStart, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFFFBCAC), contentColor = Ink),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_play), null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(when {
                        stopwatch -> "Start stopwatch"
                        mode == TimerMode.POMODORO -> "Start Pomodoro"
                        phase == Phase.BREAK -> "Start break"
                        else -> "Start focusing"
                    }, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FocusGoal(total: Int, goal: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 79.dp).clip(CardShape).background(AccentSurface)
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today’s goal", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("$total / $goal min", Modifier.padding(end = 2.dp), color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { (total.toFloat() / goal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            color = Accent, trackColor = OrbitTrack, drawStopIndicator = {})
    }
}
