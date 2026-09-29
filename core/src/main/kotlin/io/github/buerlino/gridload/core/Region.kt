package io.github.buerlino.gridload.core

import java.time.LocalTime
import java.time.ZoneId

/**
 * A supply region, served by one utility's dynamic-price API. All utilities so far follow the
 * VSE/AES standard (the CKW schema in CLAUDE.md). [pricesUrl] already has a query string.
 * [tomorrowFrom] is when tomorrow's prices are out (local time, rounded up from observations).
 */
data class Region(
    val id: String,
    val name: String,
    val utility: String,
    val pricesUrl: String,
    val tomorrowFrom: LocalTime,
)

val CKW = Region(
    id = "ckw",
    name = "Central Switzerland",
    utility = "CKW",
    pricesUrl = "https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise" +
        "?tariff_type=integrated&tariff_name=home_dynamic",
    tomorrowFrom = LocalTime.NOON, // published around 11:20
)

private const val EKZ_URL = "https://api.tariffs.ekz.ch/v1/tariffs?tariff_type=integrated&tariff_name="
private const val PRIMEO_URL = "https://tarife.primeo-energie.ch/api/v1/tariffs?tariff_type=integrated&tariff_name="

/**
 * All selectable regions, sorted by name. There is no default: the user picks one on first
 * start. Installs from before that got [CKW], the only region then, without saving it.
 */
val REGIONS: List<Region> = listOf(
    CKW,
    // EKZ publishes around 17:50.
    Region("ekz", "Canton of Zurich", "EKZ", EKZ_URL + "integrated_400D", LocalTime.of(18, 0)),
    Region("ekz_einsiedeln", "Einsiedeln", "EKZ Einsiedeln", EKZ_URL + "integrated_400D_E", LocalTime.of(18, 0)),
    // Groupe E has one dynamic tariff (Vario) and takes no tariff_name; published around 14:50.
    Region(
        "groupe_e", "Fribourg and Neuchâtel", "Groupe E",
        "https://api.tariffs.groupe-e.ch/v2/tariffs?tariff_type=integrated", LocalTime.of(15, 0),
    ),
    // Primeo's three grid areas, all published around 17:30.
    Region("primeo", "Northwestern Switzerland", "Primeo Energie", PRIMEO_URL + "NetzDynamisch", LocalTime.of(18, 0)),
    Region("primeo_avag", "Olten area", "AVAG", PRIMEO_URL + "NetzDynamischAVAG", LocalTime.of(18, 0)),
    Region("primeo_elag", "Gretzenbach", "ELAG", PRIMEO_URL + "NetzDynamischELAG", LocalTime.of(18, 0)),
).sortedBy { it.name }

/** All regions are Swiss so far: the tariff day runs from local midnight to midnight. */
val TARIFF_ZONE: ZoneId = ZoneId.of("Europe/Zurich")
