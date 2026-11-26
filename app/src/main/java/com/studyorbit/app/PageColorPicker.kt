package com.studyorbit.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal fun pageBackground(paper: Color, custom: Int?): Color =
    custom?.let { lerp(paper, Color(it), 0.18f) } ?: paper

private fun hexColor(color: Int): String = String.format(Locale.ROOT, "%06X", color and 0xFFFFFF)
private fun hsv(color: Int) = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }

@Composable
internal fun PageColorPicker(selected: Int?, recent: List<Int>, onSelect: (Int?) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val paper = Paper
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
        Box(Modifier.size(44.dp).background(pageBackground(paper, selected), FieldShape).border(1.dp, Line, FieldShape))
        OutlinedButton({ editing = true }, Modifier.weight(1f)) { Text(stringResource(R.string.background_color)) }
    }
    if (editing) {
        val initial = selected ?: 0xFF6654B6.toInt()
        val original = remember(initial) { hsv(initial) }
        var hue by rememberSaveable { mutableFloatStateOf(original[0]) }
        var saturation by rememberSaveable { mutableFloatStateOf(original[1]) }
        var brightness by rememberSaveable { mutableFloatStateOf(original[2]) }
        var hex by rememberSaveable { mutableStateOf(hexColor(initial)) }
        fun applyColor(color: Int) {
            val components = hsv(color)
            hue = components[0]
            saturation = components[1]
            brightness = components[2]
            hex = hexColor(color)
        }
        val color = Color.hsv(hue, saturation, brightness)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.background_color)) },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Gap.sm)
                ) {
                    ColorWheel(hue, saturation, brightness) { nextHue, nextSaturation ->
                        hue = nextHue
                        saturation = nextSaturation
                        hex = hexColor(Color.hsv(hue, saturation, brightness).toArgb())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                        Box(Modifier.size(38.dp).background(color, CircleShape).border(1.dp, Line, CircleShape))
                        OutlinedTextField(
                            value = hex,
                            onValueChange = { input ->
                                hex = input.filter { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
                                    .take(6).uppercase(Locale.ROOT)
                                if (hex.length == 6) hex.toIntOrNull(16)?.let { applyColor(it or 0xFF000000.toInt()) }
                            },
                            modifier = Modifier.weight(1f), singleLine = true,
                            label = { Text(stringResource(R.string.color_hex)) }, prefix = { Text("#") }
                        )
                    }
                    val brightnessLabel = stringResource(R.string.color_brightness)
                    Text(brightnessLabel, Modifier.align(Alignment.Start), color = Ink)
                    Slider(brightness, {
                        brightness = it
                        hex = hexColor(Color.hsv(hue, saturation, brightness).toArgb())
                    }, Modifier.semantics { contentDescription = brightnessLabel },
                        colors = SliderDefaults.colors(thumbColor = color))
                    if (recent.isNotEmpty()) {
                        Text(stringResource(R.string.recent_background_colors), Modifier.align(Alignment.Start), color = Ink)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap.xs)) {
                            recent.forEach { previous ->
                                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) { applyColor(previous) }
                                    .semantics { contentDescription = "#${hexColor(previous)}" },
                                    contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(26.dp).background(Color(previous), CircleShape).border(1.dp, Line, CircleShape))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({ onSelect(color.toArgb()); editing = false }, enabled = hex.length == 6) {
                    Text(stringResource(R.string.done))
                }
            },
            dismissButton = {
                TextButton({ onSelect(null); editing = false }) { Text(stringResource(R.string.reset_background_color)) }
            }
        )
    }
}

@Composable
private fun ColorWheel(hue: Float, saturation: Float, brightness: Float, onSelect: (Float, Float) -> Unit) {
    val select by rememberUpdatedState(onSelect)
    val label = stringResource(R.string.color_wheel)
    Canvas(Modifier.size(196.dp).semantics { contentDescription = label }.pointerInput(Unit) {
        fun update(position: Offset) {
            val radius = minOf(size.width, size.height) / 2f
            val delta = position - Offset(size.width / 2f, size.height / 2f)
            val degrees = atan2(delta.y, delta.x) * 180f / PI.toFloat()
            select((degrees + 450f) % 360f, (delta.getDistance() / radius).coerceIn(0f, 1f))
        }
        awaitEachGesture {
            val down = awaitFirstDown()
            update(down.position)
            down.consume()
            do {
                val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (pointer?.pressed == true) {
                    update(pointer.position)
                    pointer.consume()
                }
            } while (pointer?.pressed == true)
        }
    }) {
        rotate(-90f) {
            drawCircle(Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
                center = center
            ))
        }
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, minOf(size.width, size.height) / 2f))
        drawCircle(Color.Black.copy(alpha = 1f - brightness))
        val angle = (hue - 90f) * PI.toFloat() / 180f
        val distance = minOf(size.width, size.height) / 2f * saturation
        val marker = center + Offset(cos(angle) * distance, sin(angle) * distance)
        drawCircle(Color.Black.copy(alpha = .65f), 9.dp.toPx(), marker, style = Stroke(4.dp.toPx()))
        drawCircle(Color.White, 9.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
    }
}
