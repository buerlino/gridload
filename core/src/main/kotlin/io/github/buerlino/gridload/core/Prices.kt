package io.github.buerlino.gridload.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime

/** One quarter-hour slot. [price] is the `integrated` (total) price in CHF/kWh. */
data class PriceSlot(
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val price: Double,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parses a VSE/AES dynamic-prices response (schema in CLAUDE.md). Some utilities (EKZ) list a
 * monthly fee (`CHF_m`) next to the kWh price, so pick the `CHF_kWh` entry.
 */
fun parsePrices(body: String): List<PriceSlot> =
    json.decodeFromString<Response>(body).prices.map {
        PriceSlot(
            start = OffsetDateTime.parse(it.start_timestamp),
            end = OffsetDateTime.parse(it.end_timestamp),
            price = it.integrated.single { v -> v.unit == "CHF_kWh" }.value,
        )
    }

@Serializable
private class Response(val prices: List<Slot>)

@Suppress("PropertyName")
@Serializable
private class Slot(
    val start_timestamp: String,
    val end_timestamp: String,
    val integrated: List<Value>,
)

@Serializable
private class Value(val unit: String, val value: Double)
