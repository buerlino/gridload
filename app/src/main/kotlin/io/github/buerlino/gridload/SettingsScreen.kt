package io.github.buerlino.gridload

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.Baseline
import io.github.buerlino.gridload.core.PeakData
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.REQUIRED_DAYS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.TARIFF_ZONE
import io.github.buerlino.gridload.core.daysIn
import io.github.buerlino.gridload.core.highestHour
import io.github.buerlino.gridload.core.hourlyMonths
import io.github.buerlino.gridload.core.suggestedWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The setup guide: on first start the help, then Next; then the mode, the region, the load data,
 * the whatwatt device (both modes, optional), and for peak load mode the goal. Spot price mode
 * can skip the load data (prices only); peak load mode needs it, or switches to spot price mode.
 * From Settings ([onClose] set) it starts at the mode and can be left with back.
 */
@Composable
fun SetupGuide(
    mode: Mode,
    region: Region,
    peak: PeakData,
    importMessage: String?,
    whatwattAddress: String?,
    whatwattTestResult: String?,
    onImport: (List<Uri>) -> Unit,
    onGoalOffset: (Double) -> Unit,
    onWhatwattAddress: (String) -> Unit,
    onTestWhatwatt: () -> Unit,
    onDone: (Mode, Region) -> Unit,
    onClose: (() -> Unit)?,
) {
    var step by rememberSaveable { mutableStateOf(if (onClose == null) 0 else 1) }
    var chosenMode by rememberSaveable { mutableStateOf(mode) }
    var chosenRegion by rememberSaveable { mutableStateOf(region.id) }
    val firstStep = if (onClose == null) 0 else 1
    BackHandler(enabled = step > firstStep || onClose != null) { if (step > firstStep) step-- else onClose?.invoke() }
    Page {
        when (step) {
            0 -> {
                Text("Welcome to GridLoad", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                HelpContent()
                Button(onClick = { step = 1 }, modifier = Modifier.align(Alignment.End)) { Text("Next") }
            }
            1 -> {
                Title("Choose a mode", onClose)
                Text("You can change it later in Settings.")
                ModeChoice(if (onClose == null) null else chosenMode) { chosenMode = it; step = 2 }
            }
            2 -> {
                Title("Choose your region", onClose)
                Text("Prices come from your local electricity utility, so pick the one that supplies you. You can change it later in Settings.")
                REGIONS.forEach { r ->
                    OptionCard(selected = onClose != null && r.id == chosenRegion, onClick = {
                        chosenRegion = r.id
                        step = 3
                    }) {
                        Text(r.name, fontWeight = FontWeight.Bold)
                        Text(r.utility)
                    }
                }
            }
            3 -> {
                val region = REGIONS.first { it.id == chosenRegion }
                val ready = daysIn(peak.days, LocalDate.now(TARIFF_ZONE).month) >= REQUIRED_DAYS
                Title("Your load data", onClose)
                Text(
                    if (chosenMode == Mode.SPOT) {
                        "With a week of your hourly consumption, GridLoad also shows what your home usually draws at this hour and what that costs. Or skip this and just use the prices."
                    } else {
                        "Peak load mode needs a week of your hourly consumption, to know what your home draws at each hour, like a water heater at night."
                    },
                )
                LoadData(peak, importMessage, onImport)
                Column(Modifier.align(Alignment.End), horizontalAlignment = Alignment.End) {
                    if (chosenMode == Mode.SPOT) {
                        Button(onClick = { step = 4 }, enabled = ready) { Text("Done") }
                        TextButton(onClick = { step = 4 }) {
                            Text(if (peak.days.isEmpty()) "Skip – prices only" else "Skip for now")
                        }
                    } else {
                        Button(onClick = { step = 4 }, enabled = ready) { Text("Next") }
                        TextButton(onClick = { onDone(Mode.SPOT, region) }) { Text("Use spot price mode instead") }
                    }
                }
            }
            4 -> {
                Title("Connect a whatwatt", onClose)
                WhatwattFields(whatwattAddress, whatwattTestResult, onWhatwattAddress, onTestWhatwatt)
                Button(
                    onClick = {
                        if (chosenMode == Mode.SPOT) onDone(chosenMode, REGIONS.first { it.id == chosenRegion }) else step = 5
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(if (whatwattAddress.isNullOrBlank()) "Not now" else if (chosenMode == Mode.SPOT) "Done" else "Next") }
            }
            else -> {
                Title("Your goal", onClose)
                GoalSetting(peak, onGoalOffset)
                Button(
                    onClick = { onDone(chosenMode, REGIONS.first { it.id == chosenRegion }) },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Done") }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: UiState,
    peak: PeakData,
    importMessage: String?,
    whatwattTestResult: String?,
    onSelectMode: (Mode) -> Unit,
    onSelectRegion: (Region) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onDeleteLoadData: () -> Unit,
    onGoalOffset: (Double) -> Unit,
    onWhatwattAddress: (String) -> Unit,
    onTestWhatwatt: () -> Unit,
    onOpenGuide: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Page {
        Title("Settings", onBack)
        Text("Mode", fontWeight = FontWeight.Bold)
        ModeChoice(state.mode, onSelectMode)
        Text("Region", fontWeight = FontWeight.Bold)
        Text("Prices come from your local electricity utility.")
        RegionPicker(state.region, onSelectRegion)
        Text("Load data", fontWeight = FontWeight.Bold)
        LoadData(peak, importMessage, onImport, onDeleteLoadData)
        Text("whatwatt", fontWeight = FontWeight.Bold)
        WhatwattFields(state.whatwattAddress, whatwattTestResult, onWhatwattAddress, onTestWhatwatt)
        if (state.mode == Mode.PEAK) {
            Text("Goal", fontWeight = FontWeight.Bold)
            GoalSetting(peak, onGoalOffset)
            state.peak?.let(::raisedGoalText)?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        }
        OutlinedButton(onClick = onOpenGuide) { Text("Open the setup guide") }
    }
}

/**
 * What a whatwatt is for, the device address, "Test connection", and the last test's result.
 * Shared by the setup guide and Settings, so the explanation lives only here.
 */
@Composable
private fun WhatwattFields(address: String?, testResult: String?, onAddress: (String) -> Unit, onTest: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(address.orEmpty()) }
    Text("Have a whatwatt Go on your smart meter? Enter its address and test the connection. Showing its live readings comes in a later version.")
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onAddress(it) },
        label = { Text("Device address, e.g. 192.168.1.50 or whatwatt-ABCDEF.local") },
        singleLine = true,
    )
    OutlinedButton(onClick = onTest, enabled = text.isNotBlank()) { Text("Test connection") }
    testResult?.let { Text(it) }
}

/** A page title, with a back arrow when the page can be left. */
@Composable
private fun Title(text: String, onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { TextButton(onClick = it) { Text("←", fontSize = 22.sp) } }
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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

/** The two modes, with emoji as logos (⚡ spot, 📊 peak). */
@Composable
private fun ModeChoice(selected: Mode?, onSelect: (Mode) -> Unit) {
    OptionCard(selected == Mode.SPOT, { onSelect(Mode.SPOT) }, "⚡") {
        Text("Spot price", fontWeight = FontWeight.Bold)
        Text("Shows when electricity is cheap, so you know when to run your appliances.")
    }
    OptionCard(selected == Mode.PEAK, { onSelect(Mode.PEAK) }, "📊") {
        Text("Peak load", fontWeight = FontWeight.Bold)
        Text("Helps keep your monthly peak low, which lowers your grid bill. You tap start and stop for your appliances; GridLoad estimates the rest from your consumption data.")
    }
}

@Composable
private fun OptionCard(selected: Boolean, onClick: () -> Unit, logo: String? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            logo?.let { Text(it, fontSize = 32.sp) }
            Column(content = content)
        }
    }
}

@Composable
private fun RegionPicker(region: Region, onSelect: (Region) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedCard(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp)) {
                Text("${region.name} (${region.utility})")
                Spacer(Modifier.weight(1f))
                Text("▾")
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            REGIONS.forEach { r ->
                DropdownMenuItem(
                    text = { Text("${r.name} (${r.utility})") },
                    onClick = { open = false; onSelect(r) },
                )
            }
        }
    }
}

