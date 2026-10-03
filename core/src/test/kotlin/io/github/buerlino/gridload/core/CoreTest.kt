package io.github.buerlino.gridload.core

import java.nio.file.Files
import java.time.Instant
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
    fun aSlotCoversItsStartButNotItsEnd() {
        val slot = slots.first() // 00:00 to 00:15
        assertTrue(slot.covers(at("00:00")))
        assertTrue(slot.covers(at("00:14:59")))
        assertFalse(slot.covers(at("00:15")))
        assertFalse(slot.covers(at("00:00").minusSeconds(1)))
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
        assertTrue(wantsFetch(emptyList(), sep29("09:00"), CKW))
        assertFalse(wantsFetch(sep29, sep29("11:59"), CKW))
        assertTrue(wantsFetch(sep29, sep29("12:00"), CKW))
        assertFalse(wantsFetch(twoDays, sep29("12:00"), CKW))
        assertTrue(wantsFetch(sep29, OffsetDateTime.parse("2026-09-30T00:00+02:00").toInstant(), CKW))
        // A region that publishes later waits for its own time.
        assertFalse(wantsFetch(sep29, sep29("17:59"), CKW.copy(tomorrowFrom = java.time.LocalTime.of(18, 0))))
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
        assertTrue(classify(at, OffsetDateTime.parse("2026-10-03T19:00+02:00").toInstant()) != null)
    }

    @Test
    fun energyChartsSpringDstDayHas92Quarters() {
        // Real response for 29 Mar 2026 (23 hours in Vienna), requested from its midnight to the
        // next. It includes the slot at the end timestamp, which fetchPrices drops.
        val day = parseEnergyCharts(javaClass.getResource("/ec-at-2026-03-29.json")!!.readText())
        val midnight = OffsetDateTime.parse("2026-03-29T00:00+01:00").toInstant()
        val nextMidnight = OffsetDateTime.parse("2026-03-30T00:00+02:00").toInstant()
        assertEquals(midnight, day.first().start.toInstant())
        assertEquals(nextMidnight, day.last().start.toInstant())
        assertEquals(92, day.count { it.start.toInstant().isBefore(nextMidnight) })
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
    fun countryIsTheFirstKnownCode() {
        assertEquals(SWITZERLAND, countryOf("ch"))
        assertEquals(SWITZERLAND, countryOf("", "US", "CH"))
        assertNull(countryOf(null, "DE"))
    }

    @Test
    fun pricesShowInTheCountrysCurrency() {
        assertEquals("22.7 Rp/kWh", Currency.CHF.perKwh(0.227, Locale.ROOT))
        assertEquals("0.34 CHF/h", Currency.CHF.perHour(1.3 * 0.2615, Locale.ROOT))
        assertEquals("11.3 ct/kWh", Currency.EUR.perKwh(0.11268, Locale.ROOT))
        assertEquals("0.34 €/h", Currency.EUR.perHour(0.34, Locale.ROOT))
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

    private val austria = Country("AT", "Austria", "🇦🇹", java.time.ZoneId.of("Europe/Vienna"), Currency.EUR, vat = 20.0)

    @Test
    fun ownPriceAddsTheAddOnThenVat() {
        assertEquals(0.36, ownPrice(0.113, 18.7, 20.0, austria), 1e-9)
        // Blank VAT is the country's.
        assertEquals(0.36, ownPrice(0.113, 18.7, null, austria), 1e-9)
        assertEquals(0.30, ownPrice(0.113, 18.7, 0.0, austria), 1e-9)
        // A negative market price lowers it, and can still be outweighed by the add-on.
        assertEquals(0.18, ownPrice(-0.035, 18.5, 20.0, austria), 1e-9)
        assertEquals(-0.03, ownPrice(-0.035, 0.5, 0.0, austria), 1e-9)
        // No VAT where the country has none.
        assertEquals(0.30, ownPrice(0.113, 18.7, null, SWITZERLAND), 1e-9)
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
        val own = twoDays.map { it.copy(price = ownPrice(it.price, 18.5, 20.0, austria)) }
        for (slot in twoDays) {
            val time = slot.start.toInstant()
            assertEquals(classify(twoDays, time)?.level, classify(own, time)?.level)
            assertEquals(classify(twoDays, time)?.nextGreen?.start, classify(own, time)?.nextGreen?.start)
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
