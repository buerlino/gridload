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
import io.github.buerlino.gridload.core.LoadProfile
import io.github.buerlino.gridload.core.MonthUsage
import io.github.buerlino.gridload.core.PeakData
import io.github.buerlino.gridload.core.PeakStatus
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.TARIFF_ZONE
import io.github.buerlino.gridload.core.highestHour
import io.github.buerlino.gridload.core.hourlyMonths
import java.time.Month
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The setup guide: on first start the help, then Next; then the mode, the region, and for peak
 * load mode the load data and the goal. Spot price mode is done after the region. From Settings
 * ([onClose] set) it starts at the mode and can be left with back.
 */
@Composable
fun SetupGuide(
    mode: Mode,
    region: Region,
    peak: PeakData,
    importMessage: String?,
    onImport: (List<Uri>) -> Unit,
    onGoalOffset: (Double) -> Unit,
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
                        if (chosenMode == Mode.SPOT) onDone(chosenMode, r) else step = 3
                    }) {
                        Text(r.name, fontWeight = FontWeight.Bold)
                        Text(r.utility)
                    }
                }
            }
            3 -> {
                Title("Your load data", onClose)
                LoadData(peak, importMessage, onImport)
                Button(onClick = { step = 4 }, modifier = Modifier.align(Alignment.End)) {
                    Text(if (peak.usage.isEmpty()) "Skip for now" else "Next")
                }
            }
            else -> {
                Title("Your goal", onClose)
                GoalSetting(peak, onGoalOffset)
                Calibration(null)
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
    onSelectMode: (Mode) -> Unit,
    onSelectRegion: (Region) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onGoalOffset: (Double) -> Unit,
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
        if (state.mode == Mode.PEAK) {
            Text("Load data", fontWeight = FontWeight.Bold)
            LoadData(peak, importMessage, onImport)
            Text("Goal", fontWeight = FontWeight.Bold)
            GoalSetting(peak, onGoalOffset)
            state.peak?.let(::raisedGoalText)?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Calibration(state.peak)
        }
        OutlinedButton(onClick = onOpenGuide) { Text("Open the setup guide") }
    }
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

/** The two modes. The emoji are placeholders until the mode logos are decided. */
@Composable
private fun ModeChoice(selected: Mode?, onSelect: (Mode) -> Unit) {
    OptionCard(selected == Mode.SPOT, { onSelect(Mode.SPOT) }, "⚡") {
        Text("Spot price", fontWeight = FontWeight.Bold)
        Text("Shows when electricity is cheap, so you know when to run your appliances.")
    }
    OptionCard(selected == Mode.PEAK, { onSelect(Mode.PEAK) }, "📊") {
        Text("Peak load", fontWeight = FontWeight.Bold)
        Text("Tracks your appliances and helps keep your monthly peak low, which lowers your grid bill.")
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
 * The import of CKW exports and what GridLoad derived from them: the level per month from yearly
 * exports, and from daily exports (hourly values) what runs at which hour.
 */
@Composable
private fun LoadData(peak: PeakData, message: String?, onImport: (List<Uri>) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onImport(it) }
    Text("Import your consumption from the CKW customer portal as Excel. Yearly exports (one row per month) give each month's level, heating included. Daily exports (one row per hour; pick a whole month's files at once) show what runs at which hour, like a water heater at night. The files stay on this phone.")
    OutlinedButton(onClick = { picker.launch(arrayOf(XLSX, "application/octet-stream")) }) { Text("Import Excel files") }
    message?.let { Text(it) }
    val usage = peak.usage
    if (usage.isNotEmpty()) {
        val profile = LoadProfile(usage)
        Text("${usage.size} months, ${usage.first().yearMonth} to ${usage.last().yearMonth}. Average draw per month; heating is what a month uses above the lowest one:")
        Column {
            Month.entries.forEach { month ->
                val average = profile.averageKw[month] ?: return@forEach
                Row {
                    Text(month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), Modifier.weight(1f))
                    Text("%.2f kW".format(average), Modifier.weight(1f))
                    Text("heating %.2f kW".format(profile.heatingKw(month)), Modifier.weight(1.5f))
                }
            }
        }
    }
    hourlyMonths(peak.days).forEach { (month, hourly) ->
        val highest = peak.days.map { YearMonth.from(it.localDate) }.distinct().filter { it.month == month }
            .mapNotNull { highestHour(peak.days, it) }.maxByOrNull { it.kw }
        Text(
            buildString {
                append("${month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} (${hourly.days} days of hourly values): always on %.2f kW.".format(hourly.staticKw))
                append(" Usually highest at %02d:00 (%.1f kW).".format(hourly.peakHour, hourly.baseKw[hourly.peakHour]))
                highest?.let { append(" Highest hour: %.2f kW (%s).".format(it.kw, peakTimeFormat.format(it.quarter))) }
            },
        )
    }
}

/** The goal offset: the goal is the month's baseline plus this. */
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
    val level = Baseline(peak).levelKw(month)
    Text(
        if (level == null) {
            "Without load data for this month, the goal is just this number."
        } else {
            "In ${month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}: %.2f kW level + %.1f kW = %.2f kW goal.".format(level, peak.goalOffsetKw, level + peak.goalOffsetKw)
        },
    )
}

/** Explains the summer calibration of the baseline; [status] shows where it stands. */
@Composable
private fun Calibration(status: PeakStatus?) {
    Text("Calibration: from June to August there's no heating. Track all your appliances in the app then, and after a week GridLoad subtracts them from the summer level. What's left is what always runs (fridge, router, standby), and the baseline gets more accurate.")
    if (status != null) Text(if (status.calibrated) "Calibrated with this summer's tracking." else "Not calibrated yet.")
}

private const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
