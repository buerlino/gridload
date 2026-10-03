package io.github.buerlino.gridload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.COUNTRIES
import io.github.buerlino.gridload.core.Country
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.RecorderCheck
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.countryOf
import java.util.Locale

/** What a setting is for, shown when its label is tapped; the screens themselves stay minimal. */
private class Info(val title: String, val text: String)

private val REGION_INFO = Info(
    "Region",
    "GridLoad uses your utility's dynamic tariff. Only utilities that publish one are listed. Pick the one on your electricity bill.",
)
private val WHATWATT_INFO = Info(
    "whatwatt",
    "A whatwatt Go reads your smart meter. GridLoad then shows what you use right now and what it costs, and can watch " +
        "your monthly peak. It works while your phone is on your home Wi-Fi.",
)
private val PEAK_INFO = Info(
    "Peak load",
    "Some grid tariffs also charge for the month's highest quarter hour: your average kW over 15 minutes. The scale shows " +
        "this quarter hour, the two before and the month's highest. Close to a new peak, the bar turns red and the phone " +
        "vibrates. It needs GridLoad's recorder on the whatwatt, and an SD card in it.",
)
private val RECORDER_INFO = Info(
    "Recorder",
    "A small script GridLoad puts on the whatwatt. It records every quarter hour on the whatwatt's SD card, also while " +
        "the app is closed, so the month's highest is complete when you open the app. It uses the whatwatt's one script " +
        "slot. Peak load needs it.",
)
private val GOAL_INFO = Info(
    "Goal",
    "Off: the line not to pass is this month's highest quarter hour. On: your own value. The line is then the higher " +
        "of the two, since everything up to the month's highest is billed anyway.",
)
private val APPLIANCES_INFO = Info(
    "Appliances",
    "Export saves your measured appliances to a file, so you don't have to measure them again on a new phone. Import " +
        "adds them from such a file; one with the same name is replaced.",
)
private val COUNTDOWN_INFO = Info(
    "Quarter-hour countdown",
    "Shows the minutes left in this quarter hour. Waiting a few minutes before switching on a big appliance can keep it " +
        "out of the current one.",
)

/**
 * The setup guide: on first start the help, then Next; then the country (preselected with the
 * phone's) and its regions, and the measurement (the whatwatt, and peak load once connected).
 * Done switches the whatwatt on, Skip switches it and peak load off. From Settings ([onClose]
 * set) it starts at the region and can be left with back.
 */
@Composable
fun SetupGuide(
    state: UiState,
    viewModel: MainViewModel,
    onDone: (Region) -> Unit,
    onClose: (() -> Unit)?,
) {
    val firstStep = if (onClose == null) 0 else 1
    var step by rememberSaveable { mutableIntStateOf(firstStep) }
    var chosenRegion by rememberSaveable { mutableStateOf(state.region.id) }
    val context = LocalContext.current
    var country by rememberSaveable { mutableStateOf(if (onClose != null) state.region.country.code else phoneCountry(context)?.code) }
    BackHandler(enabled = step > firstStep || onClose != null) { if (step > firstStep) step-- else onClose?.invoke() }
    // Each step starts at its top, not where the one before was scrolled to.
    key(step) {
        Page {
            when (step) {
                0 -> {
                    Text("Welcome to GridLoad", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    HelpContent()
                    Button(onClick = { step = 1 }, modifier = Modifier.align(Alignment.End)) { Text("Next") }
                }
                1 -> {
                    Title("⚡ Region", onClose, REGION_INFO)
                    Picker(COUNTRIES.find { it.code == country }?.label ?: "Choose your country", COUNTRIES, Country::label, { country = it.code })
                    REGIONS.filter { it.country.code == country }.forEach { r ->
                        OptionCard(selected = onClose != null && r.id == chosenRegion, onClick = { chosenRegion = r.id; step = 2 }) {
                            Text(r.name, fontWeight = FontWeight.Bold)
                            Text(r.utility)
                        }
                    }
                }
                else -> {
                    val skip = state.whatwattAddress.isNullOrBlank()
                    Title("📟 Measurement", onClose)
                    InfoLabel("whatwatt", WHATWATT_INFO)
                    Connection(state, viewModel)
                    if (state.meter.connected) SwitchRow("📊 Peak load", PEAK_INFO, state.peakEnabled, viewModel::setPeakEnabled)
                    Button(
                        onClick = {
                            viewModel.setWhatwattEnabled(!skip)
                            if (skip) viewModel.setPeakEnabled(false)
                            onDone(REGIONS.first { it.id == chosenRegion })
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(if (skip) "Skip" else "Done") }
                }
            }
        }
    }
}

/**
 * Region, then Measurement: the whatwatt switch, its connection, and peak load with its own
 * switches. [pickRegion] opens the region list right away (from the region in the top bar).
 */
@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: MainViewModel,
    pickRegion: Boolean,
    onOpenGuide: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            Text("Settings", Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onOpenGuide) { Text("Setup guide") }
        }
        InfoLabel("⚡ Region", REGION_INFO, style = SECTION)
        // Only the region is saved; another country shows its regions until one is picked.
        var country by rememberSaveable { mutableStateOf(state.region.country.code) }
        Picker(COUNTRIES.first { it.code == country }.label, COUNTRIES, Country::label, { country = it.code })
        Picker(
            state.region.takeIf { it.country.code == country }?.label ?: "Choose your region",
            REGIONS.filter { it.country.code == country },
            Region::label,
            viewModel::selectRegion,
            initiallyOpen = pickRegion,
        )
        Text("📟 Measurement", style = SECTION)
        SwitchRow("whatwatt", WHATWATT_INFO, state.whatwattEnabled, viewModel::setWhatwattEnabled)
        if (state.whatwattEnabled) {
            Connection(state, viewModel)
            UnitRow(state.powerUnit, viewModel::setPowerUnit)
            SwitchRow("📊 Peak load", PEAK_INFO, state.peakEnabled, viewModel::setPeakEnabled)
            if (state.peakEnabled) {
                Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Recorder(state, viewModel)
                    SwitchRow("Goal", GOAL_INFO, state.goalEnabled, viewModel::setGoalEnabled)
                    if (state.goalEnabled) GoalField(state, viewModel::setGoal)
                    SwitchRow("Quarter-hour countdown", COUNTDOWN_INFO, state.countdown, viewModel::setCountdown)
                    ApplianceFiles(state, viewModel)
                }
            }
        }
    }
}

