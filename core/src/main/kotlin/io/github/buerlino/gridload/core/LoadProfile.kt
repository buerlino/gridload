package io.github.buerlino.gridload.core

import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

/**
 * Days of hourly values the app asks for per calendar month: a week. Tested on the user's
 * January and September 2026: any 7 days gave each hour of the month's profile within about
 * 0.4 kW (January) and 0.1 kW (September) of all days, and the same highest hour.
 */
const val REQUIRED_DAYS = 7

/** Imported days in [month] of any year; the app asks for [REQUIRED_DAYS] of the current one. */
fun daysIn(days: List<DayUsage>, month: Month): Int = days.count { it.localDate.month == month }

/**
 * The week to export for [today]'s month: its first 7 days once they are over, else the same
 * days a year earlier (the profile is per calendar month, so last year's works).
 */
fun suggestedWeek(today: LocalDate): ClosedRange<LocalDate> {
    val first = today.withDayOfMonth(1).let { if (today.dayOfMonth > REQUIRED_DAYS) it else it.minusYears(1) }
    return first..first.plusDays(REQUIRED_DAYS - 1L)
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
