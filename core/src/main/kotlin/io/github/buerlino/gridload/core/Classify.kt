package io.github.buerlino.gridload.core

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

enum class Level { GREEN, ORANGE, RED }

/**
 * [nextGreen] is the next green slot in the window when now isn't green, else null. [window] is
 * every slot the price is compared with, each in its colour (the price curve).
 */
data class Status(val level: Level, val slot: PriceSlot, val nextGreen: PriceSlot?, val window: List<SlotLevel>) {
    /**
     * When the green that's on now ends: the end of the green slots in a row from now. Null when now
     * isn't green, or when the green runs to the window's end, since what follows isn't known yet
     * (tomorrow's prices) or isn't compared.
     */
    val greenUntil: OffsetDateTime? get() {
        if (level != Level.GREEN) return null
        val run = window.dropWhile { it.slot.start.isBefore(slot.start) }.takeWhile { it.level == Level.GREEN }
        return if (run.last() == window.last()) null else run.last().slot.end
    }
}

/** A slot of the window and its colour. */
data class SlotLevel(val slot: PriceSlot, val level: Level)

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
    val levels = window.map { SlotLevel(it, level(it.price)) }
    val level = level(current.price)
    val nextGreen = if (level == Level.GREEN) null else levels.firstOrNull {
        it.slot.start.isAfter(current.start) && it.level == Level.GREEN
    }?.slot
    return Status(level, current, nextGreen, levels)
}

/**
 * The top of the colour range over [prices]. In market-price regions ([spot]) it's the 90th
 * percentile (the sorted value at index ⌊0.9 n⌋, as in `research/energy_charts/score.py`), since
 * one spike there stretches the range and turns clearly dear slots green (scored in
 * `research/classification.md`); prices above it are simply red. Where that
 * is the lowest price too (a flat window with a few dear slots), the highest keeps those red.
 * Elsewhere the highest.
 */
fun colourTop(prices: List<Double>, spot: Boolean): Double {
    val sorted = prices.sorted()
    val percentile = sorted[minOf(sorted.size - 1, sorted.size * 9 / 10)]
    return if (spot && percentile > sorted.first()) percentile else sorted.last()
}
