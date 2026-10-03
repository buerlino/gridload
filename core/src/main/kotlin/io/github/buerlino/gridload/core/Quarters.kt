package io.github.buerlino.gridload.core

import java.time.Duration
import java.time.Instant

/** One quarter hour's energy drawn from the grid; the peak tariff bills its average power. */
data class Quarter(val start: Instant, val kwh: Double) {
    val kw: Double get() = kwh * 4
}

/**
 * This quarter hour's average kW if the draw now holds until [end], from a reading at [time].
 * [usedKwh]: the energy since the quarter began, when it's exact (the recorder has a line ending
 * at the quarter's start); without it the quarter's first minutes are [estimated].
 */
data class Projection(val kw: Double, val end: Instant, val time: Instant, val usedKwh: Double? = null) {
    val start: Instant get() = end.minusSeconds(QUARTER_SECONDS)
    val estimated: Boolean get() = usedKwh == null

    /** The average draw in this quarter hour so far, when it's exact and [BASE_MINUTES] in: the base for measuring an appliance switched on now. */
    val baseKw: Double? get() = usedKwh?.let { used -> hours(start, time).takeIf { it >= BASE_MINUTES / 60.0 }?.let { used / it } }
}

/** Warn when this quarter hour's projection reaches this share of the line: a 10% margin. */
const val WARN_SHARE = 0.9

/** A shorter stretch of a quarter hour says too little about the base draw (a fridge cycling). */
private const val BASE_MINUTES = 5

/**
 * The kW not to pass in this quarter hour: the higher of the goal and the month's highest
 * quarter hour, since anything up to the month's highest is billed anyway. Null with neither.
 */
fun peakLine(goalKw: Double?, highest: Quarter?): Double? = listOfNotNull(goalKw, highest?.kw).maxOrNull()

fun isPeakWarning(projectedKw: Double, line: Double) = projectedKw >= WARN_SHARE * line

/** A typed number (kW, W, minutes, litres), with a decimal point or comma; null unless it's above 0. */
fun parsePositive(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }

const val QUARTER_SECONDS = 900L

/** The start of the quarter hour (:00, :15, :30, :45) that [time] falls in. */
fun quarterStart(time: Instant): Instant =
    Instant.ofEpochSecond(Math.floorDiv(time.epochSecond, QUARTER_SECONDS) * QUARTER_SECONDS)

private fun hours(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 3_600_000.0

/**
 * Projects the current quarter hour from the meter's register: the energy since the quarter began
 * plus the draw now for the time left. The quarter's start is the recorder's last line when that
 * ends where this quarter begins (exact). Without it (the whatwatt restarted, or the line isn't
 * copied yet) the time before the first reading seen is assumed at the average since then, or at
 * the draw now within the first minute, when the register's 0.001 kWh steps are too coarse.
 */
class QuarterProjector {
    /** The first reading seen in the current quarter: the meter's time and its register. */
    private var first: Pair<Instant, Double>? = null

    /** [start] is the recorder's last quarter end and the register there. */
    fun project(time: Instant, kwh: Double, powerKw: Double, start: Pair<Instant, Double>?): Projection {
        val qStart = quarterStart(time)
        val end = qStart.plusSeconds(QUARTER_SECONDS)
        val seenFirst = first?.takeIf { quarterStart(it.first) == qStart && kwh >= it.second } ?: (time to kwh).also { first = it }
        val exact = start != null && start.first == qStart && kwh >= start.second
        val used = if (exact) {
            kwh - start.second
        } else {
            val (firstTime, firstKwh) = seenFirst
            val seen = hours(firstTime, time)
            val before = if (seen >= 1 / 60.0) (kwh - firstKwh) / seen else powerKw
            before * hours(qStart, firstTime) + kwh - firstKwh
        }
        return Projection((used + powerKw * hours(time, end)) * 4, end, time, used.takeIf { exact })
    }

    /** Forgets the readings, e.g. when the address now points to another device. */
    fun reset() {
        first = null
    }
}
