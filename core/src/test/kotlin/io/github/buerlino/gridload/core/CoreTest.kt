package io.github.buerlino.gridload.core

import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreTest {
    // Real API response captured on 2026-09-28 (integrated range 0.1513 to 0.2801 CHF/kWh).
    private val slots = parsePrices(javaClass.getResource("/prices-2026-09-28.json")!!.readText())

    private fun at(time: String) = OffsetDateTime.parse("2026-09-28T$time+02:00").toInstant()

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
}
