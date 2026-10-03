package io.github.buerlino.gridload.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class IoTest {
    @Test
    fun sendsTheUserAgent() {
        var sent: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            sent = exchange.requestHeaders.getFirst("User-Agent")
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".encodeToByteArray()) }
        }
        server.start()
        try {
            httpGet("http://127.0.0.1:${server.address.port}/", 3000)
        } finally {
            server.stop(0)
        }
        assertEquals("GridLoad/$APP_VERSION (+https://github.com/buerlino/gridload)", sent)
    }

    @Test
    fun versionMatchesTheApp() {
        // Tests run in core/, so the app's build file is next door.
        val versionName = Regex("""versionName = "(.+)"""")
            .find(File("../app/build.gradle.kts").readText())!!.groupValues[1]
        assertEquals(versionName, APP_VERSION)
    }
}
