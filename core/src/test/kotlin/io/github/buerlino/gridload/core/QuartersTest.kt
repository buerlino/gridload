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
    fun averagesTheDrawOverTheLastTwoMinutes() {
        val average = DrawAverage()
        // A hob cycling 2 kW on and off every 20 s, on top of 0.2 kW: 1.2 kW on average.
        var kwh = 100.0
        var result: Double? = null
        for (s in 0..240 step 5) {
            val time = at("16:00:00").plusSeconds(s.toLong())
            if (s > 0) kwh += (if ((s - 5) / 20 % 2 == 0) 2.2 else 0.2) * 5 / 3600
            result = average.add(time, kwh)
            if (s < 60) assertNull(result)
        }
        assertEquals(1.2, result!!, 0.05)
        // A reading seen twice changes nothing; a register going back starts over.
        assertEquals(result, average.add(at("16:04:00"), kwh))
        assertNull(average.add(at("16:04:05"), 50.0))
    }

    @Test
    fun theLineIsTheHighestOfGoalFloorMinimumAndMonthsHighest() {
        val highest = Quarter(at("17:15:00"), 0.95)
        assertEquals(3.8, highest.kw, 1e-9)
        assertEquals(3.8, peakLine(3.0, 2.6, 2.0, highest)!!, 1e-9)
        assertEquals(4.5, peakLine(4.5, 2.6, 2.0, highest)!!, 1e-9)
        assertEquals(4.2, peakLine(3.0, 4.2, 2.0, highest)!!, 1e-9)
        assertEquals(5.0, peakLine(3.0, 2.6, 5.0, highest)!!, 1e-9)
        // Each one alone.
        assertEquals(3.0, peakLine(3.0, null, null, null)!!, 1e-9)
        assertEquals(2.6, peakLine(null, 2.6, null, null)!!, 1e-9)
        assertEquals(2.5, peakLine(null, null, 2.5, null)!!, 1e-9)
        assertEquals(3.8, peakLine(null, null, null, highest)!!, 1e-9)
        assertNull(peakLine(null, null, null, null))
        // The minimum against each of the others: it counts only where it's higher.
        assertEquals(2.5, peakLine(2.0, null, 2.5, null)!!, 1e-9)
        assertEquals(2.65, peakLine(null, 2.65, 2.5, null)!!, 1e-9)
        assertEquals(2.5, peakLine(null, null, 2.5, Quarter(at("17:15:00"), 0.525))!!, 1e-9)
        // A minimum of 0 (billed, but with no minimum) is no line.
        assertNull(peakLine(null, null, 0.0, null))
        assertEquals(2.6, peakLine(null, 2.6, 0.0, null)!!, 1e-9)
        // Red at the line itself.
        assertFalse(isPeakWarning(3.79, 3.8))
        assertTrue(isPeakWarning(3.8, 3.8))
    }

    @Test
    fun parsesTypedNumbers() {
        assertEquals(3.5, parsePositive(" 3.5 "))
        assertEquals(3.5, parsePositive("3,5"))
        assertNull(parsePositive(""))
        assertNull(parsePositive("0"))
        assertNull(parsePositive("-1"))
        assertNull(parsePositive("3.5 kW"))
        assertNull(parsePositive("Infinity"))
        assertNull(parsePositive("NaN"))
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
