package com.studyorbit.app

import android.util.LruCache
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

internal val Paper: Color @Composable get() = colorResource(R.color.orbit_paper)
internal val Ink: Color @Composable get() = colorResource(R.color.orbit_ink)
internal val Muted: Color @Composable get() = colorResource(R.color.orbit_muted)
internal val Accent: Color @Composable get() = colorResource(R.color.orbit_accent)
internal val AccentSurface: Color @Composable get() = colorResource(R.color.orbit_accent_surface)
internal val ChartSage: Color @Composable get() = colorResource(R.color.orbit_chart_sage)
internal val Line: Color @Composable get() = colorResource(R.color.orbit_line)
internal val Forest: Color @Composable get() = colorResource(R.color.orbit_forest)
internal val OrbitSurface: Color @Composable get() = colorResource(R.color.orbit_orbit_surface)
internal val Night: Color @Composable get() = colorResource(R.color.orbit_night)
internal val OnAccent: Color @Composable get() = colorResource(R.color.orbit_on_accent)
internal val OrbitTrack: Color @Composable get() = colorResource(R.color.orbit_orbit_track)
internal val Distracting: Color @Composable get() = colorResource(R.color.orbit_distracting)
internal val NavContainer: Color @Composable get() = colorResource(R.color.orbit_nav_container)
internal val NavContent: Color @Composable get() = colorResource(R.color.orbit_nav_content)
internal val NavPill: Color @Composable get() = colorResource(R.color.orbit_nav_pill)
internal val NavPillContent: Color @Composable get() = colorResource(R.color.orbit_nav_pill_content)
/* ── Spacing, shape and art tokens ─────────────────────────────────────────
 * One scale for the whole app. Screens never hand-pick dp values for these roles. */
internal object Gap {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Left/right edge of every screen, sheet and list. */
internal val ScreenPadding = 24.dp

internal val CardShape = RoundedCornerShape(20.dp)
internal val ButtonShape = RoundedCornerShape(16.dp)
internal val FieldShape = RoundedCornerShape(12.dp)
internal val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

// Colors that sit on top of artwork keep one fixed palette in both themes.
internal val ArtInk = Color(0xFF211E38)
internal val ArtText = Color.White
internal val ArtCoral = Color(0xFFFFBCAC)
internal val ArtMint = Color(0xFFBAF3DA)
internal val ArtSlate = Color(0xFF383254)
internal val ArtTrack = Color.White.copy(alpha = .26f)
private val artScrim = Brush.horizontalGradient(
    listOf(Color(0xE6171632), Color(0x66171632), Color.Transparent)
)

private val inter = FontFamily(
    Font(R.font.inter_regular),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/* Type scale: 13 caption · 14 control · 16 body/row title · 18 section · 24 sheet · 32 page.
 * Line height is relative (em) so any ad-hoc fontSize still gets a proportional leading. */
internal val orbitTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontFamily = inter, letterSpacing = 0.sp),
        titleLarge = titleLarge.copy(fontFamily = inter, letterSpacing = 0.sp),
        titleMedium = titleMedium.copy(fontFamily = inter, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontFamily = inter, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontFamily = inter, letterSpacing = 0.sp, lineHeight = 1.45.em),
        bodyMedium = bodyMedium.copy(fontFamily = inter, letterSpacing = 0.sp, lineHeight = 1.45.em),
        bodySmall = bodySmall.copy(fontFamily = inter, letterSpacing = 0.sp, lineHeight = 1.4.em),
        labelLarge = labelLarge.copy(fontFamily = inter, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontFamily = inter, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontFamily = inter, letterSpacing = 0.sp)
    )
}

internal val orbitShapes = Shapes(
    small = FieldShape, medium = FieldShape, large = CardShape, extraLarge = RoundedCornerShape(28.dp)
)

internal enum class HeadingLevel(val size: TextUnit, val line: TextUnit, val weight: FontWeight, val tracking: TextUnit) {
    Page(32.sp, 38.sp, FontWeight.Bold, (-0.5).sp),
    Sheet(24.sp, 30.sp, FontWeight.Bold, (-0.25).sp),
    Section(18.sp, 24.sp, FontWeight.SemiBold, 0.sp)
}

@Composable
internal fun OrbitHeading(
    title: String, modifier: Modifier = Modifier, level: HeadingLevel = HeadingLevel.Page, color: Color = Ink
) {
    Text(title, modifier.semantics { heading() }, color = color, fontSize = level.size, lineHeight = level.line,
        fontFamily = inter, letterSpacing = level.tracking, fontWeight = level.weight)
}

/** Heading for a group inside a screen, with an optional one-line caption and trailing action. */
@Composable
internal fun SectionTitle(
    title: String, modifier: Modifier = Modifier, caption: String? = null, action: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OrbitHeading(title, level = HeadingLevel.Section)
            if (caption != null) Text(caption, color = Muted, fontSize = 13.sp)
        }
        action?.invoke()
    }
}

