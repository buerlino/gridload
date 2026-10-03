package io.github.buerlino.gridload

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.buerlino.gridload.core.CKW
import io.github.buerlino.gridload.core.CachedPrices
import io.github.buerlino.gridload.core.HttpException
import io.github.buerlino.gridload.core.PriceCache
import io.github.buerlino.gridload.core.PriceSlot
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.Status
import io.github.buerlino.gridload.core.classify
import io.github.buerlino.gridload.core.fetchPrices
import io.github.buerlino.gridload.core.isPeakWarning
import io.github.buerlino.gridload.core.mayFetch
import io.github.buerlino.gridload.core.parseKw
import io.github.buerlino.gridload.core.peakLine
import io.github.buerlino.gridload.core.reachedServer
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

data class UiState(
    val region: Region = CKW,
    val firstStartDone: Boolean = true,
    /** Whether the whatwatt is switched on in Settings; when off, it isn't read or shown. */
    val whatwattEnabled: Boolean = false,
    /** The saved whatwatt device address, or null when none is set. */
    val whatwattAddress: String? = null,
    val meter: MeterState = MeterState(),
    /** The peak load switch; it counts only while the whatwatt is on. */
    val peakEnabled: Boolean = false,
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
    val peakLine: Double? get() = peakLine(activeGoalKw, meter.highest)
    /** Whether this quarter hour's projection is close to the line (or over it): the bar turns red. */
    val peakWarning: Boolean get() {
        val projection = meter.projection ?: return false
        return isPeakWarning(projection.kw, peakLine ?: return false)
    }
    /** Whether the main screen shows the peak window. */
    val showPeak: Boolean get() = peakEnabled && whatwattEnabled && (meter.projection != null || scaleWithoutReading)
}

