package io.github.funnyhell.daymark.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.funnyhell.daymark.domain.common.startOfWeek
import io.github.funnyhell.daymark.ui.format.Formats
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One heatmap cell. [level] 0 = scheduled but nothing done, 1..4 = increasing completion; null = not evaluated
 * (unscheduled, paused, before start or future). [description] is read by screen readers.
 */
data class HeatCell(val date: LocalDate, val level: Int?, val description: String, val marker: Boolean = false)

/**
 * GitHub-style calendar heatmap: one column per week, one row per weekday. Each cell has a text description, so
 * meaning is not carried by colour alone; selected and marked (for example paused or lapse) days get an outline.
 */
@Composable
fun Heatmap(
    cells: Map<LocalDate, HeatCell>,
    end: LocalDate,
    weeks: Int,
    firstDayOfWeek: DayOfWeek,
    color: Color,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lastWeekStart = end.startOfWeek(firstDayOfWeek)
    val firstWeekStart = lastWeekStart.minusWeeks((weeks - 1).toLong())
    val scroll = rememberScrollState()
    LaunchedEffect(weeks) { scroll.scrollTo(scroll.maxValue) }
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(modifier.horizontalScroll(scroll).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until 7) {
                val day = firstDayOfWeek.plus(i.toLong())
                Box(Modifier.size(width = 22.dp, height = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (i % 2 == 0) Text(Formats.dayShort(context, day).take(2), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        for (w in 0 until weeks) {
            val weekStart = firstWeekStart.plusWeeks(w.toLong())
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (d in 0 until 7) {
                    val date = weekStart.plusDays(d.toLong())
                    val cell = cells[date]
                    val fill = when {
                        date.isAfter(end) || cell == null -> Color.Transparent
                        cell.level == null -> empty.copy(alpha = 0.45f)
                        cell.level == 0 -> empty
                        else -> color.copy(alpha = 0.25f + 0.1875f * cell.level.coerceIn(1, 4))
                    }
                    val outline = when {
                        date == selected -> MaterialTheme.colorScheme.onSurface
                        cell?.marker == true -> MaterialTheme.colorScheme.error
                        else -> Color.Transparent
                    }
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(fill, RoundedCornerShape(4.dp))
                            .border(if (outline == Color.Transparent) 0.dp else 1.5.dp, outline, RoundedCornerShape(4.dp))
                            .then(
                                if (cell != null && !date.isAfter(end)) {
                                    Modifier.clickable { onSelect(date) }.semantics { contentDescription = cell.description }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }
}
