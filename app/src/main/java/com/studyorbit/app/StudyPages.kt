package com.studyorbit.app

import androidx.compose.ui.res.stringResource
import android.os.SystemClock
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.selection.toggleable
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
    var configured by remember(device) { mutableStateOf<List<DeviceApp>?>(null) }
    LaunchedEffect(device, resumes, rules.keys) {
        configured = if (rules.isEmpty()) emptyList() else withContext(Dispatchers.IO) { device.configuredApps(rules.keys) }
    }
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
            Column(Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = orbitTopPadding())) {
                BlockSubpageTitle("Choose apps", ::closePage)
                Spacer(Modifier.height(Gap.lg))
                OrbitField(stringResource(R.string.search_installed_apps), query, { query = it }, Modifier.fillMaxWidth(),
                    placeholder = "App name")
                Spacer(Modifier.height(Gap.lg))
                Text("Eligible apps" + (apps?.let { " · ${it.size} results" } ?: ""),
                    color = Muted, fontSize = 13.sp)
            }
        }
        LazyColumn(
            state = listState, modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = when (screen) { "rules" -> 4.dp; "catalog" -> Gap.md; else -> orbitTopPadding() },
            bottom = if (showCatalog) Gap.xs else Gap.xl),
            verticalArrangement = Arrangement.spacedBy(Gap.md)
        ) {
            if (screen == "advanced") {
                item {
                    BlockSubpageTitle("Advanced filters", ::closePage)
                    Spacer(Modifier.height(Gap.lg))
                    ProtectionStatus(
                        if (blockAccess) "Accessibility enabled" else "Accessibility required",
                        active = blockAccess
                    )
                    Text(stringResource(R.string.filters_short_videos_and_supported_sites_using),
                        Modifier.padding(top = Gap.sm), color = Muted, fontSize = 13.sp)
                    if (!blockAccess) {
                        Spacer(Modifier.height(Gap.lg))
                        OrbitButton(stringResource(R.string.set_up_accessibility), onBlockAccess, Modifier.fillMaxWidth(), enabled = !locked)
                    }
                    SectionTitle(stringResource(R.string.short_video_filters), Modifier.padding(top = Gap.xl),
                        caption = stringResource(R.string.may_vary_with_the_version_of_each))
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
                    OrbitButton(stringResource(R.string.open_web_and_youtube_rules), { contentDialog = true }, Modifier.fillMaxWidth(),
                        primary = false, container = OrbitSurface)
                }
                item {
                    Panel(contentPadding = PaddingValues(horizontal = Gap.lg)) {
                        ToggleRule(stringResource(R.string.cover_split_screen_floating_apps), "", coverWindows, !locked) { enabled ->
                            if (!store.strictWindow()) {
                                store.prefs.edit().putBoolean("cover_small_windows", enabled).apply()
                                version++
                            }
                        }
                    }
                }
                item {
                    SectionTitle(if (adminActive) "Manage uninstall protection" else "Set up uninstall protection",
                        Modifier.padding(top = Gap.sm), caption = stringResource(R.string.optional_android_device_admin),
                        action = { TextButton(onClick = onAdmin, enabled = !locked) { Text(if (adminActive) "Manage" else "Set up") } })
                }
            } else {
                if (!showCatalog) {
                    item {
                        val enabled = appProtection && protectionReady
                        AtlasPanel(R.drawable.atlas_block) {
                            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                                OrbitHeading(stringResource(R.string.app_protection), level = HeadingLevel.Sheet, color = ArtText)
                                Text(when {
                                    enabled -> "Protection on"
                                    protectionReady -> "Ready to turn on"
                                    else -> "Setup needed"
                                }, color = ArtText, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                                Spacer(Modifier.height(Gap.xl))
                                ArtButton(onAppProtection, enabled = !locked, container = ArtMint) {
                                    ArtLabel(if (enabled) "Manage protection" else if (protectionReady) "Turn on protection" else "Set up protection")
                                }
                            }
                        }
                        if (locked) Text(stringResource(R.string.strict_session_active_rules_are_read_only),
                            Modifier.padding(top = Gap.md), color = Accent, fontSize = 13.sp)
                    }
                    item {
                        SectionTitle(stringResource(R.string.app_rules), Modifier.padding(top = Gap.sm),
                            caption = if (configuredApps.isEmpty()) "No rules yet" else "${configuredApps.size} configured")
                    }
                }
                if (apps.isNullOrEmpty()) item {
                    if (!showCatalog && apps != null) {
                        Panel(tone = PanelTone.Quiet) {
                            Text(stringResource(R.string.choose_apps_to_block_or_limit), color = Ink, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.you_can_set_up_rules_before_turning),
                                Modifier.padding(top = 2.dp), color = Muted, fontSize = 13.sp)
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
                    var icon by remember(device, app, icons) { mutableStateOf(icons.get(app.id)) }
                    LaunchedEffect(device, app, icons) {
                        if (icon == null) {
                            icon = withContext(Dispatchers.IO) {
                                device.loadIcon(app)?.toBitmap(88, 88)?.asImageBitmap()?.also { icons.put(app.id, it) }
                            }
                        }
                    }
                    Panel(onClick = { editing = app.id }, onClickLabel = "Open rules for ${app.name}",
                        contentPadding = PaddingValues(horizontal = Gap.lg, vertical = Gap.md)) {
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(FieldShape).background(AccentSurface)) {
                                icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
                            }
                            Column(Modifier.weight(1f).padding(horizontal = Gap.md)) {
                                Text(app.name, color = Ink, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(description, color = Muted, fontSize = 13.sp)
                            }
                            OrbitIcon(R.drawable.ic_next, tint = Muted)
                        }
                    }
                }
                if (!showCatalog) {
                    item {
                        OrbitButton(stringResource(R.string.choose_apps), { screen = "catalog"; onSubpage(true) },
                            Modifier.fillMaxWidth(), primary = false, container = OrbitSurface)
                    }
                    item {
                        SectionTitle(stringResource(R.string.advanced_filters), Modifier.padding(top = Gap.sm),
                            caption = if (blockAccess) "Accessibility enabled" else "Optional · Requires Accessibility")
                    }
                    item {
                        Panel(onClick = { screen = "advanced"; onSubpage(true) },
                            contentPadding = PaddingValues(horizontal = Gap.lg, vertical = Gap.md)) {
                            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.short_videos_websites_youtube), color = Ink, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.reels_shorts_spotlight_and_study_rules), color = Muted, fontSize = 13.sp)
                                }
                                OrbitIcon(R.drawable.ic_next, tint = Muted)
                            }
                        }
                    }
                    item {
                        Text(
                            if (blockAccess && !appProtection) "Accessibility can still enforce your app rules."
                            else "Whole-app protection can run without Accessibility.",
                            color = Muted, fontSize = 13.sp
                        )
                    }
                }
            }
        }
        if (showCatalog) {
            val keyboardVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
            val bottom = if (keyboardVisible) 8.dp else (screenHeight - 620.dp).coerceIn(8.dp, 112.dp)
            OrbitButton(stringResource(R.string.show_configured_apps), ::closePage,
                Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = Gap.md, bottom = bottom),
                primary = false)
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
    val color = if (active) Forest else Accent
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(label, color = color, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

private const val APP_RULE_ROW = "app-rule"

@Composable
internal fun BlockSubpageTitle(title: String, onClose: () -> Unit) = OrbitPageHeader(title, onClose)

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
    OrbitScreen(if (editable) "${app.name} rule" else "Rules are locked", onDismiss) {
        if (editable) {
            val tags = listOf(AppTag.PRODUCTIVE, AppTag.DISTRACTING, AppTag.UNTAGGED)
            Panel(contentPadding = PaddingValues(horizontal = Gap.lg)) {
                ToggleRule(stringResource(R.string.block_during_focus), "Also applies to scheduled focus-rule windows.", focusOnly) { focusOnly = it }
            }
            OrbitField(stringResource(R.string.daily_time_limit_1_720_minutes), minutes, { minutes = it.filter(Char::isDigit).take(3) },
                Modifier.fillMaxWidth(), placeholder = "No daily limit", numeric = true, suffix = "minutes",
                error = minutes.isNotEmpty() && limit !in 1..720,
                errorText = stringResource(R.string.error_limit_range),
                helper = stringResource(R.string.resets_at_local_midnight_clear_the_field))
            if (limit > 0) Panel(contentPadding = PaddingValues(horizontal = Gap.lg)) {
                ToggleRule(stringResource(R.string.lock_limit_until_midnight), "Read-only until tomorrow once saved.", lock) { lock = it }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.category), color = Muted, fontSize = 13.sp)
                OrbitSegmented(tags.map { it.label }, tags.indexOf(tag), { tag = tags[it] })
            }
            usage?.let { Text("Used today · ${it / MINUTE} min", color = Muted, fontSize = 13.sp) }
            OrbitButton(stringResource(R.string.save_rule), {
                onSave(AppRule(focusOnly, limit, tag, if (limit > 0 && lock) nextMidnight() else 0L))
            }, Modifier.fillMaxWidth(), enabled = minutes.isEmpty() || limit in 1..720)
        } else {
            Panel(tone = PanelTone.Tinted) {
                OrbitHeading(if (original.isLocked) "Locked until midnight" else "Strict session active", level = HeadingLevel.Section)
                Text(if (original.isLocked) "This rule unlocks at midnight, local time." else "Rules are read-only until your strict session finishes.",
                    Modifier.padding(top = Gap.sm), color = Muted)
            }
            Panel {
                Text(app.name, color = Ink, fontWeight = FontWeight.SemiBold)
                Text("Block during focus · ${if (original.focusOnly) "On" else "Off"}", Modifier.padding(top = Gap.sm), color = Muted)
                Text("Daily limit · ${if (limit > 0) "$limit minutes" else "Off"}", color = Muted)
                Text("Category · ${original.tag.label}", color = Muted)
            }
            OrbitButton(stringResource(R.string.back_to_block), onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun ToggleRule(title: String, details: String, enabled: Boolean, editable: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = enabled, enabled = editable, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = if (editable) Ink else Muted, fontWeight = FontWeight.SemiBold)
            if (details.isNotEmpty()) Text(details, color = Muted, fontSize = 13.sp)
        }
        Switch(checked = enabled, enabled = editable, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = OrbitSurface, checkedTrackColor = Accent,
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
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = Gap.xl),
        verticalArrangement = Arrangement.spacedBy(Gap.md)
    ) {
        item {
            AtlasPanel(R.drawable.atlas_progress) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(stringResource(R.string.today_s_study_goal), color = ArtText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("$total / $goal min", color = ArtText, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                    Spacer(Modifier.height(Gap.lg))
                    LinearProgressIndicator(progress = { (total.toFloat() / goal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = ArtText, trackColor = ArtTrack, drawStopIndicator = {})
                    if (streak > 0) Text("$streak day streak", Modifier.padding(top = Gap.sm), color = ArtText, fontSize = 14.sp)
                }
            }
        }
        item {
            Panel {
                SectionTitle(stringResource(R.string.study_rhythm),
                    caption = if (weekTotal > 0) "Last 7 days · $weekTotal min · $weekSessions ${if (weekSessions == 1) "session" else "sessions"}" else null)
                if (weekTotal > 0) {
                    WeeklyStudyChart(week)
                } else {
                    Text(stringResource(R.string.your_first_study_session_this_week_will), Modifier.padding(top = Gap.sm), color = Muted)
                    OrbitButton(stringResource(R.string.start_a_focus_session), onFocus, Modifier.fillMaxWidth().padding(top = Gap.md), primary = false)
                }
            }
        }
        item { ScreenTimePanel(apps, store, usageAccess, today, onUsageAccess) }
        item {
            SectionTitle(stringResource(R.string.recent_sessions), Modifier.padding(top = Gap.sm))
        }
        if (recent.isEmpty()) item {
            Panel(tone = PanelTone.Quiet) {
                Text(stringResource(R.string.completed_study_sessions_will_appear_here), color = Muted, fontSize = 14.sp)
            }
        } else item {
            Panel(contentPadding = PaddingValues(horizontal = Gap.lg)) {
                recent.forEachIndexed { index, session ->
                    val name = session.subject.ifBlank { "Focus session" }
                    val date = when (session.day) {
                        today -> stringResource(R.string.relative_today)
                        today.minusDays(1) -> stringResource(R.string.relative_yesterday)
                        else -> session.day.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()))
                    }
                    if (index > 0) HorizontalDivider(color = Line)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Gap.sm), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = Gap.md)) {
                            Text(name, color = Ink, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(date, color = Muted, fontSize = 13.sp)
                        }
                        Text("${session.minutes} min", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoRule(title: String, mode: Int, editable: Boolean, onMode: (Int) -> Unit) {
    Panel {
        Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
        OrbitSegmented(listOf("Off", "While studying", "Always"), mode, onMode, Modifier.padding(top = Gap.md), enabled = editable)
    }
}
