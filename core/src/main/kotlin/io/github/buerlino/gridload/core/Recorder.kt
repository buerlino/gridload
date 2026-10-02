package io.github.buerlino.gridload.core

import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/*
 * The GridLoad recorder: a Berry script the app installs in the whatwatt's one script slot. It
 * records every quarter hour around the clock into small day files on the whatwatt's SD card,
 * which the app copies when it's open. Peak load needs it; there is no fallback.
 */

/** The script, `gridload_recorder.be`; its first line is `# GridLoad recorder <version>`. */
val RECORDER_SCRIPT: String by lazy { Recording::class.java.getResource("/gridload_recorder.be")!!.readText() }

val RECORDER_VERSION: Int by lazy { recorderVersion(RECORDER_SCRIPT)!! }

/** The version of a GridLoad recorder script, or null for anyone else's script. */
fun recorderVersion(script: String): Int? =
    Regex("""^# GridLoad recorder (\d+)""").find(script)?.groupValues?.get(1)?.toIntOrNull()

/** The day file for [date] (local), `GL261002.CSV`: an 8.3 name, to be safe on FAT. */
fun dayFileName(date: LocalDate) = "GL%02d%02d%02d.CSV".format(date.year % 100, date.monthValue, date.dayOfMonth)

private val DAY_FILE = Regex("""GL(\d{2})(\d{2})(\d{2})\.CSV""", RegexOption.IGNORE_CASE)

