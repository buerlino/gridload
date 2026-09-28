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

/** Parses the CKW dynamic-prices response. Schema is documented in CLAUDE.md. */
fun parsePrices(body: String): List<PriceSlot> =
    json.decodeFromString<Response>(body).prices.map {
        PriceSlot(
            start = OffsetDateTime.parse(it.start_timestamp),
            end = OffsetDateTime.parse(it.end_timestamp),
            price = it.integrated.single().value,
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
private class Value(val value: Double)
