package io.github.buerlino.gridload

import io.github.buerlino.gridload.core.HttpException
import io.github.buerlino.gridload.core.MeterReading
import io.github.buerlino.gridload.core.Projection
import io.github.buerlino.gridload.core.Quarter
import io.github.buerlino.gridload.core.QuarterRecorder
import io.github.buerlino.gridload.core.QuarterStore
import io.github.buerlino.gridload.core.TARIFF_ZONE
import io.github.buerlino.gridload.core.fetchMeterReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.YearMonth

/** What the whatwatt measured and recorded, as [UiState.meter]. */
data class MeterState(
    /** Whether the last test or reading got a meter reading; Settings then collapses the address. */
    val connected: Boolean = false,
    /** The last reading, kW drawn now; null when there is none or it failed. */
    val kw: Double? = null,
    /** Why there is no reading from the saved whatwatt, as one quiet line. */
    val problem: String? = null,
    /** This quarter hour, projected from the last good reading; null without one. */
    val projection: Projection? = null,
    /** This month's highest recorded quarter hour, and last month's (the goal's first value). */
    val highest: Quarter? = null,
    val lastMonthHighest: Quarter? = null,
    /** The last few recorded quarters, for the bars before the current one. */
    val recent: List<Quarter> = emptyList(),
    /** The result of the last Test, shown under the address field. */
    val testResult: String? = null,
)

/** While the address was edited this recently, it's probably still being typed, so it isn't read. */
private val TYPING = Duration.ofSeconds(10)

/**
 * Reads the whatwatt, records its quarter hours into one file per month in [quarterDir] (with or
 * without peak load) and projects the current one; also runs the Test in Settings. Owned by
 * [MainViewModel] and called on the main thread; each change goes to [publish].
 */
class WhatwattMeter(quarterDir: File, private val publish: (MeterState) -> Unit) {
    private val recorder = QuarterRecorder()
    private val store = QuarterStore(quarterDir)
    private var state = MeterState()
    /** The month whose quarters are loaded. */
    private var month: YearMonth? = null
    /** Bumped when the address changes or the whatwatt is switched, so a reading under way is dropped. */
    private var generation = 0
    private var editedAt: Instant? = null

    private fun set(change: (MeterState) -> MeterState) {
        state = change(state)
        publish(state)
    }

    /** Drops the last reading, which may be stale or from another device. */
    fun clearReading() {
        generation++
        set { it.copy(connected = false, kw = null, problem = null, projection = null) }
    }

    /** The address was edited; [changed] when it's really another one, maybe another meter. */
    fun addressEdited(changed: Boolean) {
        if (changed) recorder.reset()
        editedAt = Instant.now()
        clearReading()
        set { it.copy(testResult = null) }
    }

    /**
     * Called every few seconds while the app is visible: loads the month's quarters, then reads
     * [address], if any, records the quarter hours and projects this one. A failure shows a quiet
     * line instead of the last value, which would be stale.
     */
    suspend fun read(address: String?) {
        loadMonth()
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
        val projection = if (reading != null && kw != null && record(reading)) recorder.projection(kw) else null
        set { it.copy(connected = kw != null, kw = kw, problem = problem, projection = projection) }
    }

    /** A one-off reading from [address], to check it before relying on it. */
    suspend fun test(address: String) {
        val generation = generation
        set { it.copy(connected = false, testResult = "Testing…") }
        val (connected, result) = withContext(Dispatchers.IO) {
            try {
                val reading = fetchMeterReading(address)
                reading.ok to when {
                    reading.ok -> "Connected. %.1f kW now.".format(reading.powerKw ?: 0.0)
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
        if (generation == this.generation) set { it.copy(connected = connected, testResult = result) }
    }

    /** Shown instead of a test result when Android 17's local network permission is denied. */
    fun permissionDenied() {
        set { it.copy(testResult = "GridLoad needs the permission for devices on your network to reach the whatwatt.") }
    }

    /**
     * Feeds the meter's register to the recorder and saves each finished quarter hour. False
     * when the reading has no time or register, so it can't be projected either.
     */
    private suspend fun record(reading: MeterReading): Boolean {
        val time = reading.time ?: return false
        val kwh = reading.energyKwh ?: return false
        val quarter = recorder.add(time, kwh) ?: return true
        val quarterMonth = YearMonth.from(quarter.start.atZone(TARIFF_ZONE))
        set {
            val recent = (it.recent + quarter).takeLast(PAST_BARS)
            when (quarterMonth) {
                month -> it.copy(highest = higher(it.highest, quarter), recent = recent)
                month?.minusMonths(1) -> it.copy(lastMonthHighest = higher(it.lastMonthHighest, quarter), recent = recent)
                else -> it
            }
        }
        withContext(Dispatchers.IO) {
            try {
                store.add(quarter)
            } catch (_: Exception) {
                // The quarter is lost; the whatwatt's SD card log can fill it in later.
            }
        }
        return true
    }

    /**
     * Loads this month's and last month's highest quarter hour, and the last few quarters from
     * both, so the bars aren't empty at the start of a month. On start and when a month begins.
     */
    private suspend fun loadMonth() {
        val now = YearMonth.now(TARIFF_ZONE)
        if (now == month) return
        month = now
        val (quarters, lastMonth) = withContext(Dispatchers.IO) {
            try {
                store.month(now) to store.month(now.minusMonths(1))
            } catch (_: Exception) {
                emptyList<Quarter>() to emptyList()
            }
        }
        set {
            it.copy(
                highest = quarters.maxByOrNull { q -> q.kwh },
                lastMonthHighest = lastMonth.maxByOrNull { q -> q.kwh },
                recent = (lastMonth + quarters).sortedBy { q -> q.start }.takeLast(PAST_BARS),
            )
        }
    }
}

private fun higher(a: Quarter?, b: Quarter) = if (a == null || b.kwh > a.kwh) b else a
