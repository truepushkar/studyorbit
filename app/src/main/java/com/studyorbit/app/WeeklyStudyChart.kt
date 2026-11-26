package com.studyorbit.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

@Composable
internal fun WeeklyStudyChart(week: List<Pair<LocalDate, Int>>) {
    var selected by rememberSaveable(week.last().first.toEpochDay()) { mutableIntStateOf(week.lastIndex) }
    val marker = animateFloatAsState(selected.toFloat(), animationSpec = tween(180), label = "Selected study day")
    val locale = Locale.getDefault()
    val scale = remember(week) { (ceil(week.maxOf { it.second } / 15.0).toInt() * 15).coerceAtLeast(15) }
    val dates = remember(week, locale) {
        val short = DateTimeFormatter.ofPattern("EEE, MMM d", locale)
        val full = DateTimeFormatter.ofPattern("EEEE, MMMM d", locale)
        val initial = DateTimeFormatter.ofPattern("EEEEE", locale)
        week.map { (day, _) -> ChartDate(day.format(short), day.format(full), day.format(initial)) }
    }
    val minutes = week[selected].second
    val selectedLabel = if (selected == week.lastIndex) "Today" else dates[selected].short
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$selectedLabel · $minutes min", Modifier.background(AccentSurface, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("$scale min", color = Muted, fontSize = 13.sp)
    }
    Box(Modifier.fillMaxWidth().height(116.dp)) {
        val accentSurfaceColor = AccentSurface
        val orbitSurfaceColor = OrbitSurface
        val chartSageColor = ChartSage
        val accentColor = Accent
        val lineColor = Line
        Spacer(Modifier.fillMaxWidth().height(82.dp).drawWithCache {
            val step = size.width / week.size
            val top = 8.dp.toPx()
            val bottom = size.height - 10.dp.toPx()
            val points = week.mapIndexed { index, (_, value) ->
                Offset(step * (index + .5f), bottom - (bottom - top) * value / scale)
            }
            val gridLevels = listOf(top, (top + bottom) / 2, bottom)
            val gridDash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
            val line = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val fill = Path().apply {
                addPath(line)
                lineTo(points.last().x, bottom)
                lineTo(points.first().x, bottom)
                close()
            }
            val gradient = Brush.verticalGradient(listOf(chartSageColor.copy(alpha = .42f), Color.Transparent))
            val stroke = Stroke(2.dp.toPx())
            onDrawBehind {
                gridLevels.forEach { y ->
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = gridDash)
                }
                drawPath(fill, gradient)
                drawPath(line, accentColor, style = stroke)
                for (point in points) {
                    drawCircle(accentColor, 3.5.dp.toPx(), point)
                    drawCircle(orbitSurfaceColor, 2.dp.toPx(), point)
                }
                val index = marker.value.toInt().coerceIn(0, points.lastIndex)
                val start = points[index]
                val end = points[(index + 1).coerceAtMost(points.lastIndex)]
                val point = start + (end - start) * (marker.value - index)
                drawLine(accentColor.copy(alpha = .22f), Offset(point.x, top), Offset(point.x, bottom), 1.dp.toPx())
                drawCircle(accentSurfaceColor, 9.dp.toPx(), point)
                drawCircle(accentColor, 5.dp.toPx(), point)
            }
        })
        Row(Modifier.fillMaxSize().selectableGroup()) {
            week.forEachIndexed { index, (_, value) ->
                val label = dates[index]
                Box(Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected == index, role = Role.RadioButton) { selected = index }
                    .semantics { contentDescription = "${label.full}, $value study minutes" }
                    .padding(bottom = 8.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(label.initial,
                        color = if (selected == index) Accent else Muted, fontSize = 13.sp,
                        fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

private data class ChartDate(val short: String, val full: String, val initial: String)
