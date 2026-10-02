package io.github.buerlino.gridload

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.buerlino.gridload.core.CKW
import io.github.buerlino.gridload.core.HttpException
import io.github.buerlino.gridload.core.MeterReading
import io.github.buerlino.gridload.core.PriceSlot
import io.github.buerlino.gridload.core.Projection
import io.github.buerlino.gridload.core.Quarter
import io.github.buerlino.gridload.core.QuarterRecorder
import io.github.buerlino.gridload.core.QuarterStore
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.Status
import io.github.buerlino.gridload.core.TARIFF_ZONE
import io.github.buerlino.gridload.core.classify
import io.github.buerlino.gridload.core.fetchMeterReading
import io.github.buerlino.gridload.core.fetchPrices
import io.github.buerlino.gridload.core.isPeakWarning
import io.github.buerlino.gridload.core.mayFetch
import io.github.buerlino.gridload.core.peakLine
import io.github.buerlino.gridload.core.wantsFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.YearMonth

data class UiState(
    val region: Region = CKW,
    val firstStartDone: Boolean = true,
    /** Whether the whatwatt is switched on in Settings; when off, it isn't read or shown. */
    val whatwattEnabled: Boolean = false,
    /** The saved whatwatt device address, or null when none is set. */
    val whatwattAddress: String? = null,
    /** Whether the last test or reading got a meter reading; Settings then collapses the address. */
    val whatwattConnected: Boolean = false,
    /** The whatwatt's last reading, kW drawn now; null when there is none or it failed. */
    val meterKw: Double? = null,
    /** Why there is no reading from the saved whatwatt, as one quiet line. */
    val meterProblem: String? = null,
    /** This quarter hour, projected from the last good reading; null without one. */
    val projection: Projection? = null,
    /** The peak load switch; it counts only while the whatwatt is on. */
    val peakEnabled: Boolean = false,
    /** This month's highest recorded quarter hour, and last month's (the goal's first value). */
    val highest: Quarter? = null,
    val lastMonthHighest: Quarter? = null,
    /** This month's last few recorded quarters, for the bars before the current one. */
    val recent: List<Quarter> = emptyList(),
    /** The goal switch and its value in kW; with it off, or blank, the line is the month's highest. */
    val goalEnabled: Boolean = false,
    val goalKw: Double? = null,
    /** Peak load without a reading: keep the scale (true) or hide it. */
    val scaleWithoutReading: Boolean = true,
    /** Show "New quarter hour in N min" under the scale. */
    val countdown: Boolean = false,
    val status: Status? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fetchedAt: Instant? = null,
    /** Shown when a refresh was skipped because of the cooldown; cleared by the next recompute. */
    val notice: String? = null,
) {
    val activeGoalKw: Double? get() = goalKw?.takeIf { goalEnabled }
    val peakLine: Double? get() = peakLine(activeGoalKw, highest)
    /** Whether the main screen shows the peak window. */
    val showPeak: Boolean get() = peakEnabled && whatwattEnabled && (projection != null || scaleWithoutReading)
}

