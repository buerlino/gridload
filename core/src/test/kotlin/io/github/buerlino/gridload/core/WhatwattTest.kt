package io.github.buerlino.gridload.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatwattTest {
    // The documented /api/v1/report shape, not yet a real response from CKW's meter.
    private val sample = javaClass.getResource("/whatwatt-report-sample.json")!!.readText()

    @Test
    fun parsesTheFieldsTheAppUses() {
        val reading = parseMeterReading(sample)
        assertEquals(1.442, reading.powerKw)
        assertTrue(reading.ok)
    }

    @Test
    fun missingValuesAreNullAndNotOk() {
        val reading = parseMeterReading("""{"meter":{"status":"NO DATA"},"report":{"id":3}}""")
        assertNull(reading.powerKw)
        assertFalse(reading.ok)
        assertFalse(parseMeterReading("{}").ok)
    }

    @Test
    fun acceptsTypedAddresses() {
        assertEquals("http://192.168.1.50/api/v1/report", reportUrl(" 192.168.1.50 "))
        assertEquals("http://whatwatt-a1b2c3.local/api/v1/report", reportUrl("whatwatt-a1b2c3.local/"))
        assertEquals("http://192.168.1.50:8080/api/v1/report", reportUrl("http://192.168.1.50:8080/"))
    }

    @Test
    fun fetchesFromTheDevice() = withDevice(200, sample) { address ->
        assertEquals(1.442, fetchMeterReading(address).powerKw)
    }

    @Test
    fun withoutPlusLicenceTheDeviceAnswers404() = withDevice(404, "License required") { address ->
        assertEquals(404, assertFailsWith<HttpException> { fetchMeterReading(address) }.code)
    }

    /** A fake whatwatt on localhost that answers `/api/v1/report` with [code] and [body]. */
    private fun withDevice(code: Int, body: String, test: (address: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/report") { exchange ->
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            test("127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }
}
