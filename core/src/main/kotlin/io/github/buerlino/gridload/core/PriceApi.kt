package io.github.buerlino.gridload.core

import java.time.Instant
import java.time.ZoneId

/** Today and tomorrow in [zone]: from today's midnight to the midnight after tomorrow. */
fun fetchPeriod(now: Instant, zone: ZoneId): Pair<Instant, Instant> {
    val today = now.atZone(zone).toLocalDate().atStartOfDay(zone)
    return today.toInstant() to today.plusDays(2).toInstant()
}

/**
 * The request for [fetchPeriod] in the region's zone. Without start and end the API returns only today. Timestamps
 * go in UTC (`Z`), which the API also answers in; the span may be at most 31 days.
 */
fun pricesRequestUrl(region: Region, now: Instant): String {
    val (start, end) = fetchPeriod(now, region.zone)
    return "${region.pricesUrl}&start_timestamp=$start&end_timestamp=$end"
}

/**
 * Blocking fetch of today's and, once published, tomorrow's prices for [region]. Call off the
 * main thread. The API allows only a few requests per window.
 */
fun fetchPrices(region: Region, now: Instant = Instant.now()): List<PriceSlot> {
    val end = fetchPeriod(now, region.zone).second
    // The API includes the slot that starts at the end timestamp.
    return parsePrices(httpGet(pricesRequestUrl(region, now), timeoutMillis = 15_000))
        .filter { it.start.toInstant().isBefore(end) }
}
