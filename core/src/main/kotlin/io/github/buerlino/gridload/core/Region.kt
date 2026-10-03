package io.github.buerlino.gridload.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * A country with at least one region; [code] is ISO 3166-1 alpha-2, as the phone reports it.
 * [zone] is where its tariff days run from midnight to midnight; a region can have its own.
 * [currency] is what its regions' prices are in. [vat] is its standard VAT rate in %, the
 * default for the own price in its spot regions; null where it has none.
 */
data class Country(val code: String, val name: String, val flag: String, val zone: ZoneId, val currency: Currency, val vat: Double? = null) {
    val label: String get() = "$flag $name"
}

val SWITZERLAND = Country("CH", "Switzerland", "🇨🇭", ZoneId.of("Europe/Zurich"), Currency.CHF)
val AUSTRIA = Country("AT", "Austria", "🇦🇹", ZoneId.of("Europe/Vienna"), Currency.EUR, vat = 20.0)
val BELGIUM = Country("BE", "Belgium", "🇧🇪", ZoneId.of("Europe/Brussels"), Currency.EUR, vat = 6.0)
val GERMANY = Country("DE", "Germany", "🇩🇪", ZoneId.of("Europe/Berlin"), Currency.EUR, vat = 19.0)
val LUXEMBOURG = Country("LU", "Luxembourg", "🇱🇺", ZoneId.of("Europe/Luxembourg"), Currency.EUR, vat = 8.0)
val NETHERLANDS = Country("NL", "Netherlands", "🇳🇱", ZoneId.of("Europe/Amsterdam"), Currency.EUR, vat = 21.0)

// Households pay CHF, but its market price comes in € from Energy-Charts, so it shows € for now.
val LIECHTENSTEIN = Country("LI", "Liechtenstein", "🇱🇮", ZoneId.of("Europe/Vaduz"), Currency.EUR, vat = 8.1)

/** The countries the user picks from before the region, so each list stays short; sorted by name. */
val COUNTRIES: List<Country> = listOf(AUSTRIA, BELGIUM, GERMANY, LIECHTENSTEIN, LUXEMBOURG, NETHERLANDS, SWITZERLAND)

/** The first of [codes] (e.g. the SIM's country, then the locale's) that is in [COUNTRIES]. */
fun countryOf(vararg codes: String?): Country? =
    codes.firstNotNullOfOrNull { code -> COUNTRIES.find { it.code.equals(code, ignoreCase = true) } }

/** Where a region's prices come from. */
sealed interface PriceSource {
    /** A utility's dynamic tariff in the VSE/AES format (the CKW schema in CLAUDE.md); [url] already has a query string. */
    data class Vse(val url: String) : PriceSource

    /** The day-ahead market price of the bidding zone [bzn] (e.g. "AT", "DE-LU"), from Energy-Charts. */
    data class EnergyCharts(val bzn: String) : PriceSource
}

/**
 * A supply region and where its prices come from: a utility's dynamic tariff or the market price.
 * [tomorrowFrom] is when tomorrow's prices are out (local time, rounded up from observations).
 * [zone] is the country's unless the country spans several (e.g. Spain with the Canaries).
 * [minimumKw] is the least peak its tariff bills a month: null where no peak is billed, 0.0 where
 * one is billed with no minimum. [peakFrom] is the day billing the peak starts, where it hasn't yet.
 * [priceNote] and [peakNote] are what the help says about it alone, under prices and peak load.
 */
data class Region(
    val id: String,
    val name: String,
    val utility: String,
    val country: Country,
    val source: PriceSource,
    val tomorrowFrom: LocalTime,
    val zone: ZoneId = country.zone,
    val minimumKw: Double? = null,
    val peakFrom: LocalDate? = null,
    val priceNote: String? = null,
    val peakNote: String? = null,
) {
    /** How the app lists it: "Central Switzerland (CKW)". */
    val label: String get() = "$name ($utility)"

    /** Whether its price is the market price, which the user's add-on and VAT turn into their own. */
    val isSpot: Boolean get() = source is PriceSource.EnergyCharts
}