private val SECTION = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)

/**
 * The whatwatt's address with Test beside it and the last result below. Once connected it
 * collapses to one row with an expand button. Editing the address keeps it open until a Test
 * succeeds. Until connected, a button opens the setup guide for a new whatwatt in place of the
 * field. Android 17 (API 37) needs the local network permission for the device; it's asked for
 * on the first test.
 */
@Composable
private fun Connection(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.testWhatwattConnection() else viewModel.whatwattPermissionDenied()
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var guide by rememberSaveable { mutableStateOf(false) }
    if (state.meter.connected && !expanded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.whatwattAddress} · connected", Modifier.weight(1f))
            TextButton(onClick = { expanded = true }) { Text("▾", fontSize = 20.sp) }
        }
        return
    }
    if (guide) {
        WhatwattGuide(onClose = { guide = false })
        return
    }
    if (!state.meter.connected) TextButton(onClick = { guide = true }) { Text("New whatwatt? Set it up step by step ›") }
    var text by rememberSaveable { mutableStateOf(state.whatwattAddress.orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; expanded = true; viewModel.setWhatwattAddress(it) },
            label = { Text("Address") },
            placeholder = { Text("192.168.1.50") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = {
                expanded = false
                if (Build.VERSION.SDK_INT >= 37 &&
                    context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
                ) {
                    permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                } else {
                    viewModel.testWhatwattConnection()
                }
            },
            enabled = text.isNotBlank(),
        ) { Text("Test") }
    }
    state.meter.testResult?.let { Text(it) }
}

/**
 * GridLoad's recorder on the whatwatt: Install, Start or Fix when needed, and its state as one
 * line, red when nothing is being recorded. Details, collapsed, has when it started recording,
 * this month's gaps and Remove (after a confirmation), so Remove isn't tapped by accident.
 */
@Composable
private fun Recorder(state: UiState, viewModel: MainViewModel) {
    val meter = state.meter
    val check = meter.recorder.takeIf { meter.problem == null }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the recorder?") },
            text = {
                Text(
                    "GridLoad then records no quarter hours. Peak load and its alarm stop working, on every phone that uses " +
                        "this whatwatt, until you install it again. Quarter hours while it's removed are lost for good. " +
                        "Those already recorded stay.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmRemove = false; viewModel.removeRecorder() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { InfoLabel("Recorder", RECORDER_INFO) }
        val idle = meter.recorderAction == null || meter.recorderAction.startsWith("Couldn't")
        when {
            !idle || check == null -> {}
            check == RecorderCheck.NotInstalled -> Button(onClick = viewModel::installRecorder) { Text("Install") }
            check is RecorderCheck.Stopped -> Button(onClick = viewModel::startRecorder) { Text("Start") }
            check == RecorderCheck.NoAutoRun -> Button(onClick = viewModel::startRecorder) { Text("Fix") }
        }
    }
    val line = when {
        meter.recorderAction != null -> meter.recorderAction
        meter.problem != null -> meter.problem
        check == null -> "Checking…"
        check == RecorderCheck.Ok -> "Installed and recording."
        else -> recorderLine(check)
    }
    val warning = meter.recorderAction?.startsWith("Couldn't") == true || (meter.recorderAction == null && check != null && isRecorderWarning(check))
    line?.let { Text(it, color = if (warning) RED else Color.Unspecified) }
    val removable = meter.recorderInstalled && meter.recorderAction == null && check != null
    if (!removable && meter.recordedSince == null && meter.missing.isEmpty()) return
    var details by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "Details ▴" else "Details ▾") }
    if (!details) return
    meter.recordedSince?.let { Text("Recorded since ${shortTime(it)}.") }
    if (meter.missing.isNotEmpty()) {
        val count = meter.missing.size
        val restart = meter.restartAfterGap?.let { " (restart at ${shortTime(it)})" }.orEmpty()
        Text("$count quarter ${if (count == 1) "hour" else "hours"} missing this month, the last ${shortTime(meter.missing.last())}$restart.")
    }
    if (removable) OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove the recorder") }
}

