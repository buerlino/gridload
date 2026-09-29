package io.github.buerlino.gridload.core

import java.time.Duration
import java.time.Instant
import java.time.Month
import java.time.YearMonth

/** No heating in these months, so the tracked appliances can be measured against the floor. */
val SUMMER = listOf(Month.JUNE, Month.JULY, Month.AUGUST)

/** Tracked summer time needed before the calibration is trusted. */
val MIN_CALIBRATION: Duration = Duration.ofDays(7)

/**
 * The tracked appliances' average draw over the summer time tracked so far (from the first run
 * to [now]), or null with less than [MIN_CALIBRATION] of it. The floor minus this is the static
 * baseload (CLAUDE.md, "Peak load mode").
 */
fun summerAppliancesKw(runs: List<Run>, now: Instant): Double? {
    val first = runs.minOfOrNull { it.start }?.let(Instant::ofEpochSecond) ?: return null
    var seconds = 0L
    var kwh = 0.0
    for (year in first.atZone(TARIFF_ZONE).year..now.atZone(TARIFF_ZONE).year) {
        val from = maxOf(YearMonth.of(year, SUMMER.first()).atDay(1).atStartOfDay(TARIFF_ZONE).toInstant(), first)
        val to = minOf(YearMonth.of(year, SUMMER.last()).plusMonths(1).atDay(1).atStartOfDay(TARIFF_ZONE).toInstant(), now)
        if (to <= from) continue
        seconds += Duration.between(from, to).seconds
        kwh += energyKwh(runs, from, to)
    }
    if (seconds < MIN_CALIBRATION.seconds) return null
    return kwh * 3600 / seconds
}

/**
 * The draw without tracked appliances at a given time. With hourly meter data for that calendar
 * month it follows the hour of day ([HourlyMonth.baseKw], so a water heater at night counts);
 * else it is the monthly model, static baseload + heating ([LoadProfile.baselineKw]); 0 without
 * either. [appliancesKw] is the summer calibration for the monthly model.
 */
class Baseline(data: PeakData, private val appliancesKw: Double = 0.0) {
    private val hourly = hourlyMonths(data.days)
    private val monthly = LoadProfile(data.usage)

    fun hasHourly(month: Month): Boolean = month in hourly

    /** The usual draw's highest hour of [month] and its kW, from hourly data; null without. */
    fun usualPeak(month: Month): Pair<Int, Double>? = hourly[month]?.let { it.peakHour to it.baseKw[it.peakHour] }

    fun at(instant: Instant): Double {
        val local = instant.atZone(TARIFF_ZONE)
        hourly[local.month]?.let { return it.baseKw[local.hour] }
        return monthly.baselineKw(local.month, appliancesKw) ?: 0.0
    }

    /**
     * The month's level for the goal: the monthly model, or the hourly data's average; null
     * without data.
     */
    fun levelKw(month: Month): Double? = monthly.baselineKw(month, appliancesKw) ?: hourly[month]?.averageKw
}

/**
 * Where the household stands now. [levelKw] is the month's level (null without imported data;
 * the goal is then the offset alone), [baseNowKw] the draw without tracked appliances right now,
 * [hourly] whether that comes from hourly meter data. [quarterKw] is the current quarter hour,
 * [nextQuarterKw] the next one if nothing is started or stopped; running appliances weigh fully in
 * the next one. [calibrated] is false until enough summer time is tracked, so the monthly model's
 * static baseload is still the whole floor.
 *
 * [plannedGoalKw] is the level plus the user's offset. The month's billed peak can't be lower
 * than what is sure to happen, so anything up to that costs nothing extra and [goalKw] rises to
 * the highest of:
 * - [usualPeak]: the hour of day with the highest usual draw from hourly data (a water heater and
 *   the heat pump at 01:00), which happens nearly every night;
 * - [meterPeak]: the highest hour of this month in imported meter data, measured;
 * - [pastPeak]: the app's own estimate of the finished quarter hours of this month (only
 *   finished ones, or going over the goal would raise it at once and never warn).
 */