/**
 * Holds the cached slots so rotation doesn't refetch. The API is rate limited and one response
 * covers today and, once published (noon to 18:00 by region), tomorrow, so we fetch only when
 * nothing covers now, when tomorrow's prices are due but not cached, or when the user refreshes,
 * and never more often than the cooldown in core allows. The last response and the last attempt
 * are saved, so a restart neither waits for prices nor resets the cooldown.
 * The region, the whatwatt and peak load settings, and "first start done" are saved in SharedPreferences.
 * Nothing is fetched until the first start has picked a region; installs from before that fall
 * back to CKW. The whatwatt is read by [WhatwattMeter]; with peak load on, the phone vibrates
 * once per quarter hour when it comes close to a new peak; the quarter hours come from the
 * recorder on the whatwatt.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // v0.4 and v0.5 kept the imported usage data (personal), appliances and runs here, and
        // AtomicFile its backups. Peak load mode comes back with the whatwatt and doesn't use them.
        listOf("peak.json", "peak.json.new", "peak.json.bak").forEach { File(app.filesDir, it).delete() }
        // Up to v0.6 there were two modes; peak load is now a switch, and a saved goal turns the goal switch on.
        prefs.getString(KEY_MODE, null)?.let { mode ->
            prefs.edit {
                putBoolean(KEY_PEAK_ENABLED, mode == "peak" && prefs.getBoolean(KEY_WHATWATT_ENABLED, false))
                putBoolean(KEY_GOAL_ENABLED, prefs.contains(KEY_GOAL_KW))
                remove(KEY_MODE)
            }
        }
        // v0.7 recorded quarter hours only while the app was open, so its months are incomplete.
        // The recorder on the whatwatt replaces them (v0.8).
        File(app.filesDir, "quarters").deleteRecursively()
    }

    private val priceCache = PriceCache(File(app.filesDir, "prices.json"))
    private var cacheLoaded = false
    private var slots: List<PriceSlot> = emptyList()
    private var lastAttempt: Instant? = prefs.getLong(KEY_LAST_FETCH_ATTEMPT, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
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
            goalKw = prefs.getString(KEY_GOAL_KW, null)?.let(::parseKw),
            scaleWithoutReading = prefs.getBoolean(KEY_SCALE_WITHOUT_READING, true),
            countdown = prefs.getBoolean(KEY_COUNTDOWN, false),
        ),
    )
    val state: StateFlow<UiState> = _state

    private val meter = WhatwattMeter(File(app.filesDir, "recorder")) { meter -> _state.update { it.copy(meter = meter) } }

    /**
     * On app start/resume and every minute: recompute from the cache, and fetch when [wantsFetch]
     * says so. Fetches the user didn't ask for always wait the full cooldown.
     */
    suspend fun update() {
        if (!_state.value.firstStartDone) return
        loadCache()
        recompute()
        val now = Instant.now()
        if (wantsFetch(slots, now, _state.value.region.tomorrowFrom) && mayFetch(lastAttempt, now, hasCurrentData = true)) refresh()
    }

    /** Once per start: the last saved prices, if they are this region's and still cover now. */
    private suspend fun loadCache() {
        if (cacheLoaded) return
        cacheLoaded = true
        val region = _state.value.region
        val cached = withContext(Dispatchers.IO) {
            try {
                priceCache.load()
            } catch (_: Exception) {
                null
            }
        }
        if (cached == null || cached.regionId != region.id || cached.slots.none { it.covers(Instant.now()) }) return
        if (slots.isNotEmpty() || region != _state.value.region) return // fetched or switched meanwhile
        slots = cached.slots
        _state.update { it.copy(fetchedAt = cached.fetchedAt) }
    }

    private fun recompute() {
        val status = classify(slots, Instant.now())
        _state.update { it.copy(status = status, notice = null) }
    }

    /** Switching region drops the cached slots, since they belong to the old region's tariff. */
    fun selectRegion(region: Region) {
        if (region == _state.value.region) return
        prefs.edit {
            putString(KEY_REGION, region.id)
            remove(KEY_LAST_FETCH_ATTEMPT)
        }
        slots = emptyList()
        lastAttempt = null
        _state.update { it.copy(region = region, status = null, loading = false, error = null, fetchedAt = null, notice = null) }
        refresh()
    }

    /** The end of the setup guide, on first start or when opened from Settings. */
    fun finishSetup(region: Region) {
        prefs.edit { putBoolean(KEY_FIRST_START_DONE, true) }
        val firstStart = !_state.value.firstStartDone
        _state.update { it.copy(firstStartDone = true) }
        if (region != _state.value.region) {
            selectRegion(region)
        } else if (firstStart) {
            prefs.edit { putString(KEY_REGION, region.id) }
            refresh()
        }
    }

    /** The switch in Settings; the setup guide turns it on with Done and off with Skip. */
    fun setWhatwattEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WHATWATT_ENABLED, enabled) }
        _state.update { it.copy(whatwattEnabled = enabled) }
        meter.clearReading()
    }

    fun setPeakEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_PEAK_ENABLED, enabled) }
        _state.update { it.copy(peakEnabled = enabled) }
    }

    /** The goal switch; switched on for the first time, the goal starts at last month's highest. */
    fun setGoalEnabled(enabled: Boolean) {
        val first = _state.value.meter.lastMonthHighest?.takeIf { enabled && !prefs.contains(KEY_GOAL_KW) }?.let { roundKw(it.kw) }
        prefs.edit {
            putBoolean(KEY_GOAL_ENABLED, enabled)
            first?.let { putString(KEY_GOAL_KW, it.toString()) }
        }
        _state.update { it.copy(goalEnabled = enabled, goalKw = first ?: it.goalKw) }
    }

    /** The goal in kW; null (a blank field) leaves only the month's highest. */
    fun setGoal(kw: Double?) {
        prefs.edit { if (kw == null) remove(KEY_GOAL_KW) else putString(KEY_GOAL_KW, kw.toString()) }
        _state.update { it.copy(goalKw = kw) }
    }

    fun setScaleWithoutReading(show: Boolean) {
        prefs.edit { putBoolean(KEY_SCALE_WITHOUT_READING, show) }
        _state.update { it.copy(scaleWithoutReading = show) }
    }

    fun setCountdown(show: Boolean) {
        prefs.edit { putBoolean(KEY_COUNTDOWN, show) }
        _state.update { it.copy(countdown = show) }
    }

    /** Saves (or, blank, clears) the whatwatt device address. */
    fun setWhatwattAddress(address: String) {
        val trimmed = address.trim()
        prefs.edit { if (trimmed.isEmpty()) remove(KEY_WHATWATT_ADDRESS) else putString(KEY_WHATWATT_ADDRESS, trimmed) }
        val changed = trimmed != _state.value.whatwattAddress.orEmpty()
        _state.update { it.copy(whatwattAddress = trimmed.ifEmpty { null }) }
        meter.addressEdited(changed)
    }

    fun testWhatwattConnection() {
        val address = _state.value.whatwattAddress ?: return
        viewModelScope.launch { meter.test(address) }
    }

    fun whatwattPermissionDenied() = meter.permissionDenied()

    fun installRecorder() = onRecorder(meter::install)

    fun startRecorder() = onRecorder(meter::start)

    fun removeRecorder() = onRecorder(meter::remove)

    private fun onRecorder(action: suspend (String) -> Unit) {
        val address = _state.value.whatwattAddress ?: return
        viewModelScope.launch { action(address) }
    }

    /** Called every few seconds while the app is visible: reads the whatwatt, if it's switched on. */
    suspend fun readMeter() {
        if (!_state.value.whatwattEnabled || !_state.value.firstStartDone) return
        meter.read(_state.value.whatwattAddress, peak = _state.value.peakEnabled)
        warnIfClose()
    }

    /** With peak load on, vibrates once per quarter hour when it comes close to the line. */
    private fun warnIfClose() {
        val state = _state.value
        val end = state.meter.projection?.end ?: return
        if (!state.peakEnabled || !state.peakWarning || warnedFor == end) return
        warnedFor = end
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
        val previousAttempt = lastAttempt
        saveLastAttempt(now)
        _state.update { it.copy(loading = true, error = null, notice = null) }
        val region = _state.value.region
        viewModelScope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) { fetchPrices(region) }
                if (region != _state.value.region) return@launch // switched while loading
                slots = fetched
                val fetchedAt = Instant.now()
                _state.update { it.copy(fetchedAt = fetchedAt) }
                withContext(Dispatchers.IO) {
                    try {
                        priceCache.save(CachedPrices(region.id, fetchedAt, fetched))
                    } catch (_: Exception) {
                        // Only the quick start next time is lost.
                    }
                }
            } catch (e: Exception) {
                if (region != _state.value.region) return@launch
                if (!reachedServer(e)) saveLastAttempt(previousAttempt) // no cooldown for an attempt the server never saw
                _state.update { it.copy(error = priceError(e)) }
            }
            recompute()
            _state.update { it.copy(loading = false) }
        }
    }

    private fun saveLastAttempt(attempt: Instant?) {
        lastAttempt = attempt
        prefs.edit {
            if (attempt == null) remove(KEY_LAST_FETCH_ATTEMPT) else putLong(KEY_LAST_FETCH_ATTEMPT, attempt.toEpochMilli())
        }
    }
}

/** A failed price fetch in words, instead of the exception's text. */
private fun priceError(e: Exception) = when {
    e is HttpException && e.code == 429 -> "The utility's server is busy. Try again later."
    e is HttpException -> "The utility's server answered with error ${e.code}."
    e is IOException -> "No connection to the utility. Check your internet."
    else -> "The utility sent prices GridLoad can't read."
}

private const val KEY_REGION = "region"
/** Up to v0.6 only, see the migration in [MainViewModel]. */
private const val KEY_MODE = "mode"
private const val KEY_FIRST_START_DONE = "first_start_done"
/** Epoch milliseconds, so the cooldown holds across restarts. */
private const val KEY_LAST_FETCH_ATTEMPT = "last_fetch_attempt"
private const val KEY_WHATWATT_ENABLED = "whatwatt_enabled"
private const val KEY_WHATWATT_ADDRESS = "whatwatt_address"
private const val KEY_PEAK_ENABLED = "peak_enabled"
private const val KEY_GOAL_ENABLED = "peak_goal_enabled"
private const val KEY_GOAL_KW = "peak_goal_kw"
private const val KEY_SCALE_WITHOUT_READING = "peak_scale_without_reading"
private const val KEY_COUNTDOWN = "peak_countdown"