val CKW = Region(
    id = "ckw",
    name = "Central Switzerland",
    utility = "CKW",
    country = SWITZERLAND,
    source = PriceSource.Vse(
        "https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise" +
            "?tariff_type=integrated&tariff_name=home_dynamic",
    ),
    tomorrowFrom = LocalTime.NOON, // published around 11:20
    minimumKw = 0.0, // Home dynamic bills the month's highest quarter hour, with no minimum
)

private fun ekz(tariff: String) = PriceSource.Vse("https://api.tariffs.ekz.ch/v1/tariffs?tariff_type=integrated&tariff_name=$tariff")
private fun primeo(tariff: String) = PriceSource.Vse("https://tarife.primeo-energie.ch/api/v1/tariffs?tariff_type=integrated&tariff_name=$tariff")

/**
 * A region on the day-ahead market price of the bidding zone [bzn], from Energy-Charts. The
 * auction's results come out at about 12:55 CET.
 */
private fun market(
    id: String,
    name: String,
    country: Country,
    bzn: String,
    minimumKw: Double? = null,
    peakFrom: LocalDate? = null,
    priceNote: String? = null,
    peakNote: String? = null,
) = Region(
    id, name, "market price", country, PriceSource.EnergyCharts(bzn), LocalTime.of(13, 15),
    minimumKw = minimumKw, peakFrom = peakFrom, priceNote = priceNote, peakNote = peakNote,
)

/**
 * All selectable regions, sorted by name. There is no default: the user picks one on first
 * start. Installs from before that got [CKW], the only region then, without saving it. Of the
 * Swiss ones only CKW bills a household peak (checked 2026-10-03; Groupe E unconfirmed, treated
 * as none). Outside Switzerland, Austria bills the month's highest quarter hour from 1 Jan 2027
 * with at least 2 kW, and Flanders at least 2.5 kW (on the average of the last 12 months).
 */
val REGIONS: List<Region> = listOf(
    CKW,
    // EKZ publishes around 17:50.
    Region("ekz", "Canton of Zurich", "EKZ", SWITZERLAND, ekz("integrated_400D"), LocalTime.of(18, 0)),
    Region("ekz_einsiedeln", "Einsiedeln", "EKZ Einsiedeln", SWITZERLAND, ekz("integrated_400D_E"), LocalTime.of(18, 0)),
    // Groupe E has one dynamic tariff (Vario) and takes no tariff_name; published around 14:50.
    Region(
        "groupe_e", "Fribourg and Neuchâtel", "Groupe E", SWITZERLAND,
        PriceSource.Vse("https://api.tariffs.groupe-e.ch/v2/tariffs?tariff_type=integrated"), LocalTime.of(15, 0),
    ),
    // Primeo's three grid areas, all published around 17:30.
    Region("primeo", "Northwestern Switzerland", "Primeo Energie", SWITZERLAND, primeo("NetzDynamisch"), LocalTime.of(18, 0)),
    Region("primeo_avag", "Olten area", "AVAG", SWITZERLAND, primeo("NetzDynamischAVAG"), LocalTime.of(18, 0)),
    Region("primeo_elag", "Gretzenbach", "ELAG", SWITZERLAND, primeo("NetzDynamischELAG"), LocalTime.of(18, 0)),
    market("at", "Austria", AUSTRIA, "AT", minimumKw = 2.0, peakFrom = LocalDate.of(2027, 1, 1)),
    market(
        "be_flanders", "Flanders", BELGIUM, "BE", minimumKw = 2.5,
        peakNote = "Flanders bills the average of the last 12 months.",
    ),
    market(
        "be_wallonia_brussels", "Wallonia and Brussels", BELGIUM, "BE",
        priceNote = "In Wallonia and Brussels, the time-of-use grid fee isn't in the colour.",
    ),
    market("de", "Germany", GERMANY, "DE-LU"),
    market("lu", "Luxembourg", LUXEMBOURG, "DE-LU"),
    market("nl", "Netherlands", NETHERLANDS, "NL"),
    // Liechtenstein is in the Swiss bidding zone, whose market price is hourly.
    market("li", "Liechtenstein", LIECHTENSTEIN, "CH"),
).sortedBy { it.name }
