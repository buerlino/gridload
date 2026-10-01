package io.github.buerlino.gridload

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.APPLIANCE_PRESETS
import io.github.buerlino.gridload.core.Appliance
import io.github.buerlino.gridload.core.GoalRaise
import io.github.buerlino.gridload.core.Level
import io.github.buerlino.gridload.core.PeakData
import io.github.buerlino.gridload.core.PeakStatus
import io.github.buerlino.gridload.core.QUARTER
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.TARIFF_ZONE
import io.github.buerlino.gridload.core.isRunning
import io.github.buerlino.gridload.core.quarterStart
import io.github.buerlino.gridload.core.roomAt
import io.github.buerlino.gridload.core.suggestStop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

val peakTimeFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())

/**
 * Peak load mode: the price colour as in spot mode, then the current quarter hour against the
 * goal, and the appliances with start/stop and advice (CLAUDE.md, "Advice").
 */
@Composable
fun PeakScreen(
    state: UiState,
    peak: PeakData,
    importMessage: String?,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onImport: (List<Uri>) -> Unit,
    onStart: (Appliance) -> Unit,
    onStop: (Appliance) -> Unit,
    onSave: (Appliance) -> Unit,
    onDelete: (Appliance) -> Unit,
) {
    val (background, content, label, hint) = look(state)
    var editing by remember { mutableStateOf<Appliance?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding || editing != null) {
        ApplianceDialog(
            initial = editing,
            onSave = { onSave(it); adding = false; editing = null },
            onDelete = editing?.let { a -> { onDelete(a); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
    Column(
        Modifier.fillMaxSize().background(background).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenChrome(state, content, onOpenSettings)
        PriceHeader(state, content, label, hint, compact = true)
        state.peak?.let { status ->
            Card { GoalCard(status, peak) }
            Card { Appliances(status, peak, state.status?.level, onStart, onStop, onEdit = { editing = it }, onAdd = { adding = true }) }
        } ?: Card { NeedsDataCard(state.region, peak, importMessage, onImport) }
        RefreshFooter(state, background, content, onRefresh)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** The current quarter hour against the goal, what's left, and the month's peak so far. */
@Composable
private fun GoalCard(status: PeakStatus, peak: PeakData) {
    Text("This quarter hour", fontWeight = FontWeight.Bold)
    Text("%.1f of %.1f kW".format(status.quarterKw, status.goalKw), fontSize = 28.sp, fontWeight = FontWeight.Bold)
    LinearProgressIndicator(
        progress = { (status.quarterKw / status.goalKw).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = if (status.budgetKw < 0) RED else GREEN,
    )
    raisedGoalText(status)?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
    val next = timeFormat.format(quarterStart(Instant.now()) + QUARTER)
    when {
        status.budgetKw >= 0 -> Text("%.1f kW free for appliances".format(status.budgetKw))
        status.quarterKw > status.goalKw -> Text("Over your goal by %.1f kW".format(status.quarterKw - status.goalKw), color = RED, fontWeight = FontWeight.Bold)
        else -> Text("Over your goal from $next (%.1f kW)".format(status.nextQuarterKw), color = RED, fontWeight = FontWeight.Bold)
    }
    if (maxOf(status.quarterKw, status.nextQuarterKw) > status.goalKw) {
        val stop = suggestStop(peak.appliances, peak.runs, status.baseline, status.goalKw, Instant.now())
        val name = stop?.let { run -> peak.appliances.find { it.id == run.applianceId }?.name }
        Text(if (name != null) "Stop the $name to get under it." else "Stopping an appliance wouldn't help.")
    }
    Text("Usually %.1f kW at this hour without your appliances".format(status.baseNowKw))
    // Only while the estimate is the highest peak known; a higher measured one is in raisedGoalText.
    if (status.monthPeak.kw >= (status.meterPeak?.kw ?: 0.0)) {
        Text("Estimated peak this month: %.1f kW (%s)".format(status.monthPeak.kw, peakTimeFormat.format(status.monthPeak.quarter)))
    }
}

/**
 * Shown in place of the goal and appliance cards while this month has fewer than 7 imported days
 * (`status` in core is null): peak load mode needs them, so the import
 * is right here, with the same instructions as in Settings.
 */
@Composable
private fun NeedsDataCard(region: Region, peak: PeakData, message: String?, onImport: (List<Uri>) -> Unit) {
    Text("Your usage", fontWeight = FontWeight.Bold)
    LoadImport(region, peak, message, onImport)
}

/**
 * Says that this month's goal was raised to a peak that is billed anyway, and until when; null
 * while the planned goal holds. Shown on the main screen and in Settings.
 */
fun raisedGoalText(status: PeakStatus): String? {
    val reason = status.raisedBy ?: return null
    val goal = "Goal raised to %.1f kW".format(status.goalKw)
    val nextMonth = status.monthPeak.quarter.atZone(TARIFF_ZONE).toLocalDate().withDayOfMonth(1).plusMonths(1)
    val back = "Back to %.1f kW on %s.".format(status.plannedGoalKw, nextMonth.format(DateTimeFormatter.ofPattern("d MMM")))
    return when (reason) {
        GoalRaise.METER -> "$goal: your meter measured that on %s, so up to it costs nothing extra. $back".format(peakTimeFormat.format(status.meterPeak!!.quarter))
        GoalRaise.USUAL -> "$goal: your heating reaches that most nights at %02d:00, so up to it costs nothing extra.".format(status.usualPeak!!.first)
        GoalRaise.ESTIMATE -> "$goal: this month's peak (%s) already reached it, so up to it costs nothing extra. $back".format(peakTimeFormat.format(status.pastPeak.quarter))
    }
}

@Composable
private fun Appliances(
    status: PeakStatus,
    peak: PeakData,
    level: Level?,
    onStart: (Appliance) -> Unit,
    onStop: (Appliance) -> Unit,
    onEdit: (Appliance) -> Unit,
    onAdd: () -> Unit,
) {
    Text("Appliances", fontWeight = FontWeight.Bold)
    if (peak.appliances.isEmpty()) {
        Text("Add the appliances you switch on yourself, like the washing machine or kettle. Tap one to edit it.")
    }
    val now = Instant.now()
    peak.appliances.forEach { appliance ->
        val run = peak.runs.lastOrNull { it.applianceId == appliance.id && it.isRunning(now) }
        val (advice, adviceColor) = if (run != null) {
            (run.end?.let { "Running until ${timeFormat.format(Instant.ofEpochSecond(it))}" } ?: "Running") to Color.Unspecified
        } else {
            advice(appliance, status, peak, level, now)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { onEdit(appliance) }) {
                Text(appliance.name, fontWeight = FontWeight.Bold)
                Text(listOfNotNull("${appliance.watts} W", appliance.runMinutes?.let { "$it min" }).joinToString(" · "))
                Text(advice, color = adviceColor)
            }
            if (run != null) {
                Button(onClick = { onStop(appliance) }) { Text("Stop") }
            } else {
                OutlinedButton(onClick = { onStart(appliance) }) { Text("Start") }
            }
        }
    }
    TextButton(onClick = onAdd) { Text("+ Add appliance") }
}

/**
 * Whether to start [appliance] now: the goal is the hard limit, the price decides within it.
 * The colour is the price colour, or red when it would pass the goal.
 */
private fun advice(appliance: Appliance, status: PeakStatus, peak: PeakData, level: Level?, now: Instant): Pair<String, Color> {
    val room = roomAt(appliance, peak.runs, status.baseline, status.goalKw, now)
    return when {
        room == null -> "Would pass your goal. Stop something first." to RED
        room != now -> "Would pass your goal. Room from ${timeFormat.format(room)}." to RED
        level == Level.GREEN -> "Fits. Good time to start." to GREEN
        level == Level.ORANGE -> "Fits. Only if you need it." to ORANGE
        level == Level.RED -> "Fits, but a cheaper time is coming." to RED
        else -> "Fits your goal." to Color.Unspecified
    }
}

/** Add or edit an appliance; [onDelete] is set when editing. */
@Composable
private fun ApplianceDialog(initial: Appliance?, onSave: (Appliance) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var watts by rememberSaveable { mutableStateOf(initial?.watts?.toString().orEmpty()) }
    var minutes by rememberSaveable { mutableStateOf(initial?.runMinutes?.toString().orEmpty()) }
    var interruptible by rememberSaveable { mutableStateOf(initial?.interruptible ?: true) }
    val wattsValue = watts.toIntOrNull()?.takeIf { it > 0 }
    val minutesValue = minutes.toIntOrNull()?.takeIf { it > 0 }
    val valid = name.isNotBlank() && wattsValue != null && (minutes.isBlank() || minutesValue != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add appliance" else "Edit appliance") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null) {
                    PresetPicker { preset ->
                        name = preset.name
                        watts = preset.watts.toString()
                        minutes = preset.runMinutes?.toString().orEmpty()
                        interruptible = preset.interruptible
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    watts, { watts = it }, label = { Text("Power (W)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    minutes, { minutes = it }, label = { Text("Run time (min), empty = until you stop it") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(interruptible, { interruptible = it })
                    Text("Can be stopped midway")
                }
                Text(
                    "Enter only the heavy part: a washing machine heats at about 2000 W for 30 min, then uses little. Heating and hot water are already counted.",
                    style = MaterialTheme.typography.bodySmall,
                )
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = RED) } }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(Appliance(initial?.id ?: UUID.randomUUID().toString(), name.trim(), wattsValue!!, minutesValue, interruptible))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A menu of common appliances with typical values ([APPLIANCE_PRESETS]) to start from. */
@Composable
private fun PresetPicker(onPick: (Appliance) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Common appliances ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            APPLIANCE_PRESETS.forEach { preset ->
                DropdownMenuItem(
                    text = { Text("${preset.name} · ${preset.watts} W" + (preset.runMinutes?.let { ", $it min" } ?: "")) },
                    onClick = { open = false; onPick(preset) },
                )
            }
        }
    }
}
