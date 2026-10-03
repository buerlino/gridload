package io.github.buerlino.gridload

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.Advice
import io.github.buerlino.gridload.core.Appliance
import io.github.buerlino.gridload.core.Measurement
import io.github.buerlino.gridload.core.Piece
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.parseKw
import io.github.buerlino.gridload.core.waterShare
import io.github.buerlino.gridload.core.withRunTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The measured appliances, below the peak window: per appliance OK (fine to switch it on now) or
 * WAIT, with when. + adds one: the setup help (by itself the first time), then a sheet with its
 * name and fields, and Start. A measurement under way shows in its row until it's saved.
 */
@Composable
fun AppliancesPanel(state: UiState, viewModel: MainViewModel) {
    var help by remember { mutableStateOf(false) }
    // The appliance being edited, or a new one (an empty name).
    var sheet by remember { mutableStateOf<Appliance?>(null) }
    sheet?.let { appliance ->
        ApplianceSheet(appliance, state, viewModel, onHelp = { help = true }, onDismiss = { sheet = null })
    }
    // After the sheet, so that its ⓘ opens the help on top of it.
    if (help) {
        MeasureHelp(onDismiss = {
            help = false
            if (!state.applianceHelpSeen) {
                viewModel.applianceHelpShown()
                sheet = NEW
            }
        })
    }
    val measuring = state.measuring
    Panel(
        open = state.appliancesOpen,
        onToggle = { viewModel.setAppliancesOpen(!state.appliancesOpen) },
        header = {
            Text("🔌 Appliances", Modifier.weight(1f), color = INK, fontSize = 16.sp)
            if (!state.appliancesOpen) summary(state)?.let { Text(it, color = MUTED, fontSize = 14.sp) }
        },
    ) {
        if (!state.appliancesOpen) return@Panel
        for (appliance in state.appliances) {
            if (appliance.name == measuring?.appliance?.name) {
                MeasuringRow(state, viewModel)
            } else {
                ApplianceRow(appliance, state.advice[appliance.name], onClick = { sheet = appliance })
            }
        }
        if (measuring != null && state.appliances.none { it.name == measuring.appliance.name }) MeasuringRow(state, viewModel)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.appliances.isEmpty() && measuring == null) "Add an appliance to see when it fits." else "", Modifier.weight(1f), color = MUTED, fontSize = 14.sp)
            TextButton(onClick = { if (state.applianceHelpSeen) sheet = NEW else help = true }) { Text("+", fontSize = 24.sp) }
        }
    }
}

private val NEW = Appliance("", emptyList())

/** "2 OK · 1 wait", or "Measuring" while a measurement runs; null without advice. */
private fun summary(state: UiState): String? {
    if (state.measuring != null) return "Measuring"
    if (state.advice.isEmpty()) return null
    val ok = state.advice.values.count { it is Advice.Ok }
    return listOfNotNull(ok.takeIf { it > 0 }?.let { "$it OK" }, (state.advice.size - ok).takeIf { it > 0 }?.let { "$it wait" }).joinToString(" · ")
}

@Composable
private fun ApplianceRow(appliance: Appliance, advice: Advice?, onClick: () -> Unit) {
    val now = Instant.now()
    val (chip, line) = when (advice) {
        null -> null to null
        is Advice.Ok -> "OK" to advice.pricesEnd?.let { "Prices after ${startTime(it)} not out yet" }
        is Advice.NewPeak -> "WAIT" to (advice.at?.let { "Sets a new peak · ${whenToStart(appliance, it, now)}" } ?: "Sets a new peak at any start")
        is Advice.Cheaper -> "WAIT" to "Cheaper ${whenToStart(appliance, advice.at, now)}"
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(appliance.name, color = INK, fontSize = 16.sp)
            line?.let { Text(it, color = MUTED, fontSize = 13.sp) }
        }
        if (chip == null) Text("–", color = MUTED, fontSize = 16.sp) else Chip(chip, if (chip == "OK") GREEN else RED)
    }
}

/** "OK at 14:15", or with a start delay "· Delay 3 h", the number to set on the appliance. */
private fun whenToStart(appliance: Appliance, at: Instant, now: Instant) =
    if (appliance.delayMinutes > 0) "· Delay ${duration(Duration.between(now, at).toMinutes().toDouble())}" else "at ${startTime(at)}"

/** "14:15", or "tomorrow 10:00". */
private fun startTime(at: Instant): String {
    val local = at.atZone(ZoneId.systemDefault())
    return (if (local.toLocalDate() == LocalDate.now()) "" else "tomorrow ") + timeFormat.format(local)
}

/** "3 min", "2 h", "1 h 55 min"; to the nearest minute. */
private fun duration(minutes: Double): String {
    val total = minutes.roundToInt().coerceAtLeast(1)
    return listOfNotNull((total / 60).takeIf { it > 0 }?.let { "$it h" }, (total % 60).takeIf { it > 0 }?.let { "$it min" }).joinToString(" ")
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
    )
}

