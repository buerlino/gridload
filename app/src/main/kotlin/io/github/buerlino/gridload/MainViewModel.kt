package io.github.buerlino.gridload

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.buerlino.gridload.core.CKW
import io.github.buerlino.gridload.core.PriceSlot
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.Status
import io.github.buerlino.gridload.core.classify
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
import java.time.Instant

enum class Mode { SPOT, PEAK }

data class UiState(
    val region: Region = CKW,
    val mode: Mode = Mode.SPOT,
    val firstStartDone: Boolean = true,
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
 * The region, mode and "first start done" are saved in SharedPreferences. Nothing is fetched
 * until the first start has picked a region; installs from before that fall back to CKW.
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
        ),
    )
    val state: StateFlow<UiState> = _state

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
        _state.update { UiState(region = region, mode = it.mode, firstStartDone = it.firstStartDone) }
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
