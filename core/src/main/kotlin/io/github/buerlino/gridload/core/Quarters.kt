package io.github.buerlino.gridload.core

import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** One quarter hour's energy drawn from the grid; the peak tariff bills its average power. */
data class Quarter(val start: Instant, val kwh: Double) {
    val kw: Double get() = kwh * 4
}

/**
 * This quarter hour's average kW if the draw now holds until [end]. [estimated] when the app
 * didn't see the quarter's start: phase 4 will fill that part in from the whatwatt's SD card.
 */
data class Projection(val kw: Double, val end: Instant, val estimated: Boolean) {
    val start: Instant get() = end.minusSeconds(QUARTER_SECONDS)
}

/** Warn when this quarter hour's projection reaches this share of the line: a 10% margin. */
const val WARN_SHARE = 0.9

/**
 * The kW not to pass in this quarter hour: the higher of the goal and the month's highest
 * quarter hour, since anything up to the month's highest is billed anyway. Null with neither.
 */
fun peakLine(goalKw: Double?, highest: Quarter?): Double? = listOfNotNull(goalKw, highest?.kw).maxOrNull()

fun isPeakWarning(projectedKw: Double, line: Double) = projectedKw >= WARN_SHARE * line

/** A typed kW value, with a decimal point or comma; null unless it's a number above 0. */
fun parseKw(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }

const val QUARTER_SECONDS = 900L

/** The start of the quarter hour (:00, :15, :30, :45) that [time] falls in. */
fun quarterStart(time: Instant): Instant =
    Instant.ofEpochSecond(Math.floorDiv(time.epochSecond, QUARTER_SECONDS) * QUARTER_SECONDS)

private fun hours(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 3_600_000.0

/**
 * Readings further apart than this don't pin a boundary, so the quarters on either side are left
 * out. At 3 kW, a 30 s gap puts the boundary at most 0.025 kWh (0.1 kW of the quarter's average)
 * off, and only if the draw changed within the gap.
 */
private val MAX_GAP = Duration.ofSeconds(30)

/**
 * Turns successive readings of the meter's energy register into quarter hours. The register at
 * each boundary (:00, :15, :30, :45) is interpolated between the readings either side of it, so
 * a quarter is recorded only when both its boundaries were seen. Not thread-safe.
 */
class QuarterRecorder {
    /** The last reading: the meter's time and its register. */
    private var last: Pair<Instant, Double>? = null

    /** The current quarter's start and the register there, once a boundary was seen. */
    private var start: Pair<Instant, Double>? = null

    /** The first reading in the last reading's quarter, for when its start wasn't seen. */
    private var first: Pair<Instant, Double>? = null

    /** Returns the quarter that [time] finishes, if any. Repeated readings are ignored. */
    fun add(time: Instant, kwh: Double): Quarter? {
        val prev = last
        if (prev != null && !time.isAfter(prev.first)) return null
        last = time to kwh
        if (prev == null || quarterStart(time) != quarterStart(prev.first)) first = time to kwh
        if (prev == null) return null
        if (kwh < prev.second) { // a different meter, or a bad reading
            start = null
            first = time to kwh
            return null
        }
        val boundary = quarterStart(prev.first).plusSeconds(QUARTER_SECONDS)
        if (boundary.isAfter(time)) return null
        val gap = Duration.between(prev.first, time)
        if (gap > MAX_GAP) {
            start = null
            return null
        }
        val fraction = Duration.between(prev.first, boundary).toMillis().toDouble() / gap.toMillis()
        val atBoundary = prev.second + (kwh - prev.second) * fraction
        val quarter = start?.let { (s, k) -> Quarter(s, roundKwh(atBoundary - k)) }
        start = boundary to atBoundary
        return quarter
    }

    /**
     * The last reading's quarter hour, projected: the energy since the quarter began plus
     * [powerKw], the draw now, for the time left. When the quarter's start wasn't seen (the app
     * opened during it), the time before the first reading is assumed at the average since then,
     * or at [powerKw] within the first minute, when the register's 0.001 kWh steps are too coarse.
     */
    fun projection(powerKw: Double): Projection? {
        val (time, kwh) = last ?: return null
        val qStart = quarterStart(time)
        val end = qStart.plusSeconds(QUARTER_SECONDS)
        val s = start
        val used = if (s != null && s.first == qStart) {
            kwh - s.second
        } else {
            val (firstTime, firstKwh) = first ?: return null
            val seen = hours(firstTime, time)
            val before = if (seen >= 1 / 60.0) (kwh - firstKwh) / seen else powerKw
            before * hours(qStart, firstTime) + kwh - firstKwh
        }
        return Projection((used + powerKw * hours(time, end)) * 4, end, estimated = s?.first != qStart)
    }

    /** Forgets the readings, e.g. when the address now points to another device. */
    fun reset() {
        last = null
        start = null
        first = null
    }
}

private fun roundKwh(kwh: Double) = (kwh * 10_000).roundToLong() / 10_000.0

/**
 * The recorded quarter hours, one small JSON file per month in [TARIFF_ZONE] (`2026-10.json`,
 * about 3000 values), keyed by the quarter's local start: `{"2026-10-02T18:00+02:00": 0.0523}`.
 * Blocking; call off the main thread.
 */
class QuarterStore(private val dir: File) {
    fun month(month: YearMonth): List<Quarter> {
        val file = file(month)
        if (!file.exists()) return emptyList()
        return json.decodeFromString<Map<String, Double>>(file.readText())
            .map { (start, kwh) -> Quarter(OffsetDateTime.parse(start).toInstant(), kwh) }
    }

    /** Adds [quarter] to its month's file, replacing an earlier value for the same quarter. */
    fun add(quarter: Quarter) {
        val month = YearMonth.from(quarter.start.atZone(TARIFF_ZONE))
        val quarters = (month(month).associate { it.start to it.kwh } + (quarter.start to quarter.kwh)).toSortedMap()
        file(month).writeAtomically(json.encodeToString(quarters.entries.associate { (start, kwh) -> KEY.format(start.atZone(TARIFF_ZONE)) to kwh }))
    }

    private fun file(month: YearMonth) = File(dir, "$month.json")
}

private val KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmxxx")
