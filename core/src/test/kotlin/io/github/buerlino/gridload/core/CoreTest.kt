package io.github.buerlino.gridload.core

import java.nio.file.Files
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val status = classify(slots, at("13:05"), spot = false)!!
        assertEquals(Level.GREEN, status.level)
        assertEquals(0.1513, status.slot.price)
    }

    @Test
    fun morningPeakIsRed() = assertEquals(Level.RED, classify(slots, at("07:00"), spot = false)!!.level)

    @Test
    fun nightIsOrange() = assertEquals(Level.ORANGE, classify(slots, at("02:30"), spot = false)!!.level)

    @Test
    fun slotEndIsExclusive() {
        // 16:45-17:00 is 0.1759 (green), 17:00-17:15 is 0.2223 (orange).
        assertEquals(Level.GREEN, classify(slots, at("16:59:59"), spot = false)!!.level)
        assertEquals(Level.ORANGE, classify(slots, at("17:00"), spot = false)!!.level)
    }

    @Test
    fun aSlotCoversItsStartButNotItsEnd() {
        val slot = slots.first() // 00:00 to 00:15
        assertTrue(slot.covers(at("00:00")))
        assertTrue(slot.covers(at("00:14:59")))
        assertFalse(slot.covers(at("00:15")))
        assertFalse(slot.covers(at("00:00").minusSeconds(1)))
    }

    @Test
    fun staleDataGivesNoStatus() {
        assertNull(classify(slots, at("23:59").plusSeconds(60), spot = false))
        assertNull(classify(emptyList(), Instant.now(), spot = false))
    }

    @Test
    fun flatDayIsOrange() {
        val flat = slots.map { it.copy(price = 0.2) }
        assertEquals(Level.ORANGE, classify(flat, at("13:00"), spot = false)!!.level)
    }

    @Test
    fun negativePricesClassifyLikeAnyOthers() {
        // 0.10 until 10:00, −0.05 until 16:00, then 0.20: thirds of −0.05 to 0.20 at 0.0333 and 0.1167.
        val day = slots.mapIndexed { i, slot -> slot.copy(price = if (i < 40) 0.10 else if (i < 64) -0.05 else 0.20) }
        val morning = classify(day, at("06:00"), spot = false)!!
        assertEquals(Level.ORANGE, morning.level)
        assertEquals(at("10:00"), morning.nextGreen!!.start.toInstant())
        assertEquals(Level.GREEN, classify(day, at("12:00"), spot = false)!!.level)
        assertEquals(Level.RED, classify(day, at("18:00"), spot = false)!!.level)
        // All below 0: the same colours as the day shifted up.
        val below = day.map { it.copy(price = it.price - 1.0) }
        listOf("06:00", "12:00", "18:00").forEach { assertEquals(classify(day, at(it), spot = false)!!.level, classify(below, at(it), spot = false)!!.level) }
        assertEquals(at("10:00"), classify(below, at("06:00"), spot = false)!!.nextGreen!!.start.toInstant())
    }

    @Test
    fun aSpikeDoesntTurnDearMarketPricesGreen() {
        // Real Austrian prices for 10 Sep 2026: 11.7 to 51.9 ct, with an evening spike above the
        // 90th percentile (27.1 ct) from 18:45 to 20:45. With the highest, 83 of 96 quarters were
        // green, 07:00 at 23.3 ct too, though 11.7 ct was known to be coming.
        val sep10 = parseEnergyCharts(javaClass.getResource("/ec-at-2026-09-10.json")!!.readText())
        fun at(time: String) = OffsetDateTime.parse("2026-09-10T$time+02:00").toInstant()
        assertEquals(Level.GREEN, classify(sep10, at("07:00"), spot = false)!!.level)
        val spot = classify(sep10, at("07:00"), spot = true)!!
        assertEquals(Level.RED, spot.level)
        assertEquals(at("09:45"), spot.nextGreen!!.start.toInstant())
        // The spike itself is red either way.
        assertEquals(Level.RED, classify(sep10, at("19:45"), spot = true)!!.level)
        assertEquals(42, sep10.count { classify(sep10, it.start.toInstant(), spot = true)!!.level == Level.GREEN })
    }

    @Test
    fun theColourTopIsThe90thPercentileOnlyForMarketPrices() {
        val prices = (1..100).map { it.toDouble() }
        assertEquals(100.0, colourTop(prices, spot = false))
        assertEquals(91.0, colourTop(prices, spot = true))
        assertEquals(91.0, colourTop(prices.shuffled(), spot = true))
        assertEquals(-10.0, colourTop(prices.map { it - 101.0 }, spot = true))
        // Too few prices to leave any out, and a single one.
        assertEquals(3.0, colourTop(listOf(1.0, 2.0, 3.0), spot = true))
        assertEquals(5.0, colourTop(listOf(5.0), spot = true))
        // Flat apart from a few dear slots: the highest keeps them red rather than all orange.
        val flat = slots.mapIndexed { i, slot -> slot.copy(price = if (i in 72 until 76) 0.30 else 0.10) }
        assertEquals(0.30, colourTop(flat.map { it.price }, spot = true))
        assertEquals(Level.GREEN, classify(flat, at("06:00"), spot = true)!!.level)
        assertEquals(Level.RED, classify(flat, at("18:00"), spot = true)!!.level)
        assertEquals(Level.ORANGE, classify(slots.map { it.copy(price = 0.2) }, at("13:00"), spot = true)!!.level)
    }

    @Test
    fun theWindowHasEachSlotsColour() {
        // Without tomorrow the window is today, from midnight; with it, the 24 hours from now.
        val today = classify(sep29, sep29("21:00"), spot = false)!!
        assertEquals(96, today.window.size)
        assertEquals(sep29("00:00"), today.window.first().slot.start.toInstant())
        val ahead = classify(twoDays, sep29("21:05"), spot = false)!!
        assertEquals(96, ahead.window.size)
        assertEquals(sep29("21:00"), ahead.window.first().slot.start.toInstant())
        // The same colours as classifying each slot on its own against the same window.
        today.window.forEach { assertEquals(classify(sep29, it.slot.start.toInstant(), spot = false)!!.level, it.level) }
        assertEquals(ahead.nextGreen, ahead.window.first { it.level == Level.GREEN }.slot)
    }

    @Test
    fun greenUntilIsTheEndOfTheGreenRun() {
        // 2026-09-28: green from 10:00 to 17:00 (16:45 is the last green slot).
        assertEquals(at("17:00"), classify(slots, at("13:05"), spot = false)!!.greenUntil!!.toInstant())
        assertEquals(at("17:00"), classify(slots, at("16:59"), spot = false)!!.greenUntil!!.toInstant())
        assertNull(classify(slots, at("07:00"), spot = false)!!.greenUntil)
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
        val status = classify(twoDays, sep29("21:00"), spot = false)!!
        assertEquals(Level.ORANGE, status.level)
        assertEquals(OffsetDateTime.parse("2026-09-30T10:00+02:00").toInstant(), status.nextGreen!!.start.toInstant())
    }

    @Test
    fun withoutTomorrowTheWindowIsToday() {
        // Same colour, but today's green hours are over, so there is no next good time to show.
        val status = classify(sep29, sep29("21:00"), spot = false)!!
        assertEquals(Level.ORANGE, status.level)
        assertNull(status.nextGreen)
    }

    @Test
    fun nextGreenIsLaterToday() {
        val status = classify(sep29, sep29("07:00"), spot = false)!!
        assertEquals(Level.RED, status.level)
        assertEquals(sep29("10:00"), status.nextGreen!!.start.toInstant())
        assertNull(classify(twoDays, sep29("13:00"), spot = false)!!.nextGreen)
    }

    @Test
    fun fetchesTomorrowOnceItIsPublished() {
        assertTrue(wantsFetch(emptyList(), sep29("09:00"), CKW))
        assertFalse(wantsFetch(sep29, sep29("11:59"), CKW))
        assertTrue(wantsFetch(sep29, sep29("12:00"), CKW))
        assertFalse(wantsFetch(twoDays, sep29("12:00"), CKW))
        assertTrue(wantsFetch(sep29, OffsetDateTime.parse("2026-09-30T00:00+02:00").toInstant(), CKW))
        // A region that publishes later waits for its own time.
        assertFalse(wantsFetch(sep29, sep29("17:59"), CKW.copy(tomorrowFrom = LocalTime.of(18, 0))))
        // Its time is in its own zone: 12:00 in Zurich is only 11:00 in London.
        assertFalse(wantsFetch(sep29, sep29("12:00"), CKW.copy(zone = java.time.ZoneId.of("Europe/London"))))
    }

    @Test
    fun picksTheKwhPriceNextToAMonthlyFee() {
        // EKZ lists a CHF_m fee before the CHF_kWh price (real response, first hour of 29 Sep).
        val ekz = parsePrices(javaClass.getResource("/ekz-2026-09-29-first-hour.json")!!.readText())
        assertEquals(4, ekz.size)
        assertEquals(0.1998, ekz.first().price)
        assertEquals(OffsetDateTime.parse("2026-09-29T00:00+02:00"), ekz.first().start)
    }

    @Test
    fun theCacheKeepsSlotsRegionAndFetchTime() {
        val dir = Files.createTempDirectory("prices").toFile()
        try {
            val cache = PriceCache(dir.resolve("prices.json"))
            assertNull(cache.load())
            // UTC timestamps as the API sends them for a request in UTC, and local ones.
            val saved = CachedPrices("ekz", Instant.parse("2026-09-29T10:15:30Z"), twoDays + slots)
            cache.save(saved)
            assertEquals(saved, cache.load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun requestCoversTodayAndTomorrowInUtc() {
        // Winter time starts on 25 October, which then has 25 hours.
        val url = pricesRequestUrl(CKW, OffsetDateTime.parse("2026-10-24T15:00+02:00").toInstant())
        assertTrue(url.startsWith((CKW.source as PriceSource.Vse).url + "&"))
        assertTrue(url.endsWith("&start_timestamp=2026-10-23T22:00:00Z&end_timestamp=2026-10-25T23:00:00Z"), url)
        // Another zone's midnights.
        val london = pricesRequestUrl(CKW.copy(zone = java.time.ZoneId.of("Europe/London")), OffsetDateTime.parse("2026-10-24T15:00+02:00").toInstant())
        assertTrue(london.endsWith("&start_timestamp=2026-10-23T23:00:00Z&end_timestamp=2026-10-26T00:00:00Z"), london)
    }

    @Test
    fun energyChartsRequestCoversTodayAndTomorrowInUtc() {
        val austria = CKW.copy(source = PriceSource.EnergyCharts("AT"), zone = java.time.ZoneId.of("Europe/Vienna"))
        assertEquals(
            "https://api.energy-charts.info/price?bzn=AT&start=2026-10-23T22:00:00Z&end=2026-10-25T23:00:00Z",
            pricesRequestUrl(austria, OffsetDateTime.parse("2026-10-24T15:00+02:00").toInstant()),
        )
    }

    @Test
    fun parsesEnergyChartsMarketPrices() {
        // Real response for Austria, today + tomorrow, fetched 2026-10-03 23:16 (EUR/MWh).
        val at = parseEnergyCharts(javaClass.getResource("/ec-at-2026-10-03-to-04.json")!!.readText())
        assertEquals(192, at.size)
        assertEquals(OffsetDateTime.parse("2026-10-03T00:00+02:00").toInstant(), at.first().start.toInstant())
        assertEquals(OffsetDateTime.parse("2026-10-03T00:15+02:00").toInstant(), at.first().end.toInstant())
        assertEquals(0.20451, at.first().price, 1e-9)
        assertTrue(at.zipWithNext().all { (a, b) -> a.end == b.start })
        // The last slot ends after the same length.
        assertEquals(OffsetDateTime.parse("2026-10-05T00:00+02:00").toInstant(), at.last().end.toInstant())
        assertTrue(classify(at, OffsetDateTime.parse("2026-10-03T19:00+02:00").toInstant(), spot = true) != null)
    }

    @Test
    fun energyChartsSpringDstDayHas92Quarters() {
        // Real response for 29 Mar 2026 (23 hours in Vienna), requested from its midnight to the
        // next. It includes the slot at the end timestamp.
        val body = javaClass.getResource("/ec-at-2026-03-29.json")!!.readText()
        val midnight = OffsetDateTime.parse("2026-03-29T00:00+01:00").toInstant()
        val nextMidnight = OffsetDateTime.parse("2026-03-30T00:00+02:00").toInstant()
        assertEquals(nextMidnight, parseEnergyCharts(body).last().start.toInstant())
        // As the answer for 28 Mar, whose tomorrow it is, the slot at the end is dropped.
        val day = pricesFrom(REGIONS.first { it.id == "at" }, body, OffsetDateTime.parse("2026-03-28T15:00+01:00").toInstant())
        assertEquals(92, day.size)
        assertEquals(midnight, day.first().start.toInstant())
        assertEquals(nextMidnight, day.last().end.toInstant())
    }

    @Test
    fun unreadableEnergyChartsAnswers() {
        assertFailsWith<IllegalStateException> { parseEnergyCharts("""{"unix_seconds":[0,900],"price":[1.0,2.0],"deprecated":true}""") }
        assertFailsWith<IllegalStateException> { parseEnergyCharts("""{"unix_seconds":[],"price":[]}""") }
        // A slot without a price is left out; the others keep their times.
        val slots = parseEnergyCharts("""{"unix_seconds":[0,900,1800],"price":[10.0,null,-5.0]}""")
        assertEquals(listOf(0.01, -0.005), slots.map { it.price })
        assertEquals(Instant.ofEpochSecond(2700), slots.last().end.toInstant())
    }

    @Test
    fun regionsHaveUniqueIdsAndHttpsUrls() {
        assertEquals(REGIONS.size, REGIONS.map { it.id }.toSet().size)
        assertTrue(REGIONS.all { val source = it.source; source !is PriceSource.Vse || (source.url.startsWith("https://") && "?" in source.url) })
        assertEquals(REGIONS.sortedBy { it.name }, REGIONS)
        assertTrue(CKW in REGIONS)
        assertTrue(REGIONS.all { it.country in COUNTRIES })
        assertTrue(REGIONS.all { it.zone == it.country.zone })
    }

    @Test
    fun onlyCkwBillsAPeakInSwitzerland() {
        assertEquals(0.0, CKW.minimumKw)
        assertEquals(
            mapOf("ekz" to null, "ekz_einsiedeln" to null, "groupe_e" to null, "primeo" to null, "primeo_avag" to null, "primeo_elag" to null),
            REGIONS.filter { it.country == SWITZERLAND && it != CKW }.associate { it.id to it.minimumKw },
        )
        assertTrue(REGIONS.all { (it.minimumKw ?: 0.0) >= 0 })
    }

    @Test
    fun marketPriceRegions() {
        data class Expected(val country: Country, val zone: String, val bzn: String, val minimumKw: Double?)
        val expected = mapOf(
            "at" to Expected(AUSTRIA, "Europe/Vienna", "AT", 2.0),
            "be_flanders" to Expected(BELGIUM, "Europe/Brussels", "BE", 2.5),
            "be_wallonia_brussels" to Expected(BELGIUM, "Europe/Brussels", "BE", null),
            "de" to Expected(GERMANY, "Europe/Berlin", "DE-LU", null),
            "lu" to Expected(LUXEMBOURG, "Europe/Luxembourg", "DE-LU", null),
            "nl" to Expected(NETHERLANDS, "Europe/Amsterdam", "NL", null),
            "li" to Expected(LIECHTENSTEIN, "Europe/Vaduz", "CH", null),
        )
        assertEquals(expected, REGIONS.filter { it.isSpot }.associate {
            it.id to Expected(it.country, it.zone.id, (it.source as PriceSource.EnergyCharts).bzn, it.minimumKw)
        })
        REGIONS.filter { it.isSpot }.forEach {
            assertEquals(LocalTime.of(13, 15), it.tomorrowFrom)
            assertEquals("${it.name} (market price)", it.label)
        }
        // Every Swiss region is a utility's tariff; Liechtenstein shows € like its source.
        assertTrue(REGIONS.filter { it.country == SWITZERLAND }.none { it.isSpot })
        assertEquals(Currency.EUR, LIECHTENSTEIN.currency)
        assertEquals(COUNTRIES.sortedBy { it.name }, COUNTRIES)
        assertTrue(COUNTRIES.all { country -> REGIONS.any { it.country == country } })
        assertEquals(COUNTRIES.size, COUNTRIES.map { it.code }.toSet().size)
    }

    @Test
    fun countryIsTheFirstKnownCode() {
        assertEquals(SWITZERLAND, countryOf("ch"))
        assertEquals(SWITZERLAND, countryOf("", "US", "CH"))
        assertNull(countryOf(null, "FR"))
        assertEquals(
            listOf(AUSTRIA, BELGIUM, GERMANY, LUXEMBOURG, NETHERLANDS, LIECHTENSTEIN),
            listOf("at", "be", "de", "lu", "nl", "li").map { countryOf(it) },
        )
        assertEquals(GERMANY, countryOf("US", "DE"))
    }

    @Test
    fun pricesShowInTheCountrysCurrency() {
        assertEquals("22.7 Rp/kWh", Currency.CHF.perKwh(0.227, Locale.ROOT))
        assertEquals("0.34 CHF/h", Currency.CHF.perHour(1.3 * 0.2615, Locale.ROOT))
        assertEquals("11.3 ct/kWh", Currency.EUR.perKwh(0.11268, Locale.ROOT))
        assertEquals("0.34 €/h", Currency.EUR.perHour(0.34, Locale.ROOT))
        assertEquals("0.09 CHF", Currency.CHF.amount(0.0925, Locale.ROOT))
        assertEquals("0.31 €", Currency.EUR.amount(0.31, Locale.ROOT))
        assertEquals("22,7 Rp/kWh", Currency.CHF.perKwh(0.227, Locale.GERMANY))
        assertEquals(Currency.CHF, SWITZERLAND.currency)
    }

    @Test
    fun negativePricesHaveAMinusSign() {
        assertEquals("−1.2 ct/kWh", Currency.EUR.perKwh(-0.012, Locale.ROOT))
        assertEquals("−0.05 €/h", Currency.EUR.perHour(-0.05, Locale.ROOT))
        assertEquals("0.0 ct/kWh", Currency.EUR.perKwh(-0.0004, Locale.ROOT))
    }

    @Test
    fun swissPricesLookAsBefore() = (slots + twoDays).forEach {
        assertEquals("%.1f Rp/kWh".format(Locale.ROOT, it.price * 100), Currency.CHF.perKwh(it.price, Locale.ROOT))
        assertEquals("%.2f CHF/h".format(Locale.ROOT, 1.3 * it.price), Currency.CHF.perHour(1.3 * it.price, Locale.ROOT))
    }

    @Test
    fun ownPriceAddsTheAddOnThenVat() {
        assertEquals(0.36, ownPrice(0.113, 18.7, 20.0, AUSTRIA), 1e-9)
        // Blank VAT is the country's.
        assertEquals(0.36, ownPrice(0.113, 18.7, null, AUSTRIA), 1e-9)
        assertEquals(0.30, ownPrice(0.113, 18.7, 0.0, AUSTRIA), 1e-9)
        // A negative market price lowers it, and can still be outweighed by the add-on.
        assertEquals(0.18, ownPrice(-0.035, 18.5, 20.0, AUSTRIA), 1e-9)
        assertEquals(-0.03, ownPrice(-0.035, 0.5, 0.0, AUSTRIA), 1e-9)
        // No VAT where the country has none.
        assertEquals(0.30, ownPrice(0.113, 18.7, null, SWITZERLAND), 1e-9)
    }

    @Test
    fun aSavingIsTheDifferenceOfTheOwnPrices() {
        // The add-on cancels out: 2 kWh at 0.30 against 0.1875 in Austria saves 0.225 × 1.2.
        val addOn = 18.7
        val saving = 2 * (ownPrice(0.30, addOn, null, AUSTRIA) - ownPrice(0.1875, addOn, null, AUSTRIA))
        assertEquals(saving, ownSaving(0.225, null, AUSTRIA), 1e-9)
        assertEquals(0.27, ownSaving(0.225, 20.0, AUSTRIA), 1e-9)
        assertEquals(0.225, ownSaving(0.225, 0.0, AUSTRIA), 1e-9)
    }

    @Test
    fun yourPriceNeedsTheAddOnOnlyInSpotRegions() {
        val at = REGIONS.first { it.id == "at" }
        // A utility's tariff is the household's price; the add-on and VAT don't apply.
        assertEquals(0.227, yourPrice(0.227, CKW, null, null))
        assertEquals(0.227, yourPrice(0.227, CKW, 18.7, 20.0))
        // A market price needs the add-on first.
        assertNull(yourPrice(0.113, at, null, 20.0))
        assertEquals(0.36, yourPrice(0.113, at, 18.7, null)!!, 1e-9)
    }

    @Test
    fun yourSavingHidesWhatWouldShowAsZero() {
        val at = REGIONS.first { it.id == "at" }
        assertEquals(0.09, yourSaving(0.09, CKW, null, null))
        assertEquals(0.27, yourSaving(0.225, at, 18.7, null)!!, 1e-9)
        assertNull(yourSaving(0.225, at, null, null))
        // Below 0.005 it would show as "0.00 CHF"; 0.005 itself shows as 0.01.
        assertNull(yourSaving(0.0049, CKW, null, null))
        assertEquals(0.005, yourSaving(0.005, CKW, null, null))
        // VAT can lift a saving over the threshold: 0.0045 × 1.2 = 0.0054.
        assertEquals(0.0054, yourSaving(0.0045, at, 18.7, 20.0)!!, 1e-9)
    }

    @Test
    fun defaultVatPerCountry() {
        assertNull(SWITZERLAND.vat)
        // Every country with a market-price region has a VAT to start from.
        assertTrue(REGIONS.filter { it.isSpot }.all { it.country.vat != null })
    }

    @Test
    fun addOnAndVatMayBeZero() {
        assertEquals(0.0, parseNonNegative("0"))
        assertEquals(18.5, parseNonNegative(" 18,5 "))
        assertNull(parseNonNegative("-1"))
        assertNull(parseNonNegative("NaN"))
        assertNull(parsePositive("0"))
    }

    @Test
    fun ownPriceKeepsTheColourAndTheNextGoodTime() {
        val market = parseEnergyCharts(javaClass.getResource("/ec-at-2026-10-03-to-04.json")!!.readText())
        val own = market.map { it.copy(price = ownPrice(it.price, 18.5, 20.0, AUSTRIA)) }
        for (spot in listOf(true, false)) for (slot in market) {
            val time = slot.start.toInstant()
            assertEquals(classify(market, time, spot)?.level, classify(own, time, spot)?.level)
            assertEquals(classify(market, time, spot)?.nextGreen?.start, classify(own, time, spot)?.nextGreen?.start)
        }
    }

    @Test
    fun cooldownBlocksRapidFetches() {
        val last = at("12:00")
        assertEquals(true, mayFetch(null, last, hasCurrentData = true))
        assertEquals(false, mayFetch(last, at("12:04:59"), hasCurrentData = true))
        assertEquals(true, mayFetch(last, at("12:05"), hasCurrentData = true))
        assertEquals(true, mayFetch(last, at("12:10"), hasCurrentData = true))
        assertEquals(at("12:05"), cooldownEnd(last, at("12:04:59"), hasCurrentData = true))
        assertEquals(at("12:00:30"), cooldownEnd(last, at("12:00:10"), hasCurrentData = false))
        assertNull(cooldownEnd(last, at("12:05"), hasCurrentData = true))
    }

    @Test
    fun retryIsQuickerWhenThereIsNothingToShow() {
        val last = at("12:00")
        assertEquals(false, mayFetch(last, at("12:00:29"), hasCurrentData = false))
        assertEquals(true, mayFetch(last, at("12:00:30"), hasCurrentData = false))
    }

    @Test
    fun onlyFailuresThatReachedTheServerCount() {
        assertFalse(reachedServer(java.net.UnknownHostException()))
        assertFalse(reachedServer(java.net.ConnectException()))
        assertFalse(reachedServer(java.net.NoRouteToHostException()))
        assertTrue(reachedServer(java.net.SocketTimeoutException()))
        assertTrue(reachedServer(HttpException(429)))
    }
}
