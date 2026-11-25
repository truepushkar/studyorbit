package dev.stillpoint.focus

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
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
    locked: Boolean, today: LocalDate, resumes: Int, appProtection: Boolean, protectionReady: Boolean,
    onSubpage: (Boolean) -> Unit, onAppProtection: () -> Unit,
    onBlockAccess: () -> Unit,
    adminActive: Boolean, onAdmin: () -> Unit
) {
    var catalog by remember(device, resumes) { mutableStateOf<List<DeviceApp>?>(null) }
    var usage by remember(today, usageAccess) { mutableStateOf<Map<String, Long>?>(null) }
    var usageReadAt by remember(today, usageAccess) { mutableLongStateOf(-MINUTE) }
    var query by rememberSaveable { mutableStateOf("") }
    var screen by rememberSaveable { mutableStateOf("rules") }
    var searchOnOpen by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var contentDialog by rememberSaveable { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var appRulesVersion by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val rulesList = rememberLazyListState()
    val catalogList = rememberLazyListState()
    val advancedList = rememberLazyListState()
    val listState = when (screen) {
        "catalog" -> catalogList
        "advanced" -> advancedList
        else -> rulesList
    }
    fun closePage() {
        screen = "rules"
        query = ""
        onSubpage(false)
    }
    SideEffect { onSubpage(screen != "rules") }
    BackHandler(screen != "rules", ::closePage)
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
    val showCatalog = screen == "catalog"
    LaunchedEffect(device, resumes, showCatalog) {
        if (showCatalog && catalog == null) catalog = withContext(Dispatchers.IO) { device.installed() }
    }
    val inventory = catalog
    val matches = remember(inventory, searchTerm, rules.keys) { mutableStateOf<List<DeviceApp>?>(null) }
    LaunchedEffect(inventory, searchTerm, rules.keys, showCatalog) {
        if (!showCatalog || inventory == null) return@LaunchedEffect
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
    Column(Modifier.fillMaxSize()) {
        if (showCatalog) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = orbitTopPadding())) {
                BlockSubpageTitle("Choose apps", ::closePage)
                Spacer(Modifier.height(15.dp))
                val searchFocus = remember { FocusRequester() }
                OrbitField("Search installed apps", query, { query = it }, Modifier.fillMaxWidth().focusRequester(searchFocus),
                    placeholder = "App name")
                LaunchedEffect(searchOnOpen) {
                    if (searchOnOpen) {
                        searchFocus.requestFocus()
                        searchOnOpen = false
                    }
                }
                Spacer(Modifier.height(23.dp))
                Text("Eligible apps" + (apps?.let { " · ${it.size} results" } ?: ""),
                    color = Muted, fontSize = 13.sp)
            }
        }
        LazyColumn(
            state = listState, modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = when (screen) { "rules" -> 6.dp; "catalog" -> 10.dp; else -> orbitTopPadding() },
            bottom = if (showCatalog) 4.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (screen == "catalog") 14.dp else 9.dp)
        ) {
            if (screen == "advanced") {
                item {
                    BlockSubpageTitle("Advanced filters", ::closePage)
                    ProtectionStatus(
                        if (blockAccess) "Accessibility enabled" else "Accessibility required",
                        active = blockAccess
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Filters short videos and supported sites using the content visible on screen.",
                        color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                    if (!blockAccess) {
                        Spacer(Modifier.height(21.dp))
                        OrbitButton("Set up Accessibility", onBlockAccess, Modifier.fillMaxWidth(), enabled = !locked)
                    }
                    OrbitHeading("Short-video filters", Modifier.padding(top = 20.dp, bottom = 3.dp), 19)
                }
                items(videoTargets, key = { "video:${it.pkg}" }) { target ->
                    VideoRule(target.label, modes.getValue(target), !locked) { mode ->
                        if (!store.strictWindow()) {
                            store.prefs.edit().putInt(target.key, mode).apply()
                            version++
                        }
                    }
                }
                item {
                    Text("May vary with the version of each app.", Modifier.padding(vertical = 9.dp),
                        color = Muted, fontSize = 11.sp)
                    OrbitButton("Open web and YouTube rules", { contentDialog = true }, Modifier.fillMaxWidth(),
                        primary = false, rounded = false)
                    Spacer(Modifier.height(8.dp))
                    ToggleRule("Cover split-screen & floating apps", "", coverWindows, !locked) { enabled ->
                        if (!store.strictWindow()) {
                            store.prefs.edit().putBoolean("cover_small_windows", enabled).apply()
                            version++
                        }
                    }
                    TextButton(onClick = onAdmin, enabled = !locked, contentPadding = PaddingValues(0.dp)) {
                        Text(if (adminActive) "Manage uninstall protection  ›" else "Set up uninstall protection  ›")
                    }
                    Text("Optional Android device admin", color = Muted, fontSize = 11.sp)
                }
            } else {
                if (!showCatalog) {
                    item {
                        Column(Modifier.fillMaxWidth().clip(CardShape).background(Lavender)
                            .border(1.dp, Line, CardShape).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
                            OrbitHeading("Whole-app protection", size = 19)
                            val enabled = appProtection && protectionReady
                            ProtectionStatus(when {
                                enabled -> "Protection on"
                                protectionReady -> "Ready to turn on"
                                else -> "Setup needed"
                            }, enabled, Modifier.padding(top = 5.dp))
                            Text("Block selected apps during focus or when they reach a daily limit.",
                                Modifier.padding(top = 10.dp, bottom = 10.dp), color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                            OrbitButton(when {
                                enabled -> "Manage protection"
                                protectionReady -> "Turn on protection"
                                else -> "Set up protection"
                            }, onAppProtection, Modifier.fillMaxWidth(), enabled = !locked)
                        }
                        if (locked) Text("Strict session active · rules are read-only",
                            Modifier.padding(top = 12.dp), color = Purple, fontSize = 13.sp)
                    }
                    item {
                        OrbitHeading("App rules", Modifier.padding(top = 15.dp), 19)
                        Text(if (configuredApps.isEmpty()) "No rules yet" else "${configuredApps.size} configured",
                            Modifier.padding(top = 3.dp), color = Muted, fontSize = 13.sp)
                    }
                }
                if (apps.isNullOrEmpty()) item {
                    if (!showCatalog && apps != null) {
                        Panel {
                            Text("Choose apps to block or limit", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("You can set up rules before turning on protection.",
                                Modifier.padding(top = 3.dp), color = Muted, fontSize = 12.sp)
                        }
                    } else Text(
                        when {
                            apps == null && showCatalog -> "Finding installed apps…"
                            apps == null -> "Loading app rules…"
                            searchTerm.isNotEmpty() -> "No matching apps."
                            else -> "No eligible apps found."
                        }, color = Muted
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
                    }.joinToString(" · ").ifEmpty { "Not configured" }
                    val icon by produceState(icons.get(app.id), device, app, icons) {
                        value = icons.get(app.id) ?: withContext(Dispatchers.IO) {
                            device.loadIcon(app)?.toBitmap(88, 88)?.asImageBitmap()?.also { icons.put(app.id, it) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(19.dp)).background(OrbitSurface)
                            .border(1.dp, Line, RoundedCornerShape(19.dp))
                            .clickable(onClickLabel = "Open rules for ${app.name}") { editing = app.id }
                            .heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Lavender)) {
                            icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(app.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Text("›", color = Purple, fontSize = 23.sp)
                    }
                }
                if (!showCatalog) {
                    item {
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OrbitButton("Search installed apps", { searchOnOpen = true; screen = "catalog"; onSubpage(true) }, Modifier.weight(1.75f), primary = false)
                            OrbitButton("Browse all apps", { screen = "catalog"; onSubpage(true) }, Modifier.weight(1f), primary = false)
                        }
                    }
                    item {
                        OrbitHeading("Advanced filters", Modifier.padding(top = 22.dp), 19)
                        ProtectionStatus(
                            if (blockAccess) "Accessibility enabled" else "Optional · Requires Accessibility",
                            active = blockAccess, modifier = Modifier.padding(top = 5.dp, bottom = 11.dp)
                        )
                        Surface(
                            onClick = { screen = "advanced"; onSubpage(true) }, modifier = Modifier.fillMaxWidth(),
                            color = OrbitSurface, shape = CardShape, border = androidx.compose.foundation.BorderStroke(1.dp, Line)
                        ) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Short videos, websites & YouTube", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("Reels, Shorts, Spotlight and study rules", color = Muted, fontSize = 12.sp)
                                }
                                Text("›", Modifier.padding(start = 12.dp), color = Purple, fontSize = 23.sp)
                            }
                        }
                        Text(
                            if (blockAccess && !appProtection) "Accessibility can still enforce your app rules."
                            else "Whole-app protection can run without Accessibility.",
                            Modifier.padding(top = 15.dp), color = Muted, fontSize = 12.sp, lineHeight = 16.sp
                        )
                    }
                }
            }
        }
        if (showCatalog) {
            val keyboardVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
            val bottom = if (keyboardVisible) 8.dp else (screenHeight - 620.dp).coerceIn(8.dp, 112.dp)
            OrbitButton("Show configured apps", ::closePage,
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = bottom),
                primary = false, rounded = false)
        }
    }
    val app = editing?.let { id ->
        configuredApps.firstOrNull { it.id == id } ?: inventory?.firstOrNull { it.id == id }
    }
    if (app != null) RuleDialog(app, rules[app.id] ?: AppRule(), locked, usage?.get(app.id), { editing = null }) {
        if (store.saveRule(app.id, it)) appRulesVersion++
        editing = null
    }
    if (contentDialog) ContentRulesDialog(content, locked, blockAccess, onDismiss = { contentDialog = false }) {
        if (store.saveContentRules(it)) version++
        contentDialog = false
    }
}

