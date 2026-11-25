package dev.stillpoint.focus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun SessionOrbit(
    turns: Float,
    stopwatch: Boolean,
    running: Boolean,
    celebrate: Boolean,
    completionTime: Long,
    modifier: Modifier = Modifier
) {
    val coral = Color(0xFFFFBCAC)
    val orbit = animateFloatAsState(
        turns, animationSpec = if (running) tween(800, easing = LinearEasing) else snap(),
        label = "Session orbit"
    )
    var celebrated by rememberSaveable(celebrate, completionTime) { mutableStateOf(false) }
    val burst = remember(celebrate, completionTime) { Animatable(if (celebrated) 1f else 0f) }
    LaunchedEffect(celebrate, completionTime) {
        if (celebrate && !celebrated) {
            celebrated = true
            burst.animateTo(1f, tween(720))
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * .34f
            val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            val progress = orbit.value
            drawCircle(Color(0xBB232047), radius + 4.dp.toPx())
            drawCircle(Color.White.copy(alpha = .4f), radius, style = stroke)
            if (!stopwatch && progress > 0f) {
                drawArc(coral, -90f, progress.coerceAtMost(1f) * 360f, false,
                    topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2), style = stroke)
            }
            if (!celebrate) {
                val radians = ((progress % 1f) - .25f) * 2f * Math.PI
                val satellite = center + Offset(cos(radians).toFloat(), sin(radians).toFloat()) * radius
                drawCircle(coral.copy(alpha = .25f), 4.5.dp.toPx(), satellite)
                drawCircle(coral, 2.5.dp.toPx(), satellite)
            } else if (burst.value < 1f) {
                val frame = burst.value
                repeat(8) { index ->
                    val radians = index * Math.PI / 4
                    val direction = Offset(cos(radians).toFloat(), sin(radians).toFloat())
                    val position = center + direction * (radius * (.8f + .67f * frame))
                    drawLine(coral.copy(alpha = 1f - frame), position, position + direction * 3.dp.toPx(),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        Icon(painterResource(if (celebrate) R.drawable.ic_orbit_check else R.drawable.ic_timer),
            contentDescription = null, tint = coral, modifier = Modifier.size(if (celebrate) 18.dp else 13.dp))
    }
}
