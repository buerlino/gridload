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
    }

    @Test
    fun anImportReplacesByNameInPlace() {
        val oven = Appliance("Oven", listOf(Piece(30.0, 2.0)))
        val newKettle = kettle.copy(curve = listOf(Piece(4.0, 2.0)))
        assertEquals(listOf(newKettle, dishwasher, oven), mergeAppliances(listOf(kettle, dishwasher), listOf(oven, newKettle)))
    }

    @Test
    fun derivesRunTimeEnergyAndPower() {
        val a = Appliance("A", listOf(Piece(8.0, 1.5), Piece(15.0, 0.2), Piece(4.5, 2.0)))
        assertEquals(27.5, a.minutes, 1e-9)
        assertEquals((12.0 + 3.0 + 9.0) / 60, a.kwh, 1e-9)
        assertEquals(2.0, a.kw)
        assertEquals(2.0 * 1.5 / 60 + 1.0 * 0.2 / 60, a.curve.kwhBetween(6.0, 9.0), 1e-9)
        assertEquals(0.0, a.curve.kwhBetween(-15.0, 0.0), 1e-9)
    }

    @Test
    fun aKettleIsItsEnergyOverItsJump() {
        // 0.11 kWh above the base at a 2.2 kW jump: 3 minutes, from 10:07.
        val q = quarters("09:45", "10:00", "10:00" to 0.11)
        assertCurve(listOf(Piece(3.0, 2.2)), measure(at("10:07"), at("10:12"), 0.1, 2.3, q))
        // Across a boundary: 2 minutes before 10:15 and 1 after, still 3 minutes at 2.2 kW.
        val across = quarters("09:45", "10:15", "10:00" to 2.2 * 2 / 60, "10:15" to 2.2 / 60)
        assertCurve(listOf(Piece(2.0, 2.2), Piece(1.0, 2.2)), measure(at("10:13"), at("10:17"), 0.1, 2.3, across))
    }

    @Test
    fun aLongRunIsSpreadWithinItsQuarters() {
        // Started 19:07, Done 21:40. The last loud quarter (20:30) runs at the jump; the quiet ones after it are dropped.
        val q = quarters("18:45", "21:30", "19:00" to 0.24, "19:15" to 0.3, "19:30" to 0.05, "19:45" to 0.0, "20:00" to 0.5, "20:15" to 0.1, "20:30" to 0.15, "20:45" to 0.005)
        assertCurve(
            listOf(Piece(8.0, 1.8), Piece(15.0, 1.2), Piece(15.0, 0.2), Piece(15.0, 0.0), Piece(15.0, 2.0), Piece(15.0, 0.4), Piece(4.5, 2.0)),
            measure(at("19:07"), at("21:40"), 0.08, 2.08, q),
        )
    }

    @Test
    fun theBaseIsTheQuarterBeforeElseTheLiveDraw() {
        // The quarter before drew 0.1 kW (0.025 kWh): 0.135 − 0.025 = 0.11 extra, whatever the live draw said.
        val q = listOf(Quarter(at("09:45"), 0.025), Quarter(at("10:00"), 0.135))
        assertCurve(listOf(Piece(3.0, 2.2)), measure(at("10:07"), at("10:12"), 0.5, 2.7, q))
        // Without it, the live draw before the start: 0.135 − 0.2 / 4.
        assertCurve(listOf(Piece(85.0 / 2200 * 60, 2.2)), measure(at("10:07"), at("10:12"), 0.2, 2.4, q.drop(1)))
    }

    @Test
    fun withoutAJumpTheLastQuarterIsSpreadToo() {
        val q = quarters("09:45", "10:00", "10:00" to 0.11)
        assertCurve(listOf(Piece(8.0, 0.825)), measure(at("10:07"), at("10:12"), 0.1, 0.1, q))
    }

    @Test
    fun waitsForTheLastQuarterAndNamesGapsAndQuietRuns() {
        val q = quarters("09:45", "10:00", "10:00" to 0.11)
        // Done at 10:20: the 10:15 quarter is due at 10:30.
        assertEquals(Measurement.Pending(at("10:30")), measure(at("10:07"), at("10:20"), 0.1, 2.3, q))
        // 10:15 missing, but 10:30 is there: it won't come any more.
        assertEquals(Measurement.Gap, measure(at("10:07"), at("10:20"), 0.1, 2.3, q + Quarter(at("10:30"), 0.02)))
        assertEquals(Measurement.NoDraw, measure(at("10:07"), at("10:12"), 0.1, 2.3, quarters("09:45", "10:00", "10:00" to 0.005)))
    }

    @Test
    fun aMeasurementUnderWaySurvivesItsJsonAndGivesItsResultAfterDone() {
        val running = Measuring(kettle.copy(curve = emptyList()), at("10:07").epochSecond, 0.1, 2.3)
        assertEquals(running, parseMeasuring(measuringJson(running)))
        val q = quarters("09:45", "10:00", "10:00" to 0.11)
        assertEquals(null, running.result(q))
        assertCurve(listOf(Piece(3.0, 2.2)), running.copy(done = at("10:12").epochSecond).result(q)!!)
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
        PeakNow(Projection(projectionKw, quarterStart(at(now)).plusSeconds(QUARTER_SECONDS), estimated = false), drawKw, line)

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
        assertEquals(Advice.Ok(pricesEnd = at("23:59").plusSeconds(60)), advise(dishwasher, at("23:30"), peak(0.5, 0.5, 4.0, now = "23:30"), slots))
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