/** Export and import of the measured appliances, through Android's file picker: no permission needed. */
@Composable
private fun ApplianceFiles(state: UiState, viewModel: MainViewModel) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportAppliances)
    }
    // Any type: a file sent through a messenger may have lost its JSON type.
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importAppliances) }
    InfoLabel("🔌 Appliances", APPLIANCES_INFO)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { export.launch("gridload_appliances.json") }, enabled = state.appliances.isNotEmpty()) { Text("Export") }
        OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }) { Text("Import") }
    }
    state.applianceFileResult?.let { Text(it) }
}

/** kW or W, for everything the whatwatt shows. */
@Composable
private fun UnitRow(unit: PowerUnit, onUnit: (PowerUnit) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Power unit", Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            PowerUnit.entries.forEachIndexed { i, u ->
                SegmentedButton(
                    selected = u == unit,
                    onClick = { onUnit(u) },
                    shape = SegmentedButtonDefaults.itemShape(i, PowerUnit.entries.size),
                ) { Text(u.id) }
            }
        }
    }
}

/** The goal in the power unit, first filled with last month's highest. Blank leaves only the month's highest. */
@Composable
private fun GoalField(state: UiState, onGoal: (Double?) -> Unit) {
    val unit = state.powerUnit
    var text by rememberSaveable(unit) { mutableStateOf(state.goalKw?.let(unit::field).orEmpty()) }
    val invalid = text.isNotBlank() && unit.parse(text) == null
    val lastMonth = state.meter.lastMonthHighest
    val hint = when {
        invalid -> "Enter a number of ${unit.id}, e.g. ${unit.field(3.5)}"
        lastMonth != null -> "Last month's highest: ${unit.format(lastMonth.kw)}"
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val kw = unit.parse(it)
            if (it.isBlank() || kw != null) onGoal(kw)
        },
        label = { Text("Goal, ${unit.id}") },
        supportingText = hint?.let { { Text(it) } },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A setting's label, with ⓘ: tapping it opens a short dialog with [info]. */
@Composable
private fun InfoLabel(text: String, info: Info, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    var open by remember { mutableStateOf(false) }
    if (open) InfoDialog(info, onDismiss = { open = false })
    Text(
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Normal)) { append(" ⓘ") }
        },
        modifier.clickable { open = true }.padding(vertical = 4.dp),
        style = style,
    )
}

@Composable
private fun InfoDialog(info: Info, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text(info.title) },
        text = { Text(info.text) },
    )
}

@Composable
private fun SwitchRow(label: String, info: Info, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { InfoLabel(label, info) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A page title, with a back arrow when the page can be left, and ⓘ when there is [info]. */
@Composable
private fun Title(text: String, onBack: (() -> Unit)?, info: Info? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { TextButton(onClick = it) { Text("←", fontSize = 22.sp) } }
        val style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (info == null) Text(text, style = style) else InfoLabel(text, info, style = style)
    }
}

/** Scrollable, padded column clear of the system bars. */
@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    StatusBarIcons(dark = true)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun OptionCard(selected: Boolean, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** A dropdown showing [selected]; tapping it, or [initiallyOpen], opens the list of [options]. */
@Composable
private fun <T> Picker(selected: String, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit, initiallyOpen: Boolean = false) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp)) {
                Text(selected)
                Spacer(Modifier.weight(1f))
                Text("▾")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { open = false; onSelect(option) },
                )
            }
        }
    }
}

/** The phone's country, from its SIM and else its language settings, if GridLoad has regions there. */
private fun phoneCountry(context: Context): Country? =
    countryOf(context.getSystemService(TelephonyManager::class.java)?.simCountryIso, Locale.getDefault().country)
