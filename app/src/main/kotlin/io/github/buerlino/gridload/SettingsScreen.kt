package io.github.buerlino.gridload

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.parseKw
import java.util.Locale

/** What a setting is for, shown when its label is tapped; the screens themselves stay minimal. */
private class Info(val title: String, val text: String, val link: Pair<String, String>? = null)

private val REGION_INFO = Info(
    "Region",
    "GridLoad uses your utility's dynamic tariff. Only utilities that publish one are listed. Pick the one on your electricity bill.",
)
private val WHATWATT_INFO = Info(
    "whatwatt",
    "A whatwatt Go reads your smart meter over your home Wi-Fi. GridLoad then shows what you use right now and what it costs, " +
        "and can watch your monthly peak. It needs the whatwatt Plus licence and works only while your phone is on your home Wi-Fi. " +
        "Reserve the device's address in your router so it stays the same.",
    "whatwatt Go Reference Manual" to "https://whatwatt.ch/doc/whatwatt_Go_Reference_Manual_v1.0.pdf",
)
private val PEAK_INFO = Info(
    "Peak load",
    "Some grid tariffs also charge for the month's highest quarter hour: your average kW over 15 minutes. The scale shows " +
        "this quarter hour, the two before and the month's highest. Close to a new peak, the bar turns red and the phone " +
        "vibrates. GridLoad only sees quarter hours while it's open.",
)
private val GOAL_INFO = Info(
    "Goal",
    "Off: the line not to pass is this month's highest quarter hour. On: your own value in kW. The line is then the higher " +
        "of the two, since everything up to the month's highest is billed anyway.",
)
private val SCALE_INFO = Info(
    "Show the scale without a reading",
    "When the whatwatt can't be read, e.g. away from home: on keeps the scale with the recorded quarter hours, off hides it.",
)
private val COUNTDOWN_INFO = Info(
    "Quarter-hour countdown",
    "Shows the minutes until the next quarter hour. Waiting a few minutes before switching on a big appliance can keep it " +
        "out of the current one.",
)

/**
 * The setup guide: on first start the help, then Next; then the region and the measurement
 * (the whatwatt, and peak load once connected). Done switches the whatwatt on, Skip switches it
 * and peak load off. From Settings ([onClose] set) it starts at the region and can be left with back.
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
    BackHandler(enabled = step > firstStep || onClose != null) { if (step > firstStep) step-- else onClose?.invoke() }
    Page {
        when (step) {
            0 -> {
                Text("Welcome to GridLoad", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                HelpContent()
                Button(onClick = { step = 1 }, modifier = Modifier.align(Alignment.End)) { Text("Next") }
            }
            1 -> {
                Title("⚡ Region", onClose, REGION_INFO)
                REGIONS.forEach { r ->
                    OptionCard(selected = onClose != null && r.id == chosenRegion, onClick = { chosenRegion = r.id; step = 2 }) {
                        Text(r.name, fontWeight = FontWeight.Bold)
                        Text(r.utility)
                    }
                }
            }
            else -> {
                val skip = state.whatwattAddress.isNullOrBlank()
                Title("Measurement", onClose)
                InfoLabel("📟 whatwatt", WHATWATT_INFO)
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
        RegionPicker(state.region, viewModel::selectRegion, pickRegion)
        Text("Measurement", style = SECTION)
        SwitchRow("📟 whatwatt", WHATWATT_INFO, state.whatwattEnabled, viewModel::setWhatwattEnabled)
        if (state.whatwattEnabled) {
            Connection(state, viewModel)
            SwitchRow("📊 Peak load", PEAK_INFO, state.peakEnabled, viewModel::setPeakEnabled)
            if (state.peakEnabled) {
                Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SwitchRow("Goal", GOAL_INFO, state.goalEnabled, viewModel::setGoalEnabled)
                    if (state.goalEnabled) GoalField(state, viewModel::setGoal)
                    SwitchRow("Show the scale without a reading", SCALE_INFO, state.scaleWithoutReading, viewModel::setScaleWithoutReading)
                    SwitchRow("Quarter-hour countdown", COUNTDOWN_INFO, state.countdown, viewModel::setCountdown)
                }
            }
        }
    }
}

private val SECTION = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)

/**
 * The whatwatt's address with Test beside it and the last result below. Once connected it
 * collapses to one row with an expand button. Editing the address keeps it open until a Test
 * succeeds. Android 17 (API 37) needs the local network permission for the device; it's asked
 * for on the first test.
 */
@Composable
private fun Connection(state: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.testWhatwattConnection() else viewModel.whatwattPermissionDenied()
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (state.meter.connected && !expanded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.whatwattAddress} · connected", Modifier.weight(1f))
            TextButton(onClick = { expanded = true }) { Text("▾", fontSize = 20.sp) }
        }
        return
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

/** The goal in kW, first filled with last month's highest seen. Blank leaves only the month's highest. */
@Composable
private fun GoalField(state: UiState, onGoal: (Double?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(state.goalKw?.let { "%.1f".format(Locale.ROOT, it) }.orEmpty()) }
    val invalid = text.isNotBlank() && parseKw(text) == null
    val lastMonth = state.meter.lastMonthHighest
    val hint = when {
        invalid -> "Enter a number of kW, e.g. 3.5"
        lastMonth != null -> "Last month's highest seen: %.1f kW".format(lastMonth.kw)
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val kw = parseKw(it)
            if (it.isBlank() || kw != null) onGoal(kw)
        },
        label = { Text("Goal, kW") },
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
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text(info.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(info.text)
                info.link?.let { (label, url) ->
                    TextButton(
                        onClick = {
                            try {
                                uriHandler.openUri(url)
                            } catch (_: IllegalArgumentException) {
                                // No app on the phone opens web links.
                            }
                        },
                    ) { Text(label) }
                }
            }
        },
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

/** The selected region; tapping it, or [initiallyOpen], opens the list. */
@Composable
private fun RegionPicker(region: Region, onSelect: (Region) -> Unit, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    Box {
        OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp)) {
                Text(region.label)
                Spacer(Modifier.weight(1f))
                Text("▾")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            REGIONS.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.label) },
                    onClick = { open = false; onSelect(r) },
                )
            }
        }
    }
}
