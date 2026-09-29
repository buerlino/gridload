package io.github.buerlino.gridload.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant

class HttpException(val code: Int) : IOException("HTTP $code")

/** Today and tomorrow in [TARIFF_ZONE]: from today's midnight to the midnight after tomorrow. */
fun fetchPeriod(now: Instant): Pair<Instant, Instant> {
    val today = now.atZone(TARIFF_ZONE).toLocalDate().atStartOfDay(TARIFF_ZONE)
    return today.toInstant() to today.plusDays(2).toInstant()
}

/**
 * The request for [fetchPeriod]. Without start and end the API returns only today. Timestamps
 * go in UTC (`Z`), which the API also answers in; the span may be at most 31 days.
 */
fun pricesRequestUrl(region: Region, now: Instant): String {
    val (start, end) = fetchPeriod(now)
    return "${region.pricesUrl}&start_timestamp=$start&end_timestamp=$end"
}

/**
 * Blocking fetch of today's and, once published, tomorrow's prices for [region]. Call off the
 * main thread. The API allows only a few requests per window.
 */
fun fetchPrices(region: Region, now: Instant = Instant.now()): List<PriceSlot> {
    val conn = URI(pricesRequestUrl(region, now)).toURL().openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
        val end = fetchPeriod(now).second
        // The API includes the slot that starts at the end timestamp.
        return parsePrices(conn.inputStream.bufferedReader().use { it.readText() })
            .filter { it.start.toInstant().isBefore(end) }
    } finally {
        conn.disconnect()
    }
}