fun dayFileDate(name: String): LocalDate? = DAY_FILE.matchEntire(name)?.destructured?.let { (y, m, d) ->
    runCatching { LocalDate.of(2000 + y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
}

/** What the recorder's day files hold, merged and sorted. */
data class Recording(
    val quarters: List<Quarter> = emptyList(),
    /** The newest quarter's end and the register there: where the current quarter starts. */
    val lastEnd: Pair<Instant, Double>? = null,
    /** Each start of the script: after an install or update, or when the whatwatt restarted. */
    val starts: List<Instant> = emptyList(),
)

/**
 * Parses day files: `1790975700,0.0523,10529.5040` is a quarter (its start in UTC epoch seconds,
 * kWh, the register at its end), `1790975071,start` a start of the script. Other lines (e.g. one
 * cut short when the card was pulled) are skipped.
 */
fun parseRecording(files: List<String>): Recording {
    val quarters = sortedMapOf<Instant, Pair<Double, Double>>()
    val starts = sortedSetOf<Instant>()
    for (line in files.flatMap { it.lines() }) {
        val parts = line.trim().split(',')
        val time = parts[0].toLongOrNull()?.let(Instant::ofEpochSecond) ?: continue
        if (parts.size == 2 && parts[1] == "start") {
            starts += time
        } else if (parts.size == 3) {
            val kwh = parts[1].toDoubleOrNull() ?: continue
            val register = parts[2].toDoubleOrNull() ?: continue
            quarters[time] = kwh to register
        }
    }
    val last = quarters.entries.lastOrNull()
    return Recording(
        quarters = quarters.map { (start, values) -> Quarter(start, values.first) },
        lastEnd = last?.let { (start, values) -> start.plusSeconds(QUARTER_SECONDS) to values.second },
        starts = starts.toList(),
    )
}

/** How long after a quarter hour ends its line may take to arrive before it counts as missing. */
private val GRACE = Duration.ofSeconds(60)

private fun YearMonth.start(): Instant = atDay(1).atStartOfDay(TARIFF_ZONE).toInstant()

/**
 * The quarter hours of [month] the recorder should have saved by [until] but didn't: from the
 * month's start, or from its first quarter if it started recording later in the month.
 */
fun missingQuarters(recording: Recording, month: YearMonth, until: Instant): List<Instant> {
    val first = recording.quarters.firstOrNull()?.start ?: return emptyList()
    val have = recording.quarters.mapTo(HashSet()) { it.start }
    val lastDueEnd = minOf(quarterStart(until.minus(GRACE)), month.plusMonths(1).start())
    return generateSequence(maxOf(first, month.start())) { it.plusSeconds(QUARTER_SECONDS) }
        .takeWhile { !it.plusSeconds(QUARTER_SECONDS).isAfter(lastDueEnd) }
        .filter { it !in have }
        .toList()
}

/** The recorder's first quarter when it started recording during [month], so the month's start is unknown. */
fun recordedSince(recording: Recording, month: YearMonth): Instant? =
    recording.quarters.firstOrNull()?.start?.takeIf { it > month.start() && YearMonth.from(it.atZone(TARIFF_ZONE)) == month }

/** What the whatwatt says about the recorder; null where it didn't say. */
data class RecorderStatus(
    /** The script in the whatwatt's one slot; null when the slot is empty. */
    val script: String?,
    /** The interpreter's state: `"RUNNING"` while the script runs. */
    val state: String?,
    /** Whether the script starts by itself, [runDelaySeconds] after the whatwatt boots. */
    val autoRun: Boolean?,
    val runDelaySeconds: Long?,
    val sdCard: Boolean?,
    val secondsSinceBoot: Long?,
)

/** The recorder's state, most urgent first; each but [Ok] and [Waiting] stops peak load from recording. */
sealed interface RecorderCheck {
    data object NoSdCard : RecorderCheck
    data object NotInstalled : RecorderCheck
    /** Someone else's script is in the slot; GridLoad never replaces it. */
    data object Foreign : RecorderCheck
    /** An older GridLoad recorder: the app updates it. */
    data object Outdated : RecorderCheck
    /** The whatwatt restarted, and the script starts by itself [at]. */
    data class Starting(val at: Instant) : RecorderCheck
    data class Stopped(val state: String?) : RecorderCheck
    /** It runs, but won't start again after the whatwatt restarts. */
    data object NoAutoRun : RecorderCheck
    /** It runs, but nothing came [since] then (a quarter's end, or the script's start if [started]). */
    data class Silent(val since: Instant, val started: Boolean) : RecorderCheck
    /** It started; its first quarter hour is saved at [firstAt]. */
    data class Waiting(val firstAt: Instant) : RecorderCheck
    data object Ok : RecorderCheck
}

/** Checks the recorder from its [status] and the [recording] copied just now. */
fun checkRecorder(status: RecorderStatus, recording: Recording, now: Instant): RecorderCheck {
    if (status.sdCard == false) return RecorderCheck.NoSdCard
    val script = status.script ?: return RecorderCheck.NotInstalled
    val version = recorderVersion(script) ?: return RecorderCheck.Foreign
    if (version < RECORDER_VERSION) return RecorderCheck.Outdated
    if (status.state != "RUNNING") {
        val boot = status.secondsSinceBoot
        val delay = status.runDelaySeconds
        if (status.autoRun == true && boot != null && delay != null && boot < delay + 30) {
            return RecorderCheck.Starting(now.plusSeconds((delay - boot).coerceAtLeast(0)))
        }
        return RecorderCheck.Stopped(status.state)
    }
    if (status.autoRun != true) return RecorderCheck.NoAutoRun
    val lastEnd = recording.lastEnd?.first
    val lastStart = recording.starts.lastOrNull()
    if (lastStart != null && (lastEnd == null || lastStart >= lastEnd)) {
        // The script needs a boundary to begin its first quarter, which it saves at the next one.
        val first = quarterStart(lastStart).plusSeconds(2 * QUARTER_SECONDS)
        return if (now < first.plus(GRACE)) RecorderCheck.Waiting(first) else RecorderCheck.Silent(lastStart, started = true)
    }
    if (lastEnd != null && now >= lastEnd.plusSeconds(QUARTER_SECONDS).plus(GRACE)) return RecorderCheck.Silent(lastEnd, started = false)
    return RecorderCheck.Ok
}

/** When the next line is due, so the app copies the day files then; null while nothing is expected. */
fun nextLineDue(recording: Recording): Instant? {
    val lastEnd = recording.lastEnd?.first
    val lastStart = recording.starts.lastOrNull()
    return when {
        lastStart != null && (lastEnd == null || lastStart >= lastEnd) -> quarterStart(lastStart).plusSeconds(2 * QUARTER_SECONDS)
        lastEnd != null -> lastEnd.plusSeconds(QUARTER_SECONDS)
        else -> null
    }
}

/**
 * The app's copy of the recorder's day files, under the same names. Past days don't change, so
 * a sync copies only the newest day it has and the ones after it. Blocking; call off the main thread.
 */
class RecorderFiles(private val dir: File) {
    /** The day files from [from] on. */
    fun load(from: LocalDate): Recording = parseRecording(
        dir.listFiles().orEmpty()
            .filter { file -> dayFileDate(file.name)?.let { it >= from } == true }
            .sortedBy { it.name }
            .map { it.readText() },
    )

    /**
     * Copies new recorder data for [from] to [today]. With no day file of that span yet (a new
     * phone, or a new install), every one [list] names; afterwards the newest one here and the
     * days up to [today]. [download] returns null for a day without a file.
     */
    fun sync(from: LocalDate, today: LocalDate, list: () -> List<String>, download: (String) -> String?) {
        val newest = dir.listFiles().orEmpty().mapNotNull { dayFileDate(it.name) }.filter { it in from..today }.maxOrNull()
        val days = if (newest == null) {
            list().mapNotNull(::dayFileDate).filter { it in from..today }
        } else {
            generateSequence(newest) { it.plusDays(1) }.takeWhile { it <= today }.toList()
        }
        for (day in days) download(dayFileName(day))?.let { File(dir, dayFileName(day)).writeAtomically(it) }
    }
}

/** Reads what the whatwatt says about the recorder: three small requests. */
fun fetchRecorderStatus(address: String): RecorderStatus {
    val system = json.decodeFromString<SystemInfo>(httpGet(whatwattUrl(address, "/api/v1/system"), WHATWATT_TIMEOUT))
    val berry = json.decodeFromString<Settings>(httpGet(whatwattUrl(address, "/api/v1/settings"), WHATWATT_TIMEOUT)).services?.berry
    return RecorderStatus(
        script = fetchScript(address),
        state = system.services?.berry?.execution_status?.state,
        autoRun = berry?.auto_run,
        runDelaySeconds = berry?.run_delay,
        sdCard = system.sd_card?.installed,
        secondsSinceBoot = system.device?.time_since_boot,
    )
}

private fun fetchScript(address: String): String? = try {
    httpGet(whatwattUrl(address, "/api/v1/berry"), WHATWATT_TIMEOUT)
} catch (e: HttpException) {
    if (e.code == 404) null else throw e
}

/** The names of the files on the whatwatt's SD card; 503 without a card. */
fun listSdCard(address: String): List<String> =
    json.decodeFromString<SdListing>(httpGet(whatwattUrl(address, "/sdcard/"), WHATWATT_TIMEOUT)).files.map { it.name }

/** A day file from the SD card; null when there is none (the whatwatt answers 500 for a missing file). */
fun downloadDayFile(address: String, name: String): String? = try {
    httpGet(whatwattUrl(address, "/sdcard/$name"), WHATWATT_TIMEOUT)
} catch (e: HttpException) {
    if (e.code == 404 || e.code == 500) null else throw e
}

/**
 * Installs [RECORDER_SCRIPT], or replaces an older GridLoad recorder, and starts it; it then
 * starts by itself 60 s after each restart of the whatwatt (the shortest delay it allows).
 * Never replaces anyone else's script.
 */
fun installRecorder(address: String) {
    val current = fetchScript(address)
    check(current == null || recorderVersion(current) != null) { "Another script is installed" }
    berry(address, "PUT", "?run=false")
    httpRequest("POST", whatwattUrl(address, "/api/v1/berry"), WHATWATT_TIMEOUT, RECORDER_SCRIPT, "text/plain")
    setAutoRun(address, true)
    berry(address, "PUT", "?run=true")
}

/** Starts the installed recorder if it doesn't run, and makes it start by itself after a restart. */
fun startRecorder(address: String, running: Boolean) {
    setAutoRun(address, true)
    if (!running) berry(address, "PUT", "?run=true")
}

/** Stops and deletes the recorder. Its day files stay on the SD card. */
fun removeRecorder(address: String) {
    check(fetchScript(address)?.let(::recorderVersion) != null) { "Not GridLoad's script" }
    berry(address, "PUT", "?run=false")
    berry(address, "DELETE", "")
    setAutoRun(address, false)
}

private fun berry(address: String, method: String, query: String) =
    httpRequest(method, whatwattUrl(address, "/api/v1/berry$query"), WHATWATT_TIMEOUT)

private fun setAutoRun(address: String, on: Boolean) = httpRequest(
    "PUT", whatwattUrl(address, "/api/v1/settings"), WHATWATT_TIMEOUT,
    """{"services":{"berry":{"auto_run":$on,"run_delay":60}}}""",
)

@Suppress("PropertyName")
@Serializable
private class SystemInfo(val device: Device? = null, val services: Services? = null, val sd_card: SdCard? = null)

@Suppress("PropertyName")
@Serializable
private class Device(val time_since_boot: Long? = null)

@Serializable
private class Services(val berry: BerryStatus? = null)

@Suppress("PropertyName")
@Serializable
private class BerryStatus(val execution_status: ExecutionStatus? = null)

@Serializable
private class ExecutionStatus(val state: String? = null)

@Serializable
private class SdCard(val installed: Boolean? = null)

@Serializable
private class Settings(val services: SettingsServices? = null)

@Serializable
private class SettingsServices(val berry: BerrySettings? = null)

@Suppress("PropertyName")
@Serializable
private class BerrySettings(val auto_run: Boolean? = null, val run_delay: Long? = null)

@Serializable
private class SdListing(val files: List<SdFile> = emptyList())

@Serializable
private class SdFile(val name: String)
