package io.github.buerlino.gridload.core

import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The draw ahead and the projection on the meter's real readings ([MeterTrace]): what the peak
 * window, the alarm and every appliance's OK/WAIT rest on.
 */
class DrawAheadTest {
    private fun at(time: String) = Instant.parse("2026-10-05T${time}Z")

    // The household's appliances as measured (exported 4 Oct 2026).
    private val cooking = Appliance("Cooking", listOf(Piece(12.617, 2.335), Piece(15.0, 1.5292), Piece(5.517, 0.2251)), canWait = false)
    private val kettle1L = Appliance("Kettle 1 L", listOf(Piece(3.067, 1.7707)), canWait = false)
    private val dishwasher = Appliance(
        "Dishwasher 65°C",
        listOf(Piece(3.05, 0.023), Piece(15.0, 1.3267), Piece(15.0, 0.8859), Piece(15.0, 0.0), Piece(15.0, 0.7047), Piece(15.0, 0.3359)),
    )

    /**
     * 5 Oct 2026, CKW, 19:13:49 local (17:13:49 UTC): the 19:00 quarter showed 480 W, the 19:15
     * column 1930 W of house; the recorder saved 18:45 at 1060 W, 19:00 at 372 W (0.093 kWh from
     * the register at 10560.298) and 19:15 at 461 W. A fit: the night's 85 W, 1.95 kW for the two
     * minutes to 19:13:48, then 0.55 kW.
     */
    private val observed = MeterTrace(
        10560.298 - 1.06 * 16 / 60,
        listOf(at("16:44:00") to 1.06, at("17:00:00") to 0.085, at("17:11:47") to 1.95, at("17:13:48") to 0.55, at("17:15:00") to 0.461),
    )

    @Test
    fun theObservedCaseWasABurstTheAverageCarriedIntoTheNextQuarters() {
        assertEquals(0.093, observed.quarterKwh(at("17:00:00")), 0.0005)
        val app = observed.App()
        val p = app.run(at("17:10:04"), at("17:13:49"))
        // As on the phone: exact (no "Estimated"), 480 W now, 1.93 kW assumed ahead.
        assertFalse(p.estimated)
        assertEquals(0.48, p.kw, 0.01)
        assertEquals(1.93, p.aheadKw, 0.02)
        // The 19:15 column: the house at the draw ahead, 1.47 kW above what it really drew.
        val peak = PeakNow(p, 2.87, 2.87)
        val loads = kettle1L.quarterLoads(at("17:13:49"), peak)
        assertEquals(listOf(at("17:00:00"), at("17:15:00")), loads.map { it.start })
        assertEquals(2.16, loads[1].kw, 0.03)
        assertEquals(1.47, loads[1].houseKw - observed.quarterKwh(at("17:15:00")) * 4, 0.02)
        // On 1.93 kW of house, Cooking's and the dishwasher's heaviest quarters pass 2.87 at every
        // start in the 24 hours: "Sets a new peak right now".
        assertEquals(Advice.OverLimit(null, newPeak = true), advise(cooking, at("17:13:49"), peak, emptyList(), spot = false))
        assertEquals(Advice.OverLimit(null, newPeak = true), advise(dishwasher.copy(canWait = false), at("17:13:49"), peak, emptyList(), spot = false))
        // In a glance (the app just opened, no 2-minute average yet) the draw ahead is this
        // quarter so far, 0.08 kWh in 13.8 minutes, and both fit.
        val glance = observed.App().run(at("17:13:44"), at("17:13:49"))
        assertEquals(Ahead.QUARTER, glance.aheadFrom)
        assertEquals(0.36, glance.aheadKw, 0.02)
        assertEquals(Advice.Ok(), advise(cooking, at("17:13:49"), PeakNow(glance, 2.87, 2.87), emptyList(), spot = false))
        assertEquals(Advice.Ok(), advise(dishwasher.copy(canWait = false), at("17:13:49"), PeakNow(glance, 2.87, 2.87), emptyList(), spot = false))
        // Two minutes after the burst both are fine again.
        val later = PeakNow(app.run(at("17:13:54"), at("17:15:49")), 2.87, 2.87)
        assertEquals(Advice.Ok(), advise(cooking, at("17:15:49"), later, emptyList(), spot = false))
        assertEquals(Advice.Ok(), advise(dishwasher.copy(canWait = false), at("17:15:49"), later, emptyList(), spot = false))
    }

