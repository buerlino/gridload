package io.github.buerlino.gridload.core

/**
 * A supply region, served by one utility's dynamic-price API. The API schema and the
 * classification are per utility; today every region uses the CKW format (see CLAUDE.md).
 */
data class Region(val id: String, val name: String, val utility: String, val pricesUrl: String)

val CKW = Region(
    id = "ckw",
    name = "Central Switzerland",
    utility = "CKW",
    pricesUrl = "https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise",
)

/** All selectable regions. The first one is the default. */
val REGIONS: List<Region> = listOf(CKW)
