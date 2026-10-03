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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import io.github.buerlino.gridload.core.parseNonNegative
import java.text.NumberFormat
import java.util.Locale

/** What a setting is for, shown when its label is tapped; the screens themselves stay minimal. */
private class Info(val title: String, val text: String)

private val REGION_INFO = Info(
    "Region",
    "Where utilities publish a dynamic tariff, GridLoad uses it: pick the utility on your electricity bill.\n\n" +
        "Elsewhere it uses the day-ahead market price: pick where you live.",
)
private val PRICE_INFO = Info(
    "Your price",
    "The market price is only part of what you pay.\n\n" +
        "Add-on: the rest per kWh, without VAT. Your supplier's markup, the grid fee per kWh and levies, from your bill.\n\n" +
        "VAT: preset for your country.\n\n" +
        "The colour doesn't depend on them.",
)
private val MEASUREMENT_INFO = Info(
    "Measurement",
    "A whatwatt Go reads your smart meter. GridLoad then shows what you use right now and what it costs, and can watch " +
        "your monthly peak. It works while your phone is on your home Wi-Fi.",
)
private val MODE_INFO = Info(
    "Mode",
    "Without peak load, GridLoad shows the price and what you use now.\n\n" +
        "Peak load: some grid tariffs also charge for the month's highest quarter hour. GridLoad then shows how much you " +
        "can still switch on, your appliances and the month so far. The help (?) explains it. It needs GridLoad's " +
        "recorder on the whatwatt, and an SD card in it.\n\n" +
        "Where your region doesn't bill a peak, the limit is a personal cap: nothing is billed for it.",
)
private val RECORDER_INFO = Info(
    "Recorder",
    "A small script GridLoad puts on the whatwatt. It records every quarter hour on the whatwatt's SD card, also while " +
        "the app is closed, so the month's highest is complete when you open the app. It uses the whatwatt's one script " +
        "slot. Peak load needs it.",
)
private val GOAL_INFO = Info(
    "Goal",
    "Raises the limit to this value, e.g. to what you expect to need this month anyway. It never lowers it: the " +
        "month's highest is billed anyway.",
)
private val APPLIANCES_INFO = Info(
    "Appliances",
    "On: the main screen shows your measured appliances, each with OK or WAIT, and the biggest can raise the limit. " +
        "Off hides the panel; the appliances stay. " +
        "Export saves them to a file, so you don't have to measure them again on a new phone. Import adds them from such " +
        "a file; one with the same name is replaced.",
)
private val COUNTDOWN_INFO = Info(
    "Quarter-hour countdown",
    "Shows the minutes left in this quarter hour. Waiting a few minutes before switching on a big appliance can keep it " +
        "out of the current one.",
)
private val VIBRATE_INFO = Info(
    "Vibrate at the limit",
    "When this quarter hour reaches the limit, the phone vibrates once, while GridLoad is open. Unless silent: like a " +
        "notification, so not while the phone is silent. Always: like an alarm, in silent mode too.",
)

/** Where Settings opens: at the top, at the region list (from the top bar), or at the own price's add-on (from the main screen). */
enum class SettingsAt { TOP, REGION_LIST, PRICE }

/** The Settings sections' ids, for folding them. */
private const val REGION = "region"
private const val MEASUREMENT = "measurement"
private const val MODE = "mode"

