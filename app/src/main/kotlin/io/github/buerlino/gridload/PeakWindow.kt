package io.github.buerlino.gridload

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.buerlino.gridload.core.QUARTER_SECONDS
import io.github.buerlino.gridload.core.quarterStart
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.ceil

/** How many recorded quarter hours are shown left of the current one. */
const val PAST_BARS = 2

private val INK = Color(0xFF1C2126)
private val MUTED = Color(0xFF5B646D)
private val BAR = Color(0xFF37474F)
private val PAST_BAR = Color(0xFFB0BEC5)

/**
 * Peak load's white window under the spot price: the scale (the past quarter hours, this one
 * projected, the goal and the month's highest), how much more fits under the line, and, if
 * switched on, when the next quarter hour starts. The bar turns red within 10% of the line.
 * Without a line (no goal, nothing recorded yet) it shows the bars only. Below, what's wrong with
 * the recorder (tap it for Settings) and the quarter hours it missed this month.
 */
@Composable
fun PeakWindow(state: UiState, onOpenSettings: () -> Unit) {
    val projection = state.meter.projection
    val line = state.peakLine
    val current = projection?.start ?: quarterStart(Instant.now())
    val bars = (PAST_BARS downTo 1).map { i ->
        val start = current.minusSeconds(QUARTER_SECONDS * i)
        Bar(state.meter.recent.find { it.start == start }?.kw, timeFormat.format(start), current = false)
    } + Bar(projection?.kw, "now", current = true)
    Column(
        Modifier.fillMaxWidth().widthIn(max = 420.dp).background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Scale(bars, state.activeGoalKw, state.meter.highest?.kw, line, state.peakWarning)
        when {
            projection == null -> state.meter.problem?.let { Text(it, color = MUTED, fontSize = 14.sp) }
            line != null -> {
                // From the values as drawn, so "0.9 highest" and a "0.8" bar give "0.1 kW free".
                val free = roundKw(line) - roundKw(projection.kw)
                Text(
                    if (free >= 0) "%.1f kW free".format(free) else "%.1f kW over".format(-free),
                    color = INK, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                )
                val shown = when {
                    free < 0 -> OVER
                    state.peakWarning -> NEAR
                    else -> null
                }
                // Both always laid out, so the window keeps its height when a warning comes or goes.
                Box {
                    for (text in listOf(OVER, NEAR)) {
                        Warning(text, if (text == shown) Modifier else Modifier.alpha(0f).clearAndSetSemantics {})
                    }
                }
            }
        }
        projection?.takeIf { state.countdown }?.let { Text("New quarter hour in ${minutesLeft(it.end)} min", color = INK, fontSize = 14.sp) }
        // In its first minute the recorder's line for the quarter before may still be on its way.
        if (projection?.estimated == true && Duration.between(projection.start, Instant.now()).seconds >= 60) {
            Note("Estimated: the recorder has no start for this quarter hour.")
        }
        val meter = state.meter
        meter.recorder?.takeIf { meter.problem == null }?.let { check ->
            val text = meter.recorderAction ?: recorderLine(check)
            if (text != null && (isRecorderWarning(check) || meter.recorderAction != null)) {
                Warning("$text ›", Modifier.clickable(onClick = onOpenSettings))
            } else if (text != null) {
                Note(text)
            }
        }
        if (meter.missing.isNotEmpty()) {
            val count = meter.missing.size
            val restart = meter.restartAfterGap?.let { " (restart at ${shortTime(it)})" }.orEmpty()
            Warning(
                "$count quarter ${if (count == 1) "hour" else "hours"} missing this month, the last ${shortTime(meter.missing.last())}$restart.",
                Modifier,
            )
        }
        meter.recordedSince?.let { Note("Recorded since ${shortTime(it)}.") }
    }
}

private const val OVER = "This quarter hour sets a new peak."
private const val NEAR = "Close to a new peak. Wait a bit."