@Composable
internal fun OrbitButton(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = true, enabled: Boolean = true,
    container: Color = AccentSurface, danger: Boolean = false
) {
    val outlined = !primary && container == OrbitSurface
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Accent else container,
            contentColor = when {
                primary -> OnAccent
                danger -> MaterialTheme.colorScheme.error
                else -> Accent
            },
            disabledContainerColor = Night, disabledContentColor = Muted
        ),
        border = if (outlined) BorderStroke(1.dp, Line) else null,
        contentPadding = PaddingValues(horizontal = Gap.lg, vertical = 10.dp)
    ) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center) }
}

/** The one button style used on top of artwork (Focus poster, Block header). */
@Composable
internal fun ArtButton(
    onClick: () -> Unit, modifier: Modifier = Modifier,
    container: Color = ArtCoral, contentColor: Color = ArtInk,
    outlined: Boolean = false, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (outlined) Color.Transparent else container,
            contentColor = contentColor,
            disabledContainerColor = container.copy(alpha = .4f), disabledContentColor = contentColor.copy(alpha = .7f)
        ),
        border = if (outlined) BorderStroke(1.dp, ArtText.copy(alpha = .55f)) else null,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), content = content
    )
}

@Composable
internal fun ArtLabel(text: String) = Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
    overflow = TextOverflow.Ellipsis)

internal enum class PanelTone { Surface, Tinted, Quiet }

/** The only card. Rows that open something pass [onClick]; informational notes use [PanelTone.Tinted]. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier, tone: PanelTone = PanelTone.Surface,
    onClick: (() -> Unit)? = null, onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(Gap.lg),
    content: @Composable ColumnScope.() -> Unit
) {
    val fill = when (tone) {
        PanelTone.Surface -> OrbitSurface
        PanelTone.Tinted -> AccentSurface
        PanelTone.Quiet -> Night
    }
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(fill)
            .then(if (tone == PanelTone.Surface) Modifier.border(1.dp, Line, CardShape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content
    )
}

/** Surface used by every input-like container (fields, pickers, theme chooser). */
@Composable
internal fun Modifier.orbitField(): Modifier = clip(FieldShape).background(OrbitSurface).border(1.dp, Line, FieldShape)

/** Shared segmented control: modes, ranges, categories, video-filter levels. */
@Composable
internal fun OrbitSegmented(
    options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, role: Role = Role.RadioButton
) {
    Row(modifier.fillMaxWidth().clip(FieldShape).background(Night).selectableGroup().padding(3.dp)) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            val fill by animateColorAsState(
                if (chosen) Accent.copy(alpha = if (enabled) 1f else .5f) else Color.Transparent,
                tween(160), label = "Segment"
            )
            Box(
                Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(9.dp)).background(fill)
                    .selectable(chosen, enabled = enabled, role = role) { onSelect(index) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (chosen) OnAccent else Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun OrbitChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, onClickLabel: String? = null,
    @DrawableRes icon: Int? = null) {
    Box(modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 36.dp).clip(CircleShape).background(AccentSurface).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(14.dp), tint = Accent)
            Text(label, color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun OrbitIconButton(
    @DrawableRes icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Ink
) {
    IconButton(onClick, modifier.size(48.dp).background(OrbitSurface, CircleShape).border(1.dp, Line, CircleShape)) {
        Icon(painterResource(icon), description, Modifier.size(24.dp), tint = tint)
    }
}

@Composable
internal fun OrbitField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", suffix: String = "", numeric: Boolean = false,
    inline: Boolean = false, error: Boolean = false, errorText: String = "",
    helper: String = "", enabled: Boolean = true, lines: Int = 1
) {
    val interactions = remember { MutableInteractionSource() }
    val focusManager = LocalFocusManager.current
    val focused by interactions.collectIsFocusedAsState()
    val outline = when {
        error -> MaterialTheme.colorScheme.error
        focused -> Accent
        else -> Line
    }
    val contentColor = when {
        error -> MaterialTheme.colorScheme.error
        !enabled -> Muted
        inline -> Accent
        else -> Ink
    }
    val multiline = lines > 1
    val fieldStyle = TextStyle(
        color = contentColor, fontFamily = inter, fontSize = if (inline) 14.sp else 16.sp,
        fontFeatureSettings = if (numeric) "tnum" else null
    )
    val numericWidth = if (numeric && suffix.isNotEmpty() && value.isNotEmpty()) {
        val measured = rememberTextMeasurer().measure(value, fieldStyle).size.width
        with(LocalDensity.current) { measured.toDp() + 2.dp }
    } else null
    BasicTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        modifier = if (error) modifier.semantics { error("Enter a valid value for $label") } else modifier,
        interactionSource = interactions,
        singleLine = !multiline, minLines = lines, maxLines = if (multiline) lines + 3 else 1,
        textStyle = fieldStyle,
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (multiline) ImeAction.Default else ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        decorationBox = { field ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!inline) Text(label, color = if (error) MaterialTheme.colorScheme.error else Muted, fontSize = 13.sp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(FieldShape)
                        .background(if (enabled) OrbitSurface else Night)
                        .border(if (focused || error) 2.dp else 1.dp, outline, FieldShape)
                        .padding(horizontal = Gap.lg, vertical = if (multiline) 14.dp else 10.dp),
                    verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically
                ) {
                    if (inline) Text(label, Modifier.weight(1.25f), color = if (enabled) Ink else Muted, fontSize = 14.sp)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(if (numericWidth != null) Modifier.width(numericWidth) else Modifier.weight(1f, fill = multiline)) {
                            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Muted, fontSize = if (inline) 14.sp else 16.sp)
                            field()
                        }
                        if (suffix.isNotEmpty() && value.isNotEmpty()) {
                            Text(" " + suffix, color = contentColor, fontSize = if (inline) 14.sp else 16.sp)
                        }
                    }
                }
                val note = if (error && errorText.isNotEmpty()) errorText else helper
                if (note.isNotEmpty()) {
                    Text(note, Modifier.padding(horizontal = 4.dp), fontSize = 13.sp,
                        color = if (error) MaterialTheme.colorScheme.error else Muted)
                }
            }
        }
    )
}

