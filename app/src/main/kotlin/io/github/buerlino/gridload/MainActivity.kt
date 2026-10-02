package io.github.buerlino.gridload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.buerlino.gridload.core.Level
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                val whatwattTestResult by viewModel.whatwattTestResult.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        launch {
                            while (true) {
                                viewModel.update()
                                delay(60_000)
                            }
                        }
                        // The whatwatt has a new reading about every 4 s. It runs on the meter's
                        // weak power, so don't poll it faster.
                        while (true) {
                            viewModel.readMeter()
                            delay(5_000)
                        }
                    }
                }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showGuide by rememberSaveable { mutableStateOf(false) }
                // Settings opened from the region in the top bar, with the region list open.
                var pickRegion by remember { mutableStateOf(false) }
                when {
                    !state.firstStartDone || showGuide -> SetupGuide(
                        state, whatwattTestResult, viewModel,
                        onDone = { region -> viewModel.finishSetup(region); showGuide = false; showSettings = false },
                        onClose = if (state.firstStartDone) ({ showGuide = false }) else null,
                    )
                    showSettings -> SettingsScreen(
                        state, whatwattTestResult, viewModel,
                        pickRegion = pickRegion,
                        onOpenGuide = { showGuide = true },
                        onBack = { showSettings = false; pickRegion = false },
                    )
                    else -> Screen(
                        state,
                        onRefresh = viewModel::refresh,
                        onOpenSettings = { region -> pickRegion = region; showSettings = true },
                    )
                }
            }
        }
    }
}

private val GREEN = Color(0xFF2E7D32)
private val ORANGE = Color(0xFFFFA000)
private val RED = Color(0xFFC62828)
private val GREY = Color(0xFF616161)

private data class Look(val background: Color, val content: Color, val headline: String)

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * The colour, the headline, the price, the next good time and refresh. With peak load on, the
 * peak window goes below the price and the text gets smaller.
 */
@Composable
private fun Screen(state: UiState, onRefresh: () -> Unit, onOpenSettings: (pickRegion: Boolean) -> Unit) {
    val (background, content, label) = look(state)
    StatusBarIcons(dark = content == Color.Black)
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    val peak = state.showPeak
    Box(Modifier.fillMaxSize().background(background).safeDrawingPadding().padding(24.dp)) {
        TopBar(state, content, onOpenSettings, onHelp = { showHelp = true })
        Column(
            // In peak load mode, clear of the top bar and the refresh button, and scrollable on small screens.
            Modifier.align(Alignment.Center)
                .then(if (peak) Modifier.padding(top = 48.dp, bottom = 88.dp).verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (peak) 6.dp else 12.dp),
        ) {
            Text(
                label, color = content, fontSize = if (peak) 32.sp else 44.sp, lineHeight = if (peak) 38.sp else 52.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            val textSize = if (peak) 16.sp else 20.sp
            state.status?.let { status ->
                Text("%.1f Rp/kWh".format(status.slot.price * 100), color = content, fontSize = textSize)
                state.meterKw?.let { kw ->
                    Text("%.1f kW now · %.2f CHF/h".format(kw, kw * status.slot.price), color = content, fontSize = textSize)
                }
                status.nextGreen?.let { NextGoodTime(it.start, content, if (peak) 16.sp else 18.sp) }
            }
            state.error?.let { Text("Error: $it", color = content, textAlign = TextAlign.Center) }
            if (peak) {
                Spacer(Modifier.height(6.dp))
                PeakWindow(state)
            } else {
                state.meterProblem?.let { Text(it, color = content) }
            }
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

private fun look(state: UiState): Look = when (state.status?.level) {
    Level.GREEN -> Look(GREEN, Color.White, "Good time")
    Level.ORANGE -> Look(ORANGE, Color.Black, "Fair time")
    Level.RED -> Look(RED, Color.White, "Bad time")
    null -> Look(GREY, Color.White, if (state.loading) "Loading…" else "No prices")
}

/** ⚙ at the top left, the region in the middle (tap to change it), ? at the top right. */
@Composable
private fun TopBar(state: UiState, content: Color, onOpenSettings: (pickRegion: Boolean) -> Unit, onHelp: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = { onOpenSettings(false) }, modifier = Modifier.align(Alignment.TopStart)) {
            Text("⚙", color = content, fontSize = 22.sp)
        }
        Text(
            "${state.region.name} (${state.region.utility})",
            color = content,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            // Clear of the ⚙ and ? buttons; long names wrap.
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp, start = 48.dp, end = 48.dp)
                .clickable { onOpenSettings(true) }.padding(vertical = 8.dp),
        )
        TextButton(onClick = onHelp, modifier = Modifier.align(Alignment.TopEnd)) {
            Text("?", color = content, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The next good time as a green dot and "11:00", with "tomorrow" when it isn't today. */
@Composable
private fun NextGoodTime(start: OffsetDateTime, content: Color, fontSize: TextUnit) {
    val local = start.atZoneSameInstant(ZoneId.systemDefault())
    val day = if (local.toLocalDate() == LocalDate.now()) "" else "tomorrow "
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GreenDot(content)
        Text("$day${timeFormat.format(local)}", color = content, fontSize = fontSize)
    }
}

/** The app's green with a thin outline in [outline], so it shows on any background, the green one too. */
@Composable
private fun GreenDot(outline: Color) {
    Box(Modifier.size(14.dp).background(GREEN, CircleShape).border(1.5.dp, outline, CircleShape))
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How GridLoad works") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { HelpContent() } },
    )
}

/** Shared by the help dialog and the first start. A short general part, then prices and peak load. */
@Composable
fun HelpContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GridLoad shows whether now is a good time to use electricity, based on your utility's price. Cheap usually means the grid has power to spare, like at midday when solar peaks.")
        Text("⚡ Prices", fontWeight = FontWeight.Bold)
        LegendRow(GREEN, "Good time", "Cheap. Run your appliances now.")
        LegendRow(ORANGE, "Fair time", "Average. Only run what you need.")
        LegendRow(RED, "Bad time", "Expensive. Wait if you can.")
        Text("The price is compared with the next 24 hours, so red means a cheaper time is coming. Tomorrow's prices come out between noon and 6 pm.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GreenDot(LocalContentColor.current)
            Text("The next good time.")
        }
        Text("With a whatwatt on your meter, it also shows what you use right now and what that costs per hour.")
        Text("📊 Peak load", fontWeight = FontWeight.Bold)
        Text("Your grid bill can also charge for the month's highest quarter hour: the average kW over 15 minutes.")
        Text("Below the price, a scale shows this quarter hour, the two before and the month's highest. \"kW free\" is how much more you can switch on now.")
        Text("Close to a new peak, the bar turns red and the phone vibrates.")
        Text("Needs a whatwatt. Switch it on in Settings. GridLoad only sees quarter hours while it's open.")
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
