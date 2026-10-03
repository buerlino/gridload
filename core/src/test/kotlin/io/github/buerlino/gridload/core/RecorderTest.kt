package io.github.buerlino.gridload.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecorderTest {
    private fun at(time: String) = Instant.parse("2026-10-02T${time}Z")
    private val zurich = SWITZERLAND.zone
    private fun line(start: String, kwh: Double, register: Double) = "${at(start).epochSecond},%.4f,%.4f".format(java.util.Locale.ROOT, kwh, register)
    private fun startLine(time: String) = "${at(time).epochSecond},start"

    @Test
    fun theScriptIsMarkedAndFitsTheSlot() {
        assertTrue(RECORDER_VERSION >= 2)
        assertTrue(RECORDER_SCRIPT.encodeToByteArray().size < 8191)
        assertTrue("print" !in RECORDER_SCRIPT)
        assertEquals(1, recorderVersion("# GridLoad recorder 1\nimport ww"))
        assertNull(recorderVersion("# my MQTT bridge\nimport ww"))
    }

    @Test
    fun namesDayFilesByLocalDate() {
        assertEquals("GL261002.CSV", dayFileName(LocalDate.of(2026, 10, 2)))
        assertEquals(LocalDate.of(2026, 10, 2), dayFileDate("GL261002.CSV"))
        assertNull(dayFileDate("20261002.CSV"))
        assertNull(dayFileDate("GL261302.CSV"))
    }

    @Test
    fun parsesAndMergesDayFiles() {
        val recording = parseRecording(
            listOf(
                // The real first line, from the test on 2026-10-02.
                "1790975700,0.2132,10529.8292\n",
                startLine("21:44:03") + "\n" + line("21:45:00", 0.1, 10529.9) + "\n1790976600,0.1\n",
            ),
        )
        assertEquals(listOf(Quarter(Instant.ofEpochSecond(1790975700), 0.2132), Quarter(at("21:45:00"), 0.1)), recording.quarters)
        assertEquals(at("22:00:00") to 10529.9, recording.lastEnd)
        assertEquals(listOf(at("21:44:03")), recording.starts)
        assertEquals(Recording(), parseRecording(emptyList()))
    }

    @Test
    fun findsTheMissingQuartersOfTheMonth() {
        val recording = parseRecording(listOf(listOf("21:00:00", "21:15:00", "21:45:00").joinToString("\n") { line(it, 0.1, 1.0) }))
        val october = YearMonth.of(2026, 10)
        // 21:30 is missing; 22:00 ends at 22:15 and isn't due until a minute later.
        assertEquals(listOf(at("21:30:00")), missingQuarters(recording, october, at("22:15:30"), zurich))
        assertEquals(listOf(at("21:30:00"), at("22:00:00")), missingQuarters(recording, october, at("22:16:00"), zurich))
        // It started recording in October, so the time before isn't counted, and is unknown.
        assertEquals(at("21:00:00"), recordedSince(recording, october, zurich))
        assertEquals(emptyList(), missingQuarters(Recording(), october, at("22:16:00"), zurich))
        // Recording since September: October counts from its first quarter, 30 Sep 22:00 UTC.
        val sinceSeptember = parseRecording(listOf(line("21:00:00", 0.1, 1.0), "${Instant.parse("2026-09-30T21:45:00Z").epochSecond},0.1,1.0"))
        assertNull(recordedSince(sinceSeptember, october, zurich))
        assertEquals(Instant.parse("2026-09-30T22:00:00Z"), missingQuarters(sinceSeptember, october, at("22:16:00"), zurich).first())
    }

    @Test
    fun findsEachDaysHighest() {
        // 21:45 UTC on 2 Oct is 23:45 in Zurich, 22:00 UTC already 3 Oct.
        val recording = parseRecording(listOf(listOf(line("21:00:00", 0.1, 1.0), line("21:45:00", 0.3, 1.3), line("22:00:00", 0.2, 1.5)).joinToString("\n")))
        assertEquals(
            listOf(LocalDate.of(2026, 10, 2) to Quarter(at("21:45:00"), 0.3), LocalDate.of(2026, 10, 3) to Quarter(at("22:00:00"), 0.2)),
            dailyHighest(recording, YearMonth.of(2026, 10), zurich),
        )
        assertEquals(emptyList(), dailyHighest(recording, YearMonth.of(2026, 9), zurich))
    }

    @Test
    fun laysOutADayInQuarters() {
        assertEquals(96, DayQuarters(LocalDate.of(2026, 10, 3), zurich, emptyList()).slots)
        // 29 Mar: 02:00 to 03:00 doesn't exist.
        val spring = DayQuarters(LocalDate.of(2026, 3, 29), zurich, emptyList())
        assertEquals(92, spring.slots)
        assertEquals(20, spring.hourSlot(6))
        // 25 Oct: 02:00 to 03:00 twice; 02:15 CEST is 00:15 UTC, 02:15 CET 01:15 UTC.
        val first = Quarter(Instant.parse("2026-10-25T00:15:00Z"), 0.1)
        val second = Quarter(Instant.parse("2026-10-25T01:15:00Z"), 0.3)
        val dayBefore = Quarter(Instant.parse("2026-10-24T21:45:00Z"), 0.5)
        val autumn = DayQuarters(LocalDate.of(2026, 10, 25), zurich, listOf(dayBefore, first, second))
        assertEquals(100, autumn.slots)
        assertEquals(28, autumn.hourSlot(6))
        assertEquals(listOf(first, second), autumn.quarters)
        assertEquals(listOf(9, 13), autumn.quarters.map { autumn.slot(it.start) })
        assertEquals(second, autumn.highest)
        assertEquals(99, autumn.slot(Instant.parse("2026-10-25T22:45:00Z")))
        assertNull(spring.highest)
    }

    private val running = RecorderStatus(RECORDER_SCRIPT, "RUNNING", autoRun = true, runDelaySeconds = 60, sdCard = true, secondsSinceBoot = 9000)
    private val recorded = parseRecording(listOf(line("21:30:00", 0.1, 1.0)))

    @Test
    fun checksTheRecorder() {
        assertEquals(RecorderCheck.Ok, checkRecorder(running, recorded, at("21:50:00")))
        assertEquals(RecorderCheck.NoSdCard, checkRecorder(running.copy(sdCard = false), recorded, at("21:50:00")))
        assertEquals(RecorderCheck.NotInstalled, checkRecorder(running.copy(script = null), recorded, at("21:50:00")))
        assertEquals(RecorderCheck.Foreign, checkRecorder(running.copy(script = "# mine\n"), recorded, at("21:50:00")))
        assertEquals(RecorderCheck.Outdated, checkRecorder(running.copy(script = "# GridLoad recorder 1\n"), recorded, at("21:50:00")))
        assertEquals(RecorderCheck.Stopped("STOPPED"), checkRecorder(running.copy(state = "STOPPED"), recorded, at("21:50:00")))
        assertEquals(RecorderCheck.NoAutoRun, checkRecorder(running.copy(autoRun = false), recorded, at("21:50:00")))
        // 20 s after a restart, it starts by itself 40 s later.
        assertEquals(
            RecorderCheck.Starting(at("21:50:40")),
            checkRecorder(running.copy(state = "STOPPED", secondsSinceBoot = 20), recorded, at("21:50:00")),
        )
    }

    @Test
    fun aRecorderThatSavesNothingIsSilent() {
        // The 21:45 quarter ends at 22:00 and is due a minute later.
        assertEquals(RecorderCheck.Ok, checkRecorder(running, recorded, at("22:00:59")))
        assertEquals(RecorderCheck.Silent(at("21:45:00"), started = false), checkRecorder(running, recorded, at("22:01:00")))
        assertEquals(at("22:00:00"), nextLineDue(recorded))
    }

    @Test
    fun afterAStartTheFirstQuarterTakesUpToHalfAnHour() {
        val started = parseRecording(listOf(line("21:30:00", 0.1, 1.0), startLine("21:47:10")))
        // Its first boundary is 22:00, so the first quarter is saved at 22:15.
        assertEquals(RecorderCheck.Waiting(at("22:15:00")), checkRecorder(running, started, at("22:10:00")))
        assertEquals(RecorderCheck.Silent(at("21:47:10"), started = true), checkRecorder(running, started, at("22:16:00")))
        assertEquals(at("22:15:00"), nextLineDue(started))
        assertNull(nextLineDue(Recording()))
    }

    @Test
    fun syncsAllDaysFirstThenOnlyTheNewest() {
        val dir = Files.createTempDirectory("recorder").toFile()
        try {
            val files = RecorderFiles(dir)
            val from = LocalDate.of(2026, 9, 1)
            val sd = mutableMapOf(
                "GL260831.CSV" to "old",
                "GL260915.CSV" to line("10:00:00", 0.1, 1.0),
                "GL261001.CSV" to line("11:00:00", 0.2, 1.2),
                "20261001.CSV" to "the CSV log",
            )
            val downloaded = mutableListOf<String>()
            fun sync(today: LocalDate) = files.sync(from, today, list = { sd.keys.toList() }) { name -> downloaded += name; sd[name] }

            sync(LocalDate.of(2026, 10, 2))
            assertEquals(listOf("GL260915.CSV", "GL261001.CSV"), downloaded)
            assertEquals(2, files.load(from).quarters.size)

            downloaded.clear()
            sd["GL261002.CSV"] = line("12:00:00", 0.3, 1.5)
            sync(LocalDate.of(2026, 10, 2))
            assertEquals(listOf("GL261001.CSV", "GL261002.CSV"), downloaded)
            assertEquals(at("12:15:00") to 1.5, files.load(from).lastEnd)

            // Days without a file are skipped; the newest is copied again next time.
            downloaded.clear()
            sync(LocalDate.of(2026, 10, 4))
            assertEquals(listOf("GL261002.CSV", "GL261003.CSV", "GL261004.CSV"), downloaded)
            assertEquals(1, files.load(LocalDate.of(2026, 10, 2)).quarters.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun installsChecksAndRemovesOnTheDevice() = withDevice { device ->
        assertEquals(RecorderStatus(null, "STOPPED", false, 300, true, 4199), fetchRecorderStatus(device.address))
        installRecorder(device.address)
        assertEquals(RECORDER_SCRIPT, device.script)
        assertEquals(listOf("PUT ?run=false", "POST", "PUT settings", "PUT ?run=true"), device.calls)
        assertEquals("""{"services":{"berry":{"auto_run":true,"run_delay":60}}}""", device.settingsBody)
        // An older version is replaced; someone else's script never.
        device.script = "# GridLoad recorder 1\n"
        installRecorder(device.address)
        assertEquals(RECORDER_SCRIPT, device.script)
        device.script = "# my script\n"
        assertFailsWith<IllegalStateException> { installRecorder(device.address) }
        assertFailsWith<IllegalStateException> { removeRecorder(device.address) }
        assertEquals("# my script\n", device.script)

        device.script = RECORDER_SCRIPT
        device.calls.clear()
        removeRecorder(device.address)
        assertNull(device.script)
        assertEquals(listOf("PUT ?run=false", "DELETE", "PUT settings"), device.calls)
        assertEquals("""{"services":{"berry":{"auto_run":false,"run_delay":60}}}""", device.settingsBody)

        assertEquals(listOf("GL261002.CSV"), listSdCard(device.address))
        assertEquals("1790975700,0.2132,10529.8292\n", downloadDayFile(device.address, "GL261002.CSV"))
        assertNull(downloadDayFile(device.address, "GL261003.CSV"))
    }

    /** A fake whatwatt with the recorder's endpoints, as the real one answered on 2026-10-02. */
    private class Device(val address: String) {
        var script: String? = null
        var settingsBody: String? = null
        val calls = mutableListOf<String>()
    }

    private fun withDevice(test: (Device) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val device = Device("127.0.0.1:${server.address.port}")
        fun respond(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/v1/system") {
            respond(it, 200, """{"device":{"time_since_boot":4199},"services":{"berry":{"execution_status":{"state":"STOPPED"}}},"sd_card":{"installed":true,"size":488960}}""")
        }
        server.createContext("/api/v1/settings") { exchange ->
            if (exchange.requestMethod == "PUT") {
                device.calls += "PUT settings"
                device.settingsBody = exchange.requestBody.readBytes().decodeToString()
                respond(exchange, 200, "{}")
            } else {
                respond(exchange, 200, """{"services":{"berry":{"auto_run":false,"run_delay":300},"sd":{"enable":true}}}""")
            }
        }
        server.createContext("/api/v1/berry") { exchange ->
            val query = exchange.requestURI.query?.let { "?$it" }.orEmpty()
            when (exchange.requestMethod) {
                "GET" -> device.script?.let { respond(exchange, 200, it) } ?: respond(exchange, 404, "")
                "POST" -> {
                    device.calls += "POST"
                    device.script = exchange.requestBody.readBytes().decodeToString()
                    respond(exchange, 200, device.script!!)
                }
                "PUT" -> {
                    device.calls += "PUT $query"
                    respond(exchange, 200, """{"run":${query == "?run=true"}}""")
                }
                "DELETE" -> {
                    device.calls += "DELETE"
                    device.script = null
                    respond(exchange, 204, "")
                }
            }
        }
        server.createContext("/sdcard/") { exchange ->
            when (exchange.requestURI.path) {
                "/sdcard/" -> respond(exchange, 200, """{"path":"/sdcard/","files":[{"name":"GL261002.CSV","size":29,"type":"file"}]}""")
                "/sdcard/GL261002.CSV" -> respond(exchange, 200, "1790975700,0.2132,10529.8292\n")
                else -> respond(exchange, 500, "")
            }
        }
        server.start()
        try {
            test(device)
        } finally {
            server.stop(0)
        }
    }
}
