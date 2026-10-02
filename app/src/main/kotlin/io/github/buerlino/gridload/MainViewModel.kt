package io.github.buerlino.gridload

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.buerlino.gridload.core.CKW
import io.github.buerlino.gridload.core.HttpException
import io.github.buerlino.gridload.core.PriceSlot
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.Status
import io.github.buerlino.gridload.core.classify
import io.github.buerlino.gridload.core.fetchMeterReading
import io.github.buerlino.gridload.core.fetchPrices
import io.github.buerlino.gridload.core.mayFetch
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

enum class Mode { SPOT, PEAK }

data class UiState(
    val region: Region = CKW,
    val mode: Mode = Mode.SPOT,
    val firstStartDone: Boolean = true,
    /** Whether the whatwatt is switched on in Settings; when off, it isn't read or shown. */
    val whatwattEnabled: Boolean = false,
    /** The saved whatwatt device address, or null when none is set. */
    val whatwattAddress: String? = null,
    /** The whatwatt's last reading, kW drawn now; null when there is none or it failed. */
    val meterKw: Double? = null,
    /** Why there is no reading from the saved whatwatt, as one quiet line. */
    val meterProblem: String? = null,
    val status: Status? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fetchedAt: Instant? = null,
    /** Shown when a refresh was skipped because of the cooldown; cleared by the next recompute. */
    val notice: String? = null,
)

/**
 * Holds the cached slots so rotation doesn't refetch. The API is rate limited and one response
 * covers today and, once published (noon to 18:00 by region), tomorrow, so we fetch only when nothing covers now, when
 * tomorrow's prices are due but not cached, or when the user refreshes, and never more often
 * than the cooldown in core allows.
 * The region, mode, whatwatt switch and address, and "first start done" are saved in SharedPreferences.
 * Nothing is fetched until the first start has picked a region; installs from before that fall
 * back to CKW. The whatwatt is read every few seconds, only while the app is visible.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var slots: List<PriceSlot> = emptyList()
    private var lastAttempt: Instant? = null
    private val _state = MutableStateFlow(
        UiState(
            region = REGIONS.find { it.id == prefs.getString(KEY_REGION, null) } ?: CKW,
            mode = if (prefs.getString(KEY_MODE, null) == "peak") Mode.PEAK else Mode.SPOT,
            firstStartDone = prefs.getBoolean(KEY_FIRST_START_DONE, false),
            whatwattEnabled = prefs.getBoolean(KEY_WHATWATT_ENABLED, false),
            whatwattAddress = prefs.getString(KEY_WHATWATT_ADDRESS, null),
        ),
    )
    val state: StateFlow<UiState> = _state

    /** The result of the last "Test connection" tap, shown under the whatwatt address field. */
    private val _whatwattTestResult = MutableStateFlow<String?>(null)
    val whatwattTestResult: StateFlow<String?> = _whatwattTestResult

    init {
        // v0.4 and v0.5 kept the imported usage data (personal), appliances and runs here, and
        // AtomicFile its backups. Peak load mode comes back with the whatwatt and doesn't use them.
        listOf("peak.json", "peak.json.new", "peak.json.bak").forEach { File(app.filesDir, it).delete() }
    }

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
        _state.update {
            UiState(
                region = region, mode = it.mode, firstStartDone = it.firstStartDone,
                whatwattEnabled = it.whatwattEnabled, whatwattAddress = it.whatwattAddress, meterKw = it.meterKw, meterProblem = it.meterProblem,
            )
        }
        refresh()
    }

    fun selectMode(mode: Mode) {
        prefs.edit().putString(KEY_MODE, if (mode == Mode.PEAK) "peak" else "spot").apply()
        _state.update { it.copy(mode = mode) }
    }

    /** The end of the setup guide, on first start or when opened from Settings. */
    fun finishSetup(mode: Mode, region: Region) {
        prefs.edit().putBoolean(KEY_FIRST_START_DONE, true).apply()
        val firstStart = !_state.value.firstStartDone
        _state.update { it.copy(firstStartDone = true) }
        selectMode(mode)
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
        _state.update { it.copy(whatwattEnabled = enabled, meterKw = null, meterProblem = null) }
    }

    /** Saves (or, blank, clears) the whatwatt device address. */
    fun setWhatwattAddress(address: String) {
        val trimmed = address.trim()
        prefs.edit().apply {
            if (trimmed.isEmpty()) remove(KEY_WHATWATT_ADDRESS) else putString(KEY_WHATWATT_ADDRESS, trimmed)
        }.apply()
        _state.update { it.copy(whatwattAddress = trimmed.ifEmpty { null }, meterKw = null, meterProblem = null) }
        _whatwattTestResult.value = null
    }

    /** A one-off reading from the saved address, to check it before relying on it. */
    fun testWhatwattConnection() {
        val address = _state.value.whatwattAddress ?: return
        _whatwattTestResult.value = "Testing…"
        viewModelScope.launch {
            _whatwattTestResult.value = withContext(Dispatchers.IO) {
                try {
                    val reading = fetchMeterReading(address)
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
     * Called every few seconds while the app is visible: reads the saved whatwatt, if any. A
     * failure shows a quiet line instead of the last value, which would be stale.
     */
    suspend fun readMeter() {
        if (!_state.value.whatwattEnabled || !_state.value.firstStartDone) return
        val address = _state.value.whatwattAddress ?: return
        val (kw, problem) = withContext(Dispatchers.IO) {
            try {
                val reading = fetchMeterReading(address)
                if (reading.ok && reading.powerKw != null) reading.powerKw to null else null to "whatwatt: no meter reading"
            } catch (_: Exception) {
                null to "whatwatt not reachable"
            }
        }
        // Changed or switched off while reading.
        if (!_state.value.whatwattEnabled || address != _state.value.whatwattAddress) return
        _state.update { it.copy(meterKw = kw, meterProblem = problem) }
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
private const val KEY_MODE = "mode"
private const val KEY_FIRST_START_DONE = "first_start_done"
private const val KEY_WHATWATT_ENABLED = "whatwatt_enabled"
private const val KEY_WHATWATT_ADDRESS = "whatwatt_address"
