package io.github.buerlino.gridload.core

import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class CoreTest {
    // Real API response captured on 2026-09-28 (integrated range 0.1513 to 0.2801 CHF/kWh).
    private val slots = parsePrices(javaClass.getResource("/prices-2026-09-28.json")!!.readText())

    private fun at(time: String) = OffsetDateTime.parse("2026-09-28T$time+02:00").toInstant()

    // Real response for today + tomorrow, fetched 2026-09-29 with start/end in UTC.
    private val twoDays = parsePrices(javaClass.getResource("/prices-2026-09-29-to-30.json")!!.readText())
    private val sep29 = twoDays.take(96)

    private fun sep29(time: String) = OffsetDateTime.parse("2026-09-29T$time+02:00").toInstant()

    @Test
    fun parsesAllQuarterHourSlots() {
        assertEquals(96, slots.size)
        assertEquals(OffsetDateTime.parse("2026-09-28T00:00+02:00"), slots.first().start)
        assertEquals(OffsetDateTime.parse("2026-09-29T00:00+02:00"), slots.last().end)
        assertEquals(0.2274, slots.first().price)
    }

    @Test
    fun middayIsGreen() {
        val status = classify(slots, at("13:05"))!!
        assertEquals(Level.GREEN, status.level)
        assertEquals(0.1513, status.slot.price)
    }

    @Test
    fun morningPeakIsRed() = assertEquals(Level.RED, classify(slots, at("07:00"))!!.level)

    @Test
    fun nightIsOrange() = assertEquals(Level.ORANGE, classify(slots, at("02:30"))!!.level)

    @Test
    fun slotEndIsExclusive() {
        // 16:45-17:00 is 0.1759 (green), 17:00-17:15 is 0.2223 (orange).
        assertEquals(Level.GREEN, classify(slots, at("16:59:59"))!!.level)
        assertEquals(Level.ORANGE, classify(slots, at("17:00"))!!.level)
    }

    @Test
    fun staleDataGivesNoStatus() {
        assertNull(classify(slots, at("23:59").plusSeconds(60)))
        assertNull(classify(emptyList(), Instant.now()))
    }

    @Test
    fun flatDayIsOrange() {
        val flat = slots.map { it.copy(price = 0.2) }
        assertEquals(Level.ORANGE, classify(flat, at("13:00"))!!.level)
    }

    @Test
    fun parsesUtcTimestamps() {
        assertEquals(192, twoDays.size)
        assertEquals(sep29("00:00"), twoDays.first().start.toInstant())
        assertEquals(OffsetDateTime.parse("2026-10-01T00:00+02:00").toInstant(), twoDays.last().end.toInstant())
    }

    @Test
    fun eveningLooksAheadToTomorrow() {
        // 21:00 is 0.2356: orange within today and within the next 24 h. Tomorrow 10:00 is green.
        val status = classify(twoDays, sep29("21:00"))!!
        assertEquals(Level.ORANGE, status.level)
        assertEquals(OffsetDateTime.parse("2026-09-30T10:00+02:00").toInstant(), status.nextGreen!!.start.toInstant())
    }

    @Test
    fun withoutTomorrowTheWindowIsToday() {
        // Same colour, but today's green hours are over, so there is no next good time to show.
        val status = classify(sep29, sep29("21:00"))!!
        assertEquals(Level.ORANGE, status.level)
        assertNull(status.nextGreen)
    }

    @Test
    fun nextGreenIsLaterToday() {
        val status = classify(sep29, sep29("07:00"))!!
        assertEquals(Level.RED, status.level)
        assertEquals(sep29("10:00"), status.nextGreen!!.start.toInstant())
        assertNull(classify(twoDays, sep29("13:00"))!!.nextGreen)
    }

    @Test
    fun fetchesTomorrowOnceItIsPublished() {
        assertTrue(wantsFetch(emptyList(), sep29("09:00")))
        assertFalse(wantsFetch(sep29, sep29("11:59")))
        assertTrue(wantsFetch(sep29, sep29("12:00")))
        assertFalse(wantsFetch(twoDays, sep29("12:00")))
        assertTrue(wantsFetch(sep29, OffsetDateTime.parse("2026-09-30T00:00+02:00").toInstant()))
    }

    @Test
    fun requestCoversTodayAndTomorrowInUtc() {
        // Winter time starts on 25 October, which then has 25 hours.
        val url = pricesRequestUrl(CKW, OffsetDateTime.parse("2026-10-24T15:00+02:00").toInstant())
        assertTrue(url.startsWith(CKW.pricesUrl + "&"))
        assertTrue(url.endsWith("&start_timestamp=2026-10-23T22:00:00Z&end_timestamp=2026-10-25T23:00:00Z"), url)
    }

    @Test
    fun regionsHaveUniqueIdsAndHttpsUrls() {
        assertEquals(REGIONS.size, REGIONS.map { it.id }.toSet().size)
        assertEquals(true, REGIONS.all { it.pricesUrl.startsWith("https://") })
        assertEquals(CKW, REGIONS.first())
    }

    @Test
    fun cooldownBlocksRapidFetches() {
        val last = at("12:00")
        assertEquals(true, mayFetch(null, last, hasCurrentData = true))
        assertEquals(false, mayFetch(last, at("12:04:59"), hasCurrentData = true))
        assertEquals(true, mayFetch(last, at("12:05"), hasCurrentData = true))
        assertEquals(true, mayFetch(last, at("12:10"), hasCurrentData = true))
    }

    @Test
    fun retryIsQuickerWhenThereIsNothingToShow() {
        val last = at("12:00")
        assertEquals(false, mayFetch(last, at("12:00:29"), hasCurrentData = false))
        assertEquals(true, mayFetch(last, at("12:00:30"), hasCurrentData = false))
    }
}
