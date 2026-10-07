package io.github.buerlino.gridload

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.DayQuarters
import io.github.buerlino.gridload.core.Quarter
import io.github.buerlino.gridload.core.Recording
import io.github.buerlino.gridload.core.BASE_LOAD_DAYS
import io.github.buerlino.gridload.core.baseLoadKw
import io.github.buerlino.gridload.core.dailyHighest
import io.github.buerlino.gridload.core.kwhPerYear
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The month so far, below the peak window: its header names the month's highest quarter hour
 * and when it was; open, one bar per day (that day's highest), with the limit across them, as in
 * the peak window. The day of the month's highest is red, today dark. Days before the recorder
 * started, or still to come, have no bar. Tapping the chart opens the [HistoryScreen]. All the
 * history's days and times are in the region's zone, the utility's days.
 */
@Composable
fun HistoryPanel(state: UiState, onToggle: () -> Unit, onOpen: () -> Unit) {
    val unit = state.powerUnit
    val zone = state.region.zone
    val highest = state.meter.highest
    // Null when no part of the limit is on and has a value; the bars then show without a line.
    // The limit can sit above or below the highest, so the scale takes both (the canvas doesn't clip).
    val line = state.peakLine
    Panel(
        open = state.historyOpen,
        onToggle = onToggle,
        header = {
            val text = if (highest == null) AnnotatedString("No ¼ hours recorded yet") else highestText(highest, unit, zone)
            Text(text, Modifier.weight(1f), color = INK, fontSize = 16.sp)
        },
    ) {
        if (state.historyOpen && highest != null) {
            DayBars(
                state.meter.days, highest, line, maxOf(line ?: 0.0, highest.kw), unit, YearMonth.now(zone), LocalDate.now(zone), selected = null, height = 150.dp,
                Modifier.clickable(onClickLabel = "Open the history", onClick = onOpen),
            )
            if (state.baseLoadEnabled) BaseLoadLine(state)
        }
    }
}

/** "Base load **80 W** · about 700 kWh a year", or that none was recorded in its hours. */
@Composable
private fun BaseLoadLine(state: UiState) {
    val quarters = state.meter.quarters
    val from = state.baseLoadFrom
    val to = state.baseLoadTo
    val zone = state.region.zone
    val kw = remember(quarters, from, to, zone) { baseLoadKw(Recording(quarters), Instant.now(), zone, from, to) }
    if (kw == null) {
        Text("Base load: no ¼ hours ${hourText(from)}–${hourText(to)} in the last $BASE_LOAD_DAYS days", color = MUTED, fontSize = 14.sp)
    } else {
        Text(
            buildAnnotatedString {
                append("Base load ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(state.powerUnit.formatSmall(kw)) }
                append(" · about ${NumberFormat.getIntegerInstance().format(kwhPerYear(kw))} kWh a year")
            },
            color = INK,
            fontSize = 14.sp,
        )
    }
}

/** An hour as a time: "02:00". */
internal fun hourText(hour: Int) = "%02d:00".format(hour)

/**
 * The history on its own screen: this month's or last month's day bars, and below them the
 * quarter hours of the day tapped (at first the day of the month's highest), so it shows when
 * the load came. The limit is drawn for this month only, since its goal and floor are today's.
 */
@Composable
fun HistoryScreen(state: UiState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val unit = state.powerUnit
    val zone = state.region.zone
    val today = LocalDate.now(zone)
    var lastMonth by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.from(today).let { if (lastMonth) it.minusMonths(1) else it }
    val quarters = state.meter.quarters
    val days = remember(quarters, month, zone) { dailyHighest(Recording(quarters), month, zone) }
    val highest = days.maxByOrNull { it.second.kwh }?.second
    val line = if (lastMonth) null else state.peakLine
    // Both charts share one scale, so a day's bars match its bar in the month.
    val maxKw = maxOf(line ?: 0.0, highest?.kw ?: 0.0)
    var selectedDay by rememberSaveable(month) { mutableStateOf<Long?>(null) }
    val selected = (selectedDay?.let(LocalDate::ofEpochDay) ?: highest?.start?.atZone(zone)?.toLocalDate())
        ?.takeIf { day -> days.any { it.first == day } }

    Page {
        TitleRow("History", onBack) { InfoButton(HISTORY_SCREEN_HELP) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { lastMonth = true }, enabled = !lastMonth) { Text("‹", fontSize = 22.sp) }
            Text(monthFormat.format(month), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { lastMonth = false }, enabled = lastMonth) { Text("›", fontSize = 22.sp) }
        }
        if (highest == null) {
            Text("No ¼ hours recorded.", color = MUTED)
        } else {
            Text(highestText(highest, unit, zone), color = INK)
            DayBars(days, highest, line, maxKw, unit, month, today, selected, height = 200.dp, onDay = { selectedDay = it.toEpochDay() })
            val day = remember(quarters, selected, zone) { selected?.let { DayQuarters(it, zone, quarters) } }
            // Always there for a selected day: it has a bar, so it has quarters.
            val dayHighest = day?.highest
            if (day != null && dayHighest != null) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(dayFormat.format(day.date)) }
                        append(" · highest ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(unit.format(dayHighest.kw)) }
                        append(" at ${clockFormat.format(dayHighest.start.atZone(zone))}")
                    },
                    color = INK, fontSize = 18.sp,
                )
                QuarterBars(day, dayHighest, highest, line, maxKw, unit)
            }
        }
    }
}

