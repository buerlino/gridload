package io.github.buerlino.gridload

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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
import io.github.buerlino.gridload.core.QUARTER_SECONDS
import io.github.buerlino.gridload.core.quarterStart
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/** How many recorded quarter hours are shown left of the current one. */
const val PAST_BARS = 2

internal val INK = Color(0xFF1C2126)
internal val MUTED = Color(0xFF5B646D)
internal val BAR = Color(0xFF37474F)
internal val PAST_BAR = Color(0xFFB0BEC5)

/**
 * Peak load's white panel under the spot price. Its header says how much more fits under the
 * line ("1.3 kW free"), with the minutes left in this quarter hour if switched on; open, the
 * scale shows the past quarter hours, this one projected, the goal and the month's highest. The
 * bar and the header turn red within 10% of the line. Without a line (no goal, nothing recorded
 * yet) the header shows the draw now. The warning and what's wrong with the recorder (tap it for
 * Settings) show collapsed too, so the alarm is never hidden.
 */
@Composable
fun PeakWindow(state: UiState, onToggle: () -> Unit, onOpenSettings: () -> Unit) {
    val unit = state.powerUnit
    val meter = state.meter
    val projection = meter.projection
    val line = state.peakLine
    val current = projection?.start ?: quarterStart(Instant.now())
    // From the values as drawn, so "0.9 highest" and a "0.8" bar give "0.1 kW free".
    val free = if (projection != null && line != null) unit.round(line) - unit.round(projection.kw) else null
    val warning = when {
        free != null && free < 0 -> OVER
        state.peakWarning -> NEAR
        else -> null
    }
    Panel(
        open = state.peakOpen,
        onToggle = onToggle,
        header = {
            val title = when {
                projection == null -> null
                free == null -> "${unit.format(projection.kw)} now"
                free >= 0 -> "${unit.format(free)} free"
                else -> "${unit.format(-free)} over"
            }
            if (title == null) {
                Text(meter.problem ?: "Reading the whatwatt…", Modifier.weight(1f), color = MUTED, fontSize = 16.sp)
            } else {
                Text(title, Modifier.weight(1f), color = if (warning != null) RED else INK, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            projection?.takeIf { state.countdown }?.let { Text("${minutesLeft(it.end)} min left", color = MUTED, fontSize = 14.sp) }
        },
    ) {
        if (state.peakOpen) {
            val bars = (PAST_BARS downTo 1).map { i ->
                val start = current.minusSeconds(QUARTER_SECONDS * i)
                Bar(meter.recent.find { it.start == start }?.kw, timeFormat.format(start), current = false)
            } + Bar(projection?.kw, "now", current = true)
            Scale(bars, state.activeGoalKw, meter.highest?.kw, line, state.peakWarning, unit)
            if (projection != null && line != null) {
                // Both always laid out, so the panel keeps its height when a warning comes or goes.
                Box {
                    for (text in listOf(OVER, NEAR)) {
                        Warning(text, if (text == warning) Modifier else Modifier.alpha(0f).clearAndSetSemantics {})
                    }
                }
            }
            // In its first minute the recorder's line for the quarter before may still be on its way.
            if (projection?.estimated == true && Duration.between(projection.start, Instant.now()).seconds >= 60) {
                Note("Estimated: the recorder has no start for this quarter hour.")
            }
        } else {
            warning?.let { Warning(it, Modifier) }
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

/**
 * A white panel on the main screen: a header row that collapses and expands it ([onToggle]),
 * then [content], which decides itself what shows when collapsed.
 */
@Composable
internal fun Panel(
    open: Boolean,
    onToggle: () -> Unit,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().widthIn(max = 420.dp).background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clickable(onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            Text(if (open) "▴" else "▾", color = MUTED, fontSize = 20.sp)
        }
        content()
    }
}

private const val OVER = "This quarter hour sets a new peak."
private const val NEAR = "Close to a new peak. Wait a bit."

@Composable
private fun Warning(text: String, modifier: Modifier) = Text(text, modifier, color = RED, fontSize = 14.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Note(text: String) = Text(text, color = MUTED, fontSize = 12.sp, lineHeight = 15.sp)

private fun minutesLeft(end: Instant) = ceil(Duration.between(Instant.now(), end).seconds / 60.0).toInt().coerceAtLeast(1)

/** A whole number of kW on a scale's axis: "2", or "2000" in W. */
internal fun tickLabel(kw: Int, unit: PowerUnit) = if (unit == PowerUnit.W) "${kw * 1000}" else "$kw"

/** One column of the scale: a recorded quarter hour, or the current one projected; [kw] null when unknown. */
private class Bar(val kw: Double?, val label: String, val current: Boolean)

/**
 * The vertical scale in [unit]: the bars side by side, the newest on the right and wider, with
 * the month's highest (solid) and the goal (dashed) across them and labelled to the right.
 * [line], the higher of the two, sets the height with the bars. Each value sits above its bar.
 */
@Composable
private fun Scale(bars: List<Bar>, goal: Double?, highest: Double?, line: Double?, warning: Boolean, unit: PowerUnit) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val maxKw = ceil(maxOf(maxOf(line ?: 0.0, bars.maxOf { it.kw ?: 0.0 }) * 1.15, 1.0)).toInt()
        val step = if (maxKw > 8) 2 else 1
        val top = 22.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val small = TextStyle(color = MUTED, fontSize = 10.sp)
        val ticks = (0..maxKw step step).map { it to measurer.measure(tickLabel(it, unit), small) }
        val unitLabel = measurer.measure(unit.id, small)
        val axisX = maxOf(ticks.maxOf { it.second.size.width }, unitLabel.size.width) + 6.dp.toPx()
        val gap = 8.dp.toPx()
        // The current quarter hour is 1.6 times as wide as the past ones, so it stands out.
        val widths = bars.map { if (it.current) 45.dp.toPx() else 28.dp.toPx() }
        val xs = widths.runningFold(axisX + 10.dp.toPx()) { x, w -> x + w + gap }
        val linesEnd = xs.last() - gap + 6.dp.toPx()
        val labelX = linesEnd + 8.dp.toPx()
        fun y(kw: Double) = bottom - (kw / maxKw * (bottom - top)).toFloat()
        val lineYs = listOfNotNull(highest, goal).map { y(it) }
        val halfLine = 1.5.dp.toPx()
        fun clearOfLines(top: Float, height: Int) = lineYs.none { it + halfLine > top && it - halfLine < top + height }

        for ((k, r) in ticks) {
            drawText(r, topLeft = Offset(axisX - 6.dp.toPx() - r.size.width, y(k.toDouble()) - r.size.height / 2))
            drawLine(MUTED, Offset(axisX, y(k.toDouble())), Offset(axisX + 4.dp.toPx(), y(k.toDouble())), 1.dp.toPx())
        }
        drawText(unitLabel, topLeft = Offset(axisX - 6.dp.toPx() - unitLabel.size.width, 0f))
        drawLine(MUTED, Offset(axisX, top), Offset(axisX, bottom), 1.dp.toPx())
        drawLine(MUTED, Offset(axisX, bottom), Offset(linesEnd, bottom), 1.dp.toPx())

        bars.forEachIndexed { i, bar ->
            val x = xs[i]
            val barW = widths[i]
            val label = measurer.measure(bar.label, small)
            drawText(label, topLeft = Offset(x + (barW - label.size.width) / 2, bottom + 3.dp.toPx()))
            if (bar.kw == null) {
                val dash = measurer.measure("–", small)
                drawText(dash, topLeft = Offset(x + (barW - dash.size.width) / 2, bottom - dash.size.height))
                return@forEachIndexed
            }
            val barTop = y(bar.kw)
            val color = if (!bar.current) PAST_BAR else if (warning) RED else BAR
            drawRect(color, Offset(x, barTop), Size(barW, bottom - barTop))
            // Above the bar, moved up past any line it would touch.
            val value = measurer.measure(unit.number(bar.kw), TextStyle(color = INK, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            val h = value.size.height
            val valueTop = (listOf(barTop - 1.dp.toPx() - h) + lineYs.sortedDescending().map { it - halfLine - h })
                .firstOrNull { it + h <= barTop && clearOfLines(it, h) } ?: (barTop - h)
            drawText(value, topLeft = Offset(x + (barW - value.size.width) / 2, valueTop))
        }

        highest?.let { drawLine(INK, Offset(axisX, y(it)), Offset(linesEnd, y(it)), 2.5.dp.toPx()) }
        goal?.let {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            drawLine(INK, Offset(axisX, y(it)), Offset(linesEnd, y(it)), 1.5.dp.toPx(), pathEffect = dash)
        }

        val width = Constraints(maxWidth = (size.width - labelX).toInt().coerceAtLeast(1))
        val labelStyle = TextStyle(color = INK, fontSize = 12.sp, lineHeight = 14.sp)
        fun label(kw: Double, what: String) = measurer.measure(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(unit.number(kw)) }
            append(" $what")
        }, labelStyle, constraints = width)
        val labels = listOfNotNull(highest?.let { y(it) to label(it, "highest") }, goal?.let { y(it) to label(it, "goal") })
            .sortedBy { it.first }
        drawLabels(labels, labelX)
    }
}

/** Draws [labels] (wanted centre y, text) at [x], pushed apart where they would overlap. */
internal fun DrawScope.drawLabels(labels: List<Pair<Float, TextLayoutResult>>, x: Float) {
    val space = 2.dp.toPx()
    val tops = FloatArray(labels.size)
    var minTop = 0f
    labels.forEachIndexed { i, (centre, text) ->
        tops[i] = maxOf(centre - text.size.height / 2, minTop)
        minTop = tops[i] + text.size.height + space
    }
    var maxBottom = size.height
    for (i in labels.indices.reversed()) {
        tops[i] = minOf(tops[i], maxBottom - labels[i].second.size.height)
        maxBottom = tops[i] - space
    }
    labels.forEachIndexed { i, (_, text) -> drawText(text, topLeft = Offset(x, tops[i])) }
}
