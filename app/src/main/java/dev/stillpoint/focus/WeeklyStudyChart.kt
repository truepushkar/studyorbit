package dev.stillpoint.focus

import androidx.compose.foundation.Canvas
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
import kotlin.math.ceil

@Composable
internal fun WeeklyStudyChart(week: List<Pair<LocalDate, Int>>) {
    var selected by rememberSaveable(week.last().first.toEpochDay()) { mutableIntStateOf(week.lastIndex) }
    val (date, minutes) = week[selected]
    val scale = (ceil(week.maxOf { it.second } / 15.0).toInt() * 15).coerceAtLeast(15)
    val selectedLabel = if (selected == week.lastIndex) "Today" else date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$selectedLabel · $minutes min", Modifier.background(AccentSurface, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp), color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text("$scale min", color = Muted, fontSize = 11.sp)
    }
    Box(Modifier.fillMaxWidth().height(116.dp)) {
        Canvas(Modifier.fillMaxWidth().height(82.dp)) {
            val step = size.width / week.size
            val top = 8.dp.toPx()
            val bottom = size.height - 10.dp.toPx()
            val points = week.mapIndexed { index, (_, value) ->
                Offset(step * (index + .5f), bottom - (bottom - top) * value / scale)
            }
            listOf(top, (top + bottom) / 2, bottom).forEach { y ->
                drawLine(Line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
            }
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
            drawPath(fill, Brush.verticalGradient(listOf(ChartSage.copy(alpha = .42f), Color.Transparent)))
            drawPath(line, Accent, style = Stroke(2.dp.toPx()))
            points.forEachIndexed { index, point ->
                val isSelected = index == selected
                if (isSelected) drawCircle(AccentSurface, 9.dp.toPx(), point)
                drawCircle(Accent, if (isSelected) 5.dp.toPx() else 3.5.dp.toPx(), point)
                if (!isSelected) drawCircle(OrbitSurface, 2.dp.toPx(), point)
            }
        }
        Row(Modifier.fillMaxSize().selectableGroup()) {
            week.forEachIndexed { index, (day, value) ->
                val fullDay = day.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
                Box(Modifier.weight(1f).fillMaxHeight()
                    .selectable(selected == index, role = Role.RadioButton) { selected = index }
                    .semantics { contentDescription = "$fullDay, $value study minutes" }
                    .padding(bottom = 8.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(day.format(DateTimeFormatter.ofPattern("EEEEE")),
                        color = if (selected == index) Accent else Muted, fontSize = 12.sp,
                        fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