/** "Highest **4.2 kW** · 2 Oct 18:30", in [zone]. */
private fun highestText(highest: Quarter, unit: PowerUnit, zone: ZoneId) = buildAnnotatedString {
    append("Highest ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(unit.format(highest.kw)) }
    append(" · ${dayTimeFormat.format(highest.start.atZone(zone))}")
}

private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** "18:30" in the zone of the time it's given; [timeFormat] would convert to the phone's. */
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Where a chart's plot is: right of the axis, and left of the label of the limit at [line] when
 * there is one.
 */
private class Plot(val axis: Axis, val start: Float, val end: Float, private val line: Double?, private val label: TextLayoutResult?) {
    /** The limit across the plot, with its label right of it. */
    fun drawLimit(scope: DrawScope) {
        if (line != null && label != null) scope.drawLimit(axis, line, label, end, scope.size.width - label.size.width)
    }
}

private fun DrawScope.plot(measurer: TextMeasurer, unit: PowerUnit, maxKw: Double, line: Double?, bottom: Float): Plot {
    val axis = kwAxis(measurer, unit, maxKw, 16.dp.toPx(), bottom, 6.dp.toPx())
    val label = line?.let { limitLabel(measurer, unit, it, 80.dp.roundToPx()) }
    val end = if (label != null) size.width - label.size.width - 8.dp.toPx() else size.width
    return Plot(axis, axis.x + 6.dp.toPx(), end, line, label)
}

/**
 * One bar per day of [month]: the month's [highest] in red, [today] dark, the rest grey; the
 * limit at [line], the scale up to [maxKw]. [selected] is shaded; with [onDay], tapping picks the
 * day under the finger, if it has a bar. Taps outside the plot (the axis, the limit's label) do
 * nothing.
 */
@Composable
private fun DayBars(
    days: List<Pair<LocalDate, Quarter>>,
    highest: Quarter,
    line: Double?,
    maxKw: Double,
    unit: PowerUnit,
    month: YearMonth,
    today: LocalDate,
    selected: LocalDate?,
    height: Dp,
    modifier: Modifier = Modifier,
    onDay: ((LocalDate) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val length = month.lengthOfMonth()
    // The plot's ends in px, as last drawn, for mapping a tap to its day.
    val span = remember { FloatArray(2) }
    val tap = if (onDay == null) Modifier else Modifier.pointerInput(days, onDay) {
        detectTapGestures { offset ->
            val (start, end) = span
            // Before the first draw both are 0, so nothing is inside.
            if (offset.x >= start && offset.x < end) {
                val day = minOf(((offset.x - start) / (end - start) * length).toInt() + 1, length)
                days.firstOrNull { it.first.dayOfMonth == day }?.let { onDay(it.first) }
            }
        }
    }
    Canvas(modifier.then(tap).fillMaxWidth().height(height)) {
        val bottom = size.height - 18.dp.toPx()
        val plot = plot(measurer, unit, maxKw, line, bottom)
        span[0] = plot.start
        span[1] = plot.end
        val slot = (plot.end - plot.start) / length
        val barW = maxOf(slot - 2.dp.toPx(), 1f)
        fun x(day: Int) = plot.start + (day - 1) * slot + (slot - barW) / 2

        if (selected != null) drawRect(SELECTED, Offset(plot.start + (selected.dayOfMonth - 1) * slot, plot.axis.top), Size(slot, bottom - plot.axis.top))
        plot.axis.draw(this, plot.end)
        for (day in listOf(1, 8, 15, 22, 29).filter { it <= length }) {
            val r = measurer.measure("$day", SMALL)
            drawText(r, topLeft = Offset(x(day) + (barW - r.size.width) / 2, bottom + 3.dp.toPx()))
        }
        for ((day, quarter) in days) {
            val barTop = plot.axis.y(quarter.kw)
            val color = when {
                quarter == highest -> RED
                day == today -> BAR
                else -> PAST_BAR
            }
            drawRect(color, Offset(x(day.dayOfMonth), barTop), Size(barW, bottom - barTop))
        }
        plot.drawLimit(this)
    }
}

/** A light shade behind the day tapped. */
private val SELECTED = Color(0xFFE3E7EA)

/**
 * The recorded quarter hours of [day], each in its slot, so a missing one leaves a gap: the
 * month's [highest] red, the day's own [dayHighest] dark, the rest grey; the limit at [line] and
 * hour labels every 6 hours.
 */
@Composable
private fun QuarterBars(day: DayQuarters, dayHighest: Quarter, highest: Quarter, line: Double?, maxKw: Double, unit: PowerUnit) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val bottom = size.height - 18.dp.toPx()
        val plot = plot(measurer, unit, maxKw, line, bottom)
        val slot = (plot.end - plot.start) / day.slots
        val barW = maxOf(slot - 1.dp.toPx(), 1f)
        fun x(index: Int) = plot.start + index * slot

        plot.axis.draw(this, plot.end)
        for (hour in listOf(0, 6, 12, 18)) {
            val r = measurer.measure("%02d".format(hour), SMALL)
            drawText(r, topLeft = Offset(x(day.hourSlot(hour)), bottom + 3.dp.toPx()))
        }
        for (quarter in day.quarters) {
            val barTop = plot.axis.y(quarter.kw)
            val color = when (quarter) {
                highest -> RED
                dayHighest -> BAR
                else -> PAST_BAR
            }
            drawRect(color, Offset(x(day.slot(quarter.start)), barTop), Size(barW, bottom - barTop))
        }
        plot.drawLimit(this)
    }
}
