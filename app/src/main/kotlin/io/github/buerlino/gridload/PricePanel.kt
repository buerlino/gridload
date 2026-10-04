package io.github.buerlino.gridload

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.Level
import io.github.buerlino.gridload.core.Status
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The price curve, the first panel: the slots the price now is compared with (the next 24 hours,
 * or today until tomorrow's prices are out), one bar per slot in its colour, the ones already
 * over faded, and a line at now. The axis is in the small unit per kWh, the user's own price
 * where set, else the slots' own. The header says how long the green lasts, or when it comes.
 */
@Composable
fun PricePanel(state: UiState, status: Status, onToggle: () -> Unit) {
    Panel(
        open = state.curveOpen,
        onToggle = onToggle,
        header = {
            val until = status.greenUntil
            val next = status.nextGreen
            Text(
                buildAnnotatedString {
                    val time = when {
                        until != null -> { append("Green until "); until.toInstant() }
                        // Green to the window's end: it may go on past the prices known.
                        status.level == Level.GREEN -> { append("Green for now"); null }
                        next != null -> { append("Green from "); next.start.toInstant() }
                        else -> { append("No green time ahead"); null }
                    }
                    time?.let { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(comingTime(it)) } }
                },
                Modifier.weight(1f), color = INK, fontSize = 16.sp,
            )
        },
    ) {
        if (state.curveOpen) Curve(state, status)
    }
}

/** Faded: a slot already over, in a window that started at midnight. */
private const val PAST_ALPHA = 0.35f

@Composable
private fun Curve(state: UiState, status: Status) {
    val measurer = rememberTextMeasurer()
    // The line at now moves within a slot too, not only when the colour is recomputed.
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000)
            value = Instant.now()
        }
    }
    val currency = state.region.country.currency
    // In the small unit (Rp, ct), as on the main screen.
    val prices = status.window.map { (state.yourPrice(it.slot.price) ?: it.slot.price) * 100 }
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val bottom = size.height - 18.dp.toPx()
        val axis = priceAxis(measurer, prices, "${currency.small}/kWh", 16.dp.toPx(), bottom, 6.dp.toPx())
        val start = axis.x + 6.dp.toPx()
        val end = size.width
        val from = status.window.first().slot.start.toInstant()
        val span = Duration.between(from, status.window.last().slot.end.toInstant()).seconds.toFloat()
        fun x(t: Instant) = start + Duration.between(from, t).seconds / span * (end - start)

        axis.draw(this, end)
        val zero = axis.y(0.0)
        status.window.forEachIndexed { i, (slot, level) ->
            val left = x(slot.start.toInstant())
            val width = x(slot.end.toInstant()) - left
            // A hair between bars when there's room, so hourly prices show their quarters.
            val gap = if (width >= 3.dp.toPx()) 0.5.dp.toPx() else 0f
            val y = axis.y(prices[i])
            val color = when (level) {
                Level.GREEN -> GREEN
                Level.ORANGE -> ORANGE
                Level.RED -> RED
            }
            val past = !slot.end.toInstant().isAfter(now)
            drawRect(color, Offset(left, minOf(y, zero)), Size(maxOf(width - gap, 1f), abs(zero - y)), alpha = if (past) PAST_ALPHA else 1f)
        }
        // Hour labels every 6 hours, in the phone's zone like the other times.
        val zone = ZoneId.systemDefault()
        var hour = from.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        while (!hour.toInstant().isAfter(status.window.last().slot.end.toInstant())) {
            if (hour.hour % 6 == 0 && !hour.toInstant().isBefore(from)) {
                val label = measurer.measure("%02d".format(hour.hour), SMALL)
                val lx = (x(hour.toInstant()) - label.size.width / 2).coerceIn(start, end - label.size.width)
                drawText(label, topLeft = Offset(lx, bottom + 3.dp.toPx()))
            }
            hour = hour.plusHours(1)
        }
        if (!now.isBefore(from)) {
            val nowX = x(now).coerceAtMost(end)
            drawLine(INK, Offset(nowX, 0f), Offset(nowX, bottom), 1.5.dp.toPx())
            val label = measurer.measure("now", SMALL.copy(color = INK, fontWeight = FontWeight.Bold))
            drawText(label, topLeft = Offset((nowX + 3.dp.toPx()).coerceAtMost(size.width - label.size.width), 0f))
        }
    }
}

/**
 * An axis for [prices] in the small unit: from 0, or below it for negative prices, to a round
 * step above the highest, with about four steps of 1, 2 or 5 times a power of ten.
 */
private fun priceAxis(measurer: TextMeasurer, prices: List<Double>, unit: String, top: Float, bottom: Float, gap: Float): Axis {
    val low = minOf(0.0, prices.min())
    val high = maxOf(0.0, prices.max())
    val raw = maxOf(high - low, 1.0) / 4
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw }
    val axisLow = floor(low / step) * step
    val axisHigh = maxOf(ceil(high / step) * step, axisLow + step)
    val count = ((axisHigh - axisLow) / step).roundToInt()
    // Steps below 1 only for prices close to 0, e.g. a market price around 1 ct.
    val format = if (step < 1) "%.1f" else "%.0f"
    val ticks = (0..count).map { i ->
        val value = axisLow + i * step
        value to (if (value < -step / 2) "−" else "") + format.format(abs(value))
    }
    return Axis(measurer, ticks, unit, axisLow, axisHigh, top, bottom, gap)
}
