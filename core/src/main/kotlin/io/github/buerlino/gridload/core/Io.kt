package io.github.buerlino.gridload.core

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class HttpException(val code: Int) : IOException("HTTP $code")

/** For the price APIs and the whatwatt, which send more fields than the app reads. */
internal val json = Json { ignoreUnknownKeys = true }

/** Blocking GET of [url]'s JSON body; anything but 200 throws [HttpException]. Call off the main thread. */
internal fun httpGet(url: String, timeoutMillis: Int): String {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = timeoutMillis
        conn.readTimeout = timeoutMillis
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw HttpException(conn.responseCode)
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

/** Writes a copy and moves it over the file, so a crash can't leave half a file. */
internal fun File.writeAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