/**
 * A measurement under way: the jump in the draw live and Done; after Done, when the result
 * comes, or the result with Save.
 */
@Composable
private fun MeasuringRow(state: UiState, viewModel: MainViewModel) {
    val measuring = state.measuring ?: return
    val unit = state.powerUnit
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(measuring.appliance.name, Modifier.weight(1f), color = INK, fontSize = 16.sp)
            Chip("Measuring", MUTED)
        }
        val result = state.measurement
        val line = when {
            measuring.done == null -> state.meter.kw?.let { "+${unit.format((it - measuring.beforeKw).coerceAtLeast(0.0))} now. Tap Done when it has finished." }
                ?: state.meter.problem
            result is Measurement.Pending -> "Result at ${timeFormat.format(result.at)}."
            result == Measurement.Gap -> "A quarter hour of the run is missing. Measure again."
            result == Measurement.NoDraw -> "Nothing measured above the draw before the start. Measure again."
            result is Measurement.Result -> runSummary(result.curve, unit)
            else -> null
        }
        line?.let { Text(it, color = MUTED, fontSize = 13.sp) }
        if (result is Measurement.Result) Curve(result.curve)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = viewModel::discardMeasurement) { Text("Discard") }
            when {
                measuring.done == null -> Button(onClick = viewModel::finishMeasuring) { Text("Done") }
                result is Measurement.Result -> Button(onClick = viewModel::saveMeasurement) { Text("Save") }
            }
        }
    }
}

/** "1 h 55 min · 1.42 kWh · 2.1 kW". */
private fun runSummary(curve: List<Piece>, unit: PowerUnit): String {
    val a = Appliance("", curve)
    return "${duration(a.minutes)} · %.2f kWh · ${unit.format(a.kw)}".format(a.kwh)
}

/** The measured extra draw over the run, as steps. */
@Composable
private fun Curve(curve: List<Piece>) {
    val total = curve.sumOf { it.min }
    val max = curve.maxOf { it.kw }.coerceAtLeast(0.01)
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        var x = 0f
        for (piece in curve) {
            val w = (piece.min / total * size.width).toFloat()
            val h = (piece.kw / max * size.height).toFloat()
            drawRect(BAR, Offset(x, size.height - h), Size(w, h))
            x += w
        }
    }
}

/** Shown on the first +, and from ⓘ in the sheet: how to get a good measurement. */
@Composable
private fun MeasureHelp(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("Measuring an appliance") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "No red line: the recorder must be recording.",
                    "Be at home, with GridLoad open, when you switch it on.",
                    "Keep it open until the jump shows, about a minute.",
                    "Switch nothing else on or off until the result shows.",
                    "Lights and small devices are fine.",
                    "Start at a quiet time, not while cooking.",
                    "Run the programme you always use, to its end.",
                    "Another programme is another appliance.",
                    "Tap Done as soon as it has finished.",
                    "The result shows up to 15 minutes later.",
                    "Only appliances on the whatwatt's meter count.",
                ).forEach { Text(it) }
            }
        },
    )
}

/**
 * Adds an appliance ([initial] has no name) or edits one: its name, Can wait and Start delay.
 * A new one is measured with Start; an existing one shows its curve and can be measured again or
 * deleted. ⓘ in the title reopens the setup help.
 */
