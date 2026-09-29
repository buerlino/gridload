package io.github.buerlino.gridload.core

import java.time.Duration
import java.time.Instant

enum class Level { GREEN, ORANGE, RED }

/** [nextGreen] is the next green slot in the window when now isn't green, else null. */
data class Status(val level: Level, val slot: PriceSlot, val nextGreen: PriceSlot?)

/** How far ahead the price now is compared, about as long as appliances are usually delayed. */
val WINDOW: Duration = Duration.ofHours(24)

/**
 * Classifies the slot covering [now] against the range of the [WINDOW] starting now (thresholds
 * in CLAUDE.md): bottom third green, top third red, orange in between or on a flat day.
 * Before tomorrow's prices are known, the window moves back to end with the known data, which
 * makes it today. Returns null when no slot covers [now], i.e. the data is stale.
 * Expects [slots] sorted by start, as the API returns them.
 */
fun classify(slots: List<PriceSlot>, now: Instant): Status? {
    val current = slots.firstOrNull { !now.isBefore(it.start.toInstant()) && now.isBefore(it.end.toInstant()) }
        ?: return null
    val lastEnd = slots.maxOf { it.end.toInstant() }
    val windowStart = minOf(current.start.toInstant(), lastEnd - WINDOW)
    val window = slots.filter {
        val start = it.start.toInstant()
        !start.isBefore(windowStart) && start.isBefore(windowStart + WINDOW)
    }
    val min = window.minOf { it.price }
    val max = window.maxOf { it.price }
    val range = max - min
    fun level(price: Double) = when {
        range == 0.0 -> Level.ORANGE
        price <= min + range / 3 -> Level.GREEN
        price >= min + 2 * range / 3 -> Level.RED
        else -> Level.ORANGE
    }
    val level = level(current.price)
    val nextGreen = if (level == Level.GREEN) null else window.firstOrNull {
        it.start.isAfter(current.start) && level(it.price) == Level.GREEN
    }
    return Status(level, current, nextGreen)
}