    @Test
    fun realReadingsProjectTheRecordedQuarter() {
        // 5 Oct 2026, 20:34:40 to 20:44:55 local: /api/v1/report polled every 5 s, as the app does.
        // The recorder's line: 0.2635 kWh (1.054 kW) from the register at 10561.4775.
        val readings = javaClass.getResource("/whatwatt-readings-2026-10-05.csv")!!.readText().lines().drop(1).filter { it.isNotBlank() }
            .map { it.split(',').let { (time, kw, kwh) -> Triple(Instant.parse(time), kw.toDouble(), kwh.toDouble()) } }
        val average = DrawAverage()
        val projector = QuarterProjector()
        val start = at("18:30:00") to 10561.4775
        var last: Projection? = null
        for ((time, kw, kwh) in readings) {
            val avg = average.add(time, kwh)
            last = projector.project(time, kwh, avg ?: kw, start)
            // A steady ~1 kW: the 2-minute average tracks the readings, and the quarter stays within 0.06 kW.
            if (avg != null) {
                assertEquals(1.0, avg, 0.13)
                assertEquals(1.054, last.kw, 0.06)
            }
        }
        assertFalse(last!!.estimated)
        assertEquals(1.054, last.kw, 0.005)
    }

    @Test
    fun noTraceWithoutABurstGivesThatDrawAhead() {
        // Any draw up to 1 kW, changing every 5 s to 2 min, through the app's polling: the draw
        // ahead never passes 1 kW by more than the register's 0.001 kWh step and a second of
        // time stamp over the minute it needs, so 1.93 kW needs that much really drawn.
        val random = Random(5)
        repeat(100) {
            var t = at("17:00:00")
            val segments = mutableListOf<Pair<Instant, Double>>()
            while (t < at("17:30:00")) {
                segments += t to random.nextDouble(0.0, 1.0)
                t = t.plusSeconds(random.nextLong(5, 120))
            }
            val meter = MeterTrace(10560.0 + random.nextDouble(), segments, phase = random.nextDouble(0.0, 4.25))
            val app = meter.App()
            var s = at("17:00:05")
            while (s < at("17:30:00")) {
                val (_, p) = app.poll(s)
                assertTrue(p.aheadKw <= 1.0 * (1 + 1 / 60.0) + 3.6 / 60, "${p.aheadKw} at $s")
                s = s.plusSeconds(5)
            }
        }
    }

    @Test
    fun theAverageIsTheWindowsTrueAverageWithinTheRegistersStep() {
        // Against the energy truly drawn between the window's first reading and the last: off by
        // at most the 0.001 kWh step and a second of time stamp, so at the 1-minute minimum
        // 0.06 kW + 1.7%, at the full 2 minutes 0.03 kW + 0.8%.
        val random = Random(7)
        var worst = 0.0
        repeat(100) {
            var t = at("17:00:00")
            val segments = mutableListOf<Pair<Instant, Double>>()
            while (t < at("17:20:00")) {
                segments += t to random.nextDouble(0.05, 3.0)
                t = t.plusSeconds(random.nextLong(5, 200))
            }
            val meter = MeterTrace(10560.0 + random.nextDouble(), segments, phase = random.nextDouble(0.0, 4.25))
            val average = DrawAverage()
            val seen = mutableListOf<MeterTrace.Reading>()
            var s = 5.0
            while (s < 1200) {
                val r = meter.latest(s)
                if (seen.lastOrNull()?.time != r.time) seen += r
                val avg = average.add(r.time, r.kwh)
                val first = seen.first { r.time.epochSecond - it.time.epochSecond <= AVERAGE_SECONDS }
                val span = (r.time.epochSecond - first.time.epochSecond).toDouble()
                if (avg == null) {
                    assertTrue(span < 60)
                } else {
                    val truth = (meter.exactKwh(r.at) - meter.exactKwh(first.at)) / (r.at - first.at) * 3600
                    assertTrue(span >= 60)
                    assertTrue(kotlin.math.abs(avg - truth) <= 3.6 / span + truth / span + 1e-9, "$avg against $truth over $span s")
                    worst = maxOf(worst, avg - truth)
                }
                s += 5
            }
        }
        assertTrue(worst in 0.0..0.12)
    }

