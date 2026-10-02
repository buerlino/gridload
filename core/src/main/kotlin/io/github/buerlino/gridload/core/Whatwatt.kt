package io.github.buerlino.gridload.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

/**
 * One reading from a whatwatt Go's `/api/v1/report`. Every value is null when the meter doesn't
 * send it; trust them only when [ok].
 */
data class MeterReading(
    /** Current draw from the grid, kW. */
    val powerKw: Double?,
    /** `"OK"` when the device reads the meter; otherwise e.g. `"KEY REQUIRED"` or `"NOT CONNECTED"`. */
    val meterStatus: String?,
) {
    val ok: Boolean get() = meterStatus == "OK"
}

/** Accepts what users type: `192.168.1.50`, `whatwatt-A1B2C3.local`, or a full `http://` URL. */
fun reportUrl(address: String): String {
    val base = address.trim().trimEnd('/')
    return (if ("://" in base) base else "http://$base") + "/api/v1/report"
}

/**
 * Blocking fetch of the current reading from the whatwatt Go at [address] on the home network.
 * Call off the main thread. Without the Plus licence the device answers 404, with Device
 * Protection on 401 (HTTP Digest, which we don't do).
 */
fun fetchMeterReading(address: String): MeterReading {
    val conn = URI(reportUrl(address)).toURL().openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
        return parseMeterReading(conn.inputStream.bufferedReader().use { it.readText() })
    } finally {
        conn.disconnect()
    }
}

private val json = Json { ignoreUnknownKeys = true }

fun parseMeterReading(body: String): MeterReading {
    val r = json.decodeFromString<Report>(body)
    return MeterReading(
        powerKw = r.report?.instantaneous_power?.active?.positive?.total,
        meterStatus = r.meter?.status,
    )
}

@Serializable
private class Report(val report: Values? = null, val meter: Meter? = null)

@Suppress("PropertyName")
@Serializable
private class Values(val instantaneous_power: Flow? = null)

@Serializable
private class Flow(val active: Directions? = null)

@Serializable
private class Directions(val positive: Total? = null)

@Serializable
private class Total(val total: Double? = null)

@Serializable
private class Meter(val status: String? = null)