@Composable
private fun ProtectionStatus(label: String, active: Boolean, modifier: Modifier = Modifier) {
    val color = if (active) Forest else Purple
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(label, color = color, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

private const val APP_RULE_ROW = "app-rule"

@Composable
internal fun BlockSubpageTitle(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OrbitHeading(title, Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Text("×", Modifier.semantics { contentDescription = "Close" }, color = Purple, fontSize = 25.sp)
        }
    }
}

@Composable
private fun RuleDialog(
    app: DeviceApp, original: AppRule, locked: Boolean, usage: Long?,
    onDismiss: () -> Unit, onSave: (AppRule) -> Unit
) {
    var focusOnly by rememberSaveable(app.id) { mutableStateOf(original.focusOnly) }
    var minutes by rememberSaveable(app.id) {
        mutableStateOf(original.dailyMinutes.takeIf { it > 0 }?.toString().orEmpty())
    }
    val limit = minutes.toIntOrNull() ?: 0
    var tag by rememberSaveable(app.id) { mutableStateOf(original.tag) }
    var lock by rememberSaveable(app.id) { mutableStateOf(original.isLocked) }
    val editable = !locked && !original.isLocked
    OrbitFullScreen(onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = orbitTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BlockSubpageTitle(if (editable) "${app.name} rule" else "Rules are locked", onDismiss)
            if (editable) {
                Text(app.name, color = Purple, fontWeight = FontWeight.SemiBold)
                Panel {
                    ToggleRule("Block during focus", "Also applies to scheduled focus-rule windows.", focusOnly) { focusOnly = it }
                }
                OrbitField("Daily time limit · 1–720 minutes", minutes, { minutes = it.filter(Char::isDigit).take(3) },
                    Modifier.fillMaxWidth(), placeholder = "No daily limit", numeric = true, suffix = "minutes",
                    error = minutes.isNotEmpty() && limit !in 1..720)
                Text("Resets at local midnight. Clear the field to remove the limit.", color = Muted, fontSize = 12.sp)
                if (limit > 0) Panel {
                    ToggleRule("Lock limit until midnight", "Read-only until tomorrow once saved.", lock) { lock = it }
                }
                Text("Category", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(AppTag.PRODUCTIVE, AppTag.DISTRACTING, AppTag.UNTAGGED).forEach { option ->
                        OrbitButton(option.label, { tag = option }, Modifier.weight(1f), primary = tag == option)
                    }
                }
                usage?.let { Text("Used today · ${it / MINUTE} min", color = Muted, fontSize = 13.sp) }
                OrbitButton("Save rule", {
                    onSave(AppRule(focusOnly, limit, tag, if (limit > 0 && lock) nextMidnight() else 0L))
                }, Modifier.fillMaxWidth(), enabled = minutes.isEmpty() || limit in 1..720)
                OrbitButton("Cancel", onDismiss, Modifier.fillMaxWidth(), primary = false, rounded = false)
            } else {
                Panel {
                    OrbitHeading(if (original.isLocked) "Locked until midnight" else "Strict session active", size = 18)
                    Text(if (original.isLocked) "This rule unlocks at midnight, local time." else "Rules are read-only until your strict session finishes.",
                        Modifier.padding(top = 8.dp), color = Muted)
                }
                OrbitHeading(app.name, size = 19)
                Text("Block during focus · ${if (original.focusOnly) "On" else "Off"}", color = Muted)
                Text("Daily limit · ${if (limit > 0) "$limit minutes" else "Off"}", color = Muted)
                Text("Category · ${original.tag.label}", color = Muted)
                OrbitButton("Back to Block", onDismiss, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun ToggleRule(title: String, details: String, enabled: Boolean, editable: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
            if (details.isNotEmpty()) Text(details, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Switch(checked = enabled, enabled = editable, onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(checkedThumbColor = OrbitSurface, checkedTrackColor = Purple,
                uncheckedThumbColor = OrbitSurface, uncheckedTrackColor = Line, uncheckedBorderColor = Line))
    }
}

@Composable
internal fun ProgressPage(
    history: List<StudyLog>, today: LocalDate, goal: Int, apps: DeviceApps, store: FocusStore,
    usageAccess: Boolean, onFocus: () -> Unit, onUsageAccess: () -> Unit
) {
    val week = remember(history, today) {
        val grouped = history.groupBy { it.day }
        (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            date to grouped[date].orEmpty().sumOf { it.minutes }
        }
    }
    val weekTotal = week.sumOf { it.second }
    val weekSessions = remember(history, today) { history.count { it.day in today.minusDays(6)..today } }
    val recent = remember(history) { history.takeLast(30).asReversed() }
    val streak = remember(history, today) { studyStreak(history, today) }
    val total = week.last().second
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth().clip(CardShape).background(Lavender)
                .border(1.dp, Line, CardShape).padding(horizontal = 17.dp, vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DAILY STUDY GOAL", Modifier.weight(1f), color = Purple, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (streak == 0) "A fresh start" else "$streak day streak",
                        Modifier.background(OrbitSurface, CircleShape).padding(horizontal = 19.dp, vertical = 2.dp),
                        color = Purple, fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(total.toString(), color = Ink, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
                    Text("/ $goal min", color = Muted, fontSize = 16.sp)
                }
                LinearProgressIndicator(progress = { (total.toFloat() / goal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = Purple, trackColor = OrbitTrack, drawStopIndicator = {})
                Text(when {
                    total >= goal -> "You reached today’s target"
                    total == 0 -> "Your first minute starts a new orbit."
                    else -> "${goal - total} minutes to your daily target"
                }, Modifier.padding(top = 3.dp), color = Purple, fontSize = 12.sp)
            }
        }
        item {
            Panel {
                Text("Last 7 days · study time", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$weekTotal min", Modifier.weight(1f), color = Ink, fontWeight = FontWeight.SemiBold,
                        fontSize = 28.sp, lineHeight = 34.sp)
                    Text("$weekSessions ${if (weekSessions == 1) "session" else "sessions"}",
                        Modifier.weight(.49f), color = Muted, fontSize = 12.sp)
                }
                if (weekTotal == 0) {
                    Text("Your first session begins the story.", Modifier.padding(top = 12.dp, bottom = 9.dp), color = Muted, fontSize = 13.sp)
                    OrbitButton("Start a focus session", onFocus, Modifier.fillMaxWidth(), primary = false)
                } else {
                    Spacer(Modifier.height(17.dp))
                    val maximum = week.maxOf { it.second }
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { (date, minutes) ->
                            val label = date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                                contentDescription = "$label, $minutes minutes"
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.height(58.dp).width(24.dp), contentAlignment = Alignment.BottomCenter) {
                                    if (minutes > 0) Box(Modifier.fillMaxWidth()
                                        .fillMaxHeight((minutes.toFloat() / maximum).coerceAtLeast(0.08f))
                                        .background(if (date == today) Purple else Lilac, RoundedCornerShape(6.dp)))
                                }
                                Text(label.take(1), Modifier.padding(top = 7.dp), color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        item { ScreenTimePanel(apps, store, usageAccess, today, onUsageAccess) }
        item {
            Text("Recent sessions", Modifier.semantics { heading() }, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (recent.isEmpty()) Text("No sessions yet. Your first one belongs here.", color = Muted, fontSize = 12.sp)
        }
        items(recent) { session ->
            val name = session.subject.ifBlank { "Focus session" }
            val date = session.day.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
            Text("$name · $date · ${session.minutes} min", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun VideoRule(title: String, mode: Int, editable: Boolean, onMode: (Int) -> Unit) {
    Panel {
        Text(title, color = Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Off", "While studying", "Always").forEachIndexed { index, label ->
                val selected = mode == index
                Box(
                    Modifier.weight(if (index == 1) 1.55f else 1f).heightIn(min = 48.dp).clip(CircleShape)
                        .selectable(selected, enabled = editable, role = Role.RadioButton, onClick = { onMode(index) }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label, Modifier.background(if (selected) Lavender else OrbitSurface, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        color = if (selected && editable) Purple else Muted,
                        fontSize = 12.sp, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(CardShape).background(OrbitSurface).border(1.dp, Line, CardShape).padding(15.dp),
        content = content)
}
