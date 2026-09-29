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
import java.time.Instant

data class UiState(
    val region: Region = CKW,
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
 * The region and "first start done" are saved in SharedPreferences. Nothing is fetched until
 * the first start has picked a region; installs from before that fall back to CKW.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var slots: List<PriceSlot> = emptyList()
    private var lastAttempt: Instant? = null
    private val _state = MutableStateFlow(
        UiState(
            region = REGIONS.find { it.id == prefs.getString(KEY_REGION, null) } ?: CKW,
            firstStartDone = prefs.getBoolean(KEY_FIRST_START_DONE, false),
        ),
    )
    val state: StateFlow<UiState> = _state

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
        _state.update { UiState(region = region, firstStartDone = it.firstStartDone) }
        refresh()
    }

    fun finishFirstStart(region: Region) {
        prefs.edit().putString(KEY_REGION, region.id).putBoolean(KEY_FIRST_START_DONE, true).apply()
        _state.update { UiState(region = region, firstStartDone = true) }
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        val now = Instant.now()
        val hasData = _state.value.status != null
        if (!mayFetch(lastAttempt, now, hasData)) {
            val notice = if (hasData) "Already up to date" else "Please wait a few seconds"
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
private const val KEY_FIRST_START_DONE = "first_start_done"