/**
 * The setup guide: on first start the help, then Next; then the country (preselected with the
 * phone's) and its regions, and the measurement (the whatwatt, and peak load once connected),
 * on the same cards as Settings. Done switches the whatwatt on, Skip switches it and peak load
 * off. From Settings ([onClose] set) it starts at the region and can be left with back.
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
    val back: () -> Unit = { if (step > firstStep) step-- else onClose?.invoke() }
    BackHandler(enabled = step > firstStep || onClose != null, onBack = back)
    // Each step starts at its top, not where the one before was scrolled to.
    key(step) {
        Page {
            if (step == 0) {
                Text("Welcome to GridLoad", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                HelpContent(COUNTRIES.find { it.code == country })
                Button(onClick = { step = 1 }, modifier = Modifier.align(Alignment.End)) { Text("Next") }
                return@Page
            }
            TitleRow("Setup guide", back) { Text("$step of 2", color = MUTED) }
            if (step == 1) {
                Section("⚡ Region", REGION_INFO) {
                    Picker(COUNTRIES.find { it.code == country }?.label ?: "Choose your country", COUNTRIES, Country::label, { country = it.code })
                    REGIONS.filter { it.country.code == country }.forEach { r ->
                        OptionCard(selected = onClose != null && r.id == chosenRegion, onClick = { chosenRegion = r.id; step = 2 }) {
                            Text(r.name, fontWeight = FontWeight.Bold)
                            Text(r.utility.replaceFirstChar { it.uppercase() }) // "Market price" on its own line
                        }
                    }
                }
            } else {
                val skip = state.whatwattAddress.isNullOrBlank()
                Section("📟 Measurement", MEASUREMENT_INFO) { Connection(state, REGIONS.first { it.id == chosenRegion }, viewModel) }
                if (state.meter.connected) {
                    Section("📊 Mode", MODE_INFO) { PeakLoadSwitch(state, REGIONS.first { it.id == chosenRegion }, viewModel) }
                }
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

/**
 * Three sections, each a light grey card that folds to a one-line summary: the region; the
 * whatwatt with its connection, the power unit and, with peak load on, the recorder; and the
 * mode, peak load with its goal, countdown and appliances. [at] opens the region section with
 * its list or at the add-on.
 */
@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: MainViewModel,
    at: SettingsAt,
    onOpenGuide: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { if (at != SettingsAt.TOP) viewModel.setSectionOpen(REGION, true) }
    Page {
        TitleRow("Settings", onBack) { OutlinedButton(onClick = onOpenGuide) { Text("Setup guide") } }
        val closed = state.closedSections
        val toggle = { id: String -> viewModel.setSectionOpen(id, id in closed) }
        val addOn = state.priceAddOn?.takeIf { state.region.isSpot }?.let { " · + ${amount(it)} ${state.region.country.currency.small}" }
        Section("⚡ Region", REGION_INFO, REGION !in closed, { toggle(REGION) }, summary = { Summary(state.region.label + addOn.orEmpty()) }) {
            // Only the region is saved; another country shows its regions until one is picked.
            var country by rememberSaveable { mutableStateOf(state.region.country.code) }
            Picker(COUNTRIES.first { it.code == country }.label, COUNTRIES, Country::label, { country = it.code })
            Picker(
                state.region.takeIf { it.country.code == country }?.label ?: "Choose your region",
                REGIONS.filter { it.country.code == country },
                Region::label,
                viewModel::selectRegion,
                initiallyOpen = at == SettingsAt.REGION_LIST,
            )
            if (state.region.isSpot && state.region.country.code == country) {
                key(state.region.id) { PriceFields(state, viewModel, focus = at == SettingsAt.PRICE) }
            }
        }
        Section(
            "📟 Measurement", MEASUREMENT_INFO, MEASUREMENT !in closed, { toggle(MEASUREMENT) },
            summary = {
                val meter = state.meter
                Summary(
                    when {
                        !state.whatwattEnabled -> "Off"
                        meter.problem != null -> meter.problem
                        meter.connected -> "${state.whatwattAddress} · connected"
                        else -> state.whatwattAddress ?: "No address yet"
                    },
                )
                // A recorder that needs attention stays in sight.
                if (state.whatwattEnabled && state.peakEnabled) {
                    val (line, warning) = recorderState(meter)
                    if (warning) line?.let { Text(it, color = RED) }
                }
            },
        ) {
            SwitchRow("whatwatt", null, state.whatwattEnabled, viewModel::setWhatwattEnabled)
            if (state.whatwattEnabled) {
                Connection(state, state.region, viewModel)
                UnitRow(state.powerUnit, viewModel::setPowerUnit)
                // The recorder is checked only with peak load on, which is what needs it.
                if (state.peakEnabled) Recorder(state, viewModel)
            }
        }
        if (!state.whatwattEnabled) return@Page
        Section(
            "📊 Mode", MODE_INFO, MODE !in closed, { toggle(MODE) },
            summary = { Summary(if (state.peakEnabled) "Peak load" else "Prices only") },
        ) {
            PeakLoadSwitch(state, state.region, viewModel)
            if (state.peakEnabled) {
                SwitchRow("Goal", GOAL_INFO, state.goalEnabled, viewModel::setGoalEnabled)
                if (state.goalEnabled) GoalField(state, viewModel::setGoal)
                SwitchRow("Quarter-hour countdown", COUNTDOWN_INFO, state.countdown, viewModel::setCountdown)
                VibrateRow(state.vibrateAlways, viewModel::setVibrateAlways)
                Appliances(state, viewModel)
            }
        }
    }
}

