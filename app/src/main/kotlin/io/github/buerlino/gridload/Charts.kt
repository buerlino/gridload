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

// The white panels on the main screen and the charts' parts, shared with the History screen.

internal val INK = Color(0xFF1C2126)
internal val MUTED = Color(0xFF5B646D)
internal val BAR = Color(0xFF37474F)
internal val PAST_BAR = Color(0xFFB0BEC5)

/** Axis ticks, bar and day labels. */
internal val SMALL = TextStyle(color = MUTED, fontSize = 10.sp)

/**
 * A white panel on the main screen: a header row that collapses and expands it ([onToggle]),
 * then [content], which decides itself what shows when collapsed. With [icon] ("×"), the header
 * closes something instead. Its help is a topic in the help (?), not a ⓘ here.
 */
@Composable
internal fun Panel(
    open: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    icon: String? = null,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().widthIn(max = 420.dp).background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clickable(onClickLabel = if (icon != null) "Close" else if (open) "Collapse" else "Expand", onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            Text(icon ?: if (open) "▴" else "▾", color = MUTED, fontSize = 20.sp)
        }
        content()
    }
}

/**
 * A chart's vertical axis from [low] at [bottom] to [high] at [top], with [ticks] (a value and its
 * label) and the [unit] above them, [gap] px left of [x]. The baseline is at 0.
 */
internal class Axis(
    measurer: TextMeasurer,
    ticks: List<Pair<Double, String>>,
    unit: String,
    private val low: Double,
    private val high: Double,
    val top: Float,
    private val bottom: Float,
    private val gap: Float,
) {
    private val ticks = ticks.map { (value, label) -> value to measurer.measure(label, SMALL) }
    private val unitLabel = measurer.measure(unit, SMALL)

    /** Where the axis line is; the plot starts right of it. */
    val x = maxOf(this.ticks.maxOf { it.second.size.width }, unitLabel.size.width) + gap

    fun y(value: Double) = bottom - ((value - low) / (high - low) * (bottom - top)).toFloat()

    /** Draws the axis with its ticks and the baseline to [end]. */
    fun draw(scope: DrawScope, end: Float) = with(scope) {
        for ((value, label) in ticks) {
            val y = y(value)
            drawText(label, topLeft = Offset(x - gap - label.size.width, y - label.size.height / 2))
            drawLine(MUTED, Offset(x, y), Offset(x + 4.dp.toPx(), y), 1.dp.toPx())
        }
        drawText(unitLabel, topLeft = Offset(x - gap - unitLabel.size.width, 0f))
        drawLine(MUTED, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
        drawLine(MUTED, Offset(x, y(0.0)), Offset(end, y(0.0)), 1.dp.toPx())
    }
}

/**
 * An axis in [unit] from 0 to whole kW a bit above [maxKw], with a tick every 1, 2, 5, 10, 20, 50…
 * kW, at most 9 of them, so a goal typed far too high can't crowd the labels or run out of memory.
 */
internal fun kwAxis(measurer: TextMeasurer, unit: PowerUnit, maxKw: Double, top: Float, bottom: Float, gap: Float): Axis {
    val topKw = ceil(maxOf(maxKw * 1.15, 1.0)).toLong()
    val step = generateSequence(1L) { if (it.toString()[0] == '2') it / 2 * 5 else it * 2 }.first { topKw / it <= 8 }
    val ticks = (0..topKw step step).map { kw -> kw.toDouble() to if (unit == PowerUnit.W) "${kw * 1000}" else "$kw" }
    return Axis(measurer, ticks, unit.id, 0.0, topKw.toDouble(), top, bottom, gap)
}

/** A label for the limit, "**0.9** limit", wrapped within [maxWidth] px. */
internal fun limitLabel(measurer: TextMeasurer, unit: PowerUnit, kw: Double, maxWidth: Int) =
    measurer.measure(buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(unit.number(kw)) }
        append(" limit")
    }, TextStyle(color = INK, fontSize = 12.sp, lineHeight = 14.sp), constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1)))

/** The limit at [kw] across the chart from the axis to [end], with its [label] at [labelX], kept within the chart. */
internal fun DrawScope.drawLimit(axis: Axis, kw: Double, label: TextLayoutResult, end: Float, labelX: Float) {
    val y = axis.y(kw)
    drawLine(INK, Offset(axis.x, y), Offset(end, y), 2.5.dp.toPx())
    drawText(label, topLeft = Offset(labelX, (y - label.size.height / 2).coerceIn(0f, size.height - label.size.height)))
}
