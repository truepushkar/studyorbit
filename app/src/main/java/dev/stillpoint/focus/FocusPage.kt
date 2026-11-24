package dev.stillpoint.focus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

@Composable
internal fun FocusPage(
    active: Session?, phase: Phase, minutes: Int, subject: String, goal: Int, total: Int, today: LocalDate,
    mode: TimerMode, strict: Boolean, theme: TimerTheme, streak: Int?,
    onMode: (TimerMode) -> Unit, onStrict: (Boolean) -> Unit, onPin: () -> Unit,
    onPhase: (Phase) -> Unit, onMinutes: (Int) -> Unit, onSubject: (String) -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onEnd: () -> Unit, onGoal: () -> Unit,
    shortcuts: @Composable () -> Unit
) {
    var durationDialog by rememberSaveable { mutableStateOf(false) }
    val date = remember(today) { today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.padding(top = 4.dp, bottom = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Focus room", Modifier.semantics { heading() }, color = Ink, fontFamily = FontFamily.Serif,
                fontSize = 39.sp, lineHeight = 43.sp)
            Text(date, color = Muted, fontSize = 14.sp, lineHeight = 19.sp)
        }

        if (active == null) FocusModeBar(mode, onMode)
        FocusTimer(active, phase, mode, theme, minutes, onPhase, { durationDialog = true }, onStart, onPause, onEnd)

        if (active?.strict == true || (active == null && mode != TimerMode.STOPWATCH && phase == Phase.FOCUS)) {
            FocusCommitment(active?.strict == true, strict, onStrict, onPin)
        }
        OutlinedTextField(
            value = subject, onValueChange = onSubject,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (active?.phase == Phase.FOCUS) "Next study task (optional)" else "Study task (optional)") },
            placeholder = { Text("e.g. Revise circuit diagrams") },
            leadingIcon = { Mark(Glyph.BOOK, Purple, 19) },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = White, focusedContainerColor = White, unfocusedBorderColor = Line
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )
        FocusGoal(total, goal, onGoal)
        shortcuts()
        if (streak != null && active == null) {
            HorizontalDivider(color = Line, modifier = Modifier.padding(top = 2.dp))
            Text(
                when {
                    total >= goal -> "You reached today’s goal. Take a moment to recharge."
                    streak >= 2 -> "You’re on a $streak-day study streak."
                    total > 0 -> "Take a break before your next round."
                    else -> "Pick one small task for your first session."
                },
                Modifier.padding(horizontal = 2.dp), color = Muted, fontSize = 13.sp, lineHeight = 18.sp
            )
        }
    }
    if (durationDialog && active == null) NumberDialog(
        title = "${phase.label} duration", label = "Minutes", initial = minutes, range = 1..180,
        onDismiss = { durationDialog = false },
        onSave = { onMinutes(it); durationDialog = false }
    )
}