/** The Peak load switch; once it's on in a [region] that bills no peak, a muted line says so. */
@Composable
private fun PeakLoadSwitch(state: UiState, region: Region, viewModel: MainViewModel) {
    SwitchRow("Peak load", null, state.peakEnabled, viewModel::setPeakEnabled)
    if (state.peakEnabled && region.minimumKw == null) Text("Your region doesn't bill a peak.", color = MUTED)
}

private val SECTION = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)

/** The very light grey of a section's card. */
private val GROUP = Color(0xFFF4F4F5)

/**
 * A section: a light grey card with its title (and ⓘ) at the top. With [onToggle], tapping the
 * title row folds it to [summary] and opens it again, like the panels on the main screen.
 */
@Composable
private fun Section(
    title: String,
    info: Info,
    open: Boolean = true,
    onToggle: (() -> Unit)? = null,
    summary: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = GROUP, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Fold(open, onToggle) { InfoLabel(title, info, style = SECTION) }
            if (open) content() else summary()
        }
    }
}

/** A section's state when folded, in one muted line. */
@Composable
private fun Summary(text: String) {
    Text(text, color = MUTED)
}

/** A row that folds what's below it: [label], then ▴ or ▾ at the end; without [onToggle] just [label]. */
@Composable
private fun Fold(open: Boolean, onToggle: (() -> Unit)?, label: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(
            if (onToggle == null) Modifier else Modifier.clickable(onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle),
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) { Row(content = label) }
        if (onToggle != null) Text(if (open) "▴" else "▾", color = MUTED, fontSize = 20.sp)
    }
}

/**
 * The whatwatt's address with Test beside it and the last result below (or why it can't be
 * read), green once connected.
 * Once connected it folds to "192.168.0.36 · connected", and opens and folds again with a tap;
 * it stays open after a Test, so the result can be read. Until connected, a button opens the
 * setup guide for a new whatwatt in place of the field, for [region] (the one just picked in the
 * setup guide, which isn't saved until Done). Android 17 (API 37) needs the local
 * network permission for the device; it's asked for on the first test.
 */
@Composable
private fun Connection(state: UiState, region: Region, viewModel: MainViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.testWhatwattConnection() else viewModel.whatwattPermissionDenied()
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var guide by rememberSaveable { mutableStateOf(false) }
    val connected = state.meter.connected
    if (connected && !expanded) {
        Fold(open = false, onToggle = { expanded = true }) { Text("${state.whatwattAddress} · connected") }
        return
    }
    if (guide) {
        WhatwattGuide(region, onClose = { guide = false })
        return
    }
    if (connected) {
        Fold(open = true, onToggle = { expanded = false }) { Text("${state.whatwattAddress} · connected") }
    } else {
        TextButton(onClick = { guide = true }) { Text("New whatwatt? Set it up step by step ›") }
    }
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
                expanded = true
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
    (state.meter.testResult ?: state.meter.problem)?.let { Text(it, color = if (connected) GREEN else Color.Unspecified) }
}

/** The recorder's state as one line, and whether it's a warning (red: nothing is being recorded, or an action failed). */
private fun recorderState(meter: MeterState): Pair<String?, Boolean> {
    val check = meter.recorder.takeIf { meter.problem == null }
    val line = when {
        meter.recorderAction != null -> meter.recorderAction
        meter.problem != null -> meter.problem
        check == null -> "Checking…"
        check == RecorderCheck.Ok -> "Installed and recording."
        else -> recorderLine(check)
    }
    return line to (meter.recorderFailed || (meter.recorderAction == null && check != null && isRecorderWarning(check)))
}

