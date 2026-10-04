package io.github.buerlino.gridload

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.Advice
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.QUARTER_SECONDS
import io.github.buerlino.gridload.core.isPeakWarning
import io.github.buerlino.gridload.core.quarterStart
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/** How many recorded quarter hours are shown left of the current one, and coming ones right of it. */
const val PAST_BARS = 3
const val FUTURE_BARS = 3

/** An appliance's share of a bar in the preview. */
private val APPLIANCE = Color(0xFF1E88E5)

/**
 * Peak load's white panel under the spot price. Its header says how much more fits under the
 * limit ("1.3 kW free", [peakLine]), with the minutes left in this quarter hour if switched on;
 * open, the scale shows the past quarter hours, this one projected in the middle, room for the
 * coming ones, and the limit. The bar and the header turn red at the limit. Without one (no
 * goal, no appliance, nothing recorded yet) the header shows the projection. The
 * warning and what's wrong with the recorder (tap it for Settings) show collapsed too, so the
 * alarm is never hidden. With a preview (an appliance row tapped), the coming quarter hours
 * show its run started now on top of the house, and the header the tightest of them.
 */
@Composable
fun PeakWindow(state: UiState, onToggle: () -> Unit, onClosePreview: () -> Unit, onOpenSettings: () -> Unit) {
    val unit = state.powerUnit
    val meter = state.meter
    val projection = meter.projection
    val line = state.peakLine
    val current = projection?.start ?: quarterStart(Instant.now())
    val preview = state.preview?.takeIf { projection != null }
    val loads = state.previewLoads.associateBy { it.start }
    fun warns(kw: Double) = line != null && isPeakWarning(kw, line)
    // From the values as drawn, so "0.9 limit" and a "0.8" bar give "0.1 kW free". With a
    // preview, from the run's highest quarter hour. Red from the exact values.
    val drawn = if (preview != null) state.previewLoads.maxOf { it.kw } else projection?.kw
    val free = if (drawn != null && line != null) unit.round(line) - unit.round(drawn) else null
    val red = if (preview != null) state.previewLoads.any { warns(it.kw) } else state.peakWarning
    Panel(
        open = state.peakOpen || preview != null,
        onToggle = if (preview != null) onClosePreview else onToggle,
        icon = if (preview != null) "×" else null,
        header = {
            val title = when {
                projection == null -> null
                free != null && free >= 0 -> "${unit.format(free)} free"
                free != null -> "${unit.format(-free)} over"
                preview != null -> unit.format(drawn!!)
                else -> "${unit.format(projection.kw)} now"
            }
            if (title == null) {
                Text(meter.problem ?: "Reading the whatwatt…", Modifier.weight(1f), color = MUTED, fontSize = 16.sp)
            } else {
                Text(
                    buildAnnotatedString {
                        append(title)
                        preview?.let { withStyle(SpanStyle(color = MUTED, fontSize = 16.sp, fontWeight = FontWeight.Normal)) { append(" with $it") } }
                    },
                    Modifier.weight(1f), color = if (red) RED else INK, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp,
                )
            }
            projection?.takeIf { state.countdown && preview == null }?.let { Text("${minutesLeft(it.end)} min left", color = MUTED, fontSize = 14.sp) }
        },
    ) {
        if (state.peakOpen || preview != null) {
            val past = (PAST_BARS downTo 1).map { i ->
                val start = current.minusSeconds(QUARTER_SECONDS * i)
                Bar(meter.quarters.lastOrNull { it.start == start }?.kw, timeFormat.format(start))
            }
            val now = loads[current]?.let { Bar(it.houseKw, "now", it.addedKw, current = true, warning = warns(it.kw)) }
                ?: Bar(projection?.kw, "now", current = true, warning = state.peakWarning)
            val coming = (1..FUTURE_BARS).map { i ->
                val start = current.plusSeconds(QUARTER_SECONDS * i)
                val load = loads[start]
                Bar(load?.houseKw, timeFormat.format(start), load?.addedKw ?: 0.0, coming = true, warning = load != null && warns(load.kw))
            }
            Scale(past + now + coming, line, unit)
            if (preview != null) {
                Note(buildAnnotatedString {
                    withStyle(SpanStyle(color = BAR)) { append("■") }
                    append(if (state.drawAverage) " House, 2-min average   " else " House as now   ")
                    withStyle(SpanStyle(color = APPLIANCE)) { append("■") }
                    append(" $preview")
                })
                // A run longer than the columns: the rest in one line.
                val later = state.previewLoads.filter { it.start > current.plusSeconds(QUARTER_SECONDS * FUTURE_BARS) }
                if (later.isNotEmpty()) {
                    val text = "Then ${later.size} more quarter ${if (later.size == 1) "hour" else "hours"}, up to ${unit.format(later.maxOf { it.kw })}."
                    if (later.any { warns(it.kw) }) Warning(text, Modifier) else Note(text)
                }
                state.advice[preview]?.let { advice ->
                    val text = adviceLine(state.appliances.first { it.name == preview }, advice, Instant.now(), state.saving(advice)) ?: "OK to start now."
                    if (advice is Advice.NewPeak) Warning(text, Modifier) else Text(text, color = INK, fontSize = 14.sp)
                }
            } else if (projection != null && line != null) {
                // Always laid out, so the panel keeps its height when the warning comes or goes.
                Warning(OVER, if (red) Modifier else Modifier.alpha(0f).clearAndSetSemantics {})
            }
            // In its first minute the recorder's line for the quarter before may still be on its way.
            if (projection?.estimated == true && Duration.between(projection.start, Instant.now()).seconds >= 60) {
                Note("Estimated: the recorder has no start for this quarter hour.")
            }
        } else if (red) {
            Warning(OVER, Modifier)
        }
        meter.recorder?.takeIf { meter.problem == null }?.let { check ->
            val text = meter.recorderAction ?: recorderLine(check)
            if (text != null && (isRecorderWarning(check) || meter.recorderAction != null)) {
                Warning("$text ›", Modifier.clickable(onClick = onOpenSettings))
            } else if (text != null && state.peakOpen) {
                Note(text)
            }
        }
    }
}

