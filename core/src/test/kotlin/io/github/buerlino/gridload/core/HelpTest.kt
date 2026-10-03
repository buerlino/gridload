package io.github.buerlino.gridload.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class HelpTest {
    private val now = Instant.parse("2026-10-04T10:00:00Z")
    private val attribution = "Market prices: Bundesnetzagentur | SMARD.de, via energy-charts.info (CC BY 4.0)."
    private val withMinimum = "The limit is the highest of: the month's highest quarter hour, your biggest appliance plus 20%, your goal, and the least your tariff bills."
    private val noMinimum = "The limit is the highest of: the month's highest quarter hour, your biggest appliance plus 20%, and your goal."

    @Test
    fun switzerland() {
        assertEquals(
            CountryHelp(
                "your tariff's price",
                "Tomorrow's prices come out between noon and 6 pm.",
                emptyList(),
                listOf("Billed by CKW.", "Not billed in the other regions."),
                noMinimum,
            ),
            countryHelp(SWITZERLAND, now),
        )
    }

    @Test
    fun austriaUntilAndFrom2027() {
        assertEquals(
            CountryHelp(
                "the market price",
                "Tomorrow's prices come out at about 1 pm.",
                listOf(attribution),
                listOf("Billed in Austria from 2027, at least 2 kW a month."),
                withMinimum,
            ),
            countryHelp(AUSTRIA, now),
        )
        // New Year's Day in Vienna, still 31 Dec in UTC.
        assertEquals(
            listOf("Billed in Austria, at least 2 kW a month."),
            countryHelp(AUSTRIA, Instant.parse("2026-12-31T23:30:00Z")).peakLines,
        )
        assertEquals(
            listOf("Billed in Austria from 2027, at least 2 kW a month."),
            countryHelp(AUSTRIA, Instant.parse("2026-12-31T22:30:00Z")).peakLines,
        )
    }

    @Test
    fun belgiumShowsBothRegions() {
        assertEquals(
            CountryHelp(
                "the market price",
                "Tomorrow's prices come out at about 1 pm.",
                listOf("In Wallonia and Brussels, the time-of-use grid fee isn't in the colour.", attribution),
                listOf(
                    "Billed in Flanders, at least 2.5 kW a month.",
                    "Flanders bills the average of the last 12 months.",
                    "Not billed in Wallonia and Brussels.",
                ),
                withMinimum,
            ),
            countryHelp(BELGIUM, now),
        )
    }

    @Test
    fun marketPriceCountriesWithoutAPeak() {
        listOf(GERMANY, LIECHTENSTEIN, LUXEMBOURG, NETHERLANDS).forEach {
            assertEquals(
                CountryHelp(
                    "the market price",
                    "Tomorrow's prices come out at about 1 pm.",
                    listOf(attribution),
                    listOf("Not billed in ${it.name}."),
                    noMinimum,
                ),
                countryHelp(it, now),
            )
        }
    }

    @Test
    fun generalWithoutACountry() {
        assertEquals(
            CountryHelp("your tariff's price, or the market price", null, emptyList(), emptyList(), withMinimum),
            countryHelp(null, now),
        )
    }

    @Test
    fun everyCountryIsCovered() {
        assertEquals(setOf(SWITZERLAND, AUSTRIA, BELGIUM, GERMANY, LIECHTENSTEIN, LUXEMBOURG, NETHERLANDS), COUNTRIES.toSet())
    }
}
