package io.github.buerlino.gridload

import io.github.buerlino.gridload.core.HttpException
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.Projection
import io.github.buerlino.gridload.core.QUARTER_SECONDS
import io.github.buerlino.gridload.core.Quarter
import io.github.buerlino.gridload.core.QuarterProjector
import io.github.buerlino.gridload.core.RecorderCheck
import io.github.buerlino.gridload.core.RecorderFiles
import io.github.buerlino.gridload.core.Recording
import io.github.buerlino.gridload.core.checkRecorder
import io.github.buerlino.gridload.core.dailyHighest
import io.github.buerlino.gridload.core.downloadDayFile
import io.github.buerlino.gridload.core.fetchMeterReading
import io.github.buerlino.gridload.core.fetchRecorderStatus
import io.github.buerlino.gridload.core.installRecorder
import io.github.buerlino.gridload.core.listSdCard
import io.github.buerlino.gridload.core.missingQuarters
import io.github.buerlino.gridload.core.nextLineDue
import io.github.buerlino.gridload.core.recordedSince
import io.github.buerlino.gridload.core.recorderVersion
import io.github.buerlino.gridload.core.removeRecorder
import io.github.buerlino.gridload.core.startRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the whatwatt measured and its recorder saved, as [UiState.meter]. */
data class MeterState(
    /** Whether the last test or reading got a meter reading; Settings then collapses the address. */
    val connected: Boolean = false,
    /** The last reading, kW drawn now; null when there is none or it failed. */
    val kw: Double? = null,
    /** Why there is no reading from the saved whatwatt, as one quiet line. */
    val problem: String? = null,
    /** This quarter hour, projected from the last good reading; null without one or without peak load. */
    val projection: Projection? = null,
    /** This month's highest recorded quarter hour, and last month's (the goal's first value). */
    val highest: Quarter? = null,
    val lastMonthHighest: Quarter? = null,
    /** The copied quarters of this and last month: the bars before the current one, and measuring appliances. */
    val quarters: List<Quarter> = emptyList(),
    /** This month's days with a recorded quarter, and each day's highest, for the history. */
    val days: List<Pair<LocalDate, Quarter>> = emptyList(),
    /** The result of the last Test, shown under the address field. */
    val testResult: String? = null,
    /** The recorder's state at the last check; null before one, or while the whatwatt can't be read. */
    val recorder: RecorderCheck? = null,
    /** Whether GridLoad's recorder is in the whatwatt's script slot, so it can be removed. */
    val recorderInstalled: Boolean = false,
    /** An install, start or removal under way, or why the last one failed. */
    val recorderAction: String? = null,
    /** Whether [recorderAction] says why the last one failed. */
    val recorderFailed: Boolean = false,
    /** This month's quarter hours the recorder should have saved but didn't. */
    val missing: List<Instant> = emptyList(),
    /** When the recorder restarted right after the last missing quarter, which explains it. */
    val restartAfterGap: Instant? = null,
    /** The recorder's first quarter, when it started recording during this month. */
    val recordedSince: Instant? = null,
)

/** While the address was edited this recently, it's probably still being typed, so it isn't read. */
private val TYPING = Duration.ofSeconds(10)

/**
 * Reads the whatwatt and, with peak load on, copies the quarter hours its recorder saved (into
 * [recorderDir], one file per day as on its SD card), checks the recorder and projects this
 * quarter hour; also runs the Test and the recorder's Install, Start and Remove in Settings.
 * Months and days are in [zone], the selected region's. Owned by [MainViewModel] and called on the main thread; each change goes to [publish].
 */
class WhatwattMeter(recorderDir: File, private val zone: () -> ZoneId, private val publish: (MeterState) -> Unit) {
    private val projector = QuarterProjector()
    private val files = RecorderFiles(recorderDir)
    private var recording = Recording()
    /** The month whose files are loaded. */
    private var month: YearMonth? = null
    /** When the recorder was last checked and its files copied; null to do it with the next reading. */
    private var syncedAt: Instant? = null
    /** An older recorder is updated once per start of the app, so a failing update doesn't loop. */
    private var updateTried = false
    private var acting = false
    private var state = MeterState()
    /** Bumped when the address changes or the whatwatt is switched, so a reading under way is dropped. */
    private var generation = 0
    private var editedAt: Instant? = null

    private fun set(change: (MeterState) -> MeterState) {
        state = change(state)
        publish(state)
    }

    /** Drops the last reading and check, which may be stale or from another device. */
    fun clearReading() {
        generation++
        syncedAt = null
        set { it.copy(connected = false, kw = null, problem = null, projection = null, recorder = null) }
    }

    /** The address was edited; [changed] when it's really another one, maybe another meter. */
    fun addressEdited(changed: Boolean) {
        if (changed) projector.reset()
        editedAt = Instant.now()
        clearReading()
        set { it.copy(testResult = null, recorderInstalled = false) }
    }