    /** Readings every 5 s at 0.5 kW from 16:00:00 to 16:02:00, with an exact register. */
    private fun averageAfterTwoMinutesAtHalfAKw(): DrawAverage {
        val average = DrawAverage()
        for (s in 0L..120L step 5) average.add(at("16:00:00").plusSeconds(s), 100.0 + 0.5 * s / 3600)
        return average
    }

    @Test
    fun afterAPauseOfUpToTwoMinutesTheAverageSpansIt() {
        // In the background the house drew 3 kW. Up to 2 minutes later the average covers the
        // pause as the register counted it; after that, nothing is left and the latest reading
        // stands in until the readings span a minute again.
        val register = 100.0 + 0.5 * 120 / 3600
        fun after(pause: Long) = averageAfterTwoMinutesAtHalfAKw().add(at("16:02:00").plusSeconds(pause), register + 3.0 * pause / 3600)
        assertEquals((0.5 * 90 + 3.0 * 30) / 120, after(30)!!, 1e-9)
        assertEquals((0.5 * 30 + 3.0 * 90) / 120, after(90)!!, 1e-9)
        assertEquals(3.0, after(119)!!, 1e-9)
        assertNull(after(121))
        assertNull(after(600))
    }

    @Test
    fun aFrozenReportFreezesTheAverage() {
        // The same reading again and again (an unchanged report.id) gives the same answer, with
        // nothing to say it's old.
        val average = averageAfterTwoMinutesAtHalfAKw()
        val again = (1..24).map { average.add(at("16:02:00"), 100.0 + 0.5 * 120 / 3600) }
        assertTrue(again.all { it != null && kotlin.math.abs(it - 0.5) < 1e-9 })
    }

    @Test
    fun theMetersClockJumpingAheadUnderestimatesForTwoMinutesAtMost() {
        // 2 kW throughout; at 16:02:05 the meter's clock jumps a minute ahead.
        val average = DrawAverage()
        val kwh = { s: Long -> 100.0 + 2.0 * s / 3600 }
        for (s in 0L..120L step 5) average.add(at("16:00:00").plusSeconds(s), kwh(s))
        // Real 60 s counted as 120 s: half the draw.
        assertEquals(1.0, average.add(at("16:03:05"), kwh(125))!!, 1e-9)
        var result: Double? = null
        for (s in 130L..245L step 5) result = average.add(at("16:01:00").plusSeconds(s), kwh(s))
        assertEquals(2.0, result!!, 1e-9)
        // Back in time: another meter or a reset, so it starts over.
        assertNull(average.add(at("16:01:00"), kwh(250)))
    }

    @Test
    fun aReadingAfterTheBoundaryIsNeverBelowTheRecordersStart() {
        // The recorder interpolates between the readings either side of the boundary, so every
        // reading from the one after it on is at or above the line's register, rounded to 4
        // decimals or not: the projection is exact from the first reading after the line is copied.
        val random = Random(11)
        repeat(50) {
            var t = at("16:55:00")
            val segments = mutableListOf<Pair<Instant, Double>>()
            while (t < at("17:50:00")) {
                segments += t to random.nextDouble(0.03, 4.0)
                t = t.plusSeconds(random.nextLong(5, 300))
            }
            val meter = MeterTrace(10560.0 + random.nextDouble(), segments, phase = random.nextDouble(0.0, 4.25))
            val lines = meter.lines(3600.0)
            assertEquals(listOf(at("17:15:00"), at("17:30:00"), at("17:45:00")), lines.keys.sorted())
            val app = meter.App()
            var s = at("17:00:05")
            while (s < at("17:50:00")) {
                val (r, p) = app.poll(s)
                lines[quarterStart(r.time)]?.let { assertTrue(r.kwh >= it) }
                val start = quarterStart(r.time)
                if (start in lines && s >= start.plusSeconds(20)) {
                    assertFalse(p.estimated)
                    // Within the register's step and the recorder's interpolation across the
                    // boundary: a few Wh, 0.016 kW on the quarter.
                    assertEquals(meter.exactKwh(r.at) - meter.exactKwh(start), p.usedKwh!!, 0.004)
                }
                s = s.plusSeconds(5)
            }
        }
    }

