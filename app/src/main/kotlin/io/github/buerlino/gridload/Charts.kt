package io.github.buerlino.gridload

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.PowerUnit
import kotlin.math.ceil

// The white panels on the main screen (peak window and history) and their charts.

internal val INK = Color(0xFF1C2126)
internal val MUTED = Color(0xFF5B646D)
internal val BAR = Color(0xFF37474F)
internal val PAST_BAR = Color(0xFFB0BEC5)

/** Axis ticks, bar and day labels. */
internal val SMALL = TextStyle(color = MUTED, fontSize = 10.sp)

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

/**
 * A chart's vertical axis in [unit], from 0 at [bottom] to whole kW at [top] a bit above
 * [maxKw] (2 kW steps above 8), with its tick labels and the unit [gap] px left of [x].
 */
internal class Axis(measurer: TextMeasurer, unit: PowerUnit, maxKw: Double, private val top: Float, private val bottom: Float, private val gap: Float) {
    private val topKw = ceil(maxOf(maxKw * 1.15, 1.0)).toInt()
    private val ticks = (0..topKw step if (topKw > 8) 2 else 1).map { kw ->
        kw to measurer.measure(if (unit == PowerUnit.W) "${kw * 1000}" else "$kw", SMALL)
    }
    private val unitLabel = measurer.measure(unit.id, SMALL)

    /** Where the axis line is; the plot starts right of it. */
    val x = maxOf(ticks.maxOf { it.second.size.width }, unitLabel.size.width) + gap

    fun y(kw: Double) = bottom - (kw / topKw * (bottom - top)).toFloat()

    /** Draws the axis with its ticks and the baseline to [end]. */
    fun draw(scope: DrawScope, end: Float) = with(scope) {
        for ((kw, label) in ticks) {
            val y = y(kw.toDouble())
            drawText(label, topLeft = Offset(x - gap - label.size.width, y - label.size.height / 2))
            drawLine(MUTED, Offset(x, y), Offset(x + 4.dp.toPx(), y), 1.dp.toPx())
        }
        drawText(unitLabel, topLeft = Offset(x - gap - unitLabel.size.width, 0f))
        drawLine(MUTED, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
        drawLine(MUTED, Offset(x, bottom), Offset(end, bottom), 1.dp.toPx())
    }
}

/** The month's highest (solid) and the goal (dashed) across the chart from the axis to [end]. */
internal fun DrawScope.drawLines(axis: Axis, highest: Double?, goal: Double?, end: Float) {
    highest?.let { drawLine(INK, Offset(axis.x, axis.y(it)), Offset(end, axis.y(it)), 2.5.dp.toPx()) }
    goal?.let {
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        drawLine(INK, Offset(axis.x, axis.y(it)), Offset(end, axis.y(it)), 1.5.dp.toPx(), pathEffect = dash)
    }
}

/** A line's label, "**0.9** highest", wrapped within [maxWidth] px. */
internal fun lineLabel(measurer: TextMeasurer, unit: PowerUnit, kw: Double, what: String, maxWidth: Int) =
    measurer.measure(buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(unit.number(kw)) }
        append(" $what")
    }, TextStyle(color = INK, fontSize = 12.sp, lineHeight = 14.sp), constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1)))

/** Draws [labels] (wanted centre y, text) at [x], pushed apart where they would overlap. */
internal fun DrawScope.drawLabels(labels: List<Pair<Float, TextLayoutResult>>, x: Float) {
    val sorted = labels.sortedBy { it.first }
    val space = 2.dp.toPx()
    val tops = FloatArray(sorted.size)
    var minTop = 0f
    sorted.forEachIndexed { i, (centre, text) ->
        tops[i] = maxOf(centre - text.size.height / 2, minTop)
        minTop = tops[i] + text.size.height + space
    }
    var maxBottom = size.height
    for (i in sorted.indices.reversed()) {
        tops[i] = minOf(tops[i], maxBottom - sorted[i].second.size.height)
        maxBottom = tops[i] - space
    }
    sorted.forEachIndexed { i, (_, text) -> drawText(text, topLeft = Offset(x, tops[i])) }
}