/**
 * The import of CKW day exports and what GridLoad derived per month. [onDelete] (Settings only)
 * removes the data again.
 */
@Composable
private fun LoadData(peak: PeakData, message: String?, onImport: (List<Uri>) -> Unit, onDelete: (() -> Unit)? = null) {
    LoadImport(peak, message, onImport)
    hourlyMonths(peak.days).forEach { (m, hourly) ->
        val highest = peak.days.map { YearMonth.from(it.localDate) }.distinct().filter { it.month == m }
            .mapNotNull { highestHour(peak.days, it) }.maxByOrNull { it.kw }
        Text(
            buildString {
                append("${m.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} (${hourly.days} days): always on %.2f kW.".format(hourly.staticKw))
                append(" Usually highest at %02d:00 (%.1f kW).".format(hourly.peakHour, hourly.baseKw[hourly.peakHour]))
                highest?.let { append(" Highest hour: %.2f kW (%s).".format(it.kw, peakTimeFormat.format(it.quarter))) }
            },
        )
    }
    if (onDelete != null && peak.days.isNotEmpty()) {
        var confirm by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { confirm = true }) { Text("Delete load data") }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                title = { Text("Delete load data?") },
                text = { Text("GridLoad forgets the imported days. Spot price mode goes back to prices only. Your Excel files aren't touched.") },
            )
        }
    }
}