@Composable
private fun ApplianceSheet(initial: Appliance, state: UiState, viewModel: MainViewModel, onHelp: () -> Unit, onDismiss: () -> Unit) {
    val isNew = initial.name.isEmpty()
    var name by remember { mutableStateOf(initial.name) }
    var canWait by remember { mutableStateOf(initial.canWait) }
    var delay by remember { mutableIntStateOf(initial.delayMinutes) }
    var confirmDelete by remember { mutableStateOf(false) }
    var variant by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    val taken = trimmed != initial.name && (state.appliances.any { it.name == trimmed } || state.measuring?.appliance?.name == trimmed)
    val edited = initial.copy(name = trimmed, canWait = canWait, delayMinutes = delay)
    val recorder = state.meter.recorder
    // Why a measurement can't start now, if it can't.
    val blocked = when {
        state.measuring != null -> "Another measurement is under way."
        state.meter.kw == null -> state.meter.problem ?: "Reading the whatwatt…"
        recorder != null && isRecorderWarning(recorder) -> "The recorder must be recording."
        else -> null
    }
    fun measure() {
        if (!isNew) viewModel.updateAppliance(initial.name, edited)
        viewModel.startMeasuring(edited)
        onDismiss()
    }
    if (variant) {
        VariantDialog(initial, state, viewModel, onDismiss = { variant = false; onDismiss() })
        return
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${initial.name}?") },
            text = { Text("Its measurement is lost. To use it again, measure it again.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteAppliance(initial.name); onDismiss() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                buildAnnotatedString {
                    append(if (isNew) "New appliance" else initial.name)
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Normal)) { append(" ⓘ") }
                },
                Modifier.clickable(onClick = onHelp),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("Dishwasher eco") },
                    supportingText = if (taken) ({ Text("That name is taken.") }) else null,
                    isError = taken,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Can wait", Modifier.weight(1f))
                    Switch(checked = canWait, onCheckedChange = { canWait = it })
                }
                Text("Start delay")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DELAYS.forEachIndexed { i, (minutes, label) ->
                        SegmentedButton(selected = delay == minutes, onClick = { delay = minutes }, shape = SegmentedButtonDefaults.itemShape(i, DELAYS.size)) {
                            Text(label)
                        }
                    }
                }
                if (!isNew) {
                    Text(runSummary(initial.curve, state.powerUnit), color = MUTED)
                    Curve(initial.curve)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = ::measure, enabled = blocked == null && trimmed.isNotEmpty() && !taken) { Text("Measure again") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = RED) }
                    }
                    TextButton(onClick = { variant = true }) { Text("Add a variant") }
                } else {
                    Text("Tap Start, then switch the appliance on.")
                }
                blocked?.let { Text(it, color = RED) }
            }
        },
        confirmButton = {
            if (isNew) {
                TextButton(onClick = ::measure, enabled = blocked == null && trimmed.isNotEmpty() && !taken) { Text("Start") }
            } else {
                TextButton(onClick = { viewModel.updateAppliance(initial.name, edited); onDismiss() }, enabled = trimmed.isNotEmpty() && !taken) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val DELAYS = listOf(0 to "None", 30 to "30 min", 60 to "1 h")

/**
 * A variant of [measured] with another run time: its start stays, its last phase is stretched or
 * cut ([withRunTime]), and it's saved as an appliance of its own, e.g. "Cooking 60 min". From
 * water fills the run time in for a kettle ([waterShare]).
 */
@Composable
private fun VariantDialog(measured: Appliance, state: UiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var runTime by remember { mutableStateOf("") }
    var fromWater by remember { mutableStateOf(false) }
    var measuredLitres by remember { mutableStateOf("1") }
    var litres by remember { mutableStateOf("") }
    var celsius by remember { mutableIntStateOf(100) }
    // Suggested from the run time or the water until the user types a name.
    var typedName by remember { mutableStateOf<String?>(null) }
    var suggested by remember { mutableStateOf("") }
    val minutes = parseKw(runTime)
    val curve = minutes?.let { measured.curve.withRunTime(it) }
    val name = (typedName ?: suggested).trim()
    val taken = state.appliances.any { it.name == name } || state.measuring?.appliance?.name == name
    // "Kettle 1 L" gives "Kettle 1.5 L", "Cooking 34 min" gives "Cooking 60 min".
    val base = measured.name.replace(AMOUNT, "")
    fun setRunTime(text: String) {
        runTime = text
        suggested = if (parseKw(text) != null) "$base ${text.trim()} min" else ""
    }
    fun water(measuredText: String = measuredLitres, litresText: String = litres, t: Int = celsius) {
        measuredLitres = measuredText
        litres = litresText
        celsius = t
        val share = parseKw(measuredText)?.let { m -> parseKw(litresText)?.let { waterShare(m, it, t) } } ?: return
        runTime = "%.1f".format(measured.minutes * share)
        suggested = "$base ${litresText.trim()} L" + if (t < 100) " · $t °C" else ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Variant of ${measured.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = runTime,
                    onValueChange = ::setRunTime,
                    label = { Text("Run time (min)") },
                    placeholder = { Text("%.0f".format(measured.minutes)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (!fromWater) {
                    TextButton(onClick = { fromWater = true }) { Text("From water ›") }
                } else {
                    OutlinedTextField(
                        value = measuredLitres,
                        onValueChange = { water(measuredText = it) },
                        label = { Text("Measured with (L, to 100 °C)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = litres,
                        onValueChange = { water(litresText = it) },
                        label = { Text("Water (L)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Text("Temperature")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        TEMPERATURES.forEachIndexed { i, t ->
                            SegmentedButton(selected = celsius == t, onClick = { water(t = t) }, shape = SegmentedButtonDefaults.itemShape(i, TEMPERATURES.size)) {
                                Text("$t°")
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = typedName ?: suggested,
                    onValueChange = { typedName = it },
                    label = { Text("Name") },
                    supportingText = if (taken) ({ Text("That name is taken.") }) else null,
                    isError = taken,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                curve?.let {
                    Text(runSummary(it, state.powerUnit), color = MUTED)
                    Curve(it)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.addAppliance(measured.copy(name = name, curve = curve!!)); onDismiss() },
                enabled = curve != null && name.isNotEmpty() && !taken,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val TEMPERATURES = listOf(100, 90, 80, 70)
private val AMOUNT = Regex("""\s+[\d.,]+\s*(L|min)$""")
