package io.github.buerlino.gridload

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import io.github.buerlino.gridload.core.isPeakWarning
import io.github.buerlino.gridload.core.quarterStart
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** How many recorded quarter hours are shown left of the current one. */
const val PAST_BARS = 2

private val INK = Color(0xFF1C2126)
private val MUTED = Color(0xFF5B646D)
private val BAR = Color(0xFF37474F)
private val PAST_BAR = Color(0xFFB0BEC5)
private val WARNING = Color(0xFFC62828)

private val quarterFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Peak load's white window under the spot price: the scale (the past quarter hours, this one
 * projected, the goal and the month's highest), how much more fits under the line, and, if
 * switched on, when the next quarter hour starts. The bar turns red within 10% of the line.
 * Without a line (no goal, nothing recorded yet) it shows the bars only.
 */
@Composable
fun PeakWindow(state: UiState) {
    val projection = state.projection
    val line = state.peakLine
    val warning = projection != null && line != null && isPeakWarning(projection.kw, line)
    val current = projection?.end?.minusSeconds(900) ?: quarterStart(Instant.now())
    val bars = (PAST_BARS downTo 1).map { i ->
        val start = current.minusSeconds(900L * i)
        Bar(state.recent.find { it.start == start }?.kw, quarterFormat.format(start), current = false)
    } + Bar(projection?.kw, "now", current = true)
    Column(
        Modifier.fillMaxWidth().widthIn(max = 420.dp).background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Scale(bars, state.activeGoalKw, state.highest?.kw, warning)
        when {
            projection == null -> state.meterProblem?.let { Text(it, color = MUTED, fontSize = 14.sp) }
            line != null -> {
                // From the values as drawn, so "0.9 highest seen" and a "0.8" bar give "0.1 kW free".
                val free = shown(line) - shown(projection.kw)
                Text(
                    if (free >= 0) "%.1f kW free".format(free) else "%.1f kW over".format(-free),
                    color = INK, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                )
                when {
                    free < 0 -> Warning("This quarter hour sets a new peak.")
                    warning -> Warning("Close to a new peak. Wait before switching more on.")
                }
            }
        }
        projection?.takeIf { state.countdown }?.let { Text("New quarter hour in ${minutesLeft(it.end)} min", color = INK, fontSize = 14.sp) }
        if (projection?.estimated == true) Note("Estimated: GridLoad opened during this quarter hour.")
        Note("Highest seen only while GridLoad is open.")
    }
}

@Composable
private fun Warning(text: String) = Text(text, color = WARNING, fontSize = 14.sp, fontWeight = FontWeight.Medium)

@Composable
private fun Note(text: String) = Text(text, color = MUTED, fontSize = 12.sp, lineHeight = 15.sp)

/** [kw] rounded as the scale labels it (one decimal). */
private fun shown(kw: Double) = "%.1f".format(Locale.ROOT, kw).toDouble()

private fun minutesLeft(end: Instant) = ceil(Duration.between(Instant.now(), end).seconds / 60.0).toInt().coerceAtLeast(1)

/** One column of the scale: a recorded quarter hour, or the current one projected; [kw] null when unknown. */
private class Bar(val kw: Double?, val label: String, val current: Boolean)

/**
 * The vertical scale in kW: the bars side by side, the newest on the right, with the month's
 * highest (solid) and the goal (dashed) across them and labelled to the right.
 */
@Composable
private fun Scale(bars: List<Bar>, goal: Double?, highest: Double?, warning: Boolean) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
        val line = listOfNotNull(goal, highest).maxOrNull() ?: 0.0
        val maxKw = ceil(maxOf(maxOf(line, bars.maxOf { it.kw ?: 0.0 }) * 1.15, 1.0)).toInt()
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
            val color = if (!bar.current) PAST_BAR else if (warning) WARNING else BAR
            drawRect(color, Offset(x, barTop), Size(barW, bottom - barTop))
            val value = "%.1f".format(bar.kw)
            val inside = measurer.measure(value, TextStyle(color = if (bar.current) Color.White else INK, fontSize = 10.sp, fontWeight = FontWeight.Bold))
            if (bottom - barTop >= inside.size.height + 4.dp.toPx()) {
                drawText(inside, topLeft = Offset(x + (barW - inside.size.width) / 2, barTop + 2.dp.toPx()))
            } else {
                val above = measurer.measure(value, TextStyle(color = INK, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                drawText(above, topLeft = Offset(x + (barW - above.size.width) / 2, barTop - above.size.height))
            }
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
                    append(" highest seen")
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
