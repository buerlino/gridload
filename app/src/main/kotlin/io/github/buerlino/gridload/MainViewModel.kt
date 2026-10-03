package io.github.buerlino.gridload

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.buerlino.gridload.core.Advice
import io.github.buerlino.gridload.core.Appliance
import io.github.buerlino.gridload.core.ApplianceFile
import io.github.buerlino.gridload.core.CKW
import io.github.buerlino.gridload.core.CachedPrices
import io.github.buerlino.gridload.core.HttpException
import io.github.buerlino.gridload.core.Measurement
import io.github.buerlino.gridload.core.Measuring
import io.github.buerlino.gridload.core.PeakNow
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.PriceCache
import io.github.buerlino.gridload.core.PriceSlot
import io.github.buerlino.gridload.core.QuarterLoad
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.Status
import io.github.buerlino.gridload.core.advise
import io.github.buerlino.gridload.core.appliancesJson
import io.github.buerlino.gridload.core.classify
import io.github.buerlino.gridload.core.cooldownEnd
import io.github.buerlino.gridload.core.doneAt
import io.github.buerlino.gridload.core.fetchPrices
import io.github.buerlino.gridload.core.isPeakWarning
import io.github.buerlino.gridload.core.mayFetch
import io.github.buerlino.gridload.core.measuringJson
import io.github.buerlino.gridload.core.mergeAppliances
import io.github.buerlino.gridload.core.parseAppliances
import io.github.buerlino.gridload.core.parsePositive
import io.github.buerlino.gridload.core.parseMeasuring
import io.github.buerlino.gridload.core.peakFloor
import io.github.buerlino.gridload.core.peakLine
import io.github.buerlino.gridload.core.quarterLoads
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
    /** How the whatwatt's values are shown: kW or W. */
    val powerUnit: PowerUnit = PowerUnit.KW,
    val meter: MeterState = MeterState(),
    /** The peak load switch; it counts only while the whatwatt is on. */
    val peakEnabled: Boolean = false,
    /** The goal switch and its value in kW; off, or blank, it doesn't count for the limit. */
    val goalEnabled: Boolean = false,
    val goalKw: Double? = null,
    /** Show the minutes left in this quarter hour in the peak window's header. */
    val countdown: Boolean = false,
    /** Vibrate at the limit in silent mode too, as an alarm; else as a notification, so not while the phone is silent. */
    val vibrateAlways: Boolean = false,
    /** Whether the peak window, the appliances and the history are expanded. */
    val peakOpen: Boolean = true,
    val historyOpen: Boolean = true,
    val appliancesOpen: Boolean = true,
    /** Whether the main screen shows the appliances panel at all (Settings → Mode). */
    val appliancesEnabled: Boolean = true,
    /** The Settings sections folded to their summary, by id. */
    val closedSections: Set<String> = emptySet(),
    /** The measured appliances, and for each (by name) whether to start it now; no advice without a reading, nor for one that can wait without prices. */
    val appliances: List<Appliance> = emptyList(),
    val advice: Map<String, Advice> = emptyMap(),
    /** The appliance (by name) whose run, started now, the peak window previews, and its quarter hours; not saved. */
    val preview: String? = null,
    val previewLoads: List<QuarterLoad> = emptyList(),
    /** Whether the measurement setup help was shown once; afterwards it opens from ⓘ. */
    val applianceHelpSeen: Boolean = false,
    /** A measurement under way, and its result once Done was tapped. */
    val measuring: Measuring? = null,
    val measurement: Measurement? = null,
    /** The last export or import in Settings, as one line. */
    val applianceFileResult: String? = null,
    val status: Status? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fetchedAt: Instant? = null,
    /** Shown when a refresh was skipped because of the cooldown; cleared by the next recompute. */
    val notice: String? = null,
    /** Until when a refresh would be skipped because of the cooldown; null when it wouldn't. */
    val cooldownEnd: Instant? = null,
) {
    /** The limit: the goal (when on), the floor from the appliances (while their panel shows) or the month's highest, whichever is highest. */
    val peakLine: Double? get() = peakLine(goalKw?.takeIf { goalEnabled }, peakFloor(appliances).takeIf { appliancesEnabled }, meter.highest)
    /** Whether this quarter hour's projection reaches the limit: the bar turns red. */
    val peakWarning: Boolean get() {
        val projection = meter.projection ?: return false
        return isPeakWarning(projection.kw, peakLine ?: return false)
    }
    /** Whether the main screen shows the panels: the peak window, the appliances and the history. */
    val showPeak: Boolean get() = peakEnabled && whatwattEnabled
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
 * once per quarter hour when it reaches the limit; the quarter hours come from the
 * recorder on the whatwatt. The appliances are kept in `appliances.json` and advised with each
 * reading and each minute; a measurement under way is kept in the prefs.
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
            powerUnit = PowerUnit.of(prefs.getString(KEY_POWER_UNIT, null)),
            peakEnabled = prefs.getBoolean(KEY_PEAK_ENABLED, false),
            goalEnabled = prefs.getBoolean(KEY_GOAL_ENABLED, false),
            goalKw = prefs.getString(KEY_GOAL_KW, null)?.let(::parsePositive),
            countdown = prefs.getBoolean(KEY_COUNTDOWN, false),
            vibrateAlways = prefs.getBoolean(KEY_VIBRATE_ALWAYS, false),
            peakOpen = prefs.getBoolean(KEY_PEAK_OPEN, true),
            historyOpen = prefs.getBoolean(KEY_HISTORY_OPEN, true),
            appliancesOpen = prefs.getBoolean(KEY_APPLIANCES_OPEN, true),
            appliancesEnabled = prefs.getBoolean(KEY_APPLIANCES_ENABLED, true),
            closedSections = prefs.getStringSet(KEY_SETTINGS_CLOSED, null).orEmpty().toSet(),
            applianceHelpSeen = prefs.getBoolean(KEY_APPLIANCE_HELP_SEEN, false),
            measuring = prefs.getString(KEY_MEASURING, null)?.let { runCatching { parseMeasuring(it) }.getOrNull() },
        ),
    )
    val state: StateFlow<UiState> = _state

    private val applianceFile = ApplianceFile(File(app.filesDir, "appliances.json"))
    /** One write at a time, since each goes through the same temporary file. */
    private val fileWrites = Dispatchers.IO.limitedParallelism(1)

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { applianceFile.load() }.getOrDefault(emptyList()) }
            _state.update { it.copy(appliances = loaded) }
            derive()
        }
    }

    private val meter = WhatwattMeter(File(app.filesDir, "recorder"), { _state.value.region.zone }) { meter -> _state.update { it.copy(meter = meter) } }

    /**
     * On app start/resume and every minute: recompute from the cache, and fetch when [wantsFetch]
     * says so. Fetches the user didn't ask for always wait the full cooldown.
     */
    suspend fun update() {
        if (!_state.value.firstStartDone) return
        loadCache()
        recompute()
        val now = Instant.now()
        if (wantsFetch(slots, now, _state.value.region) && mayFetch(lastAttempt, now, hasCurrentData = true)) refresh()
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
        val now = Instant.now()
        val status = classify(slots, now)
        _state.update { it.copy(status = status, notice = null, cooldownEnd = cooldownEnd(lastAttempt, now, status != null)) }
        derive()
    }

    /** Each appliance's advice, from the reading, the line and the prices, and the result of a finished measurement. */
    private fun derive() {
        val now = Instant.now()
        _state.update { s ->
            val projection = s.meter.projection
            val kw = s.meter.kw
            val peak = if (projection != null && kw != null) PeakNow(projection, kw, s.peakLine) else null
            // Gone without a reading, or when the appliance is deleted, renamed or measured again.
            val previewed = s.appliances.find { it.name == s.preview && it.name != s.measuring?.appliance?.name }
            val previewLoads = if (peak != null && previewed != null) previewed.quarterLoads(now, peak) else emptyList()
            s.copy(
                advice = peak?.let { s.appliances.mapNotNull { a -> advise(a, now, it, slots)?.let { a.name to it } }.toMap() }.orEmpty(),
                preview = s.preview.takeIf { previewLoads.isNotEmpty() },
                previewLoads = previewLoads,
                measurement = s.measuring?.result(s.meter.quarters),
            )
        }
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
        _state.update { it.copy(region = region, status = null, loading = false, error = null, fetchedAt = null, notice = null, cooldownEnd = null) }
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
        val first = _state.value.meter.lastMonthHighest?.takeIf { enabled && !prefs.contains(KEY_GOAL_KW) }?.let { _state.value.powerUnit.round(it.kw) }
        prefs.edit {
            putBoolean(KEY_GOAL_ENABLED, enabled)
            first?.let { putString(KEY_GOAL_KW, it.toString()) }
        }
        _state.update { it.copy(goalEnabled = enabled, goalKw = first ?: it.goalKw) }
    }

    /** The goal in kW; null (a blank field) doesn't count for the limit. */
    fun setGoal(kw: Double?) {
        prefs.edit { if (kw == null) remove(KEY_GOAL_KW) else putString(KEY_GOAL_KW, kw.toString()) }
        _state.update { it.copy(goalKw = kw) }
    }

    fun setPowerUnit(unit: PowerUnit) {
        prefs.edit { putString(KEY_POWER_UNIT, unit.id) }
        _state.update { it.copy(powerUnit = unit) }
    }

    fun setPeakOpen(open: Boolean) {
        prefs.edit { putBoolean(KEY_PEAK_OPEN, open) }
        _state.update { it.copy(peakOpen = open) }
    }

    fun setHistoryOpen(open: Boolean) {
        prefs.edit { putBoolean(KEY_HISTORY_OPEN, open) }
        _state.update { it.copy(historyOpen = open) }
    }

    fun setAppliancesOpen(open: Boolean) {
        prefs.edit { putBoolean(KEY_APPLIANCES_OPEN, open) }
        _state.update { it.copy(appliancesOpen = open) }
    }

    /** The appliances switch in Settings; hiding the panel also closes its preview. */
    fun setAppliancesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_APPLIANCES_ENABLED, enabled) }
        _state.update { it.copy(appliancesEnabled = enabled, preview = it.preview.takeIf { enabled }) }
        derive()
    }

    fun setSectionOpen(id: String, open: Boolean) {
        val closed = if (open) _state.value.closedSections - id else _state.value.closedSections + id
        prefs.edit { putStringSet(KEY_SETTINGS_CLOSED, closed) }
        _state.update { it.copy(closedSections = closed) }
    }

    /** Shows [name]'s run, started now, in the peak window; null hides it. */
    fun setPreview(name: String?) {
        _state.update { it.copy(preview = name) }
        derive()
    }

    fun applianceHelpShown() {
        prefs.edit { putBoolean(KEY_APPLIANCE_HELP_SEEN, true) }
        _state.update { it.copy(applianceHelpSeen = true) }
    }

    /** Start tapped: from now, with the draw now as the base for the jump. Needs a reading. */
    fun startMeasuring(appliance: Appliance) {
        val meter = _state.value.meter
        val kw = meter.kw ?: return
        saveMeasuring(Measuring(appliance, Instant.now().epochSecond, kw, meter.projection?.baseKw))
    }

    /** Done tapped: with the energy so far in this quarter hour, the result shows once the recorder has saved the ones before. */
    fun finishMeasuring() {
        val measuring = _state.value.measuring ?: return
        val (done, kwh) = doneAt(Instant.ofEpochSecond(Instant.now().epochSecond), _state.value.meter.projection)
        saveMeasuring(measuring.copy(done = done.epochSecond, doneKwh = kwh))
    }

    /** Saves the measured curve; an appliance of the same name (measured again) is replaced. */
    fun saveMeasurement() {
        val measuring = _state.value.measuring ?: return
        val result = _state.value.measurement as? Measurement.Result ?: return
        saveAppliances(mergeAppliances(_state.value.appliances, listOf(measuring.appliance.copy(curve = result.curve))))
        saveMeasuring(null)
    }

    fun discardMeasurement() = saveMeasuring(null)

    private fun saveMeasuring(measuring: Measuring?) {
        prefs.edit { if (measuring == null) remove(KEY_MEASURING) else putString(KEY_MEASURING, measuringJson(measuring)) }
        _state.update { it.copy(measuring = measuring) }
        derive()
    }

    /** The edit sheet's changes to the appliance that was called [name] (renaming too). */
    fun updateAppliance(name: String, appliance: Appliance) =
        saveAppliances(_state.value.appliances.map { if (it.name == name) appliance else it })

    /** A variant of a measured appliance with another run time, e.g. "Cooking 60 min". */
    fun addAppliance(appliance: Appliance) = saveAppliances(_state.value.appliances + appliance)

    fun deleteAppliance(name: String) = saveAppliances(_state.value.appliances.filter { it.name != name })

    private fun saveAppliances(appliances: List<Appliance>) {
        _state.update { it.copy(appliances = appliances) }
        derive()
        viewModelScope.launch(fileWrites) {
            try {
                applianceFile.save(appliances)
            } catch (_: Exception) {
                // Shown again from memory until the app restarts; nothing else to do.
            }
        }
    }

    /** Writes the appliances to a file the user picked, to keep them or move them to another phone. */
    fun exportAppliances(uri: Uri) {
        val appliances = _state.value.appliances
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(appliancesJson(appliances).toByteArray()) }
                }.isSuccess
            }
            _state.update { it.copy(applianceFileResult = if (ok) "Exported ${appliancesCount(appliances.size)}." else "Couldn't write the file.") }
        }
    }

    /** Adds the appliances from a file the user picked; one with the same name is replaced. */
    fun importAppliances(uri: Uri) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    parseAppliances(getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
                }.getOrNull()
            }
            if (imported != null) saveAppliances(mergeAppliances(_state.value.appliances, imported))
            _state.update {
                it.copy(applianceFileResult = if (imported == null) "This file has no appliances GridLoad can read." else "Imported ${appliancesCount(imported.size)}.")
            }
        }
    }

    fun setCountdown(show: Boolean) {
        prefs.edit { putBoolean(KEY_COUNTDOWN, show) }
        _state.update { it.copy(countdown = show) }
    }

    fun setVibrateAlways(always: Boolean) {
        prefs.edit { putBoolean(KEY_VIBRATE_ALWAYS, always) }
        _state.update { it.copy(vibrateAlways = always) }
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
        viewModelScope.launch { meter.test(address, _state.value.powerUnit) }
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
        derive()
    }

    /** With peak load on, vibrates once per quarter hour when it reaches the limit. */
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
        val effect = VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
        // Without a usage, Android takes it for touch feedback, which silent mode turns off.
        if (Build.VERSION.SDK_INT >= 33) {
            val usage = if (state.vibrateAlways) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION
            vibrator?.vibrate(effect, VibrationAttributes.createForUsage(usage))
        } else {
            val usage = if (state.vibrateAlways) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
            @Suppress("DEPRECATION")
            vibrator?.vibrate(effect, AudioAttributes.Builder().setUsage(usage).build())
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

/** "1 appliance", "3 appliances". */
private fun appliancesCount(n: Int) = if (n == 1) "1 appliance" else "$n appliances"

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
private const val KEY_POWER_UNIT = "power_unit"
private const val KEY_COUNTDOWN = "peak_countdown"
private const val KEY_VIBRATE_ALWAYS = "peak_vibrate_always"
private const val KEY_PEAK_OPEN = "peak_open"
private const val KEY_HISTORY_OPEN = "history_open"
private const val KEY_APPLIANCES_OPEN = "appliances_open"
private const val KEY_APPLIANCES_ENABLED = "appliances_enabled"
private const val KEY_SETTINGS_CLOSED = "settings_closed"
private const val KEY_APPLIANCE_HELP_SEEN = "appliance_help_seen"
private const val KEY_MEASURING = "measuring"
