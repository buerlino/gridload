package io.github.buerlino.gridload.core

import java.time.Instant

enum class Level { GREEN, ORANGE, RED }

data class Status(val level: Level, val slot: PriceSlot)

/**
 * Classifies the slot covering [now] against the day's range (thresholds in CLAUDE.md):
 * bottom third green, top third red, orange in between or on a flat day.
 * Returns null when no slot covers [now], i.e. the data is stale.
 */
fun classify(slots: List<PriceSlot>, now: Instant): Status? {
    val current = slots.firstOrNull { !now.isBefore(it.start.toInstant()) && now.isBefore(it.end.toInstant()) }
        ?: return null
    val min = slots.minOf { it.price }
    val max = slots.maxOf { it.price }
    val range = max - min
    val level = when {
        range == 0.0 -> Level.ORANGE
        current.price <= min + range / 3 -> Level.GREEN
        current.price >= min + 2 * range / 3 -> Level.RED
        else -> Level.ORANGE
    }
    return Status(level, current)
}
