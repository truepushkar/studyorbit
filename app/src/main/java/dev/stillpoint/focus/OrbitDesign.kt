package dev.stillpoint.focus

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

internal val Paper = Color(0xFFF8F6F2)
internal val Ink = Color(0xFF211E38)
internal val Muted = Color(0xFF605F72)
internal val Accent = Color(0xFF4541A0)
internal val AccentSurface = Color(0xFFE9E5FC)
internal val ChartSage = Color(0xFF8984CD)
internal val Line = Color(0xFFDEDBE3)
internal val Forest = Color(0xFF166558)
internal val OrbitSurface = Color(0xFFFFFFFF)
internal val Night = Color(0xFFEDEAF3)
internal val OnAccent = Color(0xFFFFFFFF)
internal val OrbitTrack = Color(0xFFD7D2E7)
internal val CardShape = RoundedCornerShape(16.dp)
internal val FieldShape = RoundedCornerShape(10.dp)

private val inter = FontFamily(
    Font(R.font.inter_regular),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

internal val orbitTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontFamily = inter, letterSpacing = 0.sp),
        titleLarge = titleLarge.copy(fontFamily = inter, letterSpacing = 0.sp),
        titleMedium = titleMedium.copy(fontFamily = inter, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontFamily = inter, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontFamily = inter, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontFamily = inter, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontFamily = inter, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontFamily = inter, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontFamily = inter, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontFamily = inter, letterSpacing = 0.sp)
    )
}

@Composable
internal fun OrbitHeading(title: String, modifier: Modifier = Modifier, size: Int = 36) {
    Text(title, modifier.semantics { heading() }, color = Ink, fontSize = size.sp,
        lineHeight = (size + 6).sp, fontFamily = inter, letterSpacing = (-0.5).sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun OrbitButton(
    title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = true, enabled: Boolean = true, rounded: Boolean = true,
    container: Color = AccentSurface
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp),
        shape = if (rounded) RoundedCornerShape(14.dp) else FieldShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Accent else container,
            contentColor = if (primary) OnAccent else Accent,
            disabledContainerColor = Night, disabledContentColor = Muted
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
    ) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
internal fun OrbitField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "", suffix: String = "", numeric: Boolean = false,
    inline: Boolean = false, error: Boolean = false
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val outline = when {
        error -> MaterialTheme.colorScheme.error
        focused -> Accent
        else -> Line
    }
    val contentColor = when {
        error -> MaterialTheme.colorScheme.error
        inline -> Accent
        else -> Ink
    }
    val fieldStyle = TextStyle(
        color = contentColor, fontFamily = inter, fontSize = if (inline) 14.sp else 16.sp,
        fontFeatureSettings = if (numeric) "tnum" else null
    )
    val numericWidth = if (numeric && suffix.isNotEmpty() && value.isNotEmpty()) {
        val measured = rememberTextMeasurer().measure(value, fieldStyle).size.width
        with(LocalDensity.current) { measured.toDp() + 2.dp }
    } else null
    BasicTextField(
        value = value, onValueChange = onChange,
        modifier = if (error) modifier.semantics { error("Enter a valid value for $label") } else modifier,
        interactionSource = interactions,
        singleLine = true, textStyle = fieldStyle,
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        decorationBox = { field ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!inline) Text(label, color = if (error) MaterialTheme.colorScheme.error else Muted, fontSize = 12.sp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp).background(OrbitSurface, FieldShape)
                        .border(1.dp, outline, FieldShape)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (inline) Text(label, Modifier.weight(1.25f), color = Ink, fontSize = 14.sp)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(if (numericWidth != null) Modifier.width(numericWidth) else Modifier.weight(1f, fill = false)) {
                            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Muted, fontSize = 15.sp)
                            field()
                        }
                        if (suffix.isNotEmpty() && value.isNotEmpty()) {
                            Text(" " + suffix, color = contentColor, fontSize = if (inline) 14.sp else 16.sp)
                        }
                    }
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
        SideEffect {
            window?.let {
                it.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                WindowCompat.getInsetsController(it, it.decorView).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
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

@Composable
internal fun Modifier.orbitInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets(bottom = 20.dp)))

@Composable
internal fun orbitTopPadding(): Dp = LocalOrbitTopPadding.current
    ?: (54.dp - WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()).coerceAtLeast(5.dp)

private val LocalOrbitTopPadding = compositionLocalOf<Dp?> { null }


internal val TimerTheme.pageColor: Color
    get() = when (this) {
        TimerTheme.ORBIT -> Paper
        TimerTheme.FOREST -> Color(0xFFE2F1E4)
        TimerTheme.DUSK -> Color(0xFFF9E3EC)
        TimerTheme.OCEAN -> Color(0xFFE0EFF8)
    }

@Composable
internal fun AtlasPanel(
    artwork: Int, modifier: Modifier = Modifier, artAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.fillMaxWidth().clip(CardShape)) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(artwork), contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = artAlignment,
            modifier = Modifier.matchParentSize()
        )
        content()
    }
}