@Composable
internal fun OrbitFullScreen(onClose: () -> Unit, content: @Composable () -> Unit) {
    val topPadding = orbitTopPadding()
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val lightBars = !androidx.compose.foundation.isSystemInDarkTheme()
        SideEffect {
            window?.let {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = Paper, contentColor = Ink) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                CompositionLocalProvider(LocalOrbitTopPadding provides topPadding) {
                    Box(Modifier.widthIn(max = 580.dp).fillMaxWidth()) { content() }
                }
            }
        }
    }
}

/** Header for every full-screen page: title leading, one close button trailing. */
@Composable
internal fun OrbitPageHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap.md)) {
        OrbitHeading(title, Modifier.weight(1f))
        OrbitIconButton(R.drawable.ic_close, stringResource(R.string.close), onClose)
    }
}

/** Full-screen page with the shared header, edge padding and vertical rhythm. */
@Composable
internal fun OrbitScreen(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    OrbitFullScreen(onClose) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = ScreenPadding, end = ScreenPadding, top = orbitTopPadding(), bottom = ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Gap.lg)
        ) {
            OrbitPageHeader(title, onClose)
            content()
        }
    }
}

@Composable
internal fun Modifier.orbitInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets(bottom = 20.dp)))

@Composable
internal fun orbitTopPadding(): Dp = LocalOrbitTopPadding.current
    ?: (54.dp - WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()).coerceAtLeast(5.dp)

private val LocalOrbitTopPadding = compositionLocalOf<Dp?> { null }


internal val TimerTheme.pageColor: Color
    @Composable get() = when (this) {
        TimerTheme.ORBIT -> Paper
        TimerTheme.FOREST -> colorResource(R.color.orbit_page_forest)
        TimerTheme.DUSK -> colorResource(R.color.orbit_page_dusk)
        TimerTheme.OCEAN -> colorResource(R.color.orbit_page_ocean)
    }

@Composable
internal fun AtlasPanel(
    @DrawableRes artwork: Int, modifier: Modifier = Modifier, artAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val resources = LocalContext.current.resources
    val illustration = remember(artwork, resources) {
        atlasIllustrations.get(artwork) ?: ImageBitmap.imageResource(resources, artwork)
            .also { atlasIllustrations.put(artwork, it) }
    }
    Box(modifier.fillMaxWidth().clip(CardShape)) {
        Image(illustration, contentDescription = null, contentScale = ContentScale.Crop,
            alignment = artAlignment, modifier = Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(artScrim))
        content()
    }
}

// The four nodpi illustrations are density-independent. Retain at most two between tab visits.
private val atlasIllustrations = LruCache<Int, ImageBitmap>(2)

@Composable
internal fun OrbitIcon(@DrawableRes icon: Int, description: String? = null, tint: Color = Accent, size: Dp = 24.dp) {
    Icon(androidx.compose.ui.res.painterResource(icon), description, Modifier.size(size), tint = tint)
}

@Composable
internal fun ThemePicker(selected: TimerTheme, onSelect: (TimerTheme) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().orbitField().padding(horizontal = Gap.lg, vertical = Gap.md),
        verticalArrangement = Arrangement.spacedBy(Gap.sm)
    ) {
        Text(stringResource(R.string.timer_theme), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Gap.xs)) {
            TimerTheme.entries.forEach { item ->
                val chosen = item == selected
                Column(
                    Modifier.weight(1f).clip(FieldShape)
                        .selectable(chosen, role = Role.RadioButton) { onSelect(item) }
                        .heightIn(min = 48.dp).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.size(36.dp).background(item.pageColor, CircleShape)
                        .border(if (chosen) 2.dp else 1.dp, if (chosen) Accent else Line, CircleShape))
                    Text(item.label, color = if (chosen) Accent else Muted, fontSize = 13.sp,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
