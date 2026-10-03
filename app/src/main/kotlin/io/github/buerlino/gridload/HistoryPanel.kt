package io.github.buerlino.gridload

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.Quarter
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The month so far, below the peak window: its header names the month's highest quarter hour
 * and when it was; open, one bar per day (that day's highest), with the month's highest and the
 * goal across them. The day of the month's highest is red, today dark. Days before the recorder
 * started, or still to come, have no bar.
 */
@Composable
fun HistoryPanel(state: UiState, onToggle: () -> Unit) {
    val unit = state.powerUnit
    val highest = state.meter.highest
    Panel(
        open = state.historyOpen,
        onToggle = onToggle,
        header = {
            val text = if (highest == null) {
                buildAnnotatedString { append("No quarter hours recorded yet") }
            } else {
                buildAnnotatedString {
                    append("Highest ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(unit.format(highest.kw)) }
                    append(" · ${dayTimeFormat.format(highest.start.atZone(ZoneId.systemDefault()))}")
                }
            }
            Text(text, Modifier.weight(1f), color = INK, fontSize = 16.sp)
        },
    ) {
        if (state.historyOpen && highest != null) DayBars(state.meter.days, highest, state.activeGoalKw, unit, state.region.zone)
    }
}

/** One bar per day of this month: the month's highest in red (its line labelled), today dark, the rest grey. */
@Composable
private fun DayBars(days: List<Pair<LocalDate, Quarter>>, highest: Quarter, goal: Double?, unit: PowerUnit, zone: ZoneId) {
    val measurer = rememberTextMeasurer()
    val today = LocalDate.now(zone)
    val month = YearMonth.from(today)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val bottom = size.height - 18.dp.toPx()
        val axis = Axis(measurer, unit, maxOf(goal ?: 0.0, highest.kw), 16.dp.toPx(), bottom, 6.dp.toPx())
        val labelWidth = 80.dp.roundToPx()
        val highestLabel = lineLabel(measurer, unit, highest.kw, "highest", labelWidth)
        val goalLabel = goal?.let { axis.y(it) to lineLabel(measurer, unit, it, "goal", labelWidth) }
        val labelX = size.width - maxOf(highestLabel.size.width, goalLabel?.second?.size?.width ?: 0)
        val plotStart = axis.x + 6.dp.toPx()
        val linesEnd = labelX - 8.dp.toPx()
        val slot = (linesEnd - plotStart) / month.lengthOfMonth()
        val barW = maxOf(slot - 2.dp.toPx(), 1f)
        fun x(day: Int) = plotStart + (day - 1) * slot + (slot - barW) / 2

        axis.draw(this, linesEnd)
        for (day in listOf(1, 8, 15, 22, 29).filter { it <= month.lengthOfMonth() }) {
            val r = measurer.measure("$day", SMALL)
            drawText(r, topLeft = Offset(x(day) + (barW - r.size.width) / 2, bottom + 3.dp.toPx()))
        }
        for ((day, quarter) in days) {
            val barTop = axis.y(quarter.kw)
            val color = when {
                quarter == highest -> RED
                day == today -> BAR
                else -> PAST_BAR
            }
            drawRect(color, Offset(x(day.dayOfMonth), barTop), Size(barW, bottom - barTop))
        }
        drawLines(axis, highest.kw, goal, linesEnd)
        drawLabels(listOfNotNull(axis.y(highest.kw) to highestLabel, goalLabel), labelX)
    }
}
