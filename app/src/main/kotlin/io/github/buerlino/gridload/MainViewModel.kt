package io.github.buerlino.gridload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.buerlino.gridload.core.PriceSlot
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.Status
import io.github.buerlino.gridload.core.classify
import io.github.buerlino.gridload.core.fetchPrices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

data class UiState(
    val region: Region = REGIONS.first(),
    val status: Status? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fetchedAt: Instant? = null,
)

/**
 * Holds the day's slots so rotation doesn't refetch. The API is rate limited and one response
 * covers the whole day, so we fetch only when nothing usable is cached or the user refreshes.
 */
class MainViewModel : ViewModel() {
    private var slots: List<PriceSlot> = emptyList()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** On app start/resume: recompute from the cache, fetch only if no slot covers now. */
    fun onStart() {
        recompute()
        if (_state.value.status == null) refresh()
    }

    /** Cheap, no network: call periodically so the colour follows slot boundaries. */
    fun recompute() {
        val status = classify(slots, Instant.now())
        _state.update { it.copy(status = status) }
    }

    /** Switching region drops the cached slots, since they belong to the old region's tariff. */
    fun selectRegion(region: Region) {
        if (region == _state.value.region) return
        slots = emptyList()
        _state.update { UiState(region = region) }
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
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
