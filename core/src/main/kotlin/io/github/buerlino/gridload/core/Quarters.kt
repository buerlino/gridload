package io.github.buerlino.gridload.core

import java.time.Duration
import java.time.Instant

/** One quarter hour's energy drawn from the grid; the peak tariff bills its average power. */
data class Quarter(val start: Instant, val kwh: Double) {
    val kw: Double get() = kwh * 4
}

/**
 * This quarter hour's average kW if the draw [aheadKw] holds until [end], from a reading at [time].
 * [usedKwh]: the energy since the quarter began, when it's exact (the recorder has a line ending
 * at the quarter's start); without it the quarter's first minutes are [estimated].
 */
data class Projection(val kw: Double, val end: Instant, val time: Instant, val aheadKw: Double, val usedKwh: Double? = null) {
    val start: Instant get() = end.minusSeconds(QUARTER_SECONDS)
    val estimated: Boolean get() = usedKwh == null

    /** The average draw in this quarter hour so far, when it's exact and [BASE_MINUTES] in: the base for measuring an appliance switched on now. */
    val baseKw: Double? get() = usedKwh?.let { used -> hours(start, time).takeIf { it >= BASE_MINUTES / 60.0 }?.let { used / it } }
}

/** A shorter stretch of a quarter hour says too little about the base draw (a fridge cycling). */
private const val BASE_MINUTES = 5

/**
 * The parts the limit can come from, each with a switch in Settings → Mode → Limit, in the order
 * that names one of a tie: the month's highest first, since it's billed anyway.
 */
enum class LimitPart { HIGHEST, FLOOR, MINIMUM, GOAL }

/**
 * The parts that apply, so their switches show: the month's highest and the goal always, the
 * floor while the appliances panel shows, the tariff's minimum where it's above 0.
 */
fun limitParts(appliancesShown: Boolean, minimumKw: Double?): List<LimitPart> = LimitPart.entries.filter {
    when (it) {
        LimitPart.FLOOR -> appliancesShown
        LimitPart.MINIMUM -> (minimumKw ?: 0.0) > 0
        else -> true
    }
}

/** The limit, or why there's none. */
sealed interface LimitResult

/** The limit, the kW not to pass in this quarter hour, and the [part] that sets it; [appliance] is the floor's. */
data class Limit(val kw: Double, val part: LimitPart, val appliance: String? = null) : LimitResult

/**
 * Why there's no limit: no part that applies is switched on, or none of those on has a value:
 * nothing recorded this month, no appliance counting for the limit, or no goal typed in. Named by
 * the first part on, in [LimitPart] order.
 */
enum class NoLimit : LimitResult { NOTHING_ON, NOTHING_RECORDED, NO_APPLIANCE, NO_GOAL }

/**
 * The limit: the highest of the parts switched on ([on]) that apply ([limitParts]) and have a
 * value: the month's [highest] quarter hour, the [floor] ([peakFloor]), the tariff's minimum
 * ([Region.minimumKw]) and the goal. Anything up to the month's highest or the minimum is billed
 * anyway, and the biggest appliance sets a peak up to the floor by itself, so with those on only
 * stacking passes it. Without them it's a personal cap.
 */
fun peakLimit(on: Set<LimitPart>, appliancesShown: Boolean, minimumKw: Double?, highest: Quarter?, floor: Floor?, goalKw: Double?): LimitResult {
    val parts = limitParts(appliancesShown, minimumKw).filter { it in on }
    val values = parts.mapNotNull { part ->
        when (part) {
            LimitPart.HIGHEST -> highest?.let { Limit(it.kw, part) }
            LimitPart.FLOOR -> floor?.let { Limit(it.kw, part, it.appliance) }
            LimitPart.MINIMUM -> minimumKw?.let { Limit(it, part) }
            LimitPart.GOAL -> goalKw?.let { Limit(it, part) }
        }
    }
    // The first of a tie, in the parts' order.
    return values.maxByOrNull { it.kw } ?: when {
        parts.isEmpty() -> NoLimit.NOTHING_ON
        LimitPart.HIGHEST in parts -> NoLimit.NOTHING_RECORDED
        LimitPart.FLOOR in parts -> NoLimit.NO_APPLIANCE
        // The minimum applies only with a value above 0.
        else -> NoLimit.NO_GOAL
    }
}

