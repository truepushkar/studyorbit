package dev.stillpoint.focus

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Surface(Modifier.fillMaxWidth(), color = OrbitSurface, shape = CardShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(start = 7.dp, end = 7.dp, bottom = 18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { weekOffset-- }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("‹ Prev", fontSize = 13.sp)
                        }
                        Text(weekLabel, Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        TextButton(onClick = { weekOffset++ }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("Next ›", fontSize = 13.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        repeat(7) { index ->
                            val date = week.plusDays(index.toLong())
                            val selected = dayOffset == index
                            val full = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                            Column(Modifier.weight(1f).heightIn(min = 92.dp).clip(CircleShape)
                                .background(if (selected) Purple else Color.Transparent)
                                .selectable(selected, role = Role.Tab) { dayOffset = index }
                                .semantics(mergeDescendants = true) { contentDescription = full }
                                .padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(date.format(DateTimeFormatter.ofPattern("EEE")).take(1),
                                    color = if (selected) OnAccent else Muted, fontSize = 12.sp)
                                Text(date.dayOfMonth.toString(), Modifier.padding(top = 6.dp),
                                    color = if (selected) OnAccent else Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                if (dotDays[index]) Box(Modifier.padding(top = 7.dp).size(6.dp)
                                    .background(if (selected) OnAccent else Purple, CircleShape))
                            }
                        }
                    }
                }
            }
        }
        item {
            OrbitButton("+  Add session", { editId = "" }, Modifier.fillMaxWidth(), enabled = !locked)
        }
        item {
            OrbitHeading(day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), size = 18)
        }
        if (itemsForDay.isEmpty()) item {
            Panel {
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    OrbitHeading("No sessions planned", size = 19)
                    Text("Add a study session when you know what works.", Modifier.padding(vertical = 12.dp),
                        color = Muted, fontSize = 13.sp)
                    OrbitButton("Plan a study session", { editId = "" }, Modifier.fillMaxWidth(),
                        enabled = !locked, primary = false)
                }
            }
        }
        items(itemsForDay, key = { it.id }) { schedule ->
            Panel {
                val clock = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a")
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${schedule.time.format(clock)} – ${schedule.time.plusMinutes(schedule.minutes.toLong()).format(clock)}",
                        Modifier.background(Lavender, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
                        color = Purple, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (schedule.repeat.isEmpty()) "One-time" else "Repeats weekly", color = Muted, fontSize = 12.sp)
                }
                Text(schedule.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("${schedule.minutes} min · ${day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))}",
                    color = Muted, fontSize = 13.sp)
                Text("Reminder ${if (schedule.remind) "on" else "off"}  ·  Focus rules ${if (schedule.block) "on" else "off"}",
                    color = Muted, fontSize = 12.sp)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrbitButton(if (timerBusy) "Timer active" else "Start now", { onStart(schedule) }, Modifier.weight(1.25f),
                        enabled = !timerBusy)
                    OrbitButton("Edit", { editId = schedule.id }, Modifier.weight(1f), primary = false, enabled = !locked)
                    TextButton(onClick = { removeId = schedule.id }, enabled = !locked,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Delete") }
                }
            }
        }
        if (locked || itemsForDay.isNotEmpty()) item {
            Surface(Modifier.fillMaxWidth(), color = if (locked) Lavender else Night, shape = CardShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (locked) "Locked during strict focus" else "Your timer stays yours.",
                        color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(if (locked) "Session rules are read-only until your strict focus timer ends."
                        else "Scheduled windows apply focus rules.\nThey never start the visible timer.",
                        Modifier.padding(top = 8.dp, bottom = 7.dp), color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
        if (!exactAlarms) item {
            TextButton(onClick = onExactAlarms) { Text("Precise reminders? Open alarm settings  ↗") }
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
        AlertDialog(onDismissRequest = { removeId = null }, title = { Text("Delete this schedule?") },
            text = { Text("This also removes its upcoming reminders and repeating sessions.") },
            confirmButton = { TextButton(onClick = {
                onSave(schedules.filterNot { it.id == id }, false)
                removeId = null
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancel") } })
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
    OrbitFullScreen(onClose) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = orbitTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClose, contentPadding = PaddingValues(end = 25.dp)) { Text("Cancel") }
                OrbitHeading(if (original == null) "Plan a session" else "Edit session", size = 23)
            }
            Box(Modifier.align(Alignment.CenterHorizontally).width(44.dp).height(4.dp).background(Muted, CircleShape))
            OrbitField("Session name", title, { title = it.take(70) }, Modifier.fillMaxWidth(), placeholder = "e.g. Circuit diagrams")
            SchedulePicker("Date", date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), Modifier.fillMaxWidth()) {
                DatePickerDialog(context, { _, year, month, day ->
                    dayText = LocalDate.of(year, month + 1, day).toString()
                }, date.year, date.monthValue - 1, date.dayOfMonth).show()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SchedulePicker("Start time", time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), Modifier.weight(1f)) {
                    TimePickerDialog(context, { _, hour, minute ->
                        timeText = LocalTime.of(hour, minute).toString()
                    }, time.hour, time.minute, DateFormat.is24HourFormat(context)).show()
                }
                OrbitField("Duration · 1–180 min", length, { length = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f),
                    numeric = true, suffix = "min", error = length.toIntOrNull() !in 1..180)
            }
            Column(Modifier.padding(top = 8.dp)) {
                Text("Repeat weekly", color = Ink, fontSize = 15.sp)
                Text("Leave all days unselected for one-time", color = Muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { weekday ->
                        val selected = weekday in selectedDays
                        val label = weekday.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                            .background(if (selected) Purple else OrbitSurface).selectable(selected, role = Role.Checkbox) {
                                days = days xor (1 shl weekday.ordinal)
                            }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                            Text(label.take(1), color = if (selected) OnAccent else Muted, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            ToggleRule("Reminder", "Notify me at the scheduled start time.", remind) { remind = it }
            ToggleRule("Apply focus rules", "Use existing blocking rules for this window.", block) { block = it }
            if (!exactAlarms) OrbitButton("Precise reminders? Open alarm settings  ↗", onExactAlarms,
                Modifier.fillMaxWidth(), primary = false, rounded = false)
            OrbitButton("Save session", {
                val draft = StudySchedule(name = title.trim(), date = date, time = time, minutes = length.toInt(),
                    repeat = selectedDays, block = block, remind = remind)
                onSave(if (original == null) draft else draft.copy(id = original.id))
            }, Modifier.fillMaxWidth(), enabled = title.isNotBlank() && length.toIntOrNull() in 1..180)
        }
    }
}

@Composable
private fun SchedulePicker(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Muted, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(FieldShape).background(OrbitSurface)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart) {
            Text(value, color = Ink, fontSize = 16.sp, maxLines = 1)
        }
    }
}
