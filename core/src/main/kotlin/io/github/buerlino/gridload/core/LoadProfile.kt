package io.github.buerlino.gridload.core

import java.time.Month
import java.time.YearMonth

/**
 * Adds a new import to the saved months. A month in both is replaced by the new data, which is
 * at least as complete (an export made mid-month is completed by next year's).
 */
fun mergeUsage(saved: List<MonthUsage>, imported: List<MonthUsage>): List<MonthUsage> =
    (saved + imported).associateBy { it.yearMonth }.values.sortedBy { it.yearMonth }

/**
 * The household's load per calendar month, from imported monthly totals: the month function
 * that peak load mode builds on (model in CLAUDE.md). Months without data are missing from
 * [averageKw], so callers fall back to the tracked peak or a target.
 */
class LoadProfile(usage: List<MonthUsage>) {
    /** Average draw per calendar month over all imported years, weighted by hours covered. */
    val averageKw: Map<Month, Double> = usage.groupBy { Month.of(it.month) }
        .mapValues { (_, months) -> months.sumOf { it.kwh } / months.sumOf { it.hours } }

    /** The lowest month (summer): everything but heating. Null without data. */
    val floorKw: Double? = averageKw.values.minOrNull()

    /** What heating adds in [month] on average: that month's level above the floor. */
    fun heatingKw(month: Month): Double? = averageKw[month]?.let { it - floorKw!! }

    /**
     * The always-on load: the floor minus the average draw of the appliances tracked in the
     * app ([appliancesKw], measured over the same kind of month). With nothing tracked yet it is
     * the floor itself, an upper bound.
     */
    fun staticBaseloadKw(appliancesKw: Double = 0.0): Double? = floorKw?.let { (it - appliancesKw).coerceAtLeast(0.0) }

    /**
     * The benchmark for [month]: static baseload plus that month's heating, the draw with none
     * of the tracked appliances running. Each quarter hour's estimate is compared with it.
     */
    fun baselineKw(month: Month, appliancesKw: Double = 0.0): Double? {
        val heating = heatingKw(month) ?: return null
        return staticBaseloadKw(appliancesKw)!! + heating
    }
}

/** Adds imported days to the saved ones; a day in both is replaced by the new one. */
fun mergeDays(saved: List<DayUsage>, imported: List<DayUsage>): List<DayUsage> =
    (saved + imported).associateBy { it.date }.values.sortedBy { it.date }

/**
 * What hourly meter data says about one calendar month, over all its imported days (any year).
 * [baseKw] per hour of day (0 to 23) is the median across the days: what usually runs at that
 * hour, i.e. the always-on load, anything on a timer (a water heater at night) and the heat
 * pump's usual draw. The household's own appliances run at different hours on different days,
 * so they mostly drop out. Not a lower percentile: in January the heat pump is on at a given
 * hour on some days and off on others, and the 10th percentile left 49% of hours more than 1 kW
 * above it (the median 13%). [staticKw] is the always-on load alone (5th percentile of all
 * hours), [averageKw] the month's level.
 */
class HourlyMonth(val days: Int, val staticKw: Double, val baseKw: List<Double>, val averageKw: Double) {
    /** The hour of day with the highest usual draw, e.g. 1 for a water heater at 01:00. */
    val peakHour: Int get() = baseKw.indices.maxBy { baseKw[it] }
}

fun hourlyMonths(days: List<DayUsage>): Map<Month, HourlyMonth> =
    days.groupBy { it.localDate.month }.mapValues { (_, monthDays) ->
        val hours = monthDays.flatMap { day -> day.hourStarts().map { it.atZone(TARIFF_ZONE).hour }.zip(day.kwh) }
        val all = hours.map { it.second }
        val static = percentile(all, 0.05)
        val byHour = hours.groupBy({ it.first }, { it.second })
        HourlyMonth(
            days = monthDays.size,
            staticKw = static,
            baseKw = List(24) { h -> byHour[h]?.let { percentile(it, 0.50) } ?: static },
            averageKw = all.average(),
        )
    }

/**
 * The highest hour of [month] in the data. The utility bills the highest quarter hour, which is
 * at least as high, so this is a lower bound of the month's peak. Null without data.
 */
fun highestHour(days: List<DayUsage>, month: YearMonth): Peak? =
    days.filter { YearMonth.from(it.localDate) == month }
        .flatMap { day -> day.hourStarts().zip(day.kwh) }
        .maxByOrNull { it.second }?.let { (start, kwh) -> Peak(start, kwh) }

private fun percentile(values: List<Double>, p: Double): Double = values.sorted()[((values.size - 1) * p).toInt()]
