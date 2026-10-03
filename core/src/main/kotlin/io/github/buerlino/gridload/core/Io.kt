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

/** The app's `versionName`; bump it with each release (`IoTest` checks they match). */
internal const val APP_VERSION = "0.12.0"

/** Sent with every request, so API operators can see which client calls them and reach the project. */
internal const val USER_AGENT = "GridLoad/$APP_VERSION (+https://github.com/buerlino/gridload)"

/** Blocking GET of [url]'s JSON body; anything but 200 throws [HttpException]. Call off the main thread. */
internal fun httpGet(url: String, timeoutMillis: Int): String = httpRequest("GET", url, timeoutMillis)

/**
 * Blocking request; returns the body of a 2xx answer, anything else throws [HttpException].
 * A malformed [url] throws [IllegalArgumentException]. [body] goes as [contentType]. Call off
 * the main thread.
 */
internal fun httpRequest(
    method: String,
    url: String,
    timeoutMillis: Int,
    body: String? = null,
    contentType: String = "application/json",
): String {
    val conn = URI.create(url).toURL().openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = timeoutMillis
        conn.readTimeout = timeoutMillis
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", USER_AGENT)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        if (conn.responseCode !in 200..299) throw HttpException(conn.responseCode)
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
