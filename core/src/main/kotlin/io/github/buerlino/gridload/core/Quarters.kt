package io.github.buerlino.gridload.core

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** One quarter hour's energy drawn from the grid; the peak tariff bills its average power. */
data class Quarter(val start: Instant, val kwh: Double)

private const val QUARTER_SECONDS = 900L

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

    /** Returns the quarter that [time] finishes, if any. Repeated readings are ignored. */
    fun add(time: Instant, kwh: Double): Quarter? {
        val prev = last
        if (prev != null && !time.isAfter(prev.first)) return null
        last = time to kwh
        if (prev == null) return null
        if (kwh < prev.second) { // a different meter, or a bad reading
            start = null
            return null
        }
        val boundary = Instant.ofEpochSecond(Math.floorDiv(prev.first.epochSecond, QUARTER_SECONDS) * QUARTER_SECONDS + QUARTER_SECONDS)
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

    /** Forgets the readings, e.g. when the address now points to another device. */
    fun reset() {
        last = null
        start = null
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
        return Json.decodeFromString<Map<String, Double>>(file.readText())
            .map { (start, kwh) -> Quarter(OffsetDateTime.parse(start).toInstant(), kwh) }
    }

    /** Adds [quarter] to its month's file, replacing an earlier value for the same quarter. */
    fun add(quarter: Quarter) {
        val month = YearMonth.from(quarter.start.atZone(TARIFF_ZONE))
        val quarters = (month(month).associate { it.start to it.kwh } + (quarter.start to quarter.kwh)).toSortedMap()
        val text = Json.encodeToString(quarters.entries.associate { (start, kwh) -> KEY.format(start.atZone(TARIFF_ZONE)) to kwh })
        // Write a copy and move it over the file, so a crash can't leave half a file.
        dir.mkdirs()
        val tmp = File(dir, "${file(month).name}.tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), file(month).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun file(month: YearMonth) = File(dir, "$month.json")
}

private val KEY = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmxxx")
