package com.studyorbit.app

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
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

internal data class FocusUiState(
    val active: Session?, val result: Int, val resultTime: Long, val resultKind: FocusResult, val phase: Phase, val minutes: Int, val subject: String, val goal: Int, val total: Int,
    val restMinutes: Int, val rounds: Int, val longBreak: Int,
    val mode: TimerMode, val strict: Boolean, val theme: TimerTheme, val sound: Soundscape, val streak: Int?,
    val recentSubjects: List<String>, val lastSession: StudyLog?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FocusPage(
    state: FocusUiState,
    onMode: (TimerMode) -> Unit, onStrict: (Boolean) -> Unit, onPin: () -> Unit,
    onPhase: (Phase) -> Unit, onMinutes: (Int) -> Unit, onSubject: (String) -> Unit,
    onStart: () -> Unit, onPause: () -> Unit, onEnd: () -> Unit, onGoal: () -> Unit, onReset: () -> Unit,
    onTheme: (TimerTheme) -> Unit, onSound: (Soundscape) -> Unit, onMiniTimer: (() -> Unit)?,
    shortcuts: @Composable () -> Unit
) = with(state) {
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val ready = active == null && result < 0
    val completed = active == null && result >= 0
    val stopwatch = mode == TimerMode.STOPWATCH
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = ScreenPadding)) {
        val posterPadding = (maxHeight * .05f).coerceIn(16.dp, 42.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.animation.AnimatedContent(if (ready) 0 else if (completed) 2 else 1, label = "Focus status") { status ->
                if (status == 0) {
                    FocusModeBar(mode, onMode)
                } else {
                    val state = when {
                        status == 2 -> stringResource(R.string.session_complete)
                        active?.strict == true -> stringResource(R.string.strict_session)
                        active?.running == false -> stringResource(R.string.focus_paused)
                        mode == TimerMode.POMODORO -> "${phase.label} · Round ${active?.round ?: 1} of ${active?.rounds ?: rounds}"
                        stopwatch -> stringResource(R.string.stopwatch_running)
                        phase == Phase.BREAK -> stringResource(R.string.break_running)
                        else -> stringResource(R.string.focus_running)
                    }
                    Box(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(FieldShape).background(AccentSurface),
                        contentAlignment = Alignment.Center) {
                        Text(state, Modifier.padding(horizontal = Gap.md, vertical = Gap.sm), color = Accent,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SessionPoster(active, result, resultTime, resultKind, phase, mode, minutes,
                restMinutes, rounds, posterPadding, onEdit = if (ready && !stopwatch) ({ sheet = "duration" }) else null,
                onPhase = if (ready && mode == TimerMode.TIMER) onPhase else null,
                subjectChip = if (ready && phase == Phase.FOCUS) ({
                    ArtButton({ sheet = "subject" }, outlined = true, contentColor = ArtText) {
                        ArtLabel(subject.ifBlank { stringResource(R.string.subject_prompt) })
                    }
                }) else null,
                onStart = if (ready) onStart else null, onPause = onPause, onEnd = onEnd)

            if (completed) {
                Spacer(Modifier.height(Gap.lg))
                OrbitHeading(when (resultKind) {
                    FocusResult.BREAK -> "Break complete."
                    FocusResult.CYCLE -> "Pomodoro complete."
                    FocusResult.ENDED -> "A fresh start is always here."
                    else -> "Nice work."
                }, level = HeadingLevel.Sheet)
                Text(when (resultKind) {
                    FocusResult.BREAK -> "Ready for another study session?"
                    FocusResult.ENDED -> "Only completed countdown rounds count."
                    FocusResult.STOPWATCH -> "Full stopwatch minutes count toward your goal."
                    else -> "Focus time counts. Breaks stay separate."
                },
                    color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Gap.xs))
                Spacer(Modifier.height(Gap.xl))
                OrbitButton(stringResource(R.string.view_progress), onGoal, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Gap.sm))
                OrbitButton(stringResource(R.string.back_to_focus), onReset, Modifier.fillMaxWidth(), primary = false)
                Spacer(Modifier.height(Gap.lg))
                Text("Today’s goal · $total / $goal min", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(Gap.sm))
                return@Column
            }

            Spacer(Modifier.height(if (ready) Gap.lg else Gap.xl))
            if (ready) {
                if (phase == Phase.FOCUS && recentSubjects.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                        recentSubjects.forEach { name ->
                            OrbitChip(name, { onSubject(name); onStart() }, onClickLabel = stringResource(R.string.start_session),
                                icon = R.drawable.ic_play)
                        }
                    }
                    Spacer(Modifier.height(Gap.sm))
                }
                if (!stopwatch && phase == Phase.FOCUS) {
                    Panel(contentPadding = PaddingValues(horizontal = Gap.lg)) {
                        ToggleRule(stringResource(R.string.strict_mode), stringResource(R.string.strict_detail), strict, onChange = onStrict)
                    }
                    Spacer(Modifier.height(Gap.md))
                }
                FocusGoal(total, goal, onGoal)
                val nudge = when {
                    mode == TimerMode.POMODORO -> "$rounds × $minutes min · Long break after round $rounds ($longBreak min)"
                    streak == null -> ""
                    streak >= 2 -> "$streak study days in a row. Keep your orbit."
                    total >= goal -> "You reached today’s goal."
                    lastSession != null -> stringResource(R.string.last_session, lastSession.subject.ifBlank { Phase.FOCUS.label }, lastSession.minutes)
                    else -> ""
                }
                if (nudge.isNotEmpty()) Text(nudge, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = Gap.md),
                    color = Muted, fontSize = 13.sp)
            } else if (active != null) {
                if (active.strict) {
                    Text(stringResource(R.string.blocking_rules_locked_until_this_session_ends),
                        Modifier.fillMaxWidth().background(AccentSurface, FieldShape).padding(Gap.lg),
                        color = Accent, textAlign = TextAlign.Center, fontSize = 13.sp)
                    Spacer(Modifier.height(Gap.lg))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.md)) {
                    if (active.strict) OrbitButton(stringResource(R.string.pin_screen), onPin, Modifier.weight(1f), primary = false, container = OrbitSurface)
                    OrbitButton(stringResource(R.string.music_theme), { sheet = "atmosphere" }, Modifier.weight(1f), primary = false, container = OrbitSurface)
                    if (!active.strict && onMiniTimer != null) {
                        OrbitButton(stringResource(R.string.float_timer), onMiniTimer, Modifier.weight(1f), primary = false, container = OrbitSurface)
                    }
                }
                if (active.strict) {
                    Text(stringResource(R.string.android_screen_pinning_is_optional), Modifier.padding(top = Gap.lg), color = Muted, fontSize = 13.sp)
                } else {
                    TextButton({ sheet = "shortcuts" }, Modifier.align(Alignment.Start).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.study_shortcuts_open_study_list), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (sheet == "duration" && ready) {
        var length by rememberSaveable { mutableStateOf(minutes.toString()) }
        val parsed = length.toIntOrNull()
        OrbitSheet({ sheet = null }) {
            OrbitHeading(if (phase == Phase.BREAK) "Make time for a break" else "Make time for focus", level = HeadingLevel.Sheet)
            OrbitField("${phase.label} duration", length, { length = it.filter(Char::isDigit).take(3) },
                Modifier.fillMaxWidth(), suffix = "min", numeric = true, error = parsed !in 1..180,
                errorText = stringResource(R.string.error_range, 1, 180),
                helper = stringResource(R.string.choose_a_duration_that_feels_achievable))
            Spacer(Modifier.height(Gap.xs))
            OrbitButton(stringResource(R.string.save_duration), { parsed?.let(onMinutes); sheet = null },
                Modifier.fillMaxWidth(), enabled = parsed in 1..180)
        }
    }
    if (sheet == "subject" && ready) {
        var task by rememberSaveable { mutableStateOf(subject) }
        OrbitSheet({ sheet = null }) {
            OrbitHeading(stringResource(R.string.subject_prompt), level = HeadingLevel.Sheet)
            OrbitField(stringResource(R.string.study_subject), task, { task = it.take(70) }, Modifier.fillMaxWidth())
            OrbitButton(stringResource(R.string.save_subject), { onSubject(task.trim()); sheet = null }, Modifier.fillMaxWidth())
        }
    }
    if (sheet == "atmosphere" && active != null) OrbitSheet({ sheet = null }) {
        OrbitHeading(stringResource(R.string.set_the_atmosphere), level = HeadingLevel.Sheet)
        Text(stringResource(R.string.changes_apply_immediately_to_your_session), color = Muted)
        MusicPicker(sound, onSound)
        ThemePicker(theme, onTheme)
        OrbitButton(stringResource(R.string.done), { sheet = null }, Modifier.fillMaxWidth())
    }
    if (sheet == "shortcuts" && active != null) OrbitSheet({ sheet = null }) {
        OrbitHeading(stringResource(R.string.your_study_list), level = HeadingLevel.Sheet)
        Text(stringResource(R.string.saved_sites_and_channels_ready_when_you), color = Muted)
        shortcuts()
        OrbitButton(stringResource(R.string.back_to_session), { sheet = null }, Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitSheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose, containerColor = Paper, contentColor = Ink,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = ScreenPadding, end = ScreenPadding, bottom = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Gap.md), content = content
        )
    }
}

@Composable
private fun FocusModeBar(mode: TimerMode, onMode: (TimerMode) -> Unit) {
    OrbitSegmented(TimerMode.entries.map { it.label }, mode.ordinal, { onMode(TimerMode.entries[it]) }, role = Role.Tab)
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
    posterPadding: androidx.compose.ui.unit.Dp,
    subjectChip: (@Composable () -> Unit)?, onEdit: (() -> Unit)?, onPhase: ((Phase) -> Unit)?, onStart: (() -> Unit)?,
    onPause: () -> Unit, onEnd: () -> Unit
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
    val white = ArtText
    val savedSession = result > 0 && resultKind != FocusResult.BREAK && resultKind != FocusResult.ENDED
    val elapsed = if (active != null && !stopwatch && active.duration > 0)
        (1f - display.toFloat() / active.duration).coerceIn(0f, 1f) else 0f
    AtlasPanel(R.drawable.atlas_focus) {
        Column(Modifier.fillMaxWidth().heightIn(min = 266.dp).padding(horizontal = 20.dp, vertical = posterPadding)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onPhase != null) {
                    Row(Modifier.selectableGroup().clip(CircleShape).background(white.copy(alpha = .14f))) {
                        Phase.entries.forEach { option ->
                            val selected = option == phase
                            Box(Modifier.clip(CircleShape).background(if (selected) white else androidx.compose.ui.graphics.Color.Transparent)
                                .selectable(selected, role = Role.Tab) { onPhase(option) }
                                .heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Text(option.label, color = if (selected) ArtInk else white, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        val task = active?.subject?.takeIf { it.isNotBlank() && phase == Phase.FOCUS }
                        Text(task ?: if (mode == TimerMode.TIMER) phase.label else mode.label,
                            color = white, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (onPhase != null) Spacer(Modifier.weight(1f))
                SessionOrbit(
                    turns = when {
                        savedSession -> 1f
                        stopwatch && active != null -> display.toFloat() / MINUTE
                        else -> elapsed
                    },
                    stopwatch = stopwatch && !savedSession,
                    running = active?.running == true,
                    celebrate = savedSession,
                    completionTime = resultTime,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.height(Gap.md))
            Column(Modifier.then(if (onEdit != null) Modifier.clickable(onClickLabel = stringResource(R.string.edit_duration), onClick = onEdit) else Modifier)) {
                Text(digits, color = white, fontSize = if (digits.length > 5) 44.sp else 60.sp,
                    lineHeight = 66.sp, letterSpacing = (-2).sp, fontWeight = FontWeight.SemiBold,
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                Text(when {
                    result >= 0 -> when {
                        resultKind == FocusResult.BREAK -> stringResource(R.string.break_complete)
                        result == 1 -> "1 study minute saved"
                        else -> "$result study minutes saved"
                    }
                    active?.running == false -> stringResource(R.string.session_paused)
                    stopwatch -> stringResource(R.string.elapsed_time)
                    mode == TimerMode.POMODORO -> "$rounds rounds · $restMinutes min breaks"
                    onEdit != null -> stringResource(R.string.edit_duration)
                    else -> stringResource(R.string.remaining_time)
                }, color = white, fontSize = 14.sp)
            }
            Spacer(Modifier.height(Gap.md))
            if (active?.running == true && !stopwatch && result < 0) {
                LinearProgressIndicator(progress = { elapsed }, modifier = Modifier.fillMaxWidth(.56f).height(4.dp).clip(CircleShape),
                    color = ArtCoral, trackColor = ArtTrack, drawStopIndicator = {})
            }
            subjectChip?.invoke()
            val action = onStart ?: onPause.takeIf { active != null && !active.strict }
            if (action != null) {
                val label = when {
                    active?.running == true -> stringResource(R.string.pause_session)
                    active != null -> stringResource(R.string.resume_session)
                    stopwatch -> stringResource(R.string.start_stopwatch)
                    mode == TimerMode.POMODORO -> stringResource(R.string.start_pomodoro)
                    phase == Phase.BREAK -> stringResource(R.string.start_break)
                    else -> stringResource(R.string.start_focusing)
                }
                Spacer(Modifier.height(Gap.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.sm), verticalAlignment = Alignment.CenterVertically) {
                    ArtButton(action, Modifier.widthIn(min = 132.dp)) {
                        Icon(androidx.compose.ui.res.painterResource(
                            if (active?.running == true) R.drawable.ic_rail_pause else R.drawable.ic_play
                        ), null, Modifier.size(16.dp))
                        Spacer(Modifier.width(Gap.sm))
                        Crossfade(label, animationSpec = tween(130), label = "Session action") {
                            Text(it, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                    if (active != null) {
                        ArtButton(onEnd, container = ArtSlate, contentColor = ArtText) {
                            ArtLabel(if (mode == TimerMode.POMODORO) "End" else "Finish")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusGoal(total: Int, goal: Int, onClick: () -> Unit) {
    Panel(tone = PanelTone.Tinted, onClick = onClick, contentPadding = PaddingValues(horizontal = Gap.lg, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.today_s_goal), Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("$total / $goal min", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(Gap.md))
        LinearProgressIndicator(progress = { (total.toFloat() / goal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = Accent, trackColor = OrbitTrack, drawStopIndicator = {})
    }
}
