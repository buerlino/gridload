package io.github.buerlino.gridload.core

import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * The API allows 4 requests per ~1000 s window. Waiting 5 minutes between attempts keeps us
 * under that however often the user taps refresh.
 */
val FETCH_COOLDOWN: Duration = Duration.ofMinutes(5)

/** Shorter wait when the screen has nothing to show, so a failed fetch can be retried soon. */
val RETRY_COOLDOWN: Duration = Duration.ofSeconds(30)

/**
 * Whether a fetch is allowed now. [lastAttempt] is the start of the last fetch, successful or
 * not. [hasCurrentData] is true when a cached slot still covers [now].
 */
fun mayFetch(lastAttempt: Instant?, now: Instant, hasCurrentData: Boolean): Boolean {
    if (lastAttempt == null) return true
    val cooldown = if (hasCurrentData) FETCH_COOLDOWN else RETRY_COOLDOWN
    return Duration.between(lastAttempt, now) >= cooldown
}

/**
 * Whether to fetch without the user asking: no cached slot covers [now], or tomorrow's prices
 * should be out (from the region's [tomorrowFrom]) but the cache ends today.
 */
fun wantsFetch(slots: List<PriceSlot>, now: Instant, tomorrowFrom: LocalTime): Boolean {
    if (slots.none { it.covers(now) }) return true
    val local = now.atZone(TARIFF_ZONE)
    val tomorrow = local.toLocalDate().plusDays(1).atStartOfDay(TARIFF_ZONE).toInstant()
    return !local.toLocalTime().isBefore(tomorrowFrom) && slots.maxOf { it.end.toInstant() } <= tomorrow
}
