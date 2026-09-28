package io.github.buerlino.gridload.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

const val PRICES_URL =
    "https://e-ckw-public-data.de-c1.eu1.cloudhub.io/api/v1/netzinformationen/energie/dynamische-preise"

class HttpException(val code: Int) : IOException("HTTP $code")

/** Blocking fetch of today's prices. Call off the main thread. The API allows only a few requests per window. */
fun fetchPrices(): List<PriceSlot> {
    val conn = URI(PRICES_URL).toURL().openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
        return parsePrices(conn.inputStream.bufferedReader().use { it.readText() })
    } finally {
        conn.disconnect()
    }
}
