package io.github.buerlino.gridload.core

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.Month
import java.time.OffsetDateTime
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeakTest {
    /**
     * A made-up export in the layout of CKW's Excel (see CLAUDE.md): header block, table header
     * with rich text, one row per month or hour ([unit]), `-` after the export date, a total.
     */
    private fun fakeExport(period: String, months: List<Pair<String, Double?>>, unit: String = "Monat"): ByteArray {
        val strings = mutableListOf<String>()
        fun s(text: String): String {
            strings += text
            return "<c t=\"s\"><v>${strings.size - 1}</v></c>".replace("<c", "<c r=\"%s\"")
        }
        fun row(r: Int, vararg cells: String) =
            "<row r=\"$r\">" + cells.mapIndexed { i, c -> c.replace("%s", "${'A' + i}$r") }.joinToString("") + "</row>"
        fun n(value: Double) = "<c r=\"%s\" t=\"n\"><v>$value</v></c>"
        val rows = mutableListOf(
            row(1, s("Kunde"), s("Erika Muster")),
            row(8, s("Auswertungszeitraum:"), s(period)),
            row(9, s("Einheit"), s(unit)),
            row(13, s("Zeitraum"), s("\u0000rich"), s("Gesamtkosten (CHF)")),
        )
        months.forEachIndexed { i, (label, kwh) ->
            rows += row(14 + i, s(label), kwh?.let(::n) ?: s("-"), kwh?.let { n(0.0) } ?: s("-"))
        }
        rows += row(14 + months.size, s("Total"), n(months.sumOf { it.second ?: 0.0 }))
        val sst = strings.joinToString("") {
            if (it == "\u0000rich") "<si><r><rPr><b val=\"true\"/></rPr><t>Energieverbrauch</t></r><r><t xml:space=\"preserve\">\n</t></r><r><t>(kWh)</t></r></si>"
            else "<si><t>$it</t></si>"
        }
        val ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
        return ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                zip.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst $ns>$sst</sst>".toByteArray())
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet $ns><sheetData>${rows.joinToString("")}</sheetData></worksheet>".toByteArray())
            }
        }.toByteArray()
    }

    private val labels = listOf("Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.", "Okt.", "Nov.", "Dez.")

    // Made-up year: 2232 kWh in January (3.0 kW over 744 h), 372 in July (0.5 kW).
    private val year25 = parseCkwMonthlyExport(fakeExport(
        "01.01.2025 - 31.12.2025",
        labels.zip(listOf(2232.0, 2016.0, 1486.0, 1080.0, 744.0, 432.0, 372.0, 446.4, 648.0, 1116.0, 1728.0, 2232.0)).map { (l, v) -> "$l-25" to v },
    ).inputStream())

    @Test
    fun parsesMonthlyExport() {
        assertEquals(12, year25.size)
        assertEquals(YearMonth.of(2025, 1), year25.first().yearMonth)
        assertEquals(3.0, year25.first().averageKw, 1e-9)
        assertEquals(YearMonth.of(2025, 9), year25[8].yearMonth)
        // DST: March is an hour short, October an hour long.
        assertEquals(743.0, year25[2].hours)
        assertEquals(745.0, year25[9].hours)
    }

    @Test
    fun partialYearCoversOnlyExportedDays() {
        val year26 = parseCkwMonthlyExport(fakeExport(
            "01.01.2026 - 10.03.2026",
            listOf("Jan.-26" to 2232.0, "Feb.-26" to 2016.0, "März-26" to 720.0, "Apr.-26" to null),
        ).inputStream())
        assertEquals(3, year26.size)
        assertEquals(10 * 24.0, year26.last().hours)
        assertEquals(3.0, year26.last().averageKw, 1e-9)
    }

    @Test
    fun newerImportReplacesMonth() {
        val partial = listOf(MonthUsage(2025, 12, 100.0, 48.0))
        val merged = mergeUsage(partial, year25)
        assertEquals(12, merged.size)
        assertEquals(2232.0, merged.last().kwh)
    }

    @Test
    fun monthFunction() {
        val profile = LoadProfile(year25)
        assertEquals(0.5, profile.floorKw!!, 1e-9)
        assertEquals(2.5, profile.heatingKw(Month.JANUARY)!!, 1e-9)
        assertEquals(0.0, profile.heatingKw(Month.JULY)!!, 1e-9)
        // Tracked appliances average 0.2 kW: 0.3 kW is always on, plus heating in January.
        assertEquals(0.3, profile.staticBaseloadKw(0.2)!!, 1e-9)
        assertEquals(2.8, profile.baselineKw(Month.JANUARY, 0.2)!!, 1e-9)
    }

    @Test
    fun yearsAreAveragedAndGapsStayEmpty() {
        val profile = LoadProfile(listOf(MonthUsage(2024, 1, 744.0 * 2, 744.0), MonthUsage(2025, 1, 744.0 * 4, 744.0)))
        assertEquals(3.0, profile.averageKw[Month.JANUARY]!!, 1e-9)
        assertNull(profile.baselineKw(Month.JULY))
        assertNull(LoadProfile(emptyList()).staticBaseloadKw())
    }

    private fun jan(time: String) = OffsetDateTime.parse("2027-01-15T$time+01:00").toInstant()

    private val kettle = Appliance("kettle", "Kettle", 2000, runMinutes = 3)
    private val heater = Appliance("heater", "Heater", 1000)

    @Test
    fun shortRunAddsItsShareOfTheQuarter() {
        // 2000 W for 3 of 15 minutes adds 400 W to the quarter's average.
        val runs = listOf(startRun(kettle, jan("07:05")))
        assertEquals(2.8 + 0.4, quarterHourKw(jan("07:00"), 2.8, runs), 1e-9)
        assertEquals(2.8, quarterHourKw(jan("07:15"), 2.8, runs), 1e-9)
    }

    @Test
    fun openRunCountsToTheEndOfTheQuarter() {
        val run = startRun(heater, jan("07:10"))
        assertEquals(1.0 / 3, quarterHourKw(jan("07:00"), 0.0, listOf(run)), 1e-9)
        assertEquals(1.0, quarterHourKw(jan("09:00"), 0.0, listOf(run)), 1e-9)
        val stopped = run.stop(jan("07:13"))
        assertEquals(0.2, quarterHourKw(jan("07:00"), 0.0, listOf(stopped)), 1e-9)
        assertEquals(false, stopped.isRunning(jan("07:14")))
        // Stopping after the run time is up changes nothing.
        val done = startRun(kettle, jan("07:00"))
        assertEquals(done, done.stop(jan("08:00")))
    }

    @Test
    fun monthPeakIsTheHighestQuarterSoFar() {
        val runs = listOf(
            startRun(heater, jan("06:00")).stop(jan("08:00")),
            startRun(kettle, jan("07:05")),
            startRun(kettle, jan("12:00")),
        )
        val peak = monthPeak(YearMonth.of(2027, 1), { 2.8 }, runs, jan("20:00"))
        assertEquals(jan("07:00"), peak.quarter)
        assertEquals(2.8 + 1.0 + 0.4, peak.kw, 1e-9)
        // Before any run the peak is the baseline.
        assertEquals(2.8, monthPeak(YearMonth.of(2027, 1), { 2.8 }, runs, jan("05:00")).kw, 1e-9)
    }

    private fun jul(day: Int, time: String) = OffsetDateTime.parse("2027-07-%02dT$time+02:00".format(day)).toInstant()

    @Test
    fun calibratesOnlyOnSummerTime() {
        // 1 kW for 2 h a day over 10 July days: 20 kWh over the 222 h since the first run.
        val runs = (1..10).map { startRun(heater, jul(it, "18:00")).stop(jul(it, "20:00")) }
        assertEquals(20.0 / 222, summerAppliancesKw(runs, jul(11, "00:00"))!!, 1e-9)
        // Less than a week tracked is not enough.
        assertNull(summerAppliancesKw(runs.take(3), jul(4, "00:00")))
        // Winter runs don't count.
        assertNull(summerAppliancesKw(listOf(startRun(heater, jan("06:00"))), jan("06:00").plusSeconds(30 * 86400L)))
    }

    @Test
    fun statusUsesTheMonthsBaselineAndGoal() {
        val data = PeakData(usage = year25.map { it.copy(year = 2026) }, runs = listOf(startRun(kettle, jan("07:05"))), goalOffsetKw = 2.0)
        val status = data.status(jan("07:10"))
        assertEquals(3.0, status.levelKw!!, 1e-9)
        assertEquals(5.0, status.goalKw, 1e-9)
        assertEquals(3.4, status.quarterKw, 1e-9)
        assertEquals(3.0, status.nextQuarterKw, 1e-9)
        assertEquals(1.6, status.budgetKw, 1e-9)
        assertEquals(false, status.calibrated)
        // Without imported data the goal is the offset alone.
        assertEquals(2.0, PeakData().status(jan("07:10")).goalKw, 1e-9)
    }

    private val washer = Appliance("washer", "Washing machine", 2000, runMinutes = 60, interruptible = false)
    private val oven = Appliance("oven", "Oven", 2500, runMinutes = 30)

    @Test
    fun startsWhenItFitsElseWhenThereIsRoom() {
        // Baseline 3.0, goal 5.0. The oven (2.5 kW) until 07:30 leaves no room for the washer.
        val runs = listOf(startRun(oven, jan("07:00")))
        assertEquals(false, fitsAt(washer, runs, { 3.0 }, 5.0, jan("07:10")))
        assertEquals(jan("07:30"), roomAt(washer, runs, { 3.0 }, 5.0, jan("07:10")))
        assertEquals(jan("07:10"), roomAt(kettle, emptyList(), { 3.0 }, 5.0, jan("07:10")))
        // A heater that runs until stopped never makes room.
        assertNull(roomAt(washer, listOf(startRun(Appliance("h", "Heater", 2500), jan("07:00"))), { 3.0 }, 5.0, jan("07:10")))
    }

    @Test
    fun suggestsTheSmallestStopThatHelps() {
        val small = Appliance("small", "Small heater", 500)
        val big = Appliance("big", "Big heater", 2000)
        val appliances = listOf(small, big, washer)
        val runs = listOf(startRun(small, jan("07:00")), startRun(big, jan("07:00")), startRun(washer, jan("07:00")))
        // 3.0 + 0.5 + 2.0 + 2.0 = 7.5 over a goal of 6.0 from 07:00. Stopping the small heater
        // isn't enough, the washer can't be stopped, so the big heater.
        assertEquals("big", suggestStop(appliances, runs, { 3.0 }, 6.0, jan("07:00"))!!.applianceId)
        // At 07:10 this quarter is lost either way; for the next, the small one is too little still.
        assertEquals("big", suggestStop(appliances, runs, { 3.0 }, 6.0, jan("07:10"))!!.applianceId)
        assertNull(suggestStop(appliances, runs, { 3.0 }, 8.0, jan("07:10")))
    }

    @Test
    fun warnsAboutTheNextQuarterToo() {
        // Found on the phone: a heater started at 07:12 barely touches this quarter, but the next
        // one gets all of it. No load data, so the goal is the 2.84 kW offset alone; washer
        // (2 kW) since 07:05.
        val heater15 = Appliance("heater", "Heater", 1500)
        val runs = listOf(startRun(washer, jan("07:05")), startRun(heater15, jan("07:12")))
        val data = PeakData(appliances = listOf(washer, heater15), runs = runs, goalOffsetKw = 2.84)
        val status = data.status(jan("07:12"))
        assertEquals(true, status.quarterKw < status.goalKw)
        assertEquals(3.5, status.nextQuarterKw, 1e-9)
        assertEquals(2.84 - 3.5, status.budgetKw, 1e-9)
        assertEquals("heater", suggestStop(data.appliances, runs, { 0.0 }, 2.84, jan("07:12"))!!.applianceId)
    }

    @Test
    fun goalRisesToAPeakThatIsAlreadyBilled() {
        // Baseline 3.0 in January, planned goal 5.0. The oven (2.5 kW) 07:00 to 07:30 makes two
        // quarters of 5.5 kW.
        val data = PeakData(usage = year25, runs = listOf(startRun(oven, jan("07:00"))), goalOffsetKw = 2.0)
        // While the first of them runs it is a warning, not yet a new goal.
        val during = data.status(jan("07:10"))
        assertEquals(false, during.goalRaised)
        assertEquals(5.0, during.goalKw, 1e-9)
        assertEquals(true, during.quarterKw > during.goalKw)
        // Once it is over, it is billed anyway: up to 5.5 kW costs nothing extra this month.
        val after = data.status(jan("08:00"))
        assertEquals(true, after.goalRaised)
        assertEquals(5.5, after.goalKw, 1e-9)
        assertEquals(jan("07:00"), after.pastPeak.quarter)
        assertEquals(2.5, after.budgetKw, 1e-9)
        // A new month starts from the planned goal again.
        assertEquals(false, data.status(OffsetDateTime.parse("2027-02-01T00:05+01:00").toInstant()).goalRaised)
    }

    @Test
    fun presetsAreAboutOneRealUse() {
        assertEquals(APPLIANCE_PRESETS.size, APPLIANCE_PRESETS.map { it.name }.toSet().size)
        // Watts x run time should be one use's energy, not the whole programme's length at full power.
        APPLIANCE_PRESETS.filter { it.runMinutes != null }.forEach {
            val kwh = it.watts / 1000.0 * it.runMinutes!! / 60
            assertEquals(true, kwh in 0.05..2.0, "${it.name}: $kwh kWh")
        }
    }

    /** A made-up day export: 0.2 kWh every hour, [night] at 01:00, [evening] at 20:00. */
    private fun fakeDay(date: String, night: Double, evening: Double = 0.2, hours: Int = 24): ByteArray {
        val (y, m, d) = date.split("-").map { it.toInt() }
        val rows = (0 until hours).map { i ->
            "$d.$m.$y  %02d:00".format(if (hours == 25 && i > 2) i - 1 else i) to when (i) { 1 -> night; 20 -> evening; else -> 0.2 }
        }
        return fakeExport("%02d.%02d.$y - %02d.%02d.$y".format(d, m, d, m), rows, unit = "Stunde")
    }

    private fun day(date: String, night: Double, evening: Double = 0.2) = parseCkwExport(fakeDay(date, night, evening).inputStream()).days.single()

    @Test
    fun parsesHourlyDays() {
        val import = parseCkwExport(fakeDay("2027-01-03", night = 3.0).inputStream())
        assertEquals(emptyList(), import.months)
        val day = import.days.single()
        assertEquals("2027-01-03", day.date)
        assertEquals(24, day.kwh.size)
        assertEquals(3.0, day.kwh[1])
        assertEquals(OffsetDateTime.parse("2027-01-03T01:00+01:00").toInstant(), day.hourStarts()[1])
        // The day clocks go back has 25 hours; the hours after the change are an hour later in UTC.
        val long = parseCkwExport(fakeDay("2027-10-31", night = 3.0, hours = 25).inputStream()).days.single()
        assertEquals(25, long.kwh.size)
        assertEquals(OffsetDateTime.parse("2027-10-31T02:00+01:00").toInstant(), long.hourStarts()[3])
        // A day with a gap (today, exported before midnight) is left out.
        assertEquals(emptyList(), parseCkwExport(fakeDay("2027-01-03", night = 3.0, hours = 20).inputStream()).days)
    }

    // Ten made-up January days: 0.2 kW always on, a water heater at 01:00 (2 to 5 kW), and on
    // three evenings the household's oven at 20:00.
    private val january = (1..10).map { d -> day("2027-01-%02d".format(d), night = 2.0 + d * 0.3, evening = if (d % 3 == 0) 2.2 else 0.2) }

    @Test
    fun hourlyProfileKeepsTimersAndDropsTheHouseholdsOwnUse() {
        val month = hourlyMonths(january)[Month.JANUARY]!!
        assertEquals(10, month.days)
        assertEquals(0.2, month.staticKw, 1e-9)
        // The water heater runs every night: its usual (median) night is in the baseline.
        assertEquals(3.5, month.baseKw[1], 1e-9)
        assertEquals(1, month.peakHour)
        // The oven on three evenings doesn't.
        assertEquals(0.2, month.baseKw[20], 1e-9)
        assertEquals(Peak(OffsetDateTime.parse("2027-01-10T01:00+01:00").toInstant(), 5.0), highestHour(january, YearMonth.of(2027, 1)))
        assertNull(highestHour(january, YearMonth.of(2027, 2)))
    }

    @Test
    fun hourlyDataSetsTheBaselineByHourAndRaisesTheGoal() {
        val data = PeakData(days = january, goalOffsetKw = 2.0)
        val status = data.status(jan("14:00"))
        assertEquals(true, status.hourly)
        assertEquals(0.2, status.baseNowKw, 1e-9)
        // The meter already measured 5.0 kW this month: billed anyway, so the goal rises to it.
        assertEquals(GoalRaise.METER, status.raisedBy)
        assertEquals(5.0, status.goalKw, 1e-9)
        // Without that, the washer (2 kW, 60 min) started at 00:30 would run into the water heater.
        val baseline = Baseline(data)
        val plannedGoal = status.plannedGoalKw
        assertEquals(false, fitsAt(washer, emptyList(), baseline::at, plannedGoal, jan("00:30")))
        assertEquals(jan("02:00"), roomAt(washer, emptyList(), baseline::at, plannedGoal, jan("00:30")))
    }

    @Test
    fun goalRisesToWhatHappensEveryNight() {
        // Last January's hourly data, now a year later: nothing measured this month yet, but the
        // water heater's usual 3.5 kW at 01:00 will be billed anyway.
        val lastYear = january.map { it.copy(date = it.date.replace("2027", "2026")) }
        val status = PeakData(days = lastYear, goalOffsetKw = 2.0).status(jan("00:50"))
        assertNull(status.meterPeak)
        assertEquals(1 to 3.5, status.usualPeak)
        assertEquals(GoalRaise.USUAL, status.raisedBy)
        assertEquals(3.5, status.goalKw, 1e-9)
        // So the night doesn't show as over the goal.
        assertEquals(true, status.budgetKw >= 0)
    }

    @Test
    fun savedDataRoundTrips() {
        val data = PeakData(year25, listOf(kettle, heater), listOf(startRun(heater, jan("06:00"))))
        assertEquals(data, parsePeakData(data.toJson()))
        assertEquals(PeakData(), parsePeakData("{}"))
    }

    @Test
    fun appliancesAverage() {
        // 1 kW for 2 h over a 20 h window: 0.1 kW.
        val runs = listOf(startRun(heater, jan("06:00")).stop(jan("08:00")))
        assertEquals(0.1, averageKw(runs, jan("00:00"), jan("20:00")), 1e-9)
        assertEquals(Instant.parse("2027-01-15T06:00:00Z"), quarterStart(jan("07:14:59")))
    }
}
