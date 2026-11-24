package dev.stillpoint.focus

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.selected
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
    val itemsForDay = schedules.filter { it.on(day) }.sortedBy { it.time }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 7.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PageTitle("STUDY PLANNER", "Plan your week.", "Set aside time to study and get a reminder when it starts.") }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${week.format(dateLabel)} – ${week.plusDays(6).format(dateLabel)}",
                        Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { weekOffset-- }) { Text("Prev") }
                    TextButton(onClick = { weekOffset++ }) { Text("Next") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(7) { index ->
                        val date = week.plusDays(index.toLong())
                        FilterChip(selected = dayOffset == index, onClick = { dayOffset = index },
                            modifier = Modifier.weight(1f).padding(horizontal = 1.dp),
                            label = { Text(date.dayOfMonth.toString(), fontSize = 10.sp) },
                            contentDescriptionForDate = date)
                    }
                }
                Text(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)), color = Muted, fontSize = 13.sp)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sessions", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Bold)
                Button(enabled = !locked, onClick = { editId = "" }) { Text("Add session") }
            }
        }
        if (itemsForDay.isEmpty()) item {
            Panel { Text("Nothing scheduled for this day.", color = Muted) }
        }
        items(itemsForDay, key = { it.id }) { schedule ->
            Panel {
                Text(schedule.name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("${schedule.time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))} · ${schedule.minutes} min",
                    color = Muted, fontSize = 13.sp)
                val details = buildList {
                    if (schedule.repeat.isNotEmpty()) add("Repeats weekly")
                    if (schedule.block) add("Focus rules")
                    if (schedule.remind) add("Reminder")
                }
                if (details.isNotEmpty()) Text(details.joinToString(" · "), color = Muted, fontSize = 12.sp)
                Row {
                    TextButton(enabled = !timerBusy, onClick = { onStart(schedule) }) { Text("Start now") }
                    TextButton(enabled = !locked, onClick = { editId = schedule.id }) { Text("Edit") }
                    TextButton(enabled = !locked, onClick = { removeId = schedule.id }) { Text("Delete") }
                }
            }
        }
        if (!exactAlarms) item {
            Panel {
                Text("Exact timer alerts", color = Ink, fontWeight = FontWeight.Bold)
                Text("Allow alarms and reminders so Android can deliver them on time when the screen is off.",
                    color = Muted, fontSize = 13.sp)
                TextButton(onClick = onExactAlarms) { Text("Open alarm settings") }
            }
        }
    }
    editId?.let { id ->
        val existing = schedules.firstOrNull { it.id == id }
        ScheduleDialog(existing, maxOf(day, today), onClose = { editId = null }) { entry ->
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
private fun FilterChip(
    selected: Boolean, onClick: () -> Unit, modifier: Modifier,
    label: @Composable () -> Unit, contentDescriptionForDate: LocalDate
) {
    val dateLabel = contentDescriptionForDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
    Surface(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small, color = if (selected) Lavender else White
    ) {
        Box(Modifier.semantics { contentDescription = dateLabel; this.selected = selected },
            contentAlignment = Alignment.Center) { label() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleDialog(
    original: StudySchedule?, proposedDay: LocalDate, onClose: () -> Unit, onSave: (StudySchedule) -> Unit
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
    AlertDialog(onDismissRequest = onClose, title = { Text(if (original == null) "Schedule study" else "Edit schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(title, { title = it.take(70) }, label = { Text("Session name") }, singleLine = true)
                TextButton(onClick = {
                    DatePickerDialog(context, { _, year, month, day ->
                        dayText = LocalDate.of(year, month + 1, day).toString()
                    }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }) { Text("Date · ${date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}") }
                TextButton(onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        timeText = LocalTime.of(hour, minute).toString()
                    }, time.hour, time.minute, DateFormat.is24HourFormat(context)).show()
                }) { Text("Start time · ${time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}") }
                NumberField(length, { length = it }, "Duration in minutes", 1..180)
                Text("Repeat on", color = Ink, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    DayOfWeek.entries.forEach { weekday ->
                        FilterChip(selected = weekday in selectedDays, onClick = { days = days xor (1 shl weekday.ordinal) },
                            label = { Text(weekday.name.take(3).lowercase().replaceFirstChar(Char::uppercase)) })
                    }
                }
                ToggleRule("Reminder", "Notify me when this session starts.", remind) { remind = it }
                ToggleRule("Apply focus rules", "Block apps and content that you set to block while studying.", block) { block = it }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && (length.toIntOrNull() ?: 0) in 1..180, onClick = {
                val draft = StudySchedule(name = title.trim(), date = date, time = time, minutes = length.toInt(),
                    repeat = selectedDays, block = block, remind = remind)
                onSave(if (original == null) draft else draft.copy(id = original.id))
            }) { Text("Save") }
        }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
