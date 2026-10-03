package io.github.buerlino.gridload.core

import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppliancesTest {
    private fun at(time: String) = OffsetDateTime.parse("2026-10-03T$time+02:00").toInstant()

    private val kettle = Appliance("Kettle", listOf(Piece(3.0, 2.2)), canWait = false)
    private val dishwasher = Appliance("Dishwasher", listOf(Piece(60.0, 2.0)))

    /** Quarter hours of a base draw of 0.08 kW, plus [extra] kWh in the quarters starting at those times. */
    private fun quarters(from: String, to: String, vararg extra: Pair<String, Double>): List<Quarter> {
        val add = extra.associate { (time, kwh) -> at(time) to kwh }
        return generateSequence(at(from)) { it.plusSeconds(QUARTER_SECONDS) }.takeWhile { it <= at(to) }
            .map { Quarter(it, 0.02 + (add[it] ?: 0.0)) }.toList()
    }

    private fun assertCurve(expected: List<Piece>, measurement: Measurement) {
        val curve = (measurement as Measurement.Result).curve
        assertEquals(expected.size, curve.size, "pieces: $curve")
        expected.zip(curve).forEach { (e, c) ->
            assertEquals(e.min, c.min, 1e-6)
            assertEquals(e.kw, c.kw, 1e-6)
        }
    }

    @Test
    fun appliancesSurviveTheirJson() {
        val list = listOf(kettle, dishwasher.copy(delayMinutes = 60))
        assertEquals(list, parseAppliances(appliancesJson(list)))
        // The defaults: can wait, no start delay.
        assertEquals(Appliance("A", listOf(Piece(1.0, 1.0))), parseAppliances("""[{"name":"A","curve":[{"min":1,"kw":1}]}]""").single())
        assertFailsWith<IllegalArgumentException> { parseAppliances("""[{"name":"A","curve":[]}]""") }
        assertFailsWith<IllegalArgumentException> { parseAppliances("""[{"name":" ","curve":[{"min":1,"kw":1}]}]""") }
        assertFailsWith<IllegalArgumentException> { parseAppliances("""{"prices":[]}""") }
        // A name twice: the rows would share their advice, edits and deletion.
        assertFailsWith<IllegalArgumentException> { parseAppliances(appliancesJson(listOf(kettle, kettle.copy(canWait = true)))) }
        // A last piece at 0 kW gives a variant nothing to stretch; a quiet piece in between is fine.
        assertFailsWith<IllegalArgumentException> { parseAppliances("""[{"name":"A","curve":[{"min":5,"kw":2},{"min":5,"kw":0}]}]""") }
        assertEquals(2, parseAppliances("""[{"name":"A","curve":[{"min":5,"kw":2},{"min":5,"kw":0},{"min":5,"kw":1}]}]""").single().curve.count { it.kw > 0 })
        assertFailsWith<IllegalArgumentException> { parseAppliances("""[{"name":"A","curve":[{"min":1e400,"kw":2}]}]""") }
    }

    @Test
    fun anImportReplacesByNameInPlace() {
        val oven = Appliance("Oven", listOf(Piece(30.0, 2.0)))
        val newKettle = kettle.copy(curve = listOf(Piece(4.0, 2.0)))
        assertEquals(listOf(newKettle, dishwasher, oven), mergeAppliances(listOf(kettle, dishwasher), listOf(oven, newKettle)))
        // An import of a name twice is refused before it gets here, so the result has each name once.
        assertEquals(listOf(kettle), mergeAppliances(listOf(kettle), listOf(kettle)))
    }

    @Test
    fun derivesRunTimeEnergyAndPower() {
        val a = Appliance("A", listOf(Piece(8.0, 1.5), Piece(15.0, 0.2), Piece(4.5, 2.0)))
        assertEquals(27.5, a.curve.minutes, 1e-9)
        assertEquals((12.0 + 3.0 + 9.0) / 60, a.curve.kwh, 1e-9)
        assertEquals(2.0, a.curve.kw)
        assertEquals(2.0 * 1.5 / 60 + 1.0 * 0.2 / 60, a.curve.kwhBetween(6.0, 9.0), 1e-9)
        assertEquals(0.0, a.curve.kwhBetween(-15.0, 0.0), 1e-9)
    }

    @Test
    fun aKettleRunsFromStartToDone() {
        // 0.11 kWh above the base from 10:07 to 10:10: 3 minutes at 2.2 kW.
        val q = quarters("09:45", "10:00", "10:00" to 0.11)
        assertCurve(listOf(Piece(3.0, 2.2)), measure(at("10:07"), at("10:10"), 0.1, null, null, q))
        // Across a boundary: 2 minutes before 10:15 and 1 after.
        val across = quarters("09:45", "10:15", "10:00" to 2.2 * 2 / 60, "10:15" to 2.2 / 60)
        assertCurve(listOf(Piece(2.0, 2.2), Piece(1.0, 2.2)), measure(at("10:13"), at("10:16"), 0.1, null, null, across))
        // Done tapped later: the same energy, spread up to Done.
        assertCurve(listOf(Piece(5.0, 1.32)), measure(at("10:07"), at("10:12"), 0.1, null, null, q))
    }

    @Test
    fun cookingWithSeveralAppliances() {
        // Measured 2026-10-03: rice cooker, two plates and the vent from 11:47:23, Done at 12:20:31.
        // The last quarter is the big plate at a low setting, not a burst at the highest jump.
        val q = listOf(Quarter(at("11:30"), 0.0340), Quarter(at("11:45"), 0.5250), Quarter(at("12:00"), 0.4163), Quarter(at("12:15"), 0.0547))
        val start = at("11:47:23")
        val first = (15 * 60 - 143) / 60.0
        val last = 331 / 60.0
        assertCurve(
            listOf(Piece(first, 0.491 * 60 / first), Piece(15.0, 0.3823 * 4), Piece(last, 0.0207 * 60 / last)),
            measure(start, at("12:20:31"), 0.098, null, null, q),
        )
    }

    @Test
    fun aLongRunIsSpreadWithinItsQuarters() {
        // Started 19:07, Done 21:40. The quiet quarters after the last loud one (20:30) are dropped.
        val q = quarters("18:45", "21:30", "19:00" to 0.24, "19:15" to 0.3, "19:30" to 0.05, "19:45" to 0.0, "20:00" to 0.5, "20:15" to 0.1, "20:30" to 0.15, "20:45" to 0.005)
        assertCurve(
            listOf(Piece(8.0, 1.8), Piece(15.0, 1.2), Piece(15.0, 0.2), Piece(15.0, 0.0), Piece(15.0, 2.0), Piece(15.0, 0.4), Piece(15.0, 0.6)),
            measure(at("19:07"), at("21:40"), 0.08, null, null, q),
        )
    }

    @Test
    fun theBaseIsTheQuarterBeforeElseTheLiveDraw() {
        // The quarter before drew 0.1 kW (0.025 kWh): 0.135 − 0.025 = 0.11 extra, whatever the live draw said.
        val q = listOf(Quarter(at("09:45"), 0.025), Quarter(at("10:00"), 0.135))
        assertCurve(listOf(Piece(3.0, 2.2)), measure(at("10:07"), at("10:10"), 0.5, null, null, q))
        // Without it, the live draw before the start: 0.135 − 0.2 / 4 = 0.085 kWh in 3 minutes.
        assertCurve(listOf(Piece(3.0, 1.7)), measure(at("10:07"), at("10:10"), 0.2, null, null, q.drop(1)))
        // The start's quarter before it beats a busy quarter before (lunch at 0.58 kW): 0.2 kW from 10:00 to 10:07.
        val busy = listOf(Quarter(at("09:45"), 0.145), Quarter(at("10:00"), 0.05 + 0.11))
        assertCurve(listOf(Piece(3.0, 2.2)), measure(at("10:07"), at("10:10"), 0.5, 0.2, null, busy))
    }

    @Test
    fun doneOnABoundaryOrInStartsSecond() {
        // Done at 10:15:00 ends the 10:00 quarter; nothing of 10:15 counts, and nothing is 0 minutes long.
        val q = quarters("09:45", "10:15", "10:00" to 0.11, "10:15" to 0.02)
        assertCurve(listOf(Piece(8.0, 0.11 * 60 / 8)), measure(at("10:07"), at("10:15"), 0.1, null, null, q))
        assertCurve(listOf(Piece(8.0, 0.11 * 60 / 8)), measure(at("10:07"), at("10:15"), 0.1, null, null, q.dropLast(1)))
        // Done in Start's second: nothing measured, and no Infinity to save.
        assertEquals(Measurement.NoDraw, measure(at("10:07:30"), at("10:07:30"), 0.1, null, null, q))
        assertEquals(Measurement.NoDraw, measure(at("10:15"), at("10:15"), 0.1, null, null, q))
        assertEquals(Measurement.NoDraw, measure(at("10:07:30"), at("10:07:30"), 0.1, null, 0.01, q))
    }

    @Test
    fun theDrawAfterDoneDoesntCount() {
        // A kettle-like 2.2 kW from 10:07 to Done at 10:15:20, then the fridge for the rest of 10:15.
        val q = quarters("09:45", "10:15", "10:00" to 2.2 * 8 / 60, "10:15" to 0.02)
        // The energy saved at Done: the base 0.08 kW and the run for 20 s.
        val doneKwh = (0.08 + 2.2) * 20 / 3600
        val expected = listOf(Piece(8.0, 2.2), Piece(20 / 60.0, 2.2))
        assertCurve(expected, measure(at("10:07"), at("10:15:20"), 0.1, null, doneKwh, q))
        // Ready before the recorder saves 10:15.
        assertCurve(expected, measure(at("10:07"), at("10:15:20"), 0.1, null, doneKwh, q.dropLast(1)))
        // Without it (an older measurement, or an estimated projection), the whole quarter counts and waits for 10:30.
        assertCurve(listOf(Piece(8.0, 2.2), Piece(20 / 60.0, 3.6)), measure(at("10:07"), at("10:15:20"), 0.1, null, null, q))
        assertEquals(Measurement.Pending(at("10:30")), measure(at("10:07"), at("10:15:20"), 0.1, null, null, q.dropLast(1)))
    }

    @Test
    fun startAndDoneInOneQuarter() {
        // 10:07 to 10:10 at 2.2 kW over a base of 0.08 kW: no line from the recorder needed.
        val doneKwh = 0.08 * 10 / 60 + 0.11
        assertCurve(listOf(Piece(3.0, 2.2)), measure(at("10:07"), at("10:10"), 0.1, null, doneKwh, quarters("09:30", "09:45")))
        // With the base from the start's own quarter (0.2 kW, Start 7 min in).
        assertCurve(listOf(Piece(3.0, 2.2)), measure(at("10:07"), at("10:10"), 0.5, 0.2, 0.2 * 10 / 60 + 0.11, emptyList()))
    }

    @Test
    fun doneTakesTheEnergyFromAFreshExactReading() {
        fun projection(end: String, time: String, used: Double?) = Projection(1.0, at(end), at(time), used)
        assertEquals(at("10:15:20") to 0.0127, doneAt(at("10:15:20"), projection("10:30", "10:15:18", 0.0127)))
        // The last reading was before the boundary: Done goes back to it.
        assertEquals(at("10:15") to null, doneAt(at("10:15:03"), projection("10:15", "10:14:58", 0.2)))
        assertEquals(at("10:15") to null, doneAt(at("10:15"), projection("10:15", "10:14:57", 0.2)))
        // Done on the boundary, the reading already in the next quarter: Done ends the quarter before.
        assertEquals(at("10:15") to null, doneAt(at("10:15"), projection("10:30", "10:15:00", 0.0)))
        // Estimated, stale (the app was in the background), or no reading.
        assertEquals(at("10:20") to null, doneAt(at("10:20"), projection("10:30", "10:19:58", null)))
        assertEquals(at("10:28") to null, doneAt(at("10:28"), projection("10:30", "10:16", 0.05)))
        assertEquals(at("10:28") to null, doneAt(at("10:28"), projection("10:15", "10:14:58", 0.2)))
        assertEquals(at("10:28") to null, doneAt(at("10:28"), null))
    }

    @Test
    fun aRunAcrossMidnightAndTheMonthsEnd() {
        // 23:40 on 30 Sep to Done at 00:20, 2.4 kW over a base of 0.08 kW, from the recorder's day files.
        val dir = kotlin.io.path.createTempDirectory().toFile()
        dir.resolve("GL260930.CSV").writeText("1790802900,0.0200,1000.0200\n1790803800,0.2200,1000.2400\n1790804700,0.6200,1000.8600\n")
        dir.resolve("GL261001.CSV").writeText("1790805600,0.6200,1001.4800\n1790806500,0.2200,1001.7000\n")
        val recorded = RecorderFiles(dir).load(java.time.LocalDate.of(2026, 9, 1)).quarters
        assertEquals(5, recorded.size)
        val start = OffsetDateTime.parse("2026-09-30T23:40+02:00").toInstant()
        val done = OffsetDateTime.parse("2026-10-01T00:20+02:00").toInstant()
        assertCurve(listOf(Piece(5.0, 2.4), Piece(15.0, 2.4), Piece(15.0, 2.4), Piece(5.0, 2.4)), measure(start, done, 0.1, null, null, recorded))
    }

    @Test
    fun aRunThroughTheNightTheClocksGoBack() {
        // 01:50 summer time to 03:20 winter time on 25 Oct 2026: 2 h 30 min, 1 kW above a base of 0.08 kW.
        val start = OffsetDateTime.parse("2026-10-25T01:50+02:00").toInstant()
        val done = OffsetDateTime.parse("2026-10-25T03:20+01:00").toInstant()
        val q = generateSequence(quarterStart(start).minusSeconds(QUARTER_SECONDS)) { it.plusSeconds(QUARTER_SECONDS) }.takeWhile { it < done }
            .map { qs -> Quarter(qs, 0.02 + minOf(qs.plusSeconds(QUARTER_SECONDS), done).let { e -> maxOf(0L, e.epochSecond - maxOf(qs, start).epochSecond) } / 3600.0) }
            .toList()
        val curve = (measure(start, done, 0.1, null, null, q) as Measurement.Result).curve
        assertEquals(11, curve.size)
        assertEquals(150.0, curve.minutes, 1e-9)
        curve.forEach { assertEquals(1.0, it.kw, 1e-9) }
    }

    @Test
    fun waitsForTheLastQuarterAndNamesGapsAndQuietRuns() {
        val q = quarters("09:45", "10:00", "10:00" to 0.11)
        // Done at 10:20: the 10:15 quarter is due at 10:30.
        assertEquals(Measurement.Pending(at("10:30")), measure(at("10:07"), at("10:20"), 0.1, null, null, q))
        // 10:15 missing, but 10:30 is there: it won't come any more.
        assertEquals(Measurement.Gap, measure(at("10:07"), at("10:20"), 0.1, null, null, q + Quarter(at("10:30"), 0.02)))
        assertEquals(Measurement.NoDraw, measure(at("10:07"), at("10:12"), 0.1, null, null, quarters("09:45", "10:00", "10:00" to 0.005)))
    }

    @Test
    fun aMeasurementUnderWaySurvivesItsJsonAndGivesItsResultAfterDone() {
        val running = Measuring(kettle.copy(curve = emptyList()), at("10:07").epochSecond, 0.1)
        assertEquals(running, parseMeasuring(measuringJson(running)))
        val finished = running.copy(baseKw = 0.2, done = at("10:10").epochSecond, doneKwh = 0.12)
        assertEquals(finished, parseMeasuring(measuringJson(finished)))
        // One saved before doneKwh existed.
        assertEquals(running, parseMeasuring("""{"appliance":{"name":"Kettle","curve":[],"canWait":false},"start":${running.start},"beforeKw":0.1}"""))
        val q = quarters("09:45", "10:00", "10:00" to 0.11)
        assertEquals(null, running.result(q))
        assertCurve(listOf(Piece(3.0, 2.2)), running.copy(done = at("10:10").epochSecond).result(q)!!)
    }

    @Test
    fun otherAmountsOfWaterScaleTheRunTime() {
        assertEquals(1.0, waterShare(1.0, 1.0, 100), 1e-9)
        assertEquals(1.7, waterShare(1.0, 1.7, 100), 1e-9)
        // Half a litre to 80 °C: half the water, 65 of the 85 degrees.
        assertEquals(0.5 * 65 / 85, waterShare(1.0, 0.5, 80), 1e-9)
    }

    @Test
    fun aVariantStretchesOrCutsTheLastPhase() {
        val oven = listOf(Piece(10.0, 2.5), Piece(15.0, 1.0), Piece(5.0, 0.6))
        // Longer: the start stays, the last piece runs on.
        assertEquals(listOf(Piece(10.0, 2.5), Piece(15.0, 1.0), Piece(35.0, 0.6)), oven.withRunTime(60.0))
        // Shorter: cut from the end, across pieces.
        assertEquals(listOf(Piece(10.0, 2.5), Piece(8.0, 1.0)), oven.withRunTime(18.0))
        assertEquals(listOf(Piece(10.0, 2.5), Piece(15.0, 1.0)), oven.withRunTime(25.0))
        assertEquals(listOf(Piece(4.0, 2.5)), oven.withRunTime(4.0))
        assertEquals(oven, oven.withRunTime(30.0))
        // A kettle measured across a quarter boundary: 1.5 L heats 1.5 times as long at the same power.
        val kettle = listOf(Piece(2.0, 2.2), Piece(1.0, 2.2))
        assertEquals(listOf(Piece(2.0, 2.2), Piece(2.5, 2.2)), kettle.withRunTime(3.0 * waterShare(1.0, 1.5, 100)))
        assertEquals(listOf(Piece(1.5, 2.2)), kettle.withRunTime(1.5))
        assertFailsWith<IllegalArgumentException> { kettle.withRunTime(0.0) }
        // A variant is a plain curve: cut, then stretched again, it stretches what's left, not the original's last phase.
        assertEquals(listOf(Piece(10.0, 2.5), Piece(20.0, 1.0)), oven.withRunTime(18.0).withRunTime(30.0))
    }

    @Test
    fun theFileKeepsTheAppliances() {
        val file = kotlin.io.path.createTempDirectory().toFile().resolve("appliances.json")
        val store = ApplianceFile(file)
        assertEquals(emptyList(), store.load())
        store.save(listOf(kettle, dishwasher))
        assertEquals(listOf(kettle, dishwasher), store.load())
    }

    private fun peak(projectionKw: Double, drawKw: Double, line: Double?, now: String = "10:07") =
        PeakNow(Projection(projectionKw, quarterStart(at(now)).plusSeconds(QUARTER_SECONDS), at(now)), drawKw, line)

    @Test
    fun theKettleFitsWhenItsQuarterStaysBelowTheWarning() {
        // Line 4.0, so the warning is at 3.6. The kettle adds 2.2 × 3 / 15 = 0.44 to its quarter.
        assertTrue(kettle.fitsPeak(at("10:07"), peak(3.1, 0.5, 4.0)))
        assertFalse(kettle.fitsPeak(at("10:07"), peak(3.2, 0.5, 4.0)))
        // In a fresh quarter the house draws 0.5 now.
        assertTrue(kettle.fitsPeak(at("10:15"), peak(3.2, 0.5, 4.0)))
        assertTrue(kettle.fitsPeak(at("10:07"), peak(9.0, 9.0, null)))
        // A run of a full hour adds its whole power to each quarter: 0.5 + 2.0 against 3.6.
        assertTrue(dishwasher.fitsPeak(at("10:15"), peak(3.2, 0.5, 4.0)))
        assertFalse(dishwasher.fitsPeak(at("10:15"), peak(3.2, 1.7, 4.0)))
    }

    @Test
    fun theQuarterLoadsAreTheHouseAndTheRunPerQuarter() {
        // Started at 10:07: this quarter is the projection, the next the draw now.
        val loads = dishwasher.quarterLoads(at("10:07"), peak(1.0, 0.5, null))
        assertEquals(listOf(at("10:00"), at("10:15"), at("10:30"), at("10:45"), at("11:00")), loads.map { it.start })
        assertEquals(listOf(1.0, 0.5, 0.5, 0.5, 0.5), loads.map { it.houseKw })
        // 2.0 kW for 8 of the 15 minutes, then whole quarters, then the last 7 minutes.
        listOf(2.0 * 8 / 15, 2.0, 2.0, 2.0, 2.0 * 7 / 15).zip(loads.map { it.addedKw }).forEach { (want, got) -> assertEquals(want, got, 1e-9) }
        assertEquals(2.5, loads[1].kw, 1e-9)
    }

    /** Today's quarter-hour slots at 0.30, with 0.15 from 10:00 to 16:00. */
    private val slots = (0 until 96).map { i ->
        val start = OffsetDateTime.parse("2026-10-03T00:00+02:00").plusMinutes(15L * i)
        PriceSlot(start, start.plusMinutes(15), if (i in 40 until 64) 0.15 else 0.30)
    }

    @Test
    fun theRunPriceWeighsByEnergy() {
        assertEquals(0.30, dishwasher.runPrice(at("06:00"), slots)!!, 1e-9)
        // Half the hour before 10:00.
        assertEquals(0.225, dishwasher.runPrice(at("09:30"), slots)!!, 1e-9)
        // Most of the energy in the first half: 0.9 × 0.30 + 0.1 × 0.15.
        val heavyFirst = Appliance("D", listOf(Piece(30.0, 1.8), Piece(30.0, 0.2)))
        assertEquals(0.285, heavyFirst.runPrice(at("09:30"), slots)!!, 1e-9)
        // Past the known prices.
        assertEquals(null, dishwasher.runPrice(at("23:30"), slots))
    }

    @Test
    fun aRunThatCanWaitWaitsForTheCheapestThird() {
        val calm = peak(0.5, 0.5, 4.0, now = "06:00")
        // Thirds of 0.15 to 0.30: cheap up to 0.20. From 09:45 a quarter of the run is at 0.30: 0.1875.
        assertEquals(Advice.Cheaper(at("09:45")), advise(dishwasher, at("06:00"), calm, slots))
        // With a 1 h start delay, the steps are 07:00, 08:00, ...: 10:00 is the first cheap one.
        assertEquals(Advice.Cheaper(at("10:00")), advise(dishwasher.copy(delayMinutes = 60), at("06:00"), calm, slots))
        assertEquals(Advice.Ok(), advise(dishwasher, at("11:00"), peak(0.5, 0.5, 4.0, now = "11:00"), slots))
        // A kettle doesn't wait for the price; nor does anything on a flat day.
        assertEquals(Advice.Ok(), advise(kettle, at("06:00"), calm, slots))
        assertEquals(Advice.Ok(), advise(dishwasher, at("06:00"), calm, slots.map { it.copy(price = 0.2274) }))
    }

    @Test
    fun pastTheKnownPricesOnlyThePeakCounts() {
        assertEquals(Advice.Ok(pricesMissing = true), advise(dishwasher, at("23:30"), peak(0.5, 0.5, 4.0, now = "23:30"), slots))
        // A kettle doesn't wait for the price, so nothing is missing.
        assertEquals(Advice.Ok(), advise(kettle, at("23:30"), peak(0.5, 0.5, 4.0, now = "23:30"), slots))
    }

    @Test
    fun aStartDelayWhenThePricesEndEarly() {
        // Cheap from 22:00 to midnight, and nothing known after it: with a 1 h delay, 22:00 is "Delay 2 h".
        val evening = slots.map { if (it.start.hour >= 22) it.copy(price = 0.15) else it }
        val delayed = dishwasher.copy(delayMinutes = 60)
        assertEquals(Advice.Cheaper(at("22:00")), advise(delayed, at("20:00"), peak(0.5, 0.5, 4.0, now = "20:00"), evening))
        // At 22:30 a delay of 1 h runs past midnight: only now competes.
        assertEquals(Advice.Ok(), advise(delayed, at("22:30"), peak(0.5, 0.5, 4.0, now = "22:30"), evening))
        assertEquals(Advice.Ok(pricesMissing = true), advise(delayed, at("23:30"), peak(0.5, 0.5, 4.0, now = "23:30"), evening))
    }

    @Test
    fun withoutPricesOnlyANewPeakIsSaid() {
        // None fetched, or the fetch failed: no advice for one that can wait ("–"), but a new peak is still said.
        assertEquals(null, advise(dishwasher, at("06:00"), peak(0.5, 0.5, 4.0, now = "06:00"), emptyList()))
        assertEquals(Advice.NewPeak(at("06:15")), advise(dishwasher, at("06:00"), peak(3.2, 0.5, 4.0, now = "06:00"), emptyList()))
        // One that can't wait is judged by the peak alone, as always.
        assertEquals(Advice.Ok(), advise(kettle, at("06:00"), peak(0.5, 0.5, 4.0, now = "06:00"), emptyList()))
        assertEquals(Advice.NewPeak(at("10:15")), advise(kettle, at("10:07"), peak(3.2, 0.5, 4.0), emptyList()))
    }

    @Test
    fun withoutALineOnlyThePriceCounts() {
        assertEquals(Advice.Cheaper(at("09:45")), advise(dishwasher, at("06:00"), peak(9.0, 9.0, null, now = "06:00"), slots))
        assertEquals(Advice.Ok(), advise(kettle, at("06:00"), peak(9.0, 9.0, null, now = "06:00"), slots))
    }

    @Test
    fun theNightTheClocksGoBackHas100PriceSlots() {
        // 25 Oct 2026: 0.30, cheap 0.15 from 11:00 to 15:00 winter time.
        val zone = java.time.ZoneId.of("Europe/Zurich")
        val midnight = java.time.LocalDate.of(2026, 10, 25).atStartOfDay(zone)
        val dst = generateSequence(midnight) { it.plusMinutes(15) }.takeWhile { it < midnight.plusDays(1) }.map {
            PriceSlot(it.toOffsetDateTime(), it.plusMinutes(15).toOffsetDateTime(), if (it.hour in 11 until 15) 0.15 else 0.30)
        }.toList()
        assertEquals(100, dst.size)
        val now = OffsetDateTime.parse("2026-10-25T01:50+02:00").toInstant()
        val calm = PeakNow(Projection(0.5, quarterStart(now).plusSeconds(QUARTER_SECONDS), now), 0.5, 4.0)
        // Cheap up to 0.20: from 10:45, 15 min at 0.30 and 45 at 0.15 is 0.1875.
        assertEquals(Advice.Cheaper(OffsetDateTime.parse("2026-10-25T10:45+01:00").toInstant()), advise(dishwasher, now, calm, dst))
        // Through the repeated hour: 02:30 summer time to 02:30 winter time is one hour.
        assertEquals(0.30, dishwasher.runPrice(OffsetDateTime.parse("2026-10-25T02:30+02:00").toInstant(), dst)!!, 1e-9)
    }

    @Test
    fun aNewPeakSaysWhenItFits() {
        // The kettle sets a peak in this busy quarter (3.2 + 0.44 ≥ 3.6), but fits from 10:15 at 0.5 kW.
        assertEquals(Advice.NewPeak(at("10:15")), advise(kettle, at("10:07"), peak(3.2, 0.5, 4.0), slots))
        // Drawing 2.0 kW all along against a 1.0 line: no quarter fits.
        assertEquals(Advice.NewPeak(null), advise(kettle, at("10:07"), peak(2.0, 2.0, 1.0), slots))
        // The dishwasher at 06:00: a new peak now, and the first start that's cheap and fits is 09:45.
        assertEquals(Advice.NewPeak(at("09:45")), advise(dishwasher, at("06:00"), peak(3.2, 0.5, 4.0, now = "06:00"), slots))
    }

    @Test
    fun whenEveryCheaperStartSetsAPeakNowIsBest() {
        // 10 min at 2 kW adds 1.33 to a quarter: it fits this one (projected 0.5), but no later one at 3.0 kW.
        val short = Appliance("D", listOf(Piece(10.0, 2.0)))
        assertEquals(Advice.Ok(), advise(short, at("06:00"), peak(0.5, 3.0, 4.0, now = "06:00"), slots))
    }
}
