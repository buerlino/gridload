package io.github.buerlino.gridload.core

import java.time.Instant
import java.time.ZoneId

/** Today and tomorrow in [zone]: from today's midnight to the midnight after tomorrow. */
fun fetchPeriod(now: Instant, zone: ZoneId): Pair<Instant, Instant> {
    val today = now.atZone(zone).toLocalDate().atStartOfDay(zone)
    return today.toInstant() to today.plusDays(2).toInstant()
}

/**
 * The request for [fetchPeriod] in the region's zone, with the times in UTC (`Z`). Without start
 * and end the VSE APIs return only today; their span may be at most 31 days, and they answer in
 * UTC too.
 */
fun pricesRequestUrl(region: Region, now: Instant): String {
    val (start, end) = fetchPeriod(now, region.zone)
    return when (val source = region.source) {
        is PriceSource.Vse -> "${source.url}&start_timestamp=$start&end_timestamp=$end"
        is PriceSource.EnergyCharts -> "https://api.energy-charts.info/price?bzn=${source.bzn}&start=$start&end=$end"
    }
}

/**
 * Blocking fetch of today's and, once published, tomorrow's prices for [region]. Call off the
 * main thread. The APIs allow only a few requests per window.
 */
fun fetchPrices(region: Region, now: Instant = Instant.now()): List<PriceSlot> {
    val end = fetchPeriod(now, region.zone).second
    val body = httpGet(pricesRequestUrl(region, now), timeoutMillis = 15_000)
    val slots = when (region.source) {
        is PriceSource.Vse -> parsePrices(body)
        is PriceSource.EnergyCharts -> parseEnergyCharts(body)
    }
    // Both include the slot that starts at the end timestamp, if there are prices for it.
    return slots.filter { it.start.toInstant().isBefore(end) }
}