    /**
     * Called every few seconds while the app is visible: reads [address], if any. With [peak]
     * load on, it also shows the copied quarter hours at once, copies new ones when a line is
     * due, and projects this quarter hour. A failure shows a quiet line instead of the last
     * value, which would be stale.
     */
    suspend fun read(address: String?, peak: Boolean) {
        if (peak) loadMonth()
        if (address == null || editedAt?.let { Duration.between(it, Instant.now()) < TYPING } == true) return
        val generation = generation
        val reading = withContext(Dispatchers.IO) {
            try {
                fetchMeterReading(address)
            } catch (_: Exception) {
                null
            }
        }
        if (generation != this.generation) return
        val kw = reading?.powerKw?.takeIf { reading.ok }
        val problem = when {
            reading == null -> "whatwatt not reachable"
            kw == null -> "whatwatt: no meter reading"
            else -> null
        }
        // The last Test's result is stale once a reading connects or fails where it said otherwise.
        set { s -> s.copy(connected = kw != null, kw = kw, problem = problem, testResult = s.testResult.takeUnless { s.connected != (kw != null) }) }
        if (reading == null) {
            syncedAt = null
            set { it.copy(projection = null, recorder = null) }
            return
        }
        if (!peak) return
        if (!acting && shouldSync(Instant.now())) sync(address)
        val time = reading.time
        val kwh = reading.energyKwh
        val projection = if (kw != null && time != null && kwh != null) projector.project(time, kwh, kw, recording.lastEnd) else null
        set { it.copy(projection = projection) }
    }

    /**
     * With the first reading, when the next line is due (then every 15 s, after 2 min every
     * minute), every 15 min, and every minute until a check succeeds.
     */
    private fun shouldSync(now: Instant): Boolean {
        val last = syncedAt ?: return true
        val since = Duration.between(last, now)
        if (since >= Duration.ofMinutes(15) || (state.recorder == null && since >= Duration.ofMinutes(1))) return true
        val due = nextLineDue(recording)?.plusSeconds(5) ?: return false
        if (now < due) return false
        return since >= if (Duration.between(due, now) < Duration.ofMinutes(2)) Duration.ofSeconds(15) else Duration.ofMinutes(1)
    }

    /** Checks the recorder and copies its new lines; updates an older GridLoad recorder by itself. */
    private suspend fun sync(address: String) {
        val generation = generation
        val today = LocalDate.now(zone())
        val from = YearMonth.from(today).minusMonths(1).atDay(1)
        val result = withContext(Dispatchers.IO) {
            try {
                val status = fetchRecorderStatus(address)
                if (status.sdCard != false) files.sync(from, today, { listSdCard(address) }) { downloadDayFile(address, it) }
                status to files.load(from)
            } catch (_: Exception) {
                null
            }
        }
        if (generation != this.generation) return
        val now = Instant.now()
        syncedAt = now
        val (status, loaded) = result ?: return
        showRecording(loaded, now)
        val check = checkRecorder(status, loaded, now)
        set { it.copy(recorder = check, recorderInstalled = status.script?.let(::recorderVersion) != null) }
        if (check == RecorderCheck.Outdated && !updateTried) {
            updateTried = true
            act(address, "Updating the recorder…", "update") { installRecorder(it) }
        }
    }

    /** Installs GridLoad's recorder on the whatwatt and starts it. */
    suspend fun install(address: String) = act(address, "Installing…", "install") { installRecorder(it) }

    /** Starts the recorder and makes it start by itself after a restart of the whatwatt. */
    suspend fun start(address: String) {
        val running = state.recorder == RecorderCheck.NoAutoRun
        act(address, "Starting…", "start") { startRecorder(it, running) }
    }

    suspend fun remove(address: String) = act(address, "Removing…", "remove") { removeRecorder(it) }

    /** Runs a recorder [action] on the whatwatt, then checks it again with the next reading. */
    private suspend fun act(address: String, doing: String, verb: String, action: (String) -> Unit) {
        if (acting) return
        acting = true
        set { it.copy(recorderAction = doing, recorderFailed = false) }
        val error = withContext(Dispatchers.IO) {
            try {
                action(address)
                null
            } catch (e: HttpException) {
                "the whatwatt answered with error ${e.code}"
            } catch (_: IOException) {
                "the whatwatt isn't reachable"
            } catch (_: IllegalArgumentException) {
                "the address isn't valid"
            } catch (e: IllegalStateException) {
                e.message
            }
        }
        // A started script writes its start line with the first reading, within about 4 s.
        if (error == null && verb != "remove") delay(6_000)
        acting = false
        syncedAt = null
        // After a success the last check is stale ("stopped" right after Start) until the next one, a reading later.
        set {
            it.copy(
                recorder = if (error == null) null else it.recorder,
                recorderAction = error?.let { e -> "Couldn't $verb the recorder: $e." },
                recorderFailed = error != null,
            )
        }
    }

