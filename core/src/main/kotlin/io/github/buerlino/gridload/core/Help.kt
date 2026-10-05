package io.github.buerlino.gridload.core

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The help's lines that depend on the country, derived from its regions, so a new country needs
 * no help change. [price] completes "based on …"; [tomorrow] says when tomorrow's prices come out;
 * [priceLines] are the regions' notes and the market prices' attribution; [peakLines] say who
 * bills the peak.
 */
data class CountryHelp(
    val price: String,
    val tomorrow: String?,
    val priceLines: List<String>,
    val peakLines: List<String>,
)

/** The help for [country]'s regions in [all] at [now]; for null (the phone's country isn't listed), a general one with no country's lines. */
fun countryHelp(country: Country?, now: Instant, all: List<Region> = REGIONS): CountryHelp {
    val regions = all.filter { it.country == country }
    val spot = regions.count { it.isSpot }
    val price = when {
        regions.isEmpty() || spot in 1..<regions.size -> "your tariff's price, or the market price"
        spot == 0 -> "your tariff's price"
        else -> "the market price"
    }
    val tomorrow = regions.map { it.tomorrowFrom }.takeIf { it.isNotEmpty() }?.let { times ->
        val first = hour(times.min())
        val last = hour(times.max())
        if (first == last) "Tomorrow's prices come out at about $first." else "Tomorrow's prices come out between $first and $last."
    }
    val priceLines = regions.mapNotNull { it.priceNote } +
        listOfNotNull("Market prices: Bundesnetzagentur | SMARD.de, via energy-charts.info (CC BY 4.0).".takeIf { spot > 0 })
    val billing = regions.filter { it.minimumKw != null }
    val others = regions - billing.toSet()
    val peakLines = billing.flatMap { listOfNotNull(peakBilled(it, now), it.peakNote) } + listOfNotNull(
        when {
            others.isEmpty() -> null
            billing.isEmpty() -> "Not billed in ${country!!.name}."
            others.size == 1 -> "Not billed in ${others.single().name}."
            else -> "Not billed in the other regions."
        },
    )
    return CountryHelp(price, tomorrow, priceLines, peakLines)
}

/** "Billed by CKW.", "Billed in Austria from 2027, at least 2 kW a month."; null where [region] bills no peak. */
fun peakBilled(region: Region, now: Instant): String? {
    if (region.minimumKw == null) return null
    val who = if (region.isSpot) "in ${region.name}" else "by ${region.utility}"
    val from = region.peakFrom?.takeIf { now.atZone(region.zone).toLocalDate() < it }?.let { " from ${date(it)}" } ?: ""
    val atLeast = region.minimumKw?.takeIf { it > 0 }?.let { ", at least ${BigDecimal.valueOf(it).stripTrailingZeros().toPlainString()} kW a month" } ?: ""
    return "Billed $who$from$atLeast."
}

/** "2027" for New Year's Day, else "1 Jul 2027". */
private fun date(day: LocalDate): String =
    if (day.dayOfYear == 1) "${day.year}" else day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

/** The hour [time] falls in, as the help says it: "noon", "1 pm", "6 am". */
private fun hour(time: LocalTime): String = when (val h = time.hour) {
    0 -> "midnight"
    12 -> "noon"
    in 1..11 -> "$h am"
    else -> "${h - 12} pm"
}