/** Red at the limit: the bar, the vibration and an appliance's WAIT. */
fun isPeakWarning(projectedKw: Double, line: Double) = projectedKw >= line

/**
 * Whether a quarter hour at [kw] reaches the month's highest ([highestKw], null before anything
 * is recorded), so it's billed. With the month's highest in the limit, every warning is one; with
 * it switched off, the limit can sit below it, and a warning only says the limit is reached.
 */
fun isNewPeak(kw: Double, highestKw: Double?) = highestKw == null || kw >= highestKw

/** A typed number (kW, W, minutes, litres), with a decimal point or comma; null unless it's above 0. */
fun parsePositive(text: String): Double? = parseNonNegative(text)?.takeIf { it > 0 }

/** A number typed in a field ("18.5" or "18,5"); null unless it's 0 or more. */
fun parseNonNegative(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }

const val QUARTER_SECONDS = 900L

/** The start of the quarter hour (:00, :15, :30, :45) that [time] falls in. */
fun quarterStart(time: Instant): Instant =
    Instant.ofEpochSecond(Math.floorDiv(time.epochSecond, QUARTER_SECONDS) * QUARTER_SECONDS)

private fun hours(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 3_600_000.0

/**
 * Projects the current quarter hour from the meter's register: the energy since the quarter began
 * plus the draw ahead ([DrawAverage] or the latest reading) for the time left. The quarter's start
 * is the recorder's last line when that ends where this quarter begins (exact). Without it (the
 * whatwatt restarted, or the line isn't copied yet) the time before the first reading seen is
 * assumed at the average since then, or at the draw ahead within the first minute, when the
 * register's 0.001 kWh steps are too coarse.
 */
class QuarterProjector {
    /** The first reading seen in the current quarter: the meter's time and its register. */
    private var first: Pair<Instant, Double>? = null

    /** [start] is the recorder's last quarter end and the register there. */
    fun project(time: Instant, kwh: Double, aheadKw: Double, start: Pair<Instant, Double>?): Projection {
        val qStart = quarterStart(time)
        val end = qStart.plusSeconds(QUARTER_SECONDS)
        val seenFirst = first?.takeIf { quarterStart(it.first) == qStart && kwh >= it.second } ?: (time to kwh).also { first = it }
        val exact = start != null && start.first == qStart && kwh >= start.second
        val used = if (exact) {
            kwh - start.second
        } else {
            val (firstTime, firstKwh) = seenFirst
            val seen = hours(firstTime, time)
            val before = if (seen >= 1 / 60.0) (kwh - firstKwh) / seen else aheadKw
            before * hours(qStart, firstTime) + kwh - firstKwh
        }
        return Projection((used + aheadKw * hours(time, end)) * 4, end, time, aheadKw, used.takeIf { exact })
    }

    /** Forgets the readings, e.g. when the address now points to another device. */
    fun reset() {
        first = null
    }
}

/** How far back [DrawAverage] looks. */
const val AVERAGE_SECONDS = 120L

/**
 * The average draw over the last [AVERAGE_SECONDS], from the meter's register, so an appliance
 * switching on and off (a hob at medium heat) counts at what it draws on average rather than at
 * whichever moment the last reading caught. Null until the readings span a minute, as the
 * register's 0.001 kWh steps are too coarse for less.
 */
class DrawAverage {
    private val readings = ArrayDeque<Pair<Instant, Double>>()

    /** Adds the reading at [time] (the meter's clock) with its register [kwh], and returns the average. */
    fun add(time: Instant, kwh: Double): Double? {
        val last = readings.lastOrNull()
        // A register or clock going back is another meter or a reset: start over.
        if (last != null && (time < last.first || kwh < last.second)) readings.clear()
        if (readings.lastOrNull()?.first != time) readings.addLast(time to kwh)
        while (Duration.between(readings.first().first, time).seconds > AVERAGE_SECONDS) readings.removeFirst()
        val (firstTime, firstKwh) = readings.first()
        val span = hours(firstTime, time)
        return if (span >= 1 / 60.0) (kwh - firstKwh) / span else null
    }

    /** Forgets the readings, e.g. when the address now points to another device. */
    fun reset() = readings.clear()
}