@Composable
private fun Warning(text: String, modifier: Modifier) = Text(text, modifier, color = RED, fontSize = 14.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Note(text: String) = Text(text, color = MUTED, fontSize = 12.sp, lineHeight = 15.sp)

/** [kw] rounded as the scale and the goal field show it (one decimal). */
internal fun roundKw(kw: Double) = "%.1f".format(Locale.ROOT, kw).toDouble()

private fun minutesLeft(end: Instant) = ceil(Duration.between(Instant.now(), end).seconds / 60.0).toInt().coerceAtLeast(1)

/** One column of the scale: a recorded quarter hour, or the current one projected; [kw] null when unknown. */
private class Bar(val kw: Double?, val label: String, val current: Boolean)

/**
 * The vertical scale in kW: the bars side by side, the newest on the right, with the month's
 * highest (solid) and the goal (dashed) across them and labelled to the right. [line], the
 * higher of the two, sets the height with the bars.
 */
@Composable
private fun Scale(bars: List<Bar>, goal: Double?, highest: Double?, line: Double?, warning: Boolean) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val maxKw = ceil(maxOf(maxOf(line ?: 0.0, bars.maxOf { it.kw ?: 0.0 }) * 1.15, 1.0)).toInt()
        val step = if (maxKw > 8) 2 else 1
        val top = 22.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val axisX = 24.dp.toPx()
        val barW = 30.dp.toPx()
        val gap = 8.dp.toPx()
        val barsX = axisX + 10.dp.toPx()
        val linesEnd = barsX + bars.size * barW + (bars.size - 1) * gap + 6.dp.toPx()
        val labelX = linesEnd + 8.dp.toPx()
        fun y(kw: Double) = bottom - (kw / maxKw * (bottom - top)).toFloat()
        val small = TextStyle(color = MUTED, fontSize = 10.sp)
        val lineYs = listOfNotNull(highest, goal).map { y(it) }
        val halfLine = 1.5.dp.toPx()
        fun clearOfLines(top: Float, height: Int) = lineYs.none { it + halfLine > top && it - halfLine < top + height }

        for (k in 0..maxKw step step) {
            val r = measurer.measure("$k", small)
            drawText(r, topLeft = Offset(axisX - 6.dp.toPx() - r.size.width, y(k.toDouble()) - r.size.height / 2))
            drawLine(MUTED, Offset(axisX, y(k.toDouble())), Offset(axisX + 4.dp.toPx(), y(k.toDouble())), 1.dp.toPx())
        }
        val unit = measurer.measure("kW", small)
        drawText(unit, topLeft = Offset(axisX - 6.dp.toPx() - unit.size.width, 0f))
        drawLine(MUTED, Offset(axisX, top), Offset(axisX, bottom), 1.dp.toPx())
        drawLine(MUTED, Offset(axisX, bottom), Offset(linesEnd, bottom), 1.dp.toPx())

        bars.forEachIndexed { i, bar ->
            val x = barsX + i * (barW + gap)
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
            // The value goes at the bar's top, inside or else above it, moved off any line crossing it.
            val value = "%.1f".format(bar.kw)
            val inside = measurer.measure(value, TextStyle(color = if (bar.current) Color.White else INK, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            val above = measurer.measure(value, TextStyle(color = INK, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            val h = inside.size.height
            val insideTop = (listOf(barTop + 2.dp.toPx()) + lineYs.sorted().map { it + halfLine })
                .firstOrNull { it >= barTop && it + h + 2.dp.toPx() <= bottom && clearOfLines(it, h) }
            val aboveTop = (listOf(barTop - h) + lineYs.sortedDescending().map { it - halfLine - h })
                .firstOrNull { it + h <= barTop && clearOfLines(it, h) } ?: (barTop - h)
            val text = if (insideTop != null) inside else above
            drawText(text, topLeft = Offset(x + (barW - text.size.width) / 2, insideTop ?: aboveTop))
        }

        highest?.let { drawLine(INK, Offset(axisX, y(it)), Offset(linesEnd, y(it)), 2.5.dp.toPx()) }
        goal?.let {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            drawLine(INK, Offset(axisX, y(it)), Offset(linesEnd, y(it)), 1.5.dp.toPx(), pathEffect = dash)
        }

        val width = Constraints(maxWidth = (size.width - labelX).toInt().coerceAtLeast(1))
        val labelStyle = TextStyle(color = INK, fontSize = 12.sp, lineHeight = 14.sp)
        val labels = buildList {
            highest?.let {
                add(y(it) to measurer.measure(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("%.1f".format(it)) }
                    append(" highest")
                }, labelStyle, constraints = width))
            }
            goal?.let {
                add(y(it) to measurer.measure(buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("%.1f".format(it)) }
                    append(" goal")
                }, labelStyle, constraints = width))
            }
        }.sortedBy { it.first }
        drawLabels(labels, labelX)
    }
}

/** Draws [labels] (wanted centre y, text) at [x], pushed apart where they would overlap. */
private fun DrawScope.drawLabels(labels: List<Pair<Float, TextLayoutResult>>, x: Float) {
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