    @Test
    fun theTimeLeftIsAlwaysWithinTheQuarter() {
        val projector = QuarterProjector()
        // A reading on the boundary starts the quarter: nothing used, the draw ahead for all 15 minutes.
        val onBoundary = projector.project(at("16:15:00"), 100.0, 1.2, at("16:15:00") to 100.0)
        assertEquals(at("16:15:00"), onBoundary.start)
        assertEquals(1.2, onBoundary.kw, 1e-9)
        // A second before the end: what was used, plus one second.
        val last = projector.project(at("16:29:59"), 100.3, 1.2, at("16:15:00") to 100.0)
        assertEquals(at("16:15:00"), last.start)
        assertEquals((0.3 + 1.2 / 3600) * 4, last.kw, 1e-9)
    }

    @Test
    fun withoutTheRecordersStartAKettleBeforeTheFirstReadingIsMissed() {
        // The whatwatt restarted, so no line: a kettle at 2 kW from 17:00 to 17:03, the app
        // opened at 17:05 on 0.1 kW. The time before the first reading is assumed at the draw
        // then, so the quarter is 0.4 kW low, and says it's estimated.
        val meter = MeterTrace(100.0, listOf(at("17:00:00") to 2.1, at("17:03:00") to 0.1))
        val projector = QuarterProjector()
        val r = meter.latest(at("17:05:00"))
        val p = projector.project(r.time, r.kwh, r.kw, null)
        assertTrue(p.estimated)
        assertEquals(meter.quarterKwh(at("17:00:00")) * 4 - 0.4, p.kw, 0.01)
    }

    @Test
    fun aClockOffsetAtTheBoundaryCountsTheHouseOnce() {
        // The projection is on the meter's clock, an appliance's start on the phone's. Seconds
        // apart across a boundary, each quarter of the run still gets the house once: the
        // ending quarter is left out, or counted at the draw ahead for its last second.
        val ahead = 0.5
        val ending = PeakNow(Projection(1.8, at("17:15:00"), at("17:14:58"), ahead, 0.4), 2.87)
        val phoneAhead = kettle1L.quarterLoads(at("17:15:01"), ending)
        assertEquals(listOf(at("17:15:00")), phoneAhead.map { it.start })
        assertEquals(ahead, phoneAhead[0].houseKw)
        val begun = PeakNow(Projection(0.6, at("17:30:00"), at("17:15:02"), ahead, 0.0), 2.87)
        val phoneBehind = kettle1L.quarterLoads(at("17:14:59"), begun)
        assertEquals(listOf(at("17:00:00"), at("17:15:00")), phoneBehind.map { it.start })
        assertEquals(listOf(ahead, 0.6), phoneBehind.map { it.houseKw })
        assertEquals(1.7707 * 4 / 3600, phoneBehind[0].addedKw, 1e-9)
    }

    @Test
    fun rightAfterOpeningTheDrawAheadIsTheQuarterSoFar() {
        val start = at("17:00:00") to 100.0
        assertEquals(1.5 to Ahead.AVERAGE, drawAhead(true, 1.5, 2.2, at("17:05:00"), 100.05, start))
        // Until the readings span a minute: 0.05 kWh in the quarter's first 5 minutes.
        val (kw, from) = drawAhead(true, null, 2.2, at("17:05:00"), 100.05, start)
        assertEquals(0.6, kw, 1e-9)
        assertEquals(Ahead.QUARTER, from)
        // The latest reading in the quarter's first minute, or without the recorder's line for it.
        assertEquals(2.2 to Ahead.LATEST, drawAhead(true, null, 2.2, at("17:00:50"), 100.01, start))
        assertEquals(2.2 to Ahead.LATEST, drawAhead(true, null, 2.2, at("17:20:00"), 100.2, start))
        assertEquals(2.2 to Ahead.LATEST, drawAhead(true, null, 2.2, at("17:05:00"), 100.05, null))
        // Latest reading chosen in Settings.
        assertEquals(2.2 to Ahead.LATEST, drawAhead(false, 1.5, 2.2, at("17:05:00"), 100.05, start))
    }