/**
 * What to export for this month, the import button and how many of its days are there. Also peak
 * load mode's main screen while this month's days are missing.
 */
@Composable
fun LoadImport(peak: PeakData, message: String?, onImport: (List<Uri>) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onImport(it) }
    val today = LocalDate.now(TARIFF_ZONE)
    val month = today.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val have = daysIn(peak.days, today.month)
    if (have < REQUIRED_DAYS) {
        val week = suggestedWeek(today)
        Text("GridLoad needs $REQUIRED_DAYS days of hourly values for $month. In the CKW customer portal, open the day view and export each day as Excel, e.g. ${week.start.dayOfMonth} to ${week.endInclusive.dayOfMonth} $month ${week.start.year}. Then pick all $REQUIRED_DAYS files here. They stay on this phone.")
    }
    OutlinedButton(onClick = { picker.launch(arrayOf(XLSX, "application/octet-stream")) }) { Text("Import Excel files") }
    Text(if (have >= REQUIRED_DAYS) "$month: $have days ✓" else "$month: $have of $REQUIRED_DAYS days", fontWeight = FontWeight.Bold)
    message?.let { Text(it) }
}

/** The goal offset: the goal is the month's level plus this. */
@Composable
private fun GoalSetting(peak: PeakData, onGoalOffset: (Double) -> Unit) {
    var text by rememberSaveable { mutableStateOf("%.1f".format(Locale.ROOT, peak.goalOffsetKw)) }
    Text("Your grid bill counts the highest 15-minute average of each month. GridLoad keeps you under a goal: this month's level (your household's average draw, heating included) plus the extra you allow for appliances.")
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            value.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let(onGoalOffset)
        },
        label = { Text("Extra for appliances (kW)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
    val month = ZonedDateTime.now(TARIFF_ZONE).month
    // Without this month's days there is no goal yet (the main screen asks for the import).
    Baseline(peak).levelKw(month)?.let { level ->
        Text("In ${month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}: %.2f kW level + %.1f kW = %.2f kW goal.".format(level, peak.goalOffsetKw, level + peak.goalOffsetKw))
    }
}

const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
