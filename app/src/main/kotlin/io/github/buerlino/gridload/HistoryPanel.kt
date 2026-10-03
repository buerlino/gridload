package io.github.buerlino.gridload

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.Quarter
import io.github.buerlino.gridload.core.TARIFF_ZONE
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.ceil

/**
 * The month so far, below the peak window: its header names the month's highest quarter hour
 * and when it was; open, one bar per day (that day's highest), with the month's highest and the
 * goal across them. Days before the recorder started, or still to come, have no bar.
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
        if (state.historyOpen && highest != null) DayBars(state.meter.days, highest, state.activeGoalKw, unit)
    }
}

/** One bar per day of this month, the month's highest in the dark colour and labelled. */
@Composable
private fun DayBars(days: List<Pair<LocalDate, Quarter>>, highest: Quarter, goal: Double?, unit: PowerUnit) {
    val measurer = rememberTextMeasurer()
    val month = YearMonth.now(TARIFF_ZONE)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val maxKw = ceil(maxOf(maxOf(goal ?: 0.0, highest.kw) * 1.15, 1.0)).toInt()
        val step = if (maxKw > 8) 2 else 1
        val top = 16.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        fun y(kw: Double) = bottom - (kw / maxKw * (bottom - top)).toFloat()
        val small = TextStyle(color = MUTED, fontSize = 10.sp)
        val ticks = (0..maxKw step step).map { it to measurer.measure(tickLabel(it, unit), small) }
        val unitLabel = measurer.measure(unit.id, small)
        val axisX = maxOf(ticks.maxOf { it.second.size.width }, unitLabel.size.width) + 6.dp.toPx()
        val labelStyle = TextStyle(color = INK, fontSize = 12.sp, lineHeight = 14.sp)
        fun label(kw: Double, what: String) = measurer.measure(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(unit.number(kw)) }
            append(" $what")
        }, labelStyle, constraints = Constraints(maxWidth = 80.dp.roundToPx()))
        val highestLabel = label(highest.kw, "highest")
        val goalLabel = goal?.let { y(it) to label(it, "goal") }
        val labelX = size.width - maxOf(highestLabel.size.width, goalLabel?.second?.size?.width ?: 0)
        val plotStart = axisX + 6.dp.toPx()
        val linesEnd = labelX - 8.dp.toPx()
        val slot = (linesEnd - plotStart) / month.lengthOfMonth()
        val barW = maxOf(slot - 2.dp.toPx(), 1f)
        fun x(day: Int) = plotStart + (day - 1) * slot + (slot - barW) / 2

        for ((k, r) in ticks) {
            drawText(r, topLeft = Offset(axisX - 6.dp.toPx() - r.size.width, y(k.toDouble()) - r.size.height / 2))
            drawLine(MUTED, Offset(axisX, y(k.toDouble())), Offset(axisX + 4.dp.toPx(), y(k.toDouble())), 1.dp.toPx())
        }
        drawText(unitLabel, topLeft = Offset(axisX - 6.dp.toPx() - unitLabel.size.width, 0f))
        drawLine(MUTED, Offset(axisX, top), Offset(axisX, bottom), 1.dp.toPx())
        drawLine(MUTED, Offset(axisX, bottom), Offset(linesEnd, bottom), 1.dp.toPx())
        for (day in listOf(1, 8, 15, 22, 29).filter { it <= month.lengthOfMonth() }) {
            val r = measurer.measure("$day", small)
            drawText(r, topLeft = Offset(x(day) + (barW - r.size.width) / 2, bottom + 3.dp.toPx()))
        }

        for ((day, quarter) in days) {
            val barTop = y(quarter.kw)
            drawRect(if (quarter == highest) BAR else PAST_BAR, Offset(x(day.dayOfMonth), barTop), Size(barW, bottom - barTop))
        }

        drawLine(INK, Offset(axisX, y(highest.kw)), Offset(linesEnd, y(highest.kw)), 2.dp.toPx())
        goal?.let {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            drawLine(INK, Offset(axisX, y(it)), Offset(linesEnd, y(it)), 1.5.dp.toPx(), pathEffect = dash)
        }
        drawLabels(listOfNotNull(y(highest.kw) to highestLabel, goalLabel).sortedBy { it.first }, labelX)
    }
}
