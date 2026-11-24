package dev.stillpoint.focus

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun nextMidnight() = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
internal fun ContentRulesDialog(
    original: ContentRules, strict: Boolean, onDismiss: () -> Unit, onSave: (ContentRules) -> Unit
) {
    val editable = !original.isLocked && !strict
    var blocked by rememberSaveable { mutableStateOf(original.blockedHosts.sorted().joinToString("\n")) }
    var allowed by rememberSaveable { mutableStateOf(original.allowedHosts.sorted().joinToString("\n")) }
    var channels by rememberSaveable { mutableStateOf(original.channels.sorted().joinToString("\n")) }
    var adult by rememberSaveable { mutableStateOf(original.adultFilter) }
    var browser by rememberSaveable { mutableStateOf(original.browserStudy) }
    var youtube by rememberSaveable { mutableStateOf(original.youtubeStudy) }
    var lock by rememberSaveable { mutableStateOf(original.isLocked) }
    val blockedList = blocked.entries()
    val allowedList = allowed.entries()
    val invalidBlocked = blockedList.any { websiteHost(it) == null }
    val invalidAllowed = allowedList.any { websiteHost(it) == null }
    val channelList = channels.entries().map(::channelKey).filter { it.isNotBlank() }.toSet()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Web and YouTube rules") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!editable) Text("These rules are locked.", color = Purple, fontWeight = FontWeight.Bold)
                OutlinedTextField(blocked, { blocked = it.take(4000) }, enabled = editable, isError = invalidBlocked,
                    label = { Text("Always blocked sites") }, placeholder = { Text("example.com") }, maxLines = 4,
                    supportingText = { Text(if (invalidBlocked) "Enter valid domains or URLs." else "One domain or URL per line.") })
                ToggleRule("Filter adult sites", "Blocks a built-in list of common adult domains.", adult, editable) { adult = it }
                ToggleRule("Browser Study Mode", "Allow listed sites during focus in supported browsers.", browser, editable) { browser = it }
                if (browser) OutlinedTextField(allowed, { allowed = it.take(4000) },
                    enabled = editable, isError = invalidAllowed,
                    label = { Text("Allowed study sites") }, placeholder = { Text("khanacademy.org") }, maxLines = 4,
                    supportingText = { Text(if (invalidAllowed) "Enter valid domains or URLs." else "One domain or URL per line.") })
                ToggleRule("YouTube Study Mode", "Block Shorts and filter the home feed and videos during focus.", youtube, editable) { youtube = it }
                if (youtube) {
                    OutlinedTextField(channels, { channels = it.take(4000) }, enabled = editable,
                        label = { Text("Allowed study channels") }, maxLines = 4,
                        supportingText = { Text("One channel name or @handle per line. Add both if they differ.") })
                }
                ToggleRule("Lock rules until midnight", "Changes are available again after the daily reset.", lock, editable) { lock = it }
                Text("Browser and video detection depends on what each app exposes to Android Accessibility.",
                    color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = editable && !invalidBlocked && !invalidAllowed && (!browser || allowedList.isNotEmpty()) &&
                (!youtube || channelList.isNotEmpty()), onClick = {
                onSave(ContentRules(
                    blockedList.mapNotNull(::websiteHost).toSet(), allowedList.mapNotNull(::websiteHost).toSet(),
                    adult, browser, youtube, channelList, if (lock) nextMidnight() else 0
                ))
            }) { Text("Save rules") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text(if (editable) "Cancel" else "Close") } })
}

private fun String.entries() = lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

@Composable
internal fun StudyShortcuts(rules: ContentRules) {
    if ((!rules.youtubeStudy || rules.channels.isEmpty()) && (!rules.browserStudy || rules.allowedHosts.isEmpty())) return
    var open by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Panel {
        Text("Study shortcuts", color = Ink, fontWeight = FontWeight.Bold)
        Text("Open a website or YouTube channel from your study list.", color = Muted, fontSize = 13.sp)
        TextButton(onClick = { open = true }) { Text("Open study list") }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Study list") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                fun launch(url: String) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { android.widget.Toast.makeText(context, "No app can open this link.", android.widget.Toast.LENGTH_SHORT).show() }
                }
                if (rules.browserStudy) rules.allowedHosts.sorted().forEach { host ->
                    TextButton(onClick = { launch("https://$host") }) { Text(host) }
                }
                if (rules.youtubeStudy) rules.channels.sorted().forEach { name ->
                    TextButton(onClick = {
                        val url = if (name.matches(Regex("[a-zA-Z0-9_.-]+"))) "https://www.youtube.com/@${Uri.encode(name)}"
                            else "https://www.youtube.com/results?search_query=${Uri.encode(name)}"
                        launch(url)
                    }) { Text("YouTube · $name") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
}

@Composable
internal fun ScreenTimePanel(
    apps: DeviceApps, store: FocusStore, access: Boolean, today: LocalDate, onAccess: () -> Unit
) {
    var weekly by rememberSaveable { mutableStateOf(false) }
    var report by remember { mutableStateOf<Map<String, Long>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(access) {
        if (access) names = withContext(Dispatchers.IO) { apps.installed().associate { it.id to it.name } }
    }
    LaunchedEffect(access, today, weekly, names, lifecycle) {
        loaded = false
        report = null
        if (access && names.isNotEmpty()) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val first = if (weekly) today.minusDays(6) else today
                report = withContext(Dispatchers.IO) { apps.usageSince(first)?.filterKeys { it in names } }
                loaded = true
                delay(MINUTE)
            }
        }
    }
    Panel {
        Text("Screen time", color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        when {
            !access -> {
                Text("Allow Usage Access to see time in your apps and compare your categories.", color = Muted, fontSize = 13.sp)
                TextButton(onClick = onAccess) { Text("Allow Usage Access") }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !weekly, onClick = { weekly = false }, label = { Text("Today") })
                    FilterChip(selected = weekly, onClick = { weekly = true }, label = { Text("Last 7 days") })
                }
                val entries = report.orEmpty()
                if (entries.isEmpty()) {
                    Text(if (!loaded) "Reading app usage…" else "No app usage recorded for this period.", color = Muted, fontSize = 13.sp)
                } else {
                    Text("${usageLabel(entries.values.sum())} total", color = Purple, fontWeight = FontWeight.Bold)
                    val grouped = entries.entries.groupBy { store.rule(it.key).tag }
                    AppTag.entries.forEach { tag ->
                        val time = grouped[tag].orEmpty().sumOf { it.value }
                        UsageRow(tag.label, time)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 7.dp), color = Line)
                    Text("Most used apps", color = Ink, fontWeight = FontWeight.Bold)
                    entries.entries.sortedByDescending { it.value }.take(8).forEach { (pkg, time) ->
                        UsageRow(names[pkg] ?: pkg, time)
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, time: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), color = Ink, fontSize = 13.sp)
        Text(usageLabel(time), color = Muted, fontSize = 13.sp)
    }
}

private fun usageLabel(milliseconds: Long): String {
    val minutes = milliseconds / MINUTE
    return when {
        milliseconds == 0L -> "0 min"
        minutes == 0L -> "<1 min"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

internal fun studyStreak(history: List<StudyLog>, today: LocalDate): Int {
    val days = history.map { it.day }.toHashSet()
    var day = if (today in days) today else today.minusDays(1)
    var result = 0
    while (day in days) {
        result++
        day = day.minusDays(1)
    }
    return result
}