/**
 * GridLoad's recorder on the whatwatt, folded to "Recorder ▾". Install, Start or Fix show
 * beside it when needed, and a warning or an action under way stays visible while folded,
 * like the peak window's red line. Open, it has its state, when it started recording, this
 * month's gaps and Remove (after a confirmation).
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
    var open by rememberSaveable { mutableStateOf(false) }
    val (line, warning) = recorderState(meter)
    Fold(open, onToggle = { open = !open }) {
        Box(Modifier.weight(1f)) { InfoLabel("Recorder", RECORDER_INFO) }
        val idle = meter.recorderAction == null || meter.recorderFailed
        when {
            !idle || check == null -> {}
            check == RecorderCheck.NotInstalled -> Button(onClick = viewModel::installRecorder) { Text("Install") }
            check is RecorderCheck.Stopped -> Button(onClick = viewModel::startRecorder) { Text("Start") }
            check == RecorderCheck.NoAutoRun -> Button(onClick = viewModel::startRecorder) { Text("Fix") }
        }
    }
    if (open || warning || meter.recorderAction != null) line?.let { Text(it, color = if (warning) RED else Color.Unspecified) }
    if (!open) return
    meter.recordedSince?.let { Text("Recorded since ${shortTime(it)}.") }
    if (meter.missing.isNotEmpty()) {
        val count = meter.missing.size
        val restart = meter.restartAfterGap?.let { " (restart at ${shortTime(it)})" }.orEmpty()
        Text("$count quarter ${if (count == 1) "hour" else "hours"} missing this month, the last ${shortTime(meter.missing.last())}$restart.")
    }
    if (meter.recorderInstalled && meter.recorderAction == null && check != null) {
        OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove the recorder") }
    }
}

/**
 * The appliances panel's switch, and export and import of the measured appliances through
 * Android's file picker (no permission needed); those work with the panel hidden too.
 */
@Composable
private fun Appliances(state: UiState, viewModel: MainViewModel) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportAppliances)
    }
    // Any type: a file sent through a messenger may have lost its JSON type.
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importAppliances) }
    SwitchRow("Appliances", APPLIANCES_INFO, state.appliancesEnabled, viewModel::setAppliancesEnabled)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { export.launch("gridload_appliances.json") }, enabled = state.appliances.isNotEmpty()) { Text("Export") }
        OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }) { Text("Import") }
    }
    state.applianceFileResult?.let { Text(it) }
}

/**
 * The add-on and the VAT that turn the market price into the user's own. The VAT field shows the
 * country's rate until another is typed; that rate is saved as blank, so it follows the country.
 * [focus] puts the cursor in the add-on (from the main screen's "Set your price").
 */
@Composable
private fun PriceFields(state: UiState, viewModel: MainViewModel, focus: Boolean) {
    val country = state.region.country
    val addOn = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focus) addOn.requestFocus() }
    InfoLabel("Your price", PRICE_INFO)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(state.priceAddOn, "Add-on, ${country.currency.small}/kWh", Modifier.weight(2f).focusRequester(addOn), viewModel::setPriceAddOn)
        NumberField(state.priceVat ?: country.vat, "VAT, %", Modifier.weight(1f)) { vat -> viewModel.setPriceVat(vat.takeIf { it != country.vat }) }
    }
}

/** A field for a number of 0 or more; [onValue] gets null when it's blank, and nothing while it isn't a number. */
@Composable
private fun NumberField(value: Double?, label: String, modifier: Modifier, onValue: (Double?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value?.let(::amount).orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val number = parseNonNegative(it)
            if (it.isBlank() || number != null) onValue(number)
        },
        label = { Text(label) },
        isError = text.isNotBlank() && parseNonNegative(text) == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** An add-on or a VAT rate as typed: "18.5", "20", "8.1", in the phone's decimal separator. */
private fun amount(value: Double): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; isGroupingUsed = false }.format(value)

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

/** How the phone vibrates when this quarter hour reaches the limit: unless silent, or always. */
@Composable
private fun VibrateRow(always: Boolean, onAlways: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoLabel("Vibrate at the limit", VIBRATE_INFO)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "Unless silent", true to "Always").forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = always == value,
                    onClick = { onAlways(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
    }
}

/** The goal in the power unit, first filled with last month's highest. Blank: it doesn't count for the limit. */
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
private fun SwitchRow(label: String, info: Info?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { if (info == null) Text(label) else InfoLabel(label, info) }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Scrollable, padded column clear of the system bars. */
@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    StatusBarIcons(dark = true)
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A [Page]'s top row: ← and the [title], with [trailing] at the end. */
@Composable
internal fun TitleRow(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
        Text(title, Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        trailing()
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