private const val OVER = "This quarter hour sets a new peak."

@Composable
private fun Warning(text: String, modifier: Modifier) = Text(text, modifier, color = RED, fontSize = 14.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Note(text: String) = Note(AnnotatedString(text))

@Composable
private fun Note(text: AnnotatedString) = Text(text, color = MUTED, fontSize = 12.sp, lineHeight = 15.sp)

private fun minutesLeft(end: Instant) = ceil(Duration.between(Instant.now(), end).seconds / 60.0).toInt().coerceAtLeast(1)

/**
 * One column of the scale: a recorded quarter hour, the current one projected, or a coming one.
 * [kw] is the house's, null when unknown (a coming one then stays empty); [addedKw] an
 * appliance's on top in the preview. [warning]: at the limit, so it's drawn red.
 */
private class Bar(
    val kw: Double?,
    val label: String,
    val addedKw: Double = 0.0,
    val current: Boolean = false,
    val coming: Boolean = false,
    val warning: Boolean = false,
)

/**
 * The vertical scale in [unit]: the bars side by side, the current one in the middle and wider,
 * with the limit ([line]) across them and labelled to the right. Each value sits above its bar.
 */
@Composable
private fun Scale(bars: List<Bar>, line: Double?, unit: PowerUnit) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val bottom = size.height - 18.dp.toPx()
        val axis = Axis(measurer, unit, maxOf(line ?: 0.0, bars.maxOf { (it.kw ?: 0.0) + it.addedKw }), 22.dp.toPx(), bottom, 6.dp.toPx())
        val gap = 6.dp.toPx()
        val start = axis.x + 8.dp.toPx()
        // The columns share what the line labels leave; the current one is 1.6 times as wide, so it stands out.
        val room = size.width - start - 62.dp.toPx() - gap * (bars.size - 1)
        val unitW = minOf(room / (bars.size - 1 + 1.6f), 28.dp.toPx())
        val widths = bars.map { if (it.current) unitW * 1.6f else unitW }
        val xs = widths.runningFold(start) { x, w -> x + w + gap }
        val linesEnd = xs.last() - gap + 6.dp.toPx()
        val labelX = linesEnd + 8.dp.toPx()
        val lineYs = listOfNotNull(line).map { axis.y(it) }
        val halfLine = 1.5.dp.toPx()
        fun clearOfLines(top: Float, height: Int) = lineYs.none { it + halfLine > top && it - halfLine < top + height }
        axis.draw(this, linesEnd)

        bars.forEachIndexed { i, bar ->
            val x = xs[i]
            val barW = widths[i]
            val label = measurer.measure(bar.label, SMALL)
            drawText(label, topLeft = Offset(x + (barW - label.size.width) / 2, bottom + 3.dp.toPx()))
            if (bar.kw == null) {
                if (bar.coming) return@forEachIndexed
                val dash = measurer.measure("–", SMALL)
                drawText(dash, topLeft = Offset(x + (barW - dash.size.width) / 2, bottom - dash.size.height))
                return@forEachIndexed
            }
            val houseTop = axis.y(bar.kw)
            val barTop = axis.y(bar.kw + bar.addedKw)
            if (bar.addedKw > 0) {
                drawRect(BAR, Offset(x, houseTop), Size(barW, bottom - houseTop))
                drawRect(APPLIANCE, Offset(x, barTop), Size(barW, houseTop - barTop))
                // Red only above the limit, so it starts exactly at the line and shows how much is over.
                lineYs.firstOrNull()?.takeIf { bar.warning && it > barTop }?.let { drawRect(RED, Offset(x, barTop), Size(barW, it - barTop)) }
            } else {
                val color = if (!bar.current) PAST_BAR else if (bar.warning) RED else BAR
                drawRect(color, Offset(x, barTop), Size(barW, bottom - barTop))
            }
            // Above the bar, moved up past any line it would touch.
            val value = measurer.measure(unit.number(bar.kw + bar.addedKw), TextStyle(color = INK, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            val h = value.size.height
            val valueTop = (listOf(barTop - 1.dp.toPx() - h) + lineYs.sortedDescending().map { it - halfLine - h })
                .firstOrNull { it + h <= barTop && clearOfLines(it, h) } ?: (barTop - h)
            drawText(value, topLeft = Offset(x + (barW - value.size.width) / 2, valueTop))
        }

        line?.let { drawLimit(axis, it, limitLabel(measurer, unit, it, (size.width - labelX).toInt()), linesEnd, labelX) }
    }
}
