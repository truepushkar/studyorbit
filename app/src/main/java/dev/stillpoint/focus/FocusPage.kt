package dev.stillpoint.focus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale
import kotlin.math.roundToInt
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
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        val dialDiameter = minOf(maxWidth, 310.dp, (maxHeight - 335.dp).coerceAtLeast(242.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (ready) {
                FocusModeBar(mode, onMode)
                if (mode == TimerMode.TIMER) {
                    Row(
                        Modifier.selectableGroup().padding(top = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Phase.entries.forEach { option ->
                            val selected = option == phase
                            Box(
                                Modifier.widthIn(min = 80.dp).heightIn(min = 48.dp).clip(CircleShape)
                                    .selectable(selected, role = Role.Tab) { onPhase(option) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    option.label, Modifier.background(if (selected) Lavender else theme.pageColor, CircleShape)
                                        .padding(horizontal = 14.dp, vertical = 5.dp),
                                    color = if (selected) Purple else Muted, fontSize = 13.sp, lineHeight = 18.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                } else Spacer(Modifier.height(27.dp))
                Spacer(Modifier.height(3.dp))
            } else {
                Spacer(Modifier.height(33.dp))
                val state = when {
                    completed -> "✓  SESSION COMPLETE"
                    active?.strict == true -> "◇  STRICT SESSION"
                    active?.running == false -> "Ⅱ  PAUSED"
                    mode == TimerMode.POMODORO -> "●  ${phase.label.uppercase()} · ROUND ${active?.round ?: 1} OF ${active?.rounds ?: rounds}"
                    stopwatch -> "●  STOPWATCH RUNNING"
                    phase == Phase.BREAK -> "●  BREAK IN PROGRESS"
                    else -> "●  FOCUS IN PROGRESS"
                }
                Text(state, color = Purple, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
            }

            FlowerTimer(
                active, result, resultTime, resultKind, phase, mode, minutes, restMinutes, rounds, dialDiameter,
                onEdit = if (ready && !stopwatch) ({ sheet = "duration" }) else null
            )

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
                Text("Today’s goal · $total / $goal min", color = Purple, modifier = Modifier.padding(8.dp))
                return@Column
            }

            Spacer(Modifier.height(if (ready) 15.dp else 35.dp))
            val task = if (phase == Phase.BREAK) "Stretch, sip water, look away." else {
                (active?.subject ?: subject).ifBlank { "Add a study task" }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(CardShape).background(OrbitSurface)
                    .border(1.dp, Line, CardShape)
                    .then(if (ready && phase == Phase.FOCUS) Modifier.clickable { sheet = "task" } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("STUDY TASK", color = Muted, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(task + if (ready && phase == Phase.FOCUS && subject.isNotBlank()) "  ·  Edit" else "",
                    color = Ink, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(20.dp))
            if (ready) {
                OrbitButton(when {
                    stopwatch -> "Start stopwatch"
                    mode == TimerMode.POMODORO -> "Start Pomodoro"
                    phase == Phase.BREAK -> "Start break"
                    else -> "Start focusing"
                }, { sheet = "setup" }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
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
                if (active.strict) {
                    Text("Blocking rules locked until this session ends",
                        Modifier.fillMaxWidth().background(Lavender, FieldShape).padding(17.dp),
                        color = Purple, textAlign = TextAlign.Center, fontSize = 13.sp)
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
                        Text("Study shortcuts → Open study list", color = Purple, fontSize = 14.sp)
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
            Text("A clear intention. One thing at a time.", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Text(when (mode) {
                TimerMode.TIMER -> "${phase.label} timer"
                TimerMode.POMODORO -> "Pomodoro · $rounds focus rounds"
                TimerMode.STOPWATCH -> "Stopwatch · Count up"
            },
                Modifier.fillMaxWidth().background(Lavender, FieldShape).padding(16.dp),
                color = Purple, fontWeight = FontWeight.SemiBold)
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
    if (sheet == "task" && ready) {
        var task by rememberSaveable { mutableStateOf(subject) }
        OrbitSheet({ sheet = null }) {
            OrbitHeading("What will you work on?", size = 26)
            Spacer(Modifier.height(14.dp))
            OrbitField("Study task · optional", task, { task = it.take(70) }, Modifier.fillMaxWidth(),
                placeholder = "What are you working on?")
            Text("A small, specific task is a good place to start.", Modifier.padding(top = 8.dp), color = Muted)
            Spacer(Modifier.height(22.dp))
            OrbitButton("Save task", { onSubject(task.trim()); sheet = null }, Modifier.fillMaxWidth())
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
                    Modifier.fillMaxWidth().clip(FieldShape).background(if (selected) Lavender else OrbitSurface)
                        .selectable(selected, onClick = { onSound(item) }, role = Role.RadioButton)
                        .heightIn(min = 48.dp).padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.label, Modifier.weight(1f), color = if (selected) Purple else Ink)
                    Text(if (selected) "●" else "○", color = Purple, fontSize = 17.sp)
                }
            }
        }
        Text("THEME", color = Muted, fontSize = 11.sp)
        TimerTheme.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    OrbitButton(item.label, { onTheme(item) }, Modifier.weight(1f), primary = false, rounded = false,
                        container = if (item == TimerTheme.ORBIT) Lavender else item.pageColor)
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
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(Night).selectableGroup().padding(horizontal = 4.dp)) {
        TimerMode.entries.forEach { option ->
            val selected = mode == option
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(FieldShape)
                .background(if (selected) OrbitSurface else Night)
                .selectable(selected, role = Role.Tab) { onMode(option) }.padding(horizontal = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(option.label, color = if (selected) Purple else Muted, fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.Center)
            }
        }
    }
}

internal enum class FocusResult { FOCUS, BREAK, CYCLE, STOPWATCH, ENDED }

@Composable
internal fun rememberDisplayedTime(active: Session?): State<Long?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(active?.displayTime(), active, lifecycle) {
        value = active?.displayTime()
        if (active?.running != true) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val stopwatch = active.mode == TimerMode.STOPWATCH
            while (true) {
                val next = active.displayTime()
                value = next
                if (!stopwatch && next == 0L) break
                val remainder = next % 1000L
                delay(if (stopwatch) 1000L - remainder else if (remainder == 0L) 1000L else remainder)
            }
        }
    }
}

internal fun timerDigits(display: Long, stopwatch: Boolean): String {
    val seconds = if (stopwatch) display / 1000 else (display + 999) / 1000
    val hours = if (stopwatch) seconds / 3600 else 0
    val shownMinutes = if (hours > 0) seconds / 60 % 60 else seconds / 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, shownMinutes, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", shownMinutes, seconds % 60)
}

@Composable
private fun FlowerTimer(
    active: Session?, result: Int, resultTime: Long, resultKind: FocusResult, phase: Phase,
    mode: TimerMode, minutes: Int, restMinutes: Int, rounds: Int, diameter: androidx.compose.ui.unit.Dp, onEdit: (() -> Unit)?
) {
    val stopwatch = mode == TimerMode.STOPWATCH
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val time = rememberDisplayedTime(active)
    val bloom = remember(active?.running, active?.round, active?.phase) { Animatable(0f) }
    LaunchedEffect(bloom, lifecycle) {
        if (active?.running != true) return@LaunchedEffect
        var played = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            bloom.snapTo(0f)
            if (!played) {
                played = true
                bloom.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
                bloom.animateTo(0f, tween(440, easing = FastOutSlowInEasing))
            }
        }
    }
    val base = ImageBitmap.imageResource(R.drawable.fig_petal)
    val lit = ImageBitmap.imageResource(R.drawable.fig_petal_active)
    val pomodoro = mode == TimerMode.POMODORO
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().graphicsLayer {
            val amount = bloom.value
            rotationZ = amount * 4f
            scaleX = 1f + amount * 0.036f
            scaleY = scaleX
        }) {
            val fraction = if (active != null && active.duration > 0 && !stopwatch) {
                1f - (time.value ?: active.remaining).toFloat() / active.duration
            } else if (active != null && stopwatch) {
                (time.value ?: active.remaining).toFloat() / (25 * MINUTE)
            } else 0f
            val petals = if (result >= 0) 12 else (1 + (fraction * 12).toInt()).coerceIn(1, 12)
            val width = (size.width * 61f / 310f).roundToInt()
            val height = (size.height * 112f / 310f).roundToInt()
            repeat(12) { index ->
                rotate(index * 30f, pivot = center) {
                    drawImage(if (index < petals) lit else base,
                        dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), 0),
                        dstSize = IntSize(width, height))
                }
            }
        }
        val canEdit = if (onEdit == null) Modifier else Modifier.clickable(onClickLabel = "Edit duration", onClick = onEdit)
        Column(
            Modifier.size(diameter * (218f / 310f)).clip(CircleShape).background(Paper)
                .border(1.dp, OrbitTrack, CircleShape).then(canEdit).padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            val display = when {
                result >= 0 -> resultTime
                active != null -> time.value ?: active.remaining
                stopwatch -> 0L
                else -> minutes * MINUTE
            }
            val formatted = timerDigits(display, if (result >= 0) resultKind == FocusResult.STOPWATCH else stopwatch)
            val letterSize = if (formatted.length > 5) 40.sp else (diameter.value * 56f / 310f).sp
            Text(formatted, color = Ink, fontSize = letterSize, lineHeight = (letterSize.value + 10).sp,
                fontWeight = FontWeight.Medium, maxLines = 1,
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.semantics { contentDescription = formatted })
            val caption = when {
                result >= 0 && resultKind == FocusResult.BREAK -> "Break time recorded separately"
                result >= 0 -> "$result study minutes ${if (result == 0) "added" else "saved"}"
                active?.running == false -> "Take a breath. Come back ready."
                stopwatch -> "Count up · no time limit"
                pomodoro -> "$rounds rounds · $restMinutes min breaks"
                phase == Phase.BREAK -> "A moment to recharge"
                active != null -> "Stay with one thing."
                else -> "$minutes min  ·  tap to edit"
            }
            Text(caption, Modifier.padding(top = 7.dp), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FocusGoal(total: Int, goal: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 79.dp).clip(CardShape).background(Lavender)
        .border(1.dp, Line, CardShape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today’s goal", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("$total / $goal min", Modifier.padding(end = 27.dp), color = Purple, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = { (total.toFloat() / goal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
            color = Purple, trackColor = OrbitTrack, drawStopIndicator = {})
    }
}