@Composable
private fun FocusModeBar(mode: TimerMode, onMode: (TimerMode) -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(0xFFF0EBF6))
            .border(BorderStroke(1.dp, Line), shape).selectableGroup().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        TimerMode.entries.forEach { option ->
            val selected = mode == option
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(13.dp))
                    .background(if (selected) Purple else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onMode(option) })
                    .padding(horizontal = 3.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(option.label, color = if (selected) White else Ink, fontSize = 13.sp,
                    lineHeight = 17.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun FocusTimer(
    active: Session?, phase: Phase, mode: TimerMode, theme: TimerTheme, minutes: Int,
    onPhase: (Phase) -> Unit, onDuration: () -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onEnd: () -> Unit
) {
    val (surface, focusAccent) = when (theme) {
        TimerTheme.ORBIT -> Color(0xFF261D3D) to Color(0xFFE3D2FF)
        TimerTheme.FOREST -> Color(0xFF19312B) to Color(0xFFC3E7B4)
        TimerTheme.DUSK -> Color(0xFF402437) to Color(0xFFFFC9BC)
        TimerTheme.OCEAN -> Color(0xFF193143) to Color(0xFFB8E3F2)
    }
    val accent = if (phase == Phase.BREAK) Color(0xFFC4E9D0) else focusAccent
    val stopwatch = mode == TimerMode.STOPWATCH
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(surface)
            .padding(start = 15.dp, end = 15.dp, top = 11.dp, bottom = 15.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (active == null && mode == TimerMode.TIMER) {
                Row(Modifier.weight(1f).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Phase.entries.forEach { option ->
                        val selected = phase == option
                        Box(
                            Modifier.heightIn(min = 48.dp).clip(CircleShape)
                                .selectable(selected = selected, role = Role.Tab, onClick = { onPhase(option) })
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(option.label, color = if (selected) surface else White.copy(alpha = 0.76f),
                                fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center, modifier = Modifier.clip(CircleShape)
                                    .background(if (selected) accent else White.copy(alpha = 0.08f))
                                    .padding(horizontal = 9.dp, vertical = 6.dp))
                        }
                    }
                }
            } else {
                val label = when {
                    mode == TimerMode.POMODORO && active != null -> "Round ${active.round} of ${active.rounds}"
                    mode == TimerMode.POMODORO -> "Pomodoro"
                    stopwatch -> "Stopwatch"
                    phase == Phase.BREAK -> "Break"
                    else -> "Focus session"
                }
                Text(label, Modifier.weight(1f).padding(start = 5.dp), color = accent,
                    fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            if (active == null && !stopwatch) {
                TextButton(
                    onClick = onDuration,
                    modifier = Modifier.semantics { contentDescription = "Edit duration, $minutes minutes" },
                    contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)
                ) {
                    Mark(Glyph.TIMER, accent, 17)
                    Spacer(Modifier.width(5.dp))
                    Text("$minutes min", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        TimerDial(active, phase, mode, minutes, accent, surface)

        if (phase == Phase.FOCUS && !active?.subject.isNullOrBlank()) {
            Text(active?.subject.orEmpty(), Modifier.padding(bottom = 10.dp), color = White,
                fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (active?.strict != true) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = if (active == null) onStart else onPause,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = surface),
                    shape = RoundedCornerShape(17.dp)
                ) {
                    Mark(if (active?.running == true) Glyph.PAUSE else Glyph.PLAY, surface, 16)
                    Spacer(Modifier.width(8.dp))
                    Text(when {
                        active?.running == true -> "Pause"
                        active != null -> "Resume"
                        stopwatch -> "Start stopwatch"
                        mode == TimerMode.POMODORO -> "Start Pomodoro"
                        phase == Phase.BREAK -> "Start break"
                        else -> "Start focusing"
                    }, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
                if (active != null) {
                    OutlinedButton(onClick = onEnd, modifier = Modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(17.dp),
                        border = BorderStroke(1.dp, White.copy(alpha = 0.32f))) {
                        Text(if (stopwatch) "Finish" else "End", color = White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerDial(active: Session?, phase: Phase, mode: TimerMode, minutes: Int, accent: Color, surface: Color) {
    val stopwatch = mode == TimerMode.STOPWATCH
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val displayedTime = produceState(active?.displayTime(), active, lifecycle) {
        value = active?.displayTime()
        if (active?.running != true) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val time = active.displayTime()
                value = time
                if (!stopwatch && time == 0L) break
                val remainder = time % 1_000
                delay(if (stopwatch) 1_000 - remainder else if (remainder == 0L) 1_000 else remainder)
            }
        }
    }
    val status = when {
        active == null && stopwatch -> "Elapsed time"
        active == null -> null
        !active.running -> "Paused"
        phase == Phase.BREAK -> "On a break"
        active.strict -> "Strict Mode"
        else -> "Focusing"
    }

    BoxWithConstraints(
        Modifier.fillMaxWidth().heightIn(min = 226.dp).padding(vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        val clockWidth = minOf(maxWidth.value * 0.63f, 168f)
        Box(Modifier.matchParentSize().drawWithCache {
            val mid = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width * 0.34f, size.height * 0.405f)
            val orbit = Size(radius * 3.15f, radius * 1.22f)
            val orbitOrigin = mid - Offset(orbit.width / 2, orbit.height / 2)
            val majorTicks = Path()
            val minorTicks = Path()
            val tickStroke = Stroke(1.dp.toPx(), cap = StrokeCap.Round)
            val ringStroke = Stroke(3.dp.toPx())
            val outer = radius + 12.dp.toPx()
            for ((index, unit) in dialTicks.withIndex()) {
                val major = index % 4 == 0
                val path = if (major) majorTicks else minorTicks
                val start = mid + unit * (outer - if (major) 7.dp.toPx() else 3.dp.toPx())
                val end = mid + unit * outer
                path.moveTo(start.x, start.y)
                path.lineTo(end.x, end.y)
            }
            onDrawBehind {
                rotate(-22f, pivot = mid) {
                    drawOval(accent.copy(alpha = 0.12f), orbitOrigin, orbit, style = tickStroke)
                }
                rotate(22f, pivot = mid) {
                    drawOval(accent.copy(alpha = 0.08f), orbitOrigin, orbit, style = tickStroke)
                }
                drawCircle(surface, radius + 11.dp.toPx(), mid)
                drawPath(majorTicks, accent.copy(alpha = 0.42f), style = tickStroke)
                drawPath(minorTicks, accent.copy(alpha = 0.19f), style = tickStroke)
                drawCircle(accent.copy(alpha = 0.24f), radius, mid, style = ringStroke)
            }
        })
        Canvas(Modifier.matchParentSize()) {
            val progress = if (stopwatch || active == null || active.duration <= 0) 1f
                else ((displayedTime.value ?: active.remaining).toFloat() / active.duration).coerceIn(0f, 1f)
            if (progress > 0f) {
                val mid = center
                val radius = minOf(size.width * 0.34f, size.height * 0.405f)
                drawArc(accent, -90f, 360f * progress, false, mid - Offset(radius, radius),
                    Size(radius * 2, radius * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val angle = (progress * 2 * PI) - PI / 2
                val point = mid + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * radius
                drawCircle(accent, 5.dp.toPx(), point)
                drawCircle(surface, 2.dp.toPx(), point)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 174.dp)) {
            DialDigits(displayedTime, active, stopwatch, minutes, clockWidth)
            if (status != null) {
                Text(status, modifier = Modifier.padding(top = 3.dp), color = accent,
                    fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DialDigits(timeSnapshot: State<Long?>, active: Session?, stopwatch: Boolean, minutes: Int, width: Float) {
    val time = if (active != null) timeSnapshot.value ?: active.remaining else if (stopwatch) 0 else minutes * MINUTE
    val seconds = if (stopwatch) time / 1_000 else (time + 999) / 1_000
    val hours = if (stopwatch) seconds / 3_600 else 0
    val displayMinutes = if (hours > 0) (seconds / 60) % 60 else seconds / 60
    val clock = buildString {
        if (hours > 0) append(hours).append(':')
        if (displayMinutes < 10) append('0')
        append(displayMinutes).append(':')
        if (seconds % 60 < 10) append('0')
        append(seconds % 60)
    }
    val clockSize = minOf(57f, width / (clock.length * 0.56f * LocalDensity.current.fontScale))
    Text(
        clock, color = White, fontFamily = FontFamily.SansSerif, fontSize = clockSize.sp,
        lineHeight = (clockSize * 1.14f).sp, fontWeight = FontWeight.Light,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
        maxLines = 1, textAlign = TextAlign.Center,
        modifier = Modifier.width(width.dp).semantics {
            contentDescription = if (hours > 0) "$hours hours, $displayMinutes minutes, ${seconds % 60} seconds"
                else "$displayMinutes minutes, ${seconds % 60} seconds"
        }
    )
}

private val dialTicks = List(48) { index ->
    val angle = index * 2 * PI / 48 - PI / 2
    Offset(cos(angle).toFloat(), sin(angle).toFloat())
}

@Composable
private fun FocusCommitment(active: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, onPin: () -> Unit) {
    Surface(color = White, shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, Line)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Lavender), contentAlignment = Alignment.Center) {
                Mark(Glyph.SHIELD, Purple, 17)
            }
            Column(Modifier.weight(1f)) {
                Text(if (active) "Strict Mode is on" else "Strict Mode", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(if (active) "Keep this screen in view." else "Finish without pausing or ending early.",
                    color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            if (active) TextButton(onClick = onPin, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Pin screen") }
            else Switch(checked = enabled, onCheckedChange = onChange,
                modifier = Modifier.semantics { contentDescription = "Strict Mode" })
        }
    }
}

@Composable
private fun FocusGoal(total: Int, goal: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Mint, shape = RoundedCornerShape(20.dp),
        modifier = Modifier.semantics { contentDescription = "Daily study goal, $total of $goal minutes" }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mark(Glyph.TARGET, Forest, 18)
                Text("Today’s goal", Modifier.weight(1f), color = Forest, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("$total / $goal min", color = Forest, fontSize = 13.sp,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                Mark(Glyph.ARROW, Forest, 16)
            }
            LinearProgressIndicator(
                progress = { (total.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = Forest, trackColor = Color(0xFFC8D9CB), gapSize = 0.dp, drawStopIndicator = {}
            )
        }
    }
}
