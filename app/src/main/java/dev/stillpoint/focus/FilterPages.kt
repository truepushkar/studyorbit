package dev.stillpoint.focus

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.ZoneId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun nextMidnight() = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
internal fun ContentRulesDialog(
    original: ContentRules, strict: Boolean, enabled: Boolean, onDismiss: () -> Unit, onSave: (ContentRules) -> Unit
) {
    val editable = !original.isLocked && !strict
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var catalogReady by remember { mutableStateOf(DomainCatalog.isReady(context)) }
    var downloading by remember { mutableStateOf(false) }
    var downloadFailed by remember { mutableStateOf(false) }
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
    OrbitFullScreen(onDismiss) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = orbitTopPadding(), bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BlockSubpageTitle("Web & YouTube rules", onDismiss)
                Text(when {
                    !editable -> "These rules are locked."
                    enabled -> "Accessibility is on · rules are enforced"
                    else -> "Needs Accessibility · rules not enforced"
                }, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(blocked, { blocked = it.take(4000) }, enabled = editable, isError = invalidBlocked,
                    label = { Text("Always blocked sites") }, placeholder = { Text("example.com") }, maxLines = 4,
                    supportingText = { Text(if (invalidBlocked) "Enter valid domains or URLs." else "One domain or URL per line.") })
                val catalogStatus = when {
                    catalogReady -> R.string.adult_catalog_ready
                    downloadFailed -> R.string.adult_catalog_failed
                    downloading -> R.string.adult_catalog_downloading
                    adult -> R.string.adult_catalog_legacy
                    else -> R.string.adult_catalog_required
                }
                ToggleRule(stringResource(R.string.adult_catalog_toggle), stringResource(catalogStatus),
                    adult, editable && (catalogReady || adult)) { adult = it }
                if (!catalogReady) {
                    if (downloading) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            color = Accent, trackColor = OrbitTrack
                        )
                    } else {
                        OrbitButton(stringResource(if (downloadFailed) R.string.adult_catalog_retry else R.string.adult_catalog_download), {
                            downloading = true
                            downloadFailed = false
                            scope.launch {
                                try {
                                    DomainCatalog.download(context.applicationContext)
                                    catalogReady = true
                                } catch (_: IOException) {
                                    downloadFailed = true
                                } finally {
                                    downloading = false
                                }
                            }
                        }, Modifier.fillMaxWidth(), primary = false)
                    }
                }
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
                OrbitButton("Save rules", {
                    onSave(ContentRules(
                        blockedList.mapNotNull(::websiteHost).toSet(), allowedList.mapNotNull(::websiteHost).toSet(),
                        adult, browser, youtube, channelList, if (lock) nextMidnight() else 0
                    ))
                }, Modifier.fillMaxWidth(), enabled = editable && !invalidBlocked && !invalidAllowed &&
                    (!browser || allowedList.isNotEmpty()) && (!youtube || channelList.isNotEmpty()))
                OrbitButton(if (editable) "Cancel" else "Close", onDismiss, Modifier.fillMaxWidth(), primary = false, rounded = false)
            }
    }
}

private fun String.entries() = lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

@Composable
internal fun StudyShortcuts(rules: ContentRules) {
    val context = LocalContext.current
    fun launch(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { android.widget.Toast.makeText(context, "No app can open this link.", android.widget.Toast.LENGTH_SHORT).show() }
    }
    if (rules.allowedHosts.isEmpty() && rules.channels.isEmpty()) {
        Text("Add sites and channels under Block → Advanced filters.", color = Muted, fontSize = 13.sp)
    }
    rules.allowedHosts.sorted().forEach { host ->
        OrbitButton("$host  ↗", { launch("https://$host") }, Modifier.fillMaxWidth(), primary = false, rounded = false, container = OrbitSurface)
    }
    rules.channels.sorted().forEach { name ->
        OrbitButton("$name  ↗", {
            val url = if (name.matches(Regex("[a-zA-Z0-9_.-]+"))) "https://www.youtube.com/@${Uri.encode(name)}"
                else "https://www.youtube.com/results?search_query=${Uri.encode(name)}"
            launch(url)
        }, Modifier.fillMaxWidth(), primary = false, rounded = false, container = OrbitSurface)
    }
}

@Composable
internal fun ScreenTimePanel(
    apps: DeviceApps, store: FocusStore, access: Boolean, today: LocalDate, onAccess: () -> Unit
) {
    var weekly by rememberSaveable { mutableStateOf(false) }
    var report by remember { mutableStateOf<Map<String, Long>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var names by remember(access) { mutableStateOf<Map<String, String>?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(access) {
        if (access) names = withContext(Dispatchers.IO) { apps.installed().associate { it.id to it.name } }
    }
    LaunchedEffect(access, today, weekly, names, lifecycle) {
        loaded = false
        report = null
        val appNames = names
        if (access && appNames != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val first = if (weekly) today.minusDays(6) else today
                report = withContext(Dispatchers.IO) { apps.usageSince(first)?.filterKeys { it in appNames } }
                loaded = true
                delay(MINUTE)
            }
        }
    }
    Panel {
        Text("Device screen time", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("Not counted as study time", color = Muted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 5.dp).clip(FieldShape).background(Night)) {
            listOf("Today", "Last 7 days").forEachIndexed { index, label ->
                val selected = weekly == (index == 1)
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(FieldShape).background(if (selected) AccentSurface else Night)
                    .selectable(selected, enabled = access) { weekly = index == 1 }, contentAlignment = Alignment.Center) {
                    Text(label, color = Accent, fontSize = 12.sp)
                }
            }
        }
        val entries = report.orEmpty()
        when {
            !access -> {
                Text("Understand where your day goes.", Modifier.padding(vertical = 7.dp), color = Muted, fontSize = 13.sp)
                OrbitButton("Allow Usage Access", onAccess, Modifier.fillMaxWidth(), primary = false)
            }
            entries.isEmpty() -> {
                Text(if (!loaded) "Reading app usage…" else "No device usage recorded.",
                    Modifier.padding(vertical = 12.dp), color = Muted, fontSize = 13.sp)
            }
            else -> {
                val sum = entries.values.sum()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(usageLabel(sum), Modifier.weight(1f), color = Ink, fontSize = 28.sp, lineHeight = 34.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text(if (weekly) "Avg. ${usageLabel(sum / 7)} / day" else "Total device usage",
                        Modifier.weight(1f), color = Muted, fontSize = 11.sp)
                }
                val totals = entries.entries.groupBy { store.rule(it.key).tag }
                    .mapValues { (_, apps) -> apps.sumOf { it.value } }
                val tags = listOf(AppTag.PRODUCTIVE to Forest, AppTag.DISTRACTING to androidx.compose.ui.graphics.Color(0xFFAC4C32),
                    AppTag.UNTAGGED to ChartSage)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(12.dp).clip(CircleShape).background(Night)) {
                    tags.forEach { (tag, color) ->
                        val duration = totals[tag] ?: 0L
                        if (duration > 0) Spacer(Modifier.weight(duration.toFloat()).fillMaxHeight().background(color))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    tags.forEach { (tag, color) ->
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.size(6.dp).background(color, CircleShape))
                                Text(tag.label, Modifier.padding(start = 4.dp), color = Muted, fontSize = 11.sp)
                            }
                            Text(usageLabel(totals[tag] ?: 0L), color = Ink, fontSize = 13.sp)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 9.dp), color = Line)
                Text("Most used", color = Muted, fontSize = 11.sp)
                entries.entries.sortedByDescending { it.value }.take(4).forEach { (pkg, time) ->
                    UsageRow(names?.get(pkg) ?: pkg, time)
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
