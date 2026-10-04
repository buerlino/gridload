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
 * in CLAUDE.md): bottom third green, top third red, orange in between or on a flat day. The
 * range runs from the lowest price to [colourTop]. Before tomorrow's prices are known, the
 * window moves back to end with the known data, which makes it today. Returns null when no slot
 * covers [now], i.e. the data is stale. Expects [slots] sorted by start, as the API returns them.
 */
fun classify(slots: List<PriceSlot>, now: Instant, spot: Boolean): Status? {
    val current = slots.firstOrNull { it.covers(now) } ?: return null
    val lastEnd = slots.maxOf { it.end.toInstant() }
    val windowStart = minOf(current.start.toInstant(), lastEnd - WINDOW)
    val window = slots.filter {
        val start = it.start.toInstant()
        !start.isBefore(windowStart) && start.isBefore(windowStart + WINDOW)
    }
    val min = window.minOf { it.price }
    val range = colourTop(window.map { it.price }, spot) - min
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

/**
 * The top of the colour range over [prices]. In market-price regions ([spot]) it's the 90th
 * percentile (nearest rank), since one spike there stretches the range and turns clearly dear
 * slots green (scored in CLAUDE.md, Classification); prices above it are simply red. Where that
 * is the lowest price too (a flat window with a few dear slots), the highest keeps those red.
 * Elsewhere the highest.
 */
fun colourTop(prices: List<Double>, spot: Boolean): Double {
    val sorted = prices.sorted()
    val percentile = sorted[minOf(sorted.size - 1, sorted.size * 9 / 10)]
    return if (spot && percentile > sorted.first()) percentile else sorted.last()
}
