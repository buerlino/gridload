package io.github.buerlino.gridload

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import io.github.buerlino.gridload.core.kw
import io.github.buerlino.gridload.core.kwh
import io.github.buerlino.gridload.core.minutes
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.parsePositive
import io.github.buerlino.gridload.core.waterShare
import io.github.buerlino.gridload.core.withRunTime
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The measured appliances, below the peak window: per appliance OK (fine to switch it on now) or
 * WAIT, with when. Tapping a row previews its run in the peak window ([onPreview], null closes
 * it); holding it opens its sheet. + adds one: the setup help (by itself the first time), then a
 * sheet with its name and fields, and Start. A measurement under way shows in its row until it's saved.
 */
@Composable
fun AppliancesPanel(state: UiState, viewModel: MainViewModel, onPreview: (String?) -> Unit) {
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
    val context = LocalContext.current
    // Only where a clock app takes timers.
    val timers = remember { Intent(AlarmClock.ACTION_SET_TIMER).resolveActivity(context.packageManager) != null }
    Panel(
        open = state.appliancesOpen,
        onToggle = { viewModel.setAppliancesOpen(!state.appliancesOpen) },
        header = {
            Text("Appliances", Modifier.weight(1f), color = INK, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (!state.appliancesOpen) summary(state)?.let { Text(it, color = MUTED, fontSize = 14.sp) }
        },
    ) {
        if (!state.appliancesOpen) return@Panel
        for (appliance in state.appliances) {
            if (appliance.name == measuring?.appliance?.name) {
                MeasuringRow(state, viewModel)
            } else {
                val previewed = appliance.name == state.preview
                val advice = state.advice[appliance.name]
                // The timer rings at the start the row names; none with a start delay, which is set on the appliance now.
                val timerAt = advice?.at?.takeIf { appliance.delayMinutes == 0 && timers }
                ApplianceRow(
                    appliance, advice, advice?.let { state.adviceLine(appliance, it) }, previewed,
                    onClick = { onPreview(appliance.name.takeUnless { previewed }) },
                    onLongClick = { sheet = appliance },
                    onTimer = timerAt?.let { at -> { setTimer(context, appliance.name, at) } },
                )
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

/** The row's line under [appliance]'s name: why WAIT, when and what waiting saves ("0.09 CHF"), or that only the peak was judged. */
internal fun UiState.adviceLine(appliance: Appliance, advice: Advice): String? {
    val now = Instant.now()
    return when (advice) {
        is Advice.Ok -> "Tomorrow's prices aren't out yet.".takeIf { advice.pricesMissing }
        is Advice.OverLimit -> (if (advice.newPeak) "Sets a new peak" else "Reaches the limit").let { why ->
            advice.at?.let { "$why · ${whenToStart(appliance, it, now)}" } ?: "$why right now"
        }
        is Advice.Cheaper -> whenToStart(appliance, advice.at, now).let { if (appliance.delayMinutes > 0) "Cheaper · $it" else "Cheaper $it" } +
            yourSaving(advice.saving)?.let { " · saves ${region.country.currency.amount(it)}" }.orEmpty()
    }
}

/** Opens the clock app with a timer named after the appliance, running until [at]. */
private fun setTimer(context: Context, name: String, at: Instant) {
    // Rounded up, so it never rings before the start it names; a clock app takes up to 24 h.
    val seconds = ((Duration.between(Instant.now(), at).toMillis() + 999) / 1000).coerceIn(1, 86_400).toInt()
    context.startActivity(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, name)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false),
    )
}

/**
 * [previewed]: its run shows in the peak window, so the row is tinted. [onTimer]: the timer
 * button left of the chip, so the chips stay in one column.
 */
@Composable
private fun ApplianceRow(appliance: Appliance, advice: Advice?, line: String?, previewed: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onTimer: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (previewed) PREVIEWED else Color.Transparent, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClickLabel = if (previewed) "Close the preview" else "Preview in the peak window",
                onLongClickLabel = "Edit",
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(appliance.name, color = INK, fontSize = 16.sp)
            line?.let { Text(it, color = MUTED, fontSize = 13.sp) }
        }
        onTimer?.let {
            IconButton(onClick = it) { Icon(painterResource(R.drawable.ic_timer), "Set a timer", tint = INK) }
        }
        when (advice) {
            null -> Text("–", color = MUTED, fontSize = 16.sp)
            is Advice.Ok -> Chip("OK", GREEN, Color.White)
            // Black on orange, as on the main screen's "Fair time".
            else -> Chip("WAIT", ORANGE, Color.Black)
        }
    }
}

/** The previewed row's tint, a light shade of the preview's blue. */
private val PREVIEWED = Color(0xFFE3F2FD)

/** "at 14:15" or "tomorrow 10:00", or with a start delay "Delay 3 h", the number to set on the appliance. */
private fun whenToStart(appliance: Appliance, at: Instant, now: Instant) =
    if (appliance.delayMinutes > 0) {
        "Delay ${duration(Duration.between(now, at).toMillis() / 60_000.0)}"
    } else {
        comingTime(at, today = "at ")
    }

/** "3 min", "2 h", "1 h 55 min"; to the nearest minute. */
private fun duration(minutes: Double): String {
    val total = minutes.roundToInt().coerceAtLeast(1)
    return listOfNotNull((total / 60).takeIf { it > 0 }?.let { "$it h" }, (total % 60).takeIf { it > 0 }?.let { "$it min" }).joinToString(" ")
}

@Composable
private fun Chip(text: String, color: Color, textColor: Color) {
    Text(
        text, Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Bold,
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
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this measurement?") },
            text = { Text("Its result is lost. To get one, measure it again.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; viewModel.discardMeasurement() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(measuring.appliance.name, Modifier.weight(1f), color = INK, fontSize = 16.sp)
            Chip("Measuring", MUTED, Color.White)
        }
        val result = state.measurement
        val line = when {
            measuring.done == null -> state.meter.kw?.let { "+${unit.format((it - measuring.beforeKw).coerceAtLeast(0.0))} now. Tap Done when it has finished." }
                ?: state.meter.problem
            result is Measurement.Pending -> "Result at ${timeFormat.format(result.at)}."
            result == Measurement.Gap -> "A ¼ hour of the run is missing. Measure again."
            result == Measurement.NoDraw -> "Nothing measured above the draw before the start. Measure again."
            result is Measurement.Result -> runSummary(result.curve, unit)
            else -> null
        }
        line?.let { Text(it, color = MUTED, fontSize = 13.sp) }
        if (result is Measurement.Result) Curve(result.curve)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { confirmDiscard = true }) { Text("Discard") }
            when {
                measuring.done == null -> Button(onClick = viewModel::finishMeasuring) { Text("Done") }
                result is Measurement.Result -> Button(onClick = viewModel::saveMeasurement) { Text("Save") }
            }
        }
    }
}

