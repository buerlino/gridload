package io.github.buerlino.gridload.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.YearMonth

/** An appliance the user tracks. Without [runMinutes] it runs until stopped. */
@Serializable
data class Appliance(
    val id: String,
    val name: String,
    val watts: Int,
    val runMinutes: Int? = null,
    val interruptible: Boolean = true,
)

/**
 * One use of an appliance, in epoch seconds. [watts] is copied at start so editing or deleting
 * the appliance doesn't change past quarter hours. [end] is the planned end (start + run time),
 * set to the stop time when stopped, and null while it runs until stopped.
 */
@Serializable
data class Run(val applianceId: String, val watts: Int, val start: Long, val end: Long? = null)

/** Starts [appliance] at [now]. */
fun startRun(appliance: Appliance, now: Instant) = Run(
    applianceId = appliance.id,
    watts = appliance.watts,
    start = now.epochSecond,
    end = appliance.runMinutes?.let { now.epochSecond + it * 60L },
)

/** Stops [run] at [now]; a run already over stays as it was. */
fun Run.stop(now: Instant): Run = if (end != null && end <= now.epochSecond) this else copy(end = now.epochSecond)

fun Run.isRunning(now: Instant): Boolean = start <= now.epochSecond && (end == null || now.epochSecond < end)

/** The peak is measured as the average draw over fixed quarter hours, like the price slots. */
val QUARTER: Duration = Duration.ofMinutes(15)

/** The start of the quarter hour containing [instant]. Swiss offsets are whole hours. */
fun quarterStart(instant: Instant): Instant = Instant.ofEpochSecond(instant.epochSecond - Math.floorMod(instant.epochSecond, QUARTER.seconds))

/** Energy of [runs] between [from] and [to] in kWh. Open runs count as running until [to]. */
fun energyKwh(runs: List<Run>, from: Instant, to: Instant): Double = runs.sumOf { run ->
    val start = maxOf(run.start, from.epochSecond)
    val end = minOf(run.end ?: to.epochSecond, to.epochSecond)
    if (end <= start) 0.0 else run.watts / 1000.0 * (end - start) / 3600.0
}

/**
 * The estimated average draw over the quarter hour starting at [quarter]: [baselineKw] plus the
 * runs' share of it. A run still going counts until the quarter ends, so the current quarter
 * shows what it will average if nothing changes.
 */
fun quarterHourKw(quarter: Instant, baselineKw: Double, runs: List<Run>): Double =
    baselineKw + energyKwh(runs, quarter, quarter + QUARTER) * (Duration.ofHours(1).seconds.toDouble() / QUARTER.seconds)

/** The highest estimated quarter hour: when it starts and its average draw. */
data class Peak(val quarter: Instant, val kw: Double)

/**
 * The month's estimated peak from its start up to and including the current quarter hour.
 * [baseline] gives the draw without tracked appliances at a time ([Baseline.at]).
 */
fun monthPeak(month: YearMonth, baseline: (Instant) -> Double, runs: List<Run>, now: Instant): Peak {
    val first = month.atDay(1).atStartOfDay(TARIFF_ZONE).toInstant()
    val next = month.plusMonths(1).atDay(1).atStartOfDay(TARIFF_ZONE).toInstant()
    val last = minOf(quarterStart(now), next - QUARTER)
    // At most ~3000 quarter hours a month, so checking each is cheap enough once the runs of
    // other months are left out.
    val inMonth = runs.filter { it.start < next.epochSecond && (it.end ?: Long.MAX_VALUE) > first.epochSecond }
    return generateSequence(first) { it + QUARTER }.takeWhile { it <= last }
        .map { Peak(it, quarterHourKw(it, baseline(it), inMonth)) }
        .maxByOrNull { it.kw } ?: Peak(first, baseline(first))
}

/**
 * Everything the app saves beyond its settings, as one JSON file (no database): the imported
 * hourly [days], which both modes use, and peak load mode's appliances, runs and goal. Files
 * from before 2026-09-30 also hold `usage` (monthly totals, no longer used), which is ignored.
 * [goalOffsetKw] is what the user allows above the month's level (see [PeakStatus.goalKw]).
 */
@Serializable
data class PeakData(
    val appliances: List<Appliance> = emptyList(),
    val runs: List<Run> = emptyList(),
    val goalOffsetKw: Double = DEFAULT_GOAL_OFFSET_KW,
    val days: List<DayUsage> = emptyList(),
)

const val DEFAULT_GOAL_OFFSET_KW = 2.0

private val peakJson = Json { ignoreUnknownKeys = true }

fun PeakData.toJson(): String = peakJson.encodeToString(this)

fun parsePeakData(json: String): PeakData = peakJson.decodeFromString(json)