    /** A one-off reading from [address], to check it before relying on it. */
    suspend fun test(address: String, unit: PowerUnit) {
        val generation = generation
        set { it.copy(connected = false, testResult = "Testing…") }
        val (connected, result) = withContext(Dispatchers.IO) {
            try {
                val reading = fetchMeterReading(address)
                reading.ok to when {
                    reading.ok -> "Connected. ${unit.format(reading.powerKw ?: 0.0)} now."
                    reading.meterStatus == "KEY REQUIRED" -> "Connected, but the meter needs its key. Ask your utility for it and enter it in the whatwatt web UI."
                    reading.meterStatus != null -> "Connected, but the meter says: ${reading.meterStatus}"
                    else -> "Connected, but got no reading."
                }
            } catch (e: HttpException) {
                false to when (e.code) {
                    401 -> "The whatwatt asks for a password. Turn off Device Protection in its web UI."
                    404 -> "The whatwatt needs the Plus licence for this."
                    else -> "The device answered with error ${e.code}. Is this a whatwatt?"
                }
            } catch (_: IOException) {
                false to "Not reachable. Check the address and that the phone is on your home Wi-Fi."
            } catch (_: IllegalArgumentException) {
                false to "That doesn't look like an address."
            }
        }
        if (generation != this.generation) return
        if (connected) syncedAt = null
        set { it.copy(connected = connected, testResult = result) }
    }

    /** Shown instead of a test result when Android 17's local network permission is denied. */
    fun permissionDenied() {
        set { it.copy(testResult = "GridLoad needs the permission for devices on your network to reach the whatwatt.") }
    }

    /** Shows the copied quarter hours on start and when a month begins, before anything is fetched. */
    private suspend fun loadMonth() {
        val now = YearMonth.now(zone())
        if (now == month) return
        month = now
        val loaded = withContext(Dispatchers.IO) {
            try {
                files.load(now.minusMonths(1).atDay(1))
            } catch (_: Exception) {
                Recording()
            }
        }
        showRecording(loaded, syncedAt)
    }

    /**
     * The month's highest, last month's, the last few quarters and the gaps, from [loaded];
     * gaps only up to [checkedAt], the last time the files were copied.
     */
    private fun showRecording(loaded: Recording, checkedAt: Instant?) {
        recording = loaded
        val zone = zone()
        val now = YearMonth.now(zone)
        fun inMonth(q: Quarter, month: YearMonth) = YearMonth.from(q.start.atZone(zone)) == month
        val missing = checkedAt?.let { missingQuarters(loaded, now, it, zone) } ?: state.missing
        val lastGap = missing.lastOrNull()
        set {
            it.copy(
                highest = loaded.quarters.filter { q -> inMonth(q, now) }.maxByOrNull { q -> q.kwh },
                lastMonthHighest = loaded.quarters.filter { q -> inMonth(q, now.minusMonths(1)) }.maxByOrNull { q -> q.kwh },
                quarters = loaded.quarters,
                days = dailyHighest(loaded, now, zone),
                missing = missing,
                restartAfterGap = lastGap?.let { gap ->
                    loaded.starts.lastOrNull { s -> s >= gap && s < gap.plusSeconds(2 * QUARTER_SECONDS) }
                },
                recordedSince = recordedSince(loaded, now, zone),
            )
        }
    }
}

/**
 * The recorder's state as one line for Settings and the peak window; null when all is well.
 * [isRecorderWarning] says which lines are warnings.
 */
fun recorderLine(check: RecorderCheck): String? = when (check) {
    RecorderCheck.NoSdCard -> "No SD card in the whatwatt. The recorder needs one."
    RecorderCheck.NotInstalled -> "The recorder isn't installed. Peak load needs it."
    RecorderCheck.Foreign -> "Another script runs on the whatwatt. GridLoad doesn't replace it, so it can't record."
    RecorderCheck.Outdated -> "An older recorder is installed. GridLoad updates it."
    is RecorderCheck.Starting -> "The whatwatt restarted. The recorder starts at ${shortTime(check.at)}."
    // IDLE is the whatwatt's plain "stopped"; any other state is shown as a clue.
    is RecorderCheck.Stopped ->
        "The recorder is stopped" + (check.state?.takeIf { it != "IDLE" }?.let { " ($it)." } ?: ".")
    RecorderCheck.NoAutoRun -> "The recorder won't start again after the whatwatt restarts."
    is RecorderCheck.Silent ->
        if (check.started) "The recorder started at ${shortTime(check.since)}, but hasn't saved a quarter hour."
        else "The recorder hasn't saved a quarter hour since ${shortTime(check.since)}."
    is RecorderCheck.Waiting -> "Recorder started. First quarter hour at ${shortTime(check.firstAt)}."
    RecorderCheck.Ok -> null
}

/** Whether [check] means the month's quarter hours aren't being recorded. */
fun isRecorderWarning(check: RecorderCheck) =
    check !is RecorderCheck.Waiting && check != RecorderCheck.Ok && check != RecorderCheck.Outdated

/** "23:45" today, "2 Oct 23:45" on other days. */
fun shortTime(time: Instant): String {
    val local = time.atZone(ZoneId.systemDefault())
    return if (local.toLocalDate() == LocalDate.now()) timeFormat.format(local) else dayTimeFormat.format(local)
}

internal val dayTimeFormat = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