data class PeakStatus(
    val levelKw: Double?,
    val baseNowKw: Double,
    val hourly: Boolean,
    val plannedGoalKw: Double,
    val usualPeak: Pair<Int, Double>?,
    val pastPeak: Peak,
    val meterPeak: Peak?,
    val quarterKw: Double,
    val nextQuarterKw: Double,
    val monthPeak: Peak,
    val calibrated: Boolean,
    /** The draw without tracked appliances at a time, for [fitsAt], [roomAt] and [suggestStop]. */
    val baseline: (Instant) -> Double,
) {
    val goalKw: Double get() = maxOf(plannedGoalKw, usualPeak?.second ?: 0.0, meterPeak?.kw ?: 0.0, pastPeak.kw)

    /** Why the goal is above the planned one (the measured reason first); null while it isn't. */
    val raisedBy: GoalRaise? get() = when {
        goalKw <= plannedGoalKw -> null
        goalKw == meterPeak?.kw -> GoalRaise.METER
        goalKw == usualPeak?.second -> GoalRaise.USUAL
        else -> GoalRaise.ESTIMATE
    }

    val goalRaised: Boolean get() = raisedBy != null

    /** What an appliance started now may draw without either quarter passing the goal. */
    val budgetKw: Double get() = goalKw - maxOf(quarterKw, nextQuarterKw)
}

enum class GoalRaise { METER, USUAL, ESTIMATE }

fun PeakData.status(now: Instant): PeakStatus {
    val appliancesKw = summerAppliancesKw(runs, now)
    val baseline = Baseline(this, appliancesKw ?: 0.0)
    val local = now.atZone(TARIFF_ZONE)
    val level = baseline.levelKw(local.month)
    val month = YearMonth.from(local)
    return PeakStatus(
        levelKw = level,
        baseNowKw = baseline.at(now),
        hourly = baseline.hasHourly(local.month),
        plannedGoalKw = (level ?: 0.0) + goalOffsetKw,
        usualPeak = baseline.usualPeak(local.month),
        // Up to the quarter before the current one; in the month's first quarter, just the baseline.
        pastPeak = monthPeak(month, baseline::at, runs, now - QUARTER),
        meterPeak = highestHour(days, month),
        quarterKw = quarterHourKw(quarterStart(now), baseline.at(now), runs),
        nextQuarterKw = quarterHourKw(quarterStart(now) + QUARTER, baseline.at(quarterStart(now) + QUARTER), runs),
        monthPeak = monthPeak(month, baseline::at, runs, now),
        calibrated = appliancesKw != null,
        baseline = baseline::at,
    )
}

/**
 * Whether starting [appliance] at [at] keeps the quarter hours it touches within [goalKw].
 * [baseline] gives the draw without tracked appliances at a time ([Baseline.at]); it can rise
 * during a run (a water heater at night), so every quarter of a run with a run time is checked.
 * One that runs until stopped is checked for this quarter and the next: the user stops it.
 */
fun fitsAt(appliance: Appliance, runs: List<Run>, baseline: (Instant) -> Double, goalKw: Double, at: Instant): Boolean {
    val with = runs + startRun(appliance, at)
    val end = appliance.runMinutes?.let { at + Duration.ofMinutes(it.toLong()) } ?: (quarterStart(at) + QUARTER.multipliedBy(2))
    return generateSequence(quarterStart(at)) { it + QUARTER }.takeWhile { it < end }
        .all { quarterHourKw(it, baseline(it), with) <= goalKw }
}

/**
 * The first time within a day from [now] when [appliance] fits: now, when a running appliance
 * finishes, or at a new quarter hour. Null if nothing ends that makes room.
 */
fun roomAt(appliance: Appliance, runs: List<Run>, baseline: (Instant) -> Double, goalKw: Double, now: Instant): Instant? {
    val quarters = generateSequence(quarterStart(now) + QUARTER) { it + QUARTER }.take(96)
    val ends = runs.mapNotNull { it.end }.filter { it > now.epochSecond }.map(Instant::ofEpochSecond)
    return (sequenceOf(now) + (quarters + ends).sorted())
        .firstOrNull { fitsAt(appliance, runs, baseline, goalKw, it) }
}

/**
 * When the current or the next quarter hour will pass [goalKw]: the smallest appliance that can
 * be stopped and whose stop brings this quarter back under the goal, or, if it is too late for
 * that, the next one. Null when both are within the goal or no stop helps.
 */
fun suggestStop(appliances: List<Appliance>, runs: List<Run>, baseline: (Instant) -> Double, goalKw: Double, now: Instant): Run? {
    val quarter = quarterStart(now)
    fun under(q: Instant, runs: List<Run>) = quarterHourKw(q, baseline(q), runs) <= goalKw
    if (under(quarter, runs) && under(quarter + QUARTER, runs)) return null
    val stoppable = appliances.filter { it.interruptible }.map { it.id }.toSet()
    val candidates = runs.filter { it.isRunning(now) && it.applianceId in stoppable }.sortedBy { it.watts }
    fun without(stopped: Run) = runs.map { if (it === stopped) it.stop(now) else it }
    return candidates.firstOrNull { under(quarter, without(it)) } ?: candidates.firstOrNull { under(quarter + QUARTER, without(it)) }
}
