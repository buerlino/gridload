package io.github.buerlino.gridload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.buerlino.gridload.core.Level
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) {
                            viewModel.update()
                            delay(60_000)
                        }
                    }
                }
                val peak by viewModel.peak.collectAsStateWithLifecycle()
                val importMessage by viewModel.importMessage.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showGuide by rememberSaveable { mutableStateOf(false) }
                when {
                    !state.firstStartDone || showGuide -> SetupGuide(
                        state.mode, state.region, peak, importMessage,
                        onImport = viewModel::importLoadData,
                        onGoalOffset = viewModel::setGoalOffset,
                        onDone = { mode, region -> viewModel.finishSetup(mode, region); showGuide = false; showSettings = false },
                        onClose = if (state.firstStartDone) ({ showGuide = false }) else null,
                    )
                    showSettings -> SettingsScreen(
                        state, peak, importMessage,
                        onSelectMode = viewModel::selectMode,
                        onSelectRegion = viewModel::selectRegion,
                        onImport = viewModel::importLoadData,
                        onGoalOffset = viewModel::setGoalOffset,
                        onOpenGuide = { showGuide = true },
                        onBack = { showSettings = false },
                    )
                    state.mode == Mode.PEAK -> PeakScreen(
                        state, peak,
                        onRefresh = viewModel::refresh,
                        onOpenSettings = { showSettings = true },
                        onStart = viewModel::start,
                        onStop = viewModel::stop,
                        onSave = viewModel::saveAppliance,
                        onDelete = viewModel::deleteAppliance,
                    )
                    else -> Screen(state, onRefresh = viewModel::refresh, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

val GREEN = Color(0xFF2E7D32)
val ORANGE = Color(0xFFFFA000)
val RED = Color(0xFFC62828)
val GREY = Color(0xFF616161)

data class Look(val background: Color, val content: Color, val headline: String, val hint: String?)

val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun Screen(state: UiState, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
    val status = state.status
    val (background, content, label, hint) = look(state)
    StatusBarIcons(dark = content == Color.Black)
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    Box(Modifier.fillMaxSize().background(background).safeDrawingPadding().padding(24.dp)) {
        TopBar(state, content, onOpenSettings, onHelp = { showHelp = true })
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, color = content, fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            hint?.let { Text(it, color = content, fontSize = 22.sp, textAlign = TextAlign.Center) }
            if (status != null) {
                Text("%.1f Rp/kWh".format(status.slot.price * 100), color = content, fontSize = 20.sp)
                status.nextGreen?.let { Text(nextGoodTime(it.start), color = content, fontSize = 18.sp) }
            }
            state.error?.let { Text("Error: $it", color = content, textAlign = TextAlign.Center) }
        }
        Column(
            Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.notice?.let { Text(it, color = content) }
            state.fetchedAt?.let { Text("Updated ${timeFormat.format(it)}", color = content) }
            Button(
                onClick = onRefresh,
                enabled = !state.loading,
                colors = ButtonDefaults.buttonColors(containerColor = content, contentColor = background),
            ) { Text("Refresh") }
        }
    }
}

fun look(state: UiState): Look = when (state.status?.level) {
    Level.GREEN -> Look(GREEN, Color.White, "Good time", "Run your appliances now")
    Level.ORANGE -> Look(ORANGE, Color.Black, "Fair time", "Only run what you need")
    Level.RED -> Look(RED, Color.White, "Bad time", "Wait if you can")
    null -> Look(GREY, Color.White, if (state.loading) "Loading…" else "No data", null)
}

/** ⚙ at the top left, the region in the middle, ? at the top right. */
@Composable
fun TopBar(state: UiState, content: Color, onOpenSettings: () -> Unit, onHelp: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopStart)) {
            Text("⚙", color = content, fontSize = 22.sp)
        }
        Text(
            "${state.region.name} (${state.region.utility})",
            color = content,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            // Clear of the ⚙ and ? buttons; long names wrap.
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp, start = 48.dp, end = 48.dp),
        )
        TextButton(onClick = onHelp, modifier = Modifier.align(Alignment.TopEnd)) {
            Text("?", color = content, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** "Next good time: 11:00", with "tomorrow" when it isn't today. */
fun nextGoodTime(start: OffsetDateTime): String {
    val local = start.atZoneSameInstant(ZoneId.systemDefault())
    val day = if (local.toLocalDate() == LocalDate.now()) "" else "tomorrow "
    return "Next good time: $day${timeFormat.format(local)}"
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How GridLoad works") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { HelpContent() } },
    )
}

/** Shared by the help dialog and the first start. A short general part, then one part per mode. */
@Composable
fun HelpContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GridLoad tells you when to use electricity, based on your utility's dynamic price. Cheap times are when the grid has the most to spare, like midday when solar power peaks. Pick your region and mode in Settings (⚙).")
        Text("Spot price mode", fontWeight = FontWeight.Bold)
        LegendRow(GREEN, "Good time", "Cheap. Run your appliances now.")
        LegendRow(ORANGE, "Fair time", "Average price. Only run what you need.")
        LegendRow(RED, "Bad time", "Expensive. Wait if you can.")
        Text("The price now is compared with the next 24 hours, so red means a cheaper time is coming. Tomorrow's prices come out between noon and 6 pm, depending on your utility; until then it's compared with today. Refresh checks for new prices, at most every 5 minutes.")
        Text("Peak load mode (manual)", fontWeight = FontWeight.Bold)
        Text("Your grid bill also counts the highest 15-minute average of each month. Tap Start and Stop when you switch an appliance on and off, and GridLoad estimates the current quarter hour from your load data and keeps you under your goal. Each appliance shows whether it fits now and, with the same colours, whether the price is good.")
    }
}

/** The app draws behind the system bars, so their icons must match the screen: dark on light backgrounds. */
@Composable
fun StatusBarIcons(dark: Boolean) {
    val activity = LocalActivity.current as ComponentActivity
    LaunchedEffect(dark) {
        val style = if (dark) {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}

@Composable
private fun LegendRow(color: Color, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 4.dp).size(16.dp).background(color, CircleShape))
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(text)
        }
    }
}
