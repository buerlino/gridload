package io.github.buerlino.gridload

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.buerlino.gridload.core.Appliance
import io.github.buerlino.gridload.core.CKW
import io.github.buerlino.gridload.core.PeakData
import io.github.buerlino.gridload.core.PeakStatus
import io.github.buerlino.gridload.core.PriceSlot
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.Status
import io.github.buerlino.gridload.core.classify
import io.github.buerlino.gridload.core.fetchPrices
import io.github.buerlino.gridload.core.isRunning
import io.github.buerlino.gridload.core.mayFetch
import io.github.buerlino.gridload.core.mergeDays
import io.github.buerlino.gridload.core.mergeUsage
import io.github.buerlino.gridload.core.parseCkwExport
import io.github.buerlino.gridload.core.parsePeakData
import io.github.buerlino.gridload.core.startRun
import io.github.buerlino.gridload.core.status
import io.github.buerlino.gridload.core.stop
import io.github.buerlino.gridload.core.toJson
import io.github.buerlino.gridload.core.wantsFetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant

enum class Mode { SPOT, PEAK }

data class UiState(
    val region: Region = CKW,
    val mode: Mode = Mode.SPOT,
    val firstStartDone: Boolean = true,
    val status: Status? = null,
    /** Peak load mode only; recomputed with the colour. */
    val peak: PeakStatus? = null,
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
 * The region, mode and "first start done" are saved in SharedPreferences, peak load mode's data
 * in `peak.json`. Nothing is fetched until the first start has picked a region; installs from
 * before that fall back to CKW.
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

    /** Peak load mode's saved data: imported months, appliances, runs and the goal offset. */
    private val peakFile = AtomicFile(File(app.filesDir, "peak.json"))
    private val _peak = MutableStateFlow(
        try {
            parsePeakData(peakFile.readFully().decodeToString())
        } catch (_: FileNotFoundException) {
            PeakData()
        },
    )
    val peak: StateFlow<PeakData> = _peak

    /** The result of the last import, shown under the import button. */
    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage

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
        val now = Instant.now()
        val status = classify(slots, now)
        val peak = if (_state.value.mode == Mode.PEAK) _peak.value.status(now) else null
        _state.update { it.copy(status = status, peak = peak, notice = null) }
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
        recompute()
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

    /**
     * Reads the CKW exports picked by the user (monthly totals or hourly days, several at once)
     * and adds them to the saved ones; months and days already saved are replaced.
     */
    fun importLoadData(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importMessage.value = try {
                val imports = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    uris.map { uri -> resolver.openInputStream(uri)!!.use(::parseCkwExport) }
                }
                val months = imports.flatMap { it.months }
                val days = imports.flatMap { it.days }
                editPeak { it.copy(usage = mergeUsage(it.usage, months), days = mergeDays(it.days, days)) }
                listOfNotNull(
                    months.size.takeIf { it > 0 }?.let { if (it == 1) "1 month" else "$it months" },
                    days.size.takeIf { it > 0 }?.let { (if (it == 1) "1 day" else "$it days") + " with hourly values" },
                ).joinToString(" and ").ifEmpty { "nothing" }.let { "Imported $it" }
            } catch (e: Exception) {
                "Couldn't read the file: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun setGoalOffset(kw: Double) = editPeak { it.copy(goalOffsetKw = kw) }

    /** Adds [appliance], or replaces the one with the same id. */
    fun saveAppliance(appliance: Appliance) = editPeak { data ->
        val exists = data.appliances.any { it.id == appliance.id }
        data.copy(appliances = if (exists) data.appliances.map { if (it.id == appliance.id) appliance else it } else data.appliances + appliance)
    }

    /** Past runs stay, since they belong to this month's peak. */
    fun deleteAppliance(appliance: Appliance) = editPeak { data ->
        val now = Instant.now()
        data.copy(
            appliances = data.appliances.filter { it.id != appliance.id },
            runs = data.runs.map { if (it.applianceId == appliance.id && it.isRunning(now)) it.stop(now) else it },
        )
    }

    fun start(appliance: Appliance) = editPeak { it.copy(runs = it.runs + startRun(appliance, Instant.now())) }

    fun stop(appliance: Appliance) = editPeak { data ->
        val now = Instant.now()
        data.copy(runs = data.runs.map { if (it.applianceId == appliance.id && it.isRunning(now)) it.stop(now) else it })
    }

    private fun editPeak(change: (PeakData) -> PeakData) {
        val data = change(_peak.value)
        _peak.value = data
        recompute()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                savePeak(data)
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't save: ${e.message}") }
            }
        }
    }

    @Synchronized
    private fun savePeak(data: PeakData) {
        if (data != _peak.value) return // a newer edit is saved after this one
        val out = peakFile.startWrite()
        try {
            out.write(data.toJson().encodeToByteArray())
            peakFile.finishWrite(out)
        } catch (e: Exception) {
            peakFile.failWrite(out)
            throw e
        }
    }
}

private const val KEY_REGION = "region"
private const val KEY_MODE = "mode"
private const val KEY_FIRST_START_DONE = "first_start_done"
