package io.github.buerlino.gridload.core

import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuartersTest {
    private fun at(time: String) = Instant.parse("2026-10-02T${time}Z")

    @Test
    fun projectsExactlyFromTheRecordersLastLine() {
        val projector = QuarterProjector()
        // The recorder's 15:45 quarter ended at 16:00 with the register at 100.002.
        val start = at("16:00:00") to 100.002
        // 0.25 kWh so far, then 3 kW for 10 min: (0.25 + 0.5) * 4.
        val p = projector.project(at("16:05:00"), 100.252, 3.0, start)
        assertEquals(3.0, p.kw, 1e-9)
        assertEquals(at("16:00:00"), p.start)
        assertEquals(at("16:15:00"), p.end)
        assertFalse(p.estimated)
        // The base for measuring: 0.25 kWh in the 5 min so far.
        assertEquals(3.0, p.baseKw!!, 1e-9)
        assertNull(projector.project(at("16:04:00"), 100.252, 3.0, start).baseKw)
        // A line from an earlier quarter doesn't count.
        assertTrue(projector.project(at("16:20:00"), 100.5, 1.0, start).estimated)
    }

    @Test
    fun estimatesAQuarterWithoutTheRecordersStart() {
        val projector = QuarterProjector()
        // Within the first minute: the draw now stands in for the 5 min before.
        assertEquals(2.0, projector.project(at("16:05:00"), 100.000, 2.0, null).kw, 1e-9)
        // 1.2 kW since 16:05, so also before it: 0.1 before + 0.1 seen + 2.4 kW for 5 min (0.2), times 4.
        val p = projector.project(at("16:10:00"), 100.100, 2.4, null)
        assertEquals(1.6, p.kw, 1e-9)
        assertTrue(p.estimated)
        assertNull(p.baseKw)
        // A new quarter starts over from its own first reading.
        assertEquals(1.0, projector.project(at("16:15:30"), 100.300, 1.0, null).kw, 1e-9)
    }

    @Test
    fun theLineIsTheHigherOfGoalAndMonthsHighest() {
        val highest = Quarter(at("17:15:00"), 0.95)
        assertEquals(3.8, highest.kw, 1e-9)
        assertEquals(3.8, peakLine(3.0, highest)!!, 1e-9)
        assertEquals(4.5, peakLine(4.5, highest)!!, 1e-9)
        assertEquals(3.0, peakLine(3.0, null)!!, 1e-9)
        assertNull(peakLine(null, null))
        assertFalse(isPeakWarning(3.41, 3.8))
        assertTrue(isPeakWarning(3.42, 3.8))
    }

    @Test
    fun parsesTypedKw() {
        assertEquals(3.5, parseKw(" 3.5 "))
        assertEquals(3.5, parseKw("3,5"))
        assertNull(parseKw(""))
        assertNull(parseKw("0"))
        assertNull(parseKw("-1"))
        assertNull(parseKw("3.5 kW"))
    }

    @Test
    fun powerUnitsRoundAsShown() {
        assertEquals("1.3", PowerUnit.KW.number(1.26, Locale.ROOT))
        assertEquals("1260", PowerUnit.W.number(1.2649, Locale.ROOT))
        assertEquals("1,3", PowerUnit.KW.number(1.26, Locale.GERMANY))
        assertEquals(0.9, PowerUnit.KW.round(0.94))
        assertEquals(0.94, PowerUnit.W.round(0.9449))
        // "0.9" line and "0.8" bar give "0.1 free", whatever the exact values.
        assertEquals("0.1", PowerUnit.KW.number(PowerUnit.KW.round(0.94) - PowerUnit.KW.round(0.76), Locale.ROOT))
        assertEquals("130", PowerUnit.W.number(PowerUnit.W.round(0.94) - PowerUnit.W.round(0.81), Locale.ROOT))
        assertEquals(3.5, PowerUnit.W.parse("3500"))
        assertEquals(3.5, PowerUnit.KW.parse("3,5"))
        assertEquals("3500", PowerUnit.W.field(3.5))
        assertEquals(PowerUnit.W, PowerUnit.of("W"))
        assertEquals(PowerUnit.KW, PowerUnit.of(null))
    }
}
