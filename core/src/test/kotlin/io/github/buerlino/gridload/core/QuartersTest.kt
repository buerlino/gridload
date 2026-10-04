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

    private val all = LimitPart.entries.toSet()
    private val defaults = setOf(LimitPart.HIGHEST, LimitPart.FLOOR, LimitPart.MINIMUM)
    private val cooking = Floor(2.65, "Cooking")

    /** The limit's kW with all parts on, and the appliances panel shown unless said. */
    private fun kw(goal: Double?, floor: Double?, minimum: Double?, highest: Quarter?, on: Set<LimitPart> = all) =
        (peakLimit(on, true, minimum, highest, floor?.let { Floor(it, "A") }, goal) as? Limit)?.kw

    @Test
    fun theLimitIsTheHighestOfThePartsSwitchedOn() {
        val highest = Quarter(at("17:15:00"), 0.95)
        assertEquals(3.8, highest.kw, 1e-9)
        assertEquals(3.8, kw(3.0, 2.6, 2.0, highest)!!, 1e-9)
        assertEquals(4.5, kw(4.5, 2.6, 2.0, highest)!!, 1e-9)
        assertEquals(4.2, kw(3.0, 4.2, 2.0, highest)!!, 1e-9)
        assertEquals(5.0, kw(3.0, 2.6, 5.0, highest)!!, 1e-9)
        // Each one alone.
        assertEquals(3.0, kw(3.0, null, null, null)!!, 1e-9)
        assertEquals(2.6, kw(null, 2.6, null, null)!!, 1e-9)
        assertEquals(2.5, kw(null, null, 2.5, null)!!, 1e-9)
        assertEquals(3.8, kw(null, null, null, highest)!!, 1e-9)
        assertNull(kw(null, null, null, null))
        // The minimum against each of the others: it counts only where it's higher.
        assertEquals(2.5, kw(2.0, null, 2.5, null)!!, 1e-9)
        assertEquals(2.65, kw(null, 2.65, 2.5, null)!!, 1e-9)
        assertEquals(2.5, kw(null, null, 2.5, Quarter(at("17:15:00"), 0.525))!!, 1e-9)
        // A minimum of 0 (billed, but with no minimum) is no line.
        assertNull(kw(null, null, 0.0, null))
        assertEquals(2.6, kw(null, 2.6, 0.0, null)!!, 1e-9)
        // A part switched off doesn't count: the goal can sit below the month's highest.
        assertEquals(3.0, kw(3.0, 2.6, 2.0, highest, on = setOf(LimitPart.GOAL))!!, 1e-9)
        assertEquals(2.6, kw(1.0, 2.6, 2.0, highest, on = setOf(LimitPart.GOAL, LimitPart.FLOOR))!!, 1e-9)
        assertEquals(2.0, kw(1.0, 2.6, 2.0, highest, on = setOf(LimitPart.GOAL, LimitPart.MINIMUM))!!, 1e-9)
    }

    @Test
    fun theLimitSaysWhichPartSetsIt() {
        val highest = Quarter(at("17:15:00"), 1.0)
        assertEquals(Limit(4.0, LimitPart.HIGHEST), peakLimit(all, true, 2.0, highest, cooking, 3.0))
        assertEquals(Limit(2.65, LimitPart.FLOOR, "Cooking"), peakLimit(all, true, 2.0, null, cooking, 1.0))
        assertEquals(Limit(2.0, LimitPart.MINIMUM), peakLimit(all, true, 2.0, null, null, 1.0))
        assertEquals(Limit(5.0, LimitPart.GOAL), peakLimit(all, true, 2.0, highest, cooking, 5.0))
        // Ties name the first in order: the month's highest, the floor, the minimum, the goal.
        val two = Quarter(at("17:15:00"), 0.5)
        assertEquals(Limit(2.0, LimitPart.HIGHEST), peakLimit(all, true, 2.0, two, Floor(2.0, "A"), 2.0))
        assertEquals(Limit(2.0, LimitPart.FLOOR, "A"), peakLimit(all, true, 2.0, null, Floor(2.0, "A"), 2.0))
        assertEquals(Limit(2.0, LimitPart.MINIMUM), peakLimit(all, true, 2.0, null, null, 2.0))
    }

    @Test
    fun onlyThePartsThatApplyCount() {
        assertEquals(LimitPart.entries, limitParts(true, 2.0))
        assertEquals(listOf(LimitPart.HIGHEST, LimitPart.MINIMUM, LimitPart.GOAL), limitParts(false, 2.0))
        assertEquals(listOf(LimitPart.HIGHEST, LimitPart.FLOOR, LimitPart.GOAL), limitParts(true, 0.0))
        assertEquals(listOf(LimitPart.HIGHEST, LimitPart.GOAL), limitParts(false, null))
        // The floor while the appliances panel is hidden, the minimum where the region has none.
        assertEquals(NoLimit.NOTHING_ON, peakLimit(setOf(LimitPart.FLOOR), false, 2.0, null, cooking, null))
        assertEquals(NoLimit.NOTHING_ON, peakLimit(setOf(LimitPart.MINIMUM), true, null, null, cooking, null))
        assertEquals(NoLimit.NOTHING_ON, peakLimit(setOf(LimitPart.MINIMUM), true, 0.0, null, cooking, null))
        assertEquals(NoLimit.NOTHING_ON, peakLimit(emptySet(), true, 2.0, null, cooking, 3.0))
    }

    @Test
    fun withoutALimitItSaysWhy() {
        assertEquals(NoLimit.NOTHING_RECORDED, peakLimit(all, true, null, null, null, null))
        assertEquals(NoLimit.NOTHING_RECORDED, peakLimit(setOf(LimitPart.HIGHEST), true, 2.0, null, cooking, 3.0))
        assertEquals(NoLimit.NO_APPLIANCE, peakLimit(setOf(LimitPart.FLOOR, LimitPart.GOAL), true, null, null, null, null))
        assertEquals(NoLimit.NO_GOAL, peakLimit(setOf(LimitPart.GOAL), true, 2.0, Quarter(at("17:15:00"), 1.0), cooking, null))
        // A hidden floor doesn't name the reason: the goal is the part on that applies.
        assertEquals(NoLimit.NO_GOAL, peakLimit(setOf(LimitPart.FLOOR, LimitPart.GOAL), false, null, null, null, null))
    }

    @Test
    fun theDefaultsGiveTheLimitAsBeforeTheSwitches() {
        val highests = listOf(null, Quarter(at("17:15:00"), 0.5), Quarter(at("17:15:00"), 1.2))
        for (goalOn in listOf(false, true)) for (goal in listOf(null, 1.5, 3.0, 6.0)) for (shown in listOf(false, true))
            for (floor in listOf(null, 1.0, 2.65, 4.4)) for (minimum in listOf(null, 0.0, 2.0, 2.5)) for (highest in highests) {
                // peakLine up to v0.13: the goal when on, the floor while the panel shows, a minimum above 0, the month's highest.
                val before = listOfNotNull(goal?.takeIf { goalOn }, floor?.takeIf { shown }, minimum?.takeIf { it > 0 }, highest?.kw).maxOrNull()
                val on = if (goalOn) defaults + LimitPart.GOAL else defaults
                val now = peakLimit(on, shown, minimum, highest, floor?.let { Floor(it, "A") }, goal)
                assertEquals(before, (now as? Limit)?.kw)
            }
    }

    @Test
    fun withTheMonthsHighestOnEveryWarningIsANewPeak() {
        // As before the switches: the limit is at least the month's highest, so reaching it is billed.
        for (shown in listOf(true, false)) for (goal in listOf(null, 1.0, 5.0)) for (floor in listOf(null, 2.65))
            for (minimum in listOf(null, 2.0)) for (highest in listOf(null, Quarter(at("17:15:00"), 0.725))) {
                val limit = peakLimit(defaults + LimitPart.GOAL, shown, minimum, highest, floor?.let { Floor(it, "A") }, goal) as? Limit ?: continue
                for (kw in listOf(limit.kw, limit.kw + 0.01, 9.0)) assertTrue(isNewPeak(kw, highest?.kw))
            }
    }

    @Test
    fun belowTheMonthsHighestIsNoNewPeak() {
        // The phone test: a goal of 0.3 kW with Month's highest off, 0.5 kW drawn, the month's highest 2.9 kW.
        val highest = Quarter(at("09:45:00"), 0.725)
        val limit = peakLimit(setOf(LimitPart.GOAL), true, null, highest, null, 0.3) as Limit
        assertTrue(isPeakWarning(0.5, limit.kw))
        assertFalse(isNewPeak(0.5, highest.kw))
        // Austria with only the tariff minimum on: 2.1 kW is over its 2.0, but not the month's 2.9.
        assertFalse(isNewPeak(2.1, 2.9))
        assertTrue(isNewPeak(2.9, 2.9))
        // Nothing recorded this month: any quarter is the month's highest.
        assertTrue(isNewPeak(0.1, null))
    }

    @Test
    fun redAtTheLimitItself() {
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
