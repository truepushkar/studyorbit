package dev.stillpoint.focus

import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun BlockPage(
    device: DeviceApps, store: FocusStore, blockAccess: Boolean, usageAccess: Boolean,
    locked: Boolean, today: LocalDate, resumes: Int, onBlockAccess: () -> Unit,
    onUsageAccess: () -> Unit, adminActive: Boolean, onAdmin: () -> Unit
) {
    var catalog by remember(device, resumes) { mutableStateOf<List<DeviceApp>?>(null) }
    var usage by remember(today, usageAccess) { mutableStateOf<Map<String, Long>?>(null) }
    var usageReadAt by remember(today, usageAccess) { mutableLongStateOf(-MINUTE) }
    var query by rememberSaveable { mutableStateOf("") }
    var browseAll by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var contentDialog by rememberSaveable { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var appRulesVersion by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val listState = rememberLazyListState()
    val icons = remember(resumes) { LruCache<String, ImageBitmap>(64) }
    val showingAppRows by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.contentType == APP_RULE_ROW } }
    }
    LaunchedEffect(usageAccess, today, showingAppRows, lifecycle) {
        if (usageAccess && showingAppRows) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val wait = MINUTE - (SystemClock.elapsedRealtime() - usageReadAt)
                if (wait > 0) delay(wait)
                usage = withContext(Dispatchers.IO) { device.usageSince(today) }
                usageReadAt = SystemClock.elapsedRealtime()
            }
        }
    }
    val rules = remember(store, appRulesVersion, today, locked) { store.configuredRules() }
    val configured = produceState<List<DeviceApp>?>(null, device, resumes, rules.keys) {
        value = if (rules.isEmpty()) emptyList() else withContext(Dispatchers.IO) { device.configuredApps(rules.keys) }
    }.value
    val configuredApps = remember(configured, rules.keys) {
        configured.orEmpty().filter { it.id in rules }
    }
    val searchTerm = query.trim()
    val showCatalog = browseAll || searchTerm.isNotEmpty()
    LaunchedEffect(device, resumes, showCatalog) {
        if (showCatalog && catalog == null) catalog = withContext(Dispatchers.IO) { device.installed() }
    }
    val inventory = catalog
    val matches = remember(inventory, searchTerm, rules.keys) { mutableStateOf<List<DeviceApp>?>(null) }
    LaunchedEffect(inventory, searchTerm, rules.keys) {
        if (inventory == null) return@LaunchedEffect
        if (searchTerm.isNotEmpty()) delay(120)
        matches.value = withContext(Dispatchers.Default) {
            val results = if (searchTerm.isBlank()) inventory else inventory.filter {
                it.name.contains(searchTerm, ignoreCase = true) || it.id.contains(searchTerm, ignoreCase = true)
            }
            if (rules.isEmpty()) results else results.sortedByDescending { it.id in rules }
        }
    }
    val apps = if (showCatalog) matches.value else configured?.let { configuredApps }
    val content = remember(version, today, locked) { readContentRules(store.prefs) }
    val modes = remember(version) { videoTargets.associateWith { it.mode(store.prefs) } }
    val coverWindows = remember(version) { store.prefs.getBoolean("cover_small_windows", true) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 7.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageTitle("DISTRACTION CONTROLS", "Protect your focus.", "Choose what can interrupt your study time.")
        }
        item {
            Column(Modifier.fillMaxWidth().clip(CardShape).background(Lavender).padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Glyph.SHIELD, Purple, White)
                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                        Text(if (blockAccess) "Blocking is enabled" else "Enable app blocking",
                            color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(if (blockAccess) "Your rules apply automatically." else "Grant Android Accessibility access to enforce rules.",
                            color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    if (!blockAccess) TextButton(onClick = onBlockAccess) { Text("Enable") }
                    else Mark(Glyph.CHECK, Forest, 20)
                }
                if (!usageAccess) {
                    HorizontalDivider(Modifier.padding(vertical = 9.dp), color = White.copy(alpha = 0.65f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Glyph.TIMER, Purple, White)
                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                            Text("Daily limit access", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Grant Usage Access to count time in your apps.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        TextButton(onClick = onUsageAccess) { Text("Allow") }
                    }
                }
            }
        }
        if (locked) item {
            Panel { Text("Strict Mode keeps your blocking settings fixed until this session ends.", color = Purple) }
        }
        item {
            Panel {
                Text("Web and YouTube study rules", color = Ink, fontWeight = FontWeight.Bold)
                Text("Block domains or restrict browsers and YouTube to your study list.",
                    color = Muted, fontSize = 13.sp)
                TextButton(onClick = { contentDialog = true }) { Text("Open web and YouTube rules") }
            }
        }
        items(videoTargets, key = { "video:${it.pkg}" }) { target ->
            VideoRule(target.label, "Skip the short-video viewer.", if (target.pkg == YOUTUBE) Glyph.PLAY else Glyph.REELS,
                modes.getValue(target), !locked) { mode ->
                if (!store.strictWindow()) {
                    store.prefs.edit().putInt(target.key, mode).apply()
                    version++
                }
            }
        }
        item {
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("App rules", Modifier.weight(1f).semantics { heading() }, color = Ink, fontFamily = FontFamily.Serif, fontSize = 25.sp)
                Text("${configuredApps.size} configured", color = Muted, fontSize = 12.sp)
            }
            OutlinedTextField(
                query, { query = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true, label = { Text("Search installed apps") },
                leadingIcon = { Mark(Glyph.SEARCH, Muted, 18) }, shape = RoundedCornerShape(16.dp)
            )
            TextButton(onClick = {
                query = ""
                browseAll = !showCatalog
            }) {
                Text(if (showCatalog) "Show configured apps" else "Browse all apps")
            }
        }
        if (apps.isNullOrEmpty()) item {
            Text(
                when {
                    apps == null && showCatalog -> "Finding installed apps…"
                    apps == null -> "Loading app rules…"
                    searchTerm.isNotEmpty() -> "No matching apps."
                    browseAll -> "No eligible apps found."
                    else -> "Search for an app to add a rule."
                },
                color = Muted, modifier = Modifier.padding(horizontal = 6.dp)
            )
        }
        items(apps.orEmpty(), key = { "app:${it.id}" }, contentType = { APP_RULE_ROW }) { app ->
            val rule = rules[app.id] ?: AppRule()
            val used = usage?.let { (it[app.id] ?: 0L) / MINUTE }
            val description = buildList {
                if (rule.focusOnly) add("Blocked in focus")
                if (rule.dailyMinutes > 0) add("${rule.dailyMinutes} min/day")
                if (rule.tag != AppTag.UNTAGGED) add(rule.tag.label)
                if (rule.isLocked) add("Locked until midnight")
                if (used != null && (used > 0 || rule.dailyMinutes > 0)) add("$used min used")
            }.joinToString(" · ").ifEmpty { "Add a block or daily limit" }
            val icon by produceState(icons.get(app.id), device, app, icons) {
                value = icons.get(app.id) ?: withContext(Dispatchers.IO) {
                    device.loadIcon(app)?.toBitmap(88, 88)?.asImageBitmap()?.also { icons.put(app.id, it) }
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(White)
                    .border(1.dp, Line, RoundedCornerShape(19.dp))
                    .clickable(enabled = !locked, onClickLabel = "Edit rules for ${app.name}") { editing = app.id }
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Lavender)) {
                    icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(app.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Mark(Glyph.ARROW, Purple, 19)
            }
        }
        item {
            Panel {
                ToggleRule("Cover split-screen and floating apps",
                    "Apply your blocking rules to secondary windows Android makes visible.",
                    coverWindows, !locked) { enabled ->
                    if (!store.strictWindow()) {
                        store.prefs.edit().putBoolean("cover_small_windows", enabled).apply()
                        version++
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Line)
                Text("Uninstall protection", color = Ink, fontWeight = FontWeight.Bold)
                Text("Android requires you to turn off device admin before uninstalling StudyOrbit. You can remove access in Android Settings.",
                    color = Muted, fontSize = 13.sp)
                TextButton(enabled = !locked, onClick = onAdmin) {
                    Text(if (adminActive) "Turn off protection" else "Set up protection")
                }
            }
        }
    }
    val app = editing?.let { id ->
        configuredApps.firstOrNull { it.id == id } ?: inventory?.firstOrNull { it.id == id }
    }
    if (app != null) RuleDialog(app, rules[app.id] ?: AppRule(), locked, { editing = null }) {
        if (store.saveRule(app.id, it)) appRulesVersion++
        editing = null
    }
    if (contentDialog) ContentRulesDialog(content, locked, onDismiss = { contentDialog = false }) {
        if (store.saveContentRules(it)) version++
        contentDialog = false
    }
}

private const val APP_RULE_ROW = "app-rule"

@Composable
private fun RuleDialog(app: DeviceApp, original: AppRule, locked: Boolean, onDismiss: () -> Unit, onSave: (AppRule) -> Unit) {
    var focusOnly by rememberSaveable(app.id) { mutableStateOf(original.focusOnly) }
    var dailyLimit by rememberSaveable(app.id) { mutableStateOf(original.dailyMinutes > 0) }
    var minutes by rememberSaveable(app.id) {
        mutableStateOf(original.dailyMinutes.takeIf { it > 0 }?.toString() ?: "30")
    }
    val limit = minutes.toIntOrNull() ?: 0
    var tag by rememberSaveable(app.id) { mutableStateOf(original.tag) }
    var lock by rememberSaveable(app.id) { mutableStateOf(original.isLocked) }
    val editable = !locked && !original.isLocked
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(app.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!editable) Text("This rule is locked.", color = Purple, fontWeight = FontWeight.SemiBold)
                ToggleRule("Block during focus", "Applies during active and scheduled focus.", focusOnly, editable) { focusOnly = it }
                HorizontalDivider(color = Line)
                ToggleRule("Daily time limit", "Resets at local midnight.", dailyLimit, editable) { dailyLimit = it }
                if (dailyLimit && editable) NumberField(minutes, { minutes = it }, "Minutes per day", 1..720)
                if (dailyLimit && !editable) Text("$limit minutes per day", color = Muted)
                Text("Screen time category", color = Ink, fontWeight = FontWeight.SemiBold)
                AppTag.entries.forEach { option ->
                    FilterChip(selected = tag == option, enabled = editable, onClick = { tag = option },
                        label = { Text(option.label) })
                }
                if (dailyLimit) ToggleRule("Lock limit until midnight",
                    "You can edit it again after the daily reset.", lock, editable) { lock = it }
            }
        },
        confirmButton = {
            TextButton(enabled = editable && (!dailyLimit || limit in 1..720), onClick = {
                onSave(AppRule(focusOnly, if (dailyLimit) limit else 0, tag, if (dailyLimit && lock) nextMidnight() else 0))
            }) { Text("Save rule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun ToggleRule(title: String, details: String, enabled: Boolean, editable: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(details, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Switch(checked = enabled, enabled = editable, onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
internal fun ProgressPage(
    history: List<StudyLog>, today: LocalDate, goal: Int, apps: DeviceApps, store: FocusStore,
    usageAccess: Boolean, onUsageAccess: () -> Unit
) {
    val week = remember(history, today) {
        val grouped = history.groupBy { it.day }
        (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            date to grouped[date].orEmpty().sumOf { it.minutes }
        }
    }
    val weekTotal = week.sumOf { it.second }
    val weekSessions = history.count { !it.day.isBefore(today.minusDays(6)) && !it.day.isAfter(today) }
    val recent = remember(history) { history.sortedByDescending { it.endedAt }.take(30) }
    val streak = remember(history, today) { studyStreak(history, today) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 7.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        item { PageTitle("STUDY ACTIVITY", "See your progress.", "Every completed session adds to your study record.") }
        item { GoalCard(week.last().second, goal) }
        item {
            Panel {
                Text("Study streak", color = Ink, fontWeight = FontWeight.Bold)
                Text("$streak ${if (streak == 1) "day" else "days"} in a row", color = Purple)
            }
        }
        item {
            Panel {
                Text("Last 7 days", Modifier.semantics { heading() }, color = Ink, fontFamily = FontFamily.Serif, fontSize = 25.sp)
                Text("$weekTotal min · $weekSessions ${if (weekSessions == 1) "session" else "sessions"}",
                    color = Purple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                val maximum = maxOf(1, week.maxOf { it.second })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    week.forEach { (date, minutes) ->
                        val current = date == today
                        val label = date.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH))
                        Column(
                            Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$label, $minutes minutes" },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(if (minutes > 0) minutes.toString() else "·", color = Muted, fontSize = 11.sp, maxLines = 1)
                            Box(Modifier.height(81.dp).padding(vertical = 4.dp).widthIn(max = 34.dp).fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp)).background(Color(0xFFF1EDF6)), contentAlignment = Alignment.BottomCenter) {
                                if (minutes > 0) Box(Modifier.fillMaxWidth()
                                    .fillMaxHeight((minutes.toFloat() / maximum).coerceAtLeast(0.05f))
                                    .clip(RoundedCornerShape(7.dp)).background(if (current) Purple else Lilac))
                            }
                            Text(label, color = if (current) Purple else Muted, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
        item { ScreenTimePanel(apps, store, usageAccess, today, onUsageAccess) }
        item {
            Text("Recent sessions", Modifier.padding(top = 5.dp).semantics { heading() },
                color = Ink, fontFamily = FontFamily.Serif, fontSize = 25.sp)
        }
        if (recent.isEmpty()) item {
            Panel {
                Text("No completed sessions yet", fontWeight = FontWeight.Bold, color = Ink)
                Text("Finish a focus timer to see it here.", color = Muted, fontSize = 13.sp)
            }
        }
        items(recent) { session ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Glyph.BOOK, Purple, Lavender)
                    Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                        Text(session.subject.ifBlank { "Focus session" }, color = Ink, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(session.day.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)), color = Muted, fontSize = 12.sp)
                    }
                    Text("${session.minutes} min", color = Purple, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun VideoRule(
    title: String, description: String, glyph: Glyph, mode: Int, editable: Boolean, onMode: (Int) -> Unit
) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(glyph, Purple, Lavender)
            Column(Modifier.padding(start = 11.dp).weight(1f)) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Off", "While studying", "Always").forEachIndexed { index, label ->
                FilterChip(
                    selected = mode == index, enabled = editable, onClick = { onMode(index) },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lavender)
                )
            }
        }
        if (mode != 0) {
            Text("Detection can vary by app version.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(White).border(1.dp, Line, CardShape).padding(15.dp),
        content = content)
}

@Composable
private fun IconTile(glyph: Glyph, color: Color, background: Color) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(background), contentAlignment = Alignment.Center) {
        Mark(glyph, color, 21)
    }
}

internal enum class Glyph { TIMER, SHIELD, CHART, CALENDAR, SETTINGS, PLAY, REELS, PAUSE, TARGET, ARROW, BOOK, SEARCH, CHECK }

@Composable
internal fun Mark(glyph: Glyph, color: Color, dimension: Int) {
    Canvas(Modifier.size(dimension.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.7f, StrokeCap.Round)

            when (glyph) {
                Glyph.TIMER -> {
                    drawCircle(color, 7.5f, Offset(12f, 13.5f), style = stroke)
                    line(10f, 2f, 14f, 2f); line(12f, 2f, 12f, 6f)
                    line(12f, 13.5f, 15f, 11f)
                }
                Glyph.SHIELD -> drawPath(Path().apply {
                    moveTo(12f, 2.5f); lineTo(20f, 6f); lineTo(20f, 12f)
                    quadraticTo(19.8f, 17.4f, 12f, 21.2f)
                    quadraticTo(4.2f, 17.4f, 4f, 12f)
                    lineTo(4f, 6f); close()
                }, color, style = stroke)
                Glyph.CHART -> {
                    line(4f, 20f, 20f, 20f); line(7f, 15f, 7f, 11f)
                    line(12f, 15f, 12f, 5f); line(17f, 15f, 17f, 8f)
                }
                Glyph.CALENDAR -> {
                    drawRoundRect(color, Offset(3f, 5f), Size(18f, 16f), CornerRadius(3f), style = stroke)
                    line(3f, 10f, 21f, 10f)
                    line(8f, 3f, 8f, 7f)
                    line(16f, 3f, 16f, 7f)
                    line(8f, 15f, 10f, 15f)
                    line(14f, 15f, 16f, 15f)
                }
                Glyph.SETTINGS -> {
                    line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f)
                    listOf(Offset(8f, 6f), Offset(16f, 12f), Offset(10f, 18f)).forEach {
                        drawCircle(Paper, 2.6f, it); drawCircle(color, 2.6f, it, style = stroke)
                    }
                }
                Glyph.PLAY -> drawPath(Path().apply {
                    moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close()
                }, color)
                Glyph.REELS -> {
                    drawRoundRect(color, Offset(3f, 3f), Size(18f, 18f), CornerRadius(4f), style = stroke)
                    line(3f, 8f, 21f, 8f)
                    line(8f, 3f, 11f, 8f)
                    line(14f, 3f, 17f, 8f)
                    drawPath(Path().apply {
                        moveTo(10f, 11f); lineTo(15f, 14.5f); lineTo(10f, 18f); close()
                    }, color)
                }
                Glyph.PAUSE -> { line(9f, 5f, 9f, 19f); line(15f, 5f, 15f, 19f) }
                Glyph.TARGET -> {
                    drawCircle(color, 8.5f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, 4.7f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, 1.4f, Offset(12f, 12f))
                }
                Glyph.ARROW -> {
                    line(4f, 12f, 19f, 12f); line(14f, 7f, 19f, 12f); line(14f, 17f, 19f, 12f)
                }
                Glyph.BOOK -> {
                    drawRoundRect(color, Offset(4f, 3f), Size(16f, 18f), CornerRadius(2f), style = stroke)
                    line(8f, 3f, 8f, 21f); line(11f, 8f, 17f, 8f); line(11f, 12f, 15f, 12f)
                }
                Glyph.SEARCH -> {
                    drawCircle(color, 6.1f, Offset(10f, 10f), style = stroke); line(14.5f, 14.5f, 20f, 20f)
                }
                Glyph.CHECK -> { line(4f, 12f, 9f, 17f); line(9f, 17f, 20f, 6f) }
            }
        }
    }
}