/**
 * Holds the cached slots so rotation doesn't refetch. The API is rate limited and one response
 * covers today and, once published (noon to 18:00 by region), tomorrow, so we fetch only when nothing covers now, when
 * tomorrow's prices are due but not cached, or when the user refreshes, and never more often
 * than the cooldown in core allows.
 * The region, the whatwatt and peak load settings, and "first start done" are saved in SharedPreferences.
 * Nothing is fetched until the first start has picked a region; installs from before that fall
 * back to CKW. The whatwatt is read every few seconds, only while the app is visible, and its
 * quarter hours go to one file per month in `files/quarters`, with or without peak load. With
 * peak load on, the phone vibrates once per quarter hour when it comes close to a new peak.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // v0.4 and v0.5 kept the imported usage data (personal), appliances and runs here, and
        // AtomicFile its backups. Peak load mode comes back with the whatwatt and doesn't use them.
        listOf("peak.json", "peak.json.new", "peak.json.bak").forEach { File(app.filesDir, it).delete() }
        // Up to v0.6 there were two modes; peak load is now a switch, and a saved goal turns the goal switch on.
        prefs.getString(KEY_MODE, null)?.let { mode ->
            prefs.edit()
                .putBoolean(KEY_PEAK_ENABLED, mode == "peak" && prefs.getBoolean(KEY_WHATWATT_ENABLED, false))
                .putBoolean(KEY_GOAL_ENABLED, prefs.contains(KEY_GOAL_KW))
                .remove(KEY_MODE)
                .apply()
        }
    }

    private var slots: List<PriceSlot> = emptyList()
    private var lastAttempt: Instant? = null
    private val recorder = QuarterRecorder()
    private val quarterStore = QuarterStore(File(app.filesDir, "quarters"))
    /** The month whose quarters are loaded into the state. */
    private var month: YearMonth? = null
    /** The quarter hour (its end) the phone last vibrated for. */
    private var warnedFor: Instant? = null
    private val _state = MutableStateFlow(
        UiState(
            region = REGIONS.find { it.id == prefs.getString(KEY_REGION, null) } ?: CKW,
            firstStartDone = prefs.getBoolean(KEY_FIRST_START_DONE, false),
            whatwattEnabled = prefs.getBoolean(KEY_WHATWATT_ENABLED, false),
            whatwattAddress = prefs.getString(KEY_WHATWATT_ADDRESS, null),
            peakEnabled = prefs.getBoolean(KEY_PEAK_ENABLED, false),
            goalEnabled = prefs.getBoolean(KEY_GOAL_ENABLED, false),
            goalKw = prefs.getString(KEY_GOAL_KW, null)?.toDoubleOrNull(),
            scaleWithoutReading = prefs.getBoolean(KEY_SCALE_WITHOUT_READING, true),
            countdown = prefs.getBoolean(KEY_COUNTDOWN, false),
        ),
    )
    val state: StateFlow<UiState> = _state

    /** The result of the last "Test connection" tap, shown under the whatwatt address field. */
    private val _whatwattTestResult = MutableStateFlow<String?>(null)
    val whatwattTestResult: StateFlow<String?> = _whatwattTestResult

    /**
     * On app start/resume and every minute: recompute from the cache, and fetch when [wantsFetch]
     * says so. Fetches the user didn't ask for always wait the full cooldown.
     */
    fun update() {
        if (!_state.value.firstStartDone) return
        recompute()
        val now = Instant.now()
        if (wantsFetch(slots, now, _state.value.region.tomorrowFrom) && mayFetch(lastAttempt, now, hasCurrentData = true)) refresh()
    }

    private fun recompute() {
        val status = classify(slots, Instant.now())
        _state.update { it.copy(status = status, notice = null) }
    }

    /** Switching region drops the cached slots, since they belong to the old region's tariff. */
    fun selectRegion(region: Region) {
        if (region == _state.value.region) return
        prefs.edit().putString(KEY_REGION, region.id).apply()
        slots = emptyList()
        lastAttempt = null
        _state.update { it.copy(region = region, status = null, loading = false, error = null, fetchedAt = null, notice = null) }
        refresh()
    }

    /** The end of the setup guide, on first start or when opened from Settings. */
    fun finishSetup(region: Region) {
        prefs.edit().putBoolean(KEY_FIRST_START_DONE, true).apply()
        val firstStart = !_state.value.firstStartDone
        _state.update { it.copy(firstStartDone = true) }
        if (region != _state.value.region) {
            selectRegion(region)
        } else if (firstStart) {
            prefs.edit().putString(KEY_REGION, region.id).apply()
            refresh()
        }
    }

    /** The switch in Settings; the setup guide turns it on with Done and off with Skip. */
    fun setWhatwattEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WHATWATT_ENABLED, enabled).apply()
        _state.update { it.copy(whatwattEnabled = enabled, whatwattConnected = false, meterKw = null, meterProblem = null, projection = null) }
    }

    fun setPeakEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PEAK_ENABLED, enabled).apply()
        _state.update { it.copy(peakEnabled = enabled) }
    }

    /** The goal switch; switched on for the first time, the goal starts at last month's highest seen. */
    fun setGoalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GOAL_ENABLED, enabled).apply()
        val state = _state.value
        val first = state.lastMonthHighest?.takeIf { enabled && !prefs.contains(KEY_GOAL_KW) }
        first?.let { prefs.edit().putString(KEY_GOAL_KW, roundKw(it.kw).toString()).apply() }
        _state.update { it.copy(goalEnabled = enabled, goalKw = first?.let { q -> roundKw(q.kw) } ?: it.goalKw) }
    }

    /** The goal in kW; null (a blank field) leaves only the month's highest. */
    fun setGoal(kw: Double?) {
        prefs.edit().apply { if (kw == null) remove(KEY_GOAL_KW) else putString(KEY_GOAL_KW, kw.toString()) }.apply()
        _state.update { it.copy(goalKw = kw) }
    }

    fun setScaleWithoutReading(show: Boolean) {
        prefs.edit().putBoolean(KEY_SCALE_WITHOUT_READING, show).apply()
        _state.update { it.copy(scaleWithoutReading = show) }
    }

    fun setCountdown(show: Boolean) {
        prefs.edit().putBoolean(KEY_COUNTDOWN, show).apply()
        _state.update { it.copy(countdown = show) }
    }

    /** Saves (or, blank, clears) the whatwatt device address. */
    fun setWhatwattAddress(address: String) {
        val trimmed = address.trim()
        prefs.edit().apply {
            if (trimmed.isEmpty()) remove(KEY_WHATWATT_ADDRESS) else putString(KEY_WHATWATT_ADDRESS, trimmed)
        }.apply()
        if (trimmed != _state.value.whatwattAddress) recorder.reset() // maybe another meter
        _state.update {
            it.copy(whatwattAddress = trimmed.ifEmpty { null }, whatwattConnected = false, meterKw = null, meterProblem = null, projection = null)
        }
        _whatwattTestResult.value = null
    }

    /** A one-off reading from the saved address, to check it before relying on it. */
    fun testWhatwattConnection() {
        val address = _state.value.whatwattAddress ?: return
        _whatwattTestResult.value = "Testing…"
        _state.update { it.copy(whatwattConnected = false) }
        viewModelScope.launch {
            _whatwattTestResult.value = withContext(Dispatchers.IO) {
                try {
                    val reading = fetchMeterReading(address)
                    if (reading.ok && address == _state.value.whatwattAddress) _state.update { it.copy(whatwattConnected = true) }
                    when {
                        reading.ok -> "Connected. %.2f kW now.".format(reading.powerKw ?: 0.0)
                        reading.meterStatus == "KEY REQUIRED" -> "Connected, but the meter needs its key. Ask your utility for it and enter it in the whatwatt web UI."
                        reading.meterStatus != null -> "Connected, but the meter says: ${reading.meterStatus}"
                        else -> "Connected, but got no reading."
                    }
                } catch (e: HttpException) {
                    when (e.code) {
                        401 -> "The whatwatt asks for a password. Turn off Device Protection in its web UI."
                        404 -> "The whatwatt needs the Plus licence for this."
                        else -> "The device answered with error ${e.code}. Is this a whatwatt?"
                    }
                } catch (_: IOException) {
                    "Not reachable. Check the address and that the phone is on your home Wi-Fi."
                } catch (_: IllegalArgumentException) {
                    "That doesn't look like an address."
                }
            }
        }
    }

    /** Shown instead of a test result when Android 17's local network permission is denied. */
    fun whatwattPermissionDenied() {
        _whatwattTestResult.value = "GridLoad needs the permission for devices on your network to reach the whatwatt."
    }

    /**
     * Called every few seconds while the app is visible: reads the saved whatwatt, if any,
     * records the quarter hours and projects this one. A failure shows a quiet line instead of
     * the last value, which would be stale.
     */
    suspend fun readMeter() {
        if (!_state.value.whatwattEnabled || !_state.value.firstStartDone) return
        loadMonth()
        val address = _state.value.whatwattAddress ?: return
        val reading = withContext(Dispatchers.IO) {
            try {
                fetchMeterReading(address)
            } catch (_: Exception) {
                null
            }
        }
        // Changed or switched off while reading.
        if (!_state.value.whatwattEnabled || address != _state.value.whatwattAddress) return
        val kw = reading?.powerKw?.takeIf { reading.ok }
        val problem = when {
            reading == null -> "whatwatt not reachable"
            kw == null -> "whatwatt: no meter reading"
            else -> null
        }
        val projection = if (reading != null && kw != null && record(reading)) recorder.projection(kw) else null
        _state.update { it.copy(whatwattConnected = kw != null, meterKw = kw, meterProblem = problem, projection = projection) }
        warnIfClose()
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
        _state.update {
            when (quarterMonth) {
                month -> it.copy(highest = higher(it.highest, quarter), recent = (it.recent + quarter).takeLast(PAST_BARS))
                month?.minusMonths(1) -> it.copy(lastMonthHighest = higher(it.lastMonthHighest, quarter))
                else -> it
            }
        }
        withContext(Dispatchers.IO) {
            try {
                quarterStore.add(quarter)
            } catch (_: Exception) {
                // The quarter is lost; the whatwatt's SD card log can fill it in later.
            }
        }
        return true
    }

    /** Loads this month's and last month's highest quarter hour, on start and when a month begins. */
    private suspend fun loadMonth() {
        val now = YearMonth.now(TARIFF_ZONE)
        if (now == month) return
        month = now
        val (quarters, lastMonth) = withContext(Dispatchers.IO) {
            try {
                quarterStore.month(now) to quarterStore.month(now.minusMonths(1))
            } catch (_: Exception) {
                emptyList<Quarter>() to emptyList()
            }
        }
        _state.update {
            it.copy(
                highest = quarters.maxByOrNull { q -> q.kwh },
                lastMonthHighest = lastMonth.maxByOrNull { q -> q.kwh },
                recent = quarters.sortedBy { q -> q.start }.takeLast(PAST_BARS),
            )
        }
    }

    /** With peak load on, vibrates once per quarter hour when it comes close to the line. */
    private fun warnIfClose() {
        val state = _state.value
        val projection = state.projection ?: return
        val line = state.peakLine ?: return
        if (!state.peakEnabled || !isPeakWarning(projection.kw, line) || warnedFor == projection.end) return
        warnedFor = projection.end
        val app = getApplication<Application>()
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            app.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun refresh() {
        if (_state.value.loading) return
        val now = Instant.now()
        val hasData = _state.value.status != null
        if (!mayFetch(lastAttempt, now, hasData)) {
            val notice = if (hasData) "Already up to date" else "Try again in a few seconds"
            _state.update { it.copy(notice = notice) }
            return
        }
        lastAttempt = now
        _state.update { it.copy(loading = true, error = null, notice = null) }
        val region = _state.value.region
        viewModelScope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) { fetchPrices(region) }
                if (region != _state.value.region) return@launch // switched while loading
                slots = fetched
                _state.update { it.copy(fetchedAt = Instant.now()) }
            } catch (e: Exception) {
                if (region != _state.value.region) return@launch
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
            recompute()
            _state.update { it.copy(loading = false) }
        }
    }
}

private const val KEY_REGION = "region"
/** Up to v0.6 only, see the migration in [MainViewModel]. */
private const val KEY_MODE = "mode"
private const val KEY_FIRST_START_DONE = "first_start_done"
private const val KEY_WHATWATT_ENABLED = "whatwatt_enabled"
private const val KEY_WHATWATT_ADDRESS = "whatwatt_address"
private const val KEY_PEAK_ENABLED = "peak_enabled"
private const val KEY_GOAL_ENABLED = "peak_goal_enabled"
private const val KEY_GOAL_KW = "peak_goal_kw"
private const val KEY_SCALE_WITHOUT_READING = "peak_scale_without_reading"
private const val KEY_COUNTDOWN = "peak_countdown"

/** kW to one decimal, as the goal field shows it. */
private fun roundKw(kw: Double) = Math.round(kw * 10) / 10.0

private fun higher(a: Quarter?, b: Quarter) = if (a == null || b.kwh > a.kwh) b else a