    @Test
    fun aGlanceAtACyclingHobDoesntTurnRed() {
        // 1 kW steady and a 2 kW plate on 30 s, off 30 s: the quarter is 2.0 kW. Opened at 17:05
        // with the plate on, the latest reading projects 2.7 kW, past a 2.5 limit; the quarter so
        // far doesn't.
        val plate = (0 until 15).flatMap { listOf(at("17:00:00").plusSeconds(it * 60L) to 3.0, at("17:00:30").plusSeconds(it * 60L) to 1.0) }
        val hob = MeterTrace(100.0, listOf(at("16:44:00") to 1.0) + plate)
        assertEquals(2.0, hob.quarterKwh(at("17:00:00")) * 4, 1e-9)
        val glance = hob.App().run(at("17:04:58"), at("17:05:03"))
        assertEquals(Ahead.QUARTER, glance.aheadFrom)
        assertEquals(2.0, glance.kw, 0.05)
        assertFalse(isPeakWarning(glance.kw, 2.5))
        val latest = hob.App(averaged = false).run(at("17:04:58"), at("17:05:03"))
        assertEquals(2.7, latest.kw, 0.05)
        assertTrue(isPeakWarning(latest.kw, 2.5))
    }

    @Test
    fun aReadingThatDoesntMoveOnFor30SecondsIsStale() {
        val freshness = Freshness()
        assertFalse(freshness.stale(at("17:00:00")))
        // A new reading with each poll keeps it fresh, though the meter's clock is 2 minutes behind.
        for (s in 0L..120L step 5) {
            freshness.seen(at("16:58:00").plusSeconds(s), at("17:00:00").plusSeconds(s))
            assertFalse(freshness.stale(at("17:00:00").plusSeconds(s)))
        }
        // The same report again and again: old 30 s after it last moved on.
        for (s in 125L..150L step 5) freshness.seen(at("17:00:00"), at("17:00:00").plusSeconds(s))
        assertFalse(freshness.stale(at("17:02:29")))
        assertTrue(freshness.stale(at("17:02:30")))
        // A new one is current again; back from the background, the last one is old before anything is read.
        freshness.seen(at("17:00:04"), at("17:02:35"))
        assertFalse(freshness.stale(at("17:02:35")))
        assertTrue(freshness.stale(at("17:03:05")))
        // A meter that sends no time can't be judged.
        val noTime = Freshness()
        noTime.seen(null, at("17:00:00"))
        assertFalse(noTime.stale(at("18:00:00")))
    }

    @Test
    fun aFrozenReportKeepsItsQuarterAsNow() {
        // The same reading polled minutes later gives the same projection, still for its own
        // quarter, and the advice puts the draw ahead in every quarter as if current: nothing in
        // the projection says it's old. [Freshness] does, so the app drops it.
        val projector = QuarterProjector()
        val start = at("17:00:00") to 100.0
        val p = projector.project(at("17:14:50"), 100.2, 1.5, start)
        assertEquals(p, projector.project(at("17:14:50"), 100.2, 1.5, start))
        val loads = kettle1L.quarterLoads(at("17:20:00"), PeakNow(p, 2.87))
        assertEquals(listOf(at("17:15:00")), loads.map { it.start })
        assertEquals(1.5, loads[0].houseKw)
    }

    @Test
    fun theChipAndThePreviewReadTheSameQuarters() {
        // OverLimit exactly when a quarter of the preview reaches the limit, "sets a new peak"
        // exactly when one also reaches the month's highest; and a red window makes every
        // appliance wait, since the preview's first quarter is the window's projection.
        for (appliance in listOf(kettle1L, cooking, dishwasher.copy(canWait = false))) for (second in 0L until 900L step 37)
            for (house in listOf(0.1, 0.6, 0.9, 1.5, 2.9)) for (ahead in listOf(0.1, 0.7, 1.9)) for (highest in listOf(null, 2.5, 3.2)) {
                val now = at("17:00:00").plusSeconds(second)
                val peak = PeakNow(Projection(house, at("17:15:00"), now, ahead, 0.0), 2.87, highest)
                val loads = appliance.quarterLoads(now, peak)
                val warns = loads.filter { isPeakWarning(it.kw, 2.87) }
                val advice = advise(appliance, now, peak, emptyList(), spot = false)
                assertEquals(house, loads.first().houseKw)
                assertEquals(warns.isNotEmpty(), advice is Advice.OverLimit)
                if (advice is Advice.OverLimit) assertEquals(warns.any { isNewPeak(it.kw, highest) }, advice.newPeak)
                if (isPeakWarning(house, 2.87)) assertTrue(advice is Advice.OverLimit)
            }
    }
}
