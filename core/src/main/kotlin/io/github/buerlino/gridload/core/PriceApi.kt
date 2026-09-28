package io.github.buerlino.gridload.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class HttpException(val code: Int) : IOException("HTTP $code")

/** Blocking fetch of today's prices for [region]. Call off the main thread. The API allows only a few requests per window. */
fun fetchPrices(region: Region): List<PriceSlot> {
    val conn = URI(region.pricesUrl).toURL().openConnection() as HttpURLConnection
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