/** "1 h 55 min · 1.42 kWh · 2.1 kW". */
private fun runSummary(curve: List<Piece>, unit: PowerUnit) =
    "${duration(curve.minutes)} · %.2f kWh · ${unit.format(curve.kw)}".format(curve.kwh)

/** The measured extra draw over the run, as steps. */
@Composable
private fun Curve(curve: List<Piece>) {
    val total = curve.minutes
    val max = curve.kw.coerceAtLeast(0.01)
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
                    "Switch nothing else on or off until you tap Done.",
                    "Lights and small devices are fine.",
                    "Start at a quiet time, not while cooking.",
                    "Run the programme you always use, to its end.",
                    "Another programme is another appliance.",
                    "Tap Done as soon as it has finished.",
                    "The result usually shows right after Done.",
                    "Only appliances on the whatwatt's meter count.",
                ).forEach { Text(it) }
            }
        },
    )
}

/**
 * Adds an appliance ([initial] has no name) or edits one: its name, Can wait, Counts for the
 * limit and Start delay.
 * A new one is measured with Start; an existing one shows its curve and can be measured again or
 * deleted. ⓘ in the title reopens the setup help.
 */
@Composable
private fun ApplianceSheet(initial: Appliance, state: UiState, viewModel: MainViewModel, onHelp: () -> Unit, onDismiss: () -> Unit) {
    val isNew = initial.name.isEmpty()
    var name by remember { mutableStateOf(initial.name) }
    var canWait by remember { mutableStateOf(initial.canWait) }
    var countsForLimit by remember { mutableStateOf(initial.countsForLimit) }
    var delay by remember { mutableIntStateOf(initial.delayMinutes) }
    var confirmDelete by remember { mutableStateOf(false) }
    var variant by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    val taken = trimmed != initial.name && (state.appliances.any { it.name == trimmed } || state.measuring?.appliance?.name == trimmed)
    val edited = initial.copy(name = trimmed, canWait = canWait, delayMinutes = delay, countsForLimit = countsForLimit)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Counts for the limit", Modifier.weight(1f))
                    Switch(checked = countsForLimit, onCheckedChange = { countsForLimit = it })
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
    val minutes = parsePositive(runTime)
    val curve = minutes?.let { measured.curve.withRunTime(it) }
    val name = (typedName ?: suggested).trim()
    val taken = state.appliances.any { it.name == name } || state.measuring?.appliance?.name == name
    // "Kettle 1 L" gives "Kettle 1.5 L", "Cooking 34 min" "Cooking 60 min"; a variant's "· 80 °C" goes too.
    val base = measured.name.replace(AMOUNT, "")
    fun setRunTime(text: String) {
        runTime = text
        suggested = if (parsePositive(text) != null) "$base ${text.trim()} min" else ""
    }
    fun water(measuredText: String = measuredLitres, litresText: String = litres, t: Int = celsius) {
        measuredLitres = measuredText
        litres = litresText
        celsius = t
        val share = parsePositive(measuredText)?.let { m -> parsePositive(litresText)?.let { waterShare(m, it, t) } } ?: return
        runTime = "%.1f".format(measured.curve.minutes * share)
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
                    placeholder = { Text("%.0f".format(measured.curve.minutes)) },
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
private val AMOUNT = Regex("""\s+[\d.,]+\s*(L|min)(\s*·\s*\d+\s*°C)?$""")
