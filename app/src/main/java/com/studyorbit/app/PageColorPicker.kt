package com.studyorbit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal fun pageBackground(paper: Color, custom: Int?): Color =
    custom?.let { lerp(paper, Color(it), 0.18f) } ?: paper

@Composable
internal fun PageColorPicker(selected: Int?, onSelect: (Int?) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val paper = Paper
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
        Box(Modifier.size(44.dp).background(pageBackground(paper, selected), FieldShape).border(1.dp, Line, FieldShape))
        OutlinedButton({ editing = true }, Modifier.weight(1f)) { Text(stringResource(R.string.background_color)) }
    }
    if (editing) {
        val original = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(selected ?: 0xFF6654B6.toInt(), it) } }
        var hue by rememberSaveable { mutableFloatStateOf(original[0]) }
        var saturation by rememberSaveable { mutableFloatStateOf(original[1]) }
        var brightness by rememberSaveable { mutableFloatStateOf(original[2]) }
        val color = Color.hsv(hue, saturation, brightness)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.background_color)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(42.dp).background(pageBackground(paper, color.toArgb()), FieldShape)
                        .border(1.dp, Line, FieldShape))
                    val hueLabel = stringResource(R.string.color_hue)
                    val saturationLabel = stringResource(R.string.color_saturation)
                    val brightnessLabel = stringResource(R.string.color_brightness)
                    Text(hueLabel)
                    Slider(hue, { hue = it }, Modifier.semantics { contentDescription = hueLabel }, valueRange = 0f..360f)
                    Text(saturationLabel)
                    Slider(saturation, { saturation = it }, Modifier.semantics { contentDescription = saturationLabel })
                    Text(brightnessLabel)
                    Slider(brightness, { brightness = it }, Modifier.semantics { contentDescription = brightnessLabel })
                }
            },
            confirmButton = { TextButton({ onSelect(color.toArgb()); editing = false }) { Text(stringResource(R.string.done)) } },
            dismissButton = {
                TextButton({ onSelect(null); editing = false }) { Text(stringResource(R.string.reset_background_color)) }
            }
        )
    }
}
