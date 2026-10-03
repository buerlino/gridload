package io.github.buerlino.gridload.core

import java.time.LocalTime
import java.time.ZoneId

/**
 * A country with at least one region; [code] is ISO 3166-1 alpha-2, as the phone reports it.
 * [zone] is where its tariff days run from midnight to midnight; a region can have its own.
 * [currency] is what its regions' prices are in.
 */
data class Country(val code: String, val name: String, val flag: String, val zone: ZoneId, val currency: Currency) {
    val label: String get() = "$flag $name"
}

val SWITZERLAND = Country("CH", "Switzerland", "🇨🇭", ZoneId.of("Europe/Zurich"), Currency.CHF)

/** The countries the user picks from before the region, so each list stays short. */
val COUNTRIES: List<Country> = listOf(SWITZERLAND)

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
 */
data class Region(
    val id: String,
    val name: String,
    val utility: String,
    val country: Country,
    val source: PriceSource,
    val tomorrowFrom: LocalTime,
    val zone: ZoneId = country.zone,
) {
    /** How the app lists it: "Central Switzerland (CKW)". */
    val label: String get() = "$name ($utility)"
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
)

private fun ekz(tariff: String) = PriceSource.Vse("https://api.tariffs.ekz.ch/v1/tariffs?tariff_type=integrated&tariff_name=$tariff")
private fun primeo(tariff: String) = PriceSource.Vse("https://tarife.primeo-energie.ch/api/v1/tariffs?tariff_type=integrated&tariff_name=$tariff")

/**
 * All selectable regions, sorted by name. There is no default: the user picks one on first
 * start. Installs from before that got [CKW], the only region then, without saving it.
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
).sortedBy { it.name }
