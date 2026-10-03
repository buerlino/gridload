package io.github.buerlino.gridload.core

import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime

/** One quarter-hour slot. [price] is the `integrated` (total) price in CHF/kWh. */
data class PriceSlot(
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val price: Double,
) {
    fun covers(time: Instant) = !time.isBefore(start.toInstant()) && time.isBefore(end.toInstant())
}

/**
 * Parses a VSE/AES dynamic-prices response (schema in CLAUDE.md). Some utilities (EKZ) list a
 * monthly fee (`CHF_m`) next to the kWh price, so pick the `CHF_kWh` entry.
 */
fun parsePrices(body: String): List<PriceSlot> = json.decodeFromString<Response>(body).prices.map { it.toPriceSlot() }

/** The last fetched prices of the region with [regionId]. */
data class CachedPrices(val regionId: String, val fetchedAt: Instant, val slots: List<PriceSlot>)

/**
 * The last fetched prices in one small file, so the colour shows at once on the next start,
 * offline too: the API's own JSON plus the region and the fetch time. Blocking; call off the
 * main thread.
 */
class PriceCache(private val file: File) {
    /** Null when nothing is saved. */
    fun load(): CachedPrices? {
        if (!file.exists()) return null
        val cache = json.decodeFromString<Cache>(file.readText())
        return CachedPrices(cache.region, Instant.parse(cache.fetched_at), cache.prices.map { it.toPriceSlot() })
    }

    fun save(prices: CachedPrices) {
        val slots = prices.slots.map { Slot(it.start.toString(), it.end.toString(), listOf(Value(KWH, it.price))) }
        file.writeAtomically(json.encodeToString(Cache(prices.regionId, prices.fetchedAt.toString(), slots)))
    }
}

private const val KWH = "CHF_kWh"

@Serializable
private class Response(val prices: List<Slot>)

@Suppress("PropertyName")
@Serializable
private class Cache(val region: String, val fetched_at: String, val prices: List<Slot>)

@Suppress("PropertyName")
@Serializable
private class Slot(
    val start_timestamp: String,
    val end_timestamp: String,
    val integrated: List<Value>,
) {
    fun toPriceSlot() = PriceSlot(
        start = OffsetDateTime.parse(start_timestamp),
        end = OffsetDateTime.parse(end_timestamp),
        price = integrated.single { it.unit == KWH }.value,
    )
}

@Serializable
private class Value(val unit: String, val value: Double)
