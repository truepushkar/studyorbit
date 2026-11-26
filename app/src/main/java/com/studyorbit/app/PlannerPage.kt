package com.studyorbit.app

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters

@Composable
internal fun PlannerPage(
    schedules: List<StudySchedule>,
    today: LocalDate,
    timerBusy: Boolean,
    locked: Boolean,
    exactAlarms: Boolean,
    onExactAlarms: () -> Unit,
    onSave: (List<StudySchedule>, Boolean) -> Unit,
    onStart: (StudySchedule) -> Unit
) {
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var dayOffset by rememberSaveable { mutableIntStateOf(today.dayOfWeek.ordinal) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
    val day = week.plusDays(dayOffset.toLong())
    val dateLabel = DateTimeFormatter.ofPattern("MMM d")
    val finalDay = week.plusDays(6)
    val weekLabel = "${week.format(dateLabel)} – ${if (week.month == finalDay.month) finalDay.dayOfMonth else finalDay.format(dateLabel)}"
    val dotDays = remember(week, schedules) { (0L..6L).map { offset -> schedules.any { it.on(week.plusDays(offset)) } } }
    val itemsForDay = schedules.filter { it.on(day) }.sortedBy { it.time }
    val atToday = weekOffset == 0 && dayOffset == today.dayOfWeek.ordinal
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = Gap.xl),
        verticalArrangement = Arrangement.spacedBy(Gap.md)
    ) {
        item {
            Surface(Modifier.fillMaxWidth(), color = OrbitSurface, shape = CardShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Column(Modifier.pointerInput(Unit) {
                    var drag = 0f
                    detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragEnd = {
                        if (kotlin.math.abs(drag) >= 48.dp.toPx()) weekOffset += if (drag < 0) 1 else -1
                    }) { _, delta -> drag += delta }
                }) {
                    Row(Modifier.fillMaxWidth().background(AccentSurface), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ weekOffset-- }) { OrbitIcon(R.drawable.ic_previous, androidx.compose.ui.res.stringResource(R.string.previous_week)) }
                        Text(weekLabel, Modifier.weight(1f), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        if (!atToday) TextButton(
                            onClick = { weekOffset = 0; dayOffset = today.dayOfWeek.ordinal },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text(stringResource(R.string.go_to_today), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        IconButton({ weekOffset++ }) { OrbitIcon(R.drawable.ic_next, androidx.compose.ui.res.stringResource(R.string.next_week)) }
                    }
                    Column(Modifier.padding(Gap.sm)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(7) { index ->
                                val date = week.plusDays(index.toLong())
                                val selected = dayOffset == index
                                val isToday = date == today
                                val fullDate = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                                val full = if (isToday) stringResource(R.string.planner_day_today, fullDate) else fullDate
                                val cell by animateColorAsState(
                                    if (selected) Accent else Accent.copy(alpha = 0f), tween(160), label = "Planner day"
                                )
                                Column(Modifier.weight(1f).heightIn(min = 70.dp).clip(FieldShape)
                                    .background(cell)
                                    .then(if (isToday && !selected) Modifier.border(1.5.dp, Accent, FieldShape) else Modifier)
                                    .selectable(selected, role = Role.Tab) { dayOffset = index }
                                    .semantics(mergeDescendants = true) { contentDescription = full }
                                    .padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(date.format(DateTimeFormatter.ofPattern("EEE")).take(2),
                                        color = if (selected) OnAccent else Muted, fontSize = 13.sp)
                                    Text(date.dayOfMonth.toString(), Modifier.padding(top = 6.dp),
                                        color = if (selected) OnAccent else if (isToday) Accent else Ink,
                                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                    if (dotDays[index]) Box(Modifier.padding(top = 7.dp).size(6.dp)
                                        .background(if (selected) OnAccent else Accent, CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrbitHeading(day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), Modifier.weight(1f), level = HeadingLevel.Section)
                if (itemsForDay.isNotEmpty()) TextButton({ editId = "" }, enabled = !locked, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.add_session), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (itemsForDay.isEmpty()) item {
            Panel(tone = PanelTone.Quiet) {
                OrbitHeading(stringResource(R.string.open_study_day), level = HeadingLevel.Section)
                Text(stringResource(R.string.no_sessions_scheduled_for_this_date), Modifier.padding(top = 4.dp, bottom = Gap.lg),
                    color = Muted, fontSize = 14.sp)
                OrbitButton(stringResource(R.string.plan_a_session), { editId = "" }, Modifier.fillMaxWidth(), enabled = !locked)
            }
        }
        items(itemsForDay, key = { it.id }) { schedule ->
            Panel {
                val clock = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a")
                Row(Modifier.fillMaxWidth().padding(bottom = Gap.md), verticalAlignment = Alignment.CenterVertically) {
                    Text("${schedule.time.format(clock)} – ${schedule.time.plusMinutes(schedule.minutes.toLong()).format(clock)}",
                        Modifier.background(AccentSurface, CircleShape).padding(horizontal = 10.dp, vertical = 3.dp),
                        color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(if (schedule.repeat.isEmpty()) "One-time" else "Repeats weekly", color = Muted, fontSize = 13.sp)
                }
                Text(schedule.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("${schedule.minutes} min · Reminder ${if (schedule.remind) "on" else "off"} · Focus rules ${if (schedule.block) "on" else "off"}",
                    Modifier.padding(top = 2.dp), color = Muted, fontSize = 13.sp)
                Row(Modifier.padding(top = Gap.lg), horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                    OrbitButton(if (timerBusy) "Timer active" else "Start now", { onStart(schedule) }, Modifier.weight(1.4f),
                        enabled = !timerBusy)
                    OrbitButton(stringResource(R.string.edit), { editId = schedule.id }, Modifier.weight(1f), primary = false, enabled = !locked)
                    OrbitButton(stringResource(R.string.delete), { removeId = schedule.id }, Modifier.weight(1f), primary = false,
                        danger = true, enabled = !locked)
                }
            }
        }
        if (locked || itemsForDay.isNotEmpty()) item {
            Panel(tone = if (locked) PanelTone.Tinted else PanelTone.Quiet) {
                Text(if (locked) "Locked during strict focus" else "Your timer stays yours.",
                    color = Ink, fontWeight = FontWeight.SemiBold)
                Text(if (locked) "Session rules are read-only until your strict focus timer ends."
                    else "Scheduled windows apply focus rules.\nThey never start the visible timer.",
                    Modifier.padding(top = 4.dp), color = Muted, fontSize = 13.sp)
            }
        }
        if (!exactAlarms) item {
            OrbitButton(stringResource(R.string.precise_reminders_open_alarm_settings), onExactAlarms, Modifier.fillMaxWidth(),
                primary = false, container = OrbitSurface)
        }
    }
    editId?.let { id ->
        val existing = schedules.firstOrNull { it.id == id }
        ScheduleDialog(existing, maxOf(day, today), exactAlarms, onExactAlarms, onClose = { editId = null }) { entry ->
            onSave(schedules.filterNot { it.id == entry.id } + entry, entry.remind)
            val firstOccurrence = (0L..6L).map(entry.date::plusDays).first(entry::on)
            val editedWeek = firstOccurrence.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val baseWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            weekOffset = java.time.temporal.ChronoUnit.WEEKS.between(baseWeek, editedWeek).toInt()
            dayOffset = firstOccurrence.dayOfWeek.ordinal
            editId = null
        }
    }
    removeId?.let { id ->
        AlertDialog(onDismissRequest = { removeId = null }, title = { Text(stringResource(R.string.delete_this_schedule)) },
            text = { Text(stringResource(R.string.this_also_removes_its_upcoming_reminders_and)) },
            confirmButton = { TextButton(onClick = {
                onSave(schedules.filterNot { it.id == id }, false)
                removeId = null
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.delete))
            } },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ScheduleDialog(
    original: StudySchedule?, proposedDay: LocalDate, exactAlarms: Boolean, onExactAlarms: () -> Unit,
    onClose: () -> Unit, onSave: (StudySchedule) -> Unit
) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var dayText by rememberSaveable { mutableStateOf((original?.date ?: proposedDay).toString()) }
    var timeText by rememberSaveable { mutableStateOf((original?.time ?: LocalTime.of(18, 0)).toString()) }
    var length by rememberSaveable { mutableStateOf((original?.minutes ?: 25).toString()) }
    var days by rememberSaveable { mutableIntStateOf(original?.repeat?.sumOf { 1 shl it.ordinal } ?: 0) }
    var block by rememberSaveable { mutableStateOf(original?.block ?: false) }
    var remind by rememberSaveable { mutableStateOf(original?.remind ?: true) }
    val date = LocalDate.parse(dayText)
    val time = LocalTime.parse(timeText)
    val selectedDays = DayOfWeek.entries.filter { days and (1 shl it.ordinal) != 0 }.toSet()
    OrbitScreen(if (original == null) "Plan a session" else "Edit session", onClose) {
        OrbitField(stringResource(R.string.session_name), title, { title = it.take(70) }, Modifier.fillMaxWidth(), placeholder = "e.g. Circuit diagrams")
        SchedulePicker("Date", date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), Modifier.fillMaxWidth()) {
            DatePickerDialog(context, { _, year, month, day ->
                dayText = LocalDate.of(year, month + 1, day).toString()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.md)) {
            SchedulePicker("Start time", time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), Modifier.weight(1f)) {
                TimePickerDialog(context, { _, hour, minute ->
                    timeText = LocalTime.of(hour, minute).toString()
                }, time.hour, time.minute, DateFormat.is24HourFormat(context)).show()
            }
            OrbitField(stringResource(R.string.duration_1_180_min), length, { length = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f),
                numeric = true, suffix = "min", error = length.toIntOrNull() !in 1..180,
                errorText = stringResource(R.string.error_range, 1, 180))
        }
        Column(verticalArrangement = Arrangement.spacedBy(Gap.sm)) {
            SectionTitle(stringResource(R.string.repeat_weekly), caption = stringResource(R.string.leave_all_days_unselected_for_one_time))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(Gap.xs)) {
                DayOfWeek.entries.forEach { weekday ->
                    val selected = weekday in selectedDays
                    val label = weekday.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                        .background(if (selected) Accent else OrbitSurface)
                        .border(1.dp, if (selected) Accent else Line, CircleShape)
                        .selectable(selected, role = Role.Checkbox) {
                            days = days xor (1 shl weekday.ordinal)
                        }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                        Text(label.take(2), color = if (selected) OnAccent else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Panel(contentPadding = PaddingValues(horizontal = Gap.lg)) {
            ToggleRule(stringResource(R.string.reminder), "Notify me at the scheduled start time.", remind) { remind = it }
            HorizontalDivider(color = Line)
            ToggleRule(stringResource(R.string.apply_focus_rules), "Use existing blocking rules for this window.", block) { block = it }
        }
        if (!exactAlarms) OrbitButton(stringResource(R.string.precise_reminders_open_alarm_settings), onExactAlarms,
            Modifier.fillMaxWidth(), primary = false, container = OrbitSurface)
        OrbitButton(stringResource(R.string.save_session), {
            val draft = StudySchedule(name = title.trim(), date = date, time = time, minutes = length.toInt(),
                repeat = selectedDays, block = block, remind = remind)
            onSave(if (original == null) draft else draft.copy(id = original.id))
        }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && length.toIntOrNull() in 1..180)
    }
}

@Composable
private fun SchedulePicker(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, fontSize = 13.sp)
        Box(Modifier.fillMaxWidth().heightIn(min = 54.dp).orbitField()
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Gap.lg),
            contentAlignment = Alignment.CenterStart) {
            Text(value, color = Ink, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
