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
                        viewModel.onStart()
                        while (true) {
                            delay(60_000)
                            viewModel.recompute()
                        }
                    }
                }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                when {
                    !state.firstStartDone -> FirstStartScreen(onDone = viewModel::finishFirstStart)
                    showSettings -> SettingsScreen(state.region, viewModel::selectRegion, onBack = { showSettings = false })
                    else -> Screen(state, onRefresh = viewModel::refresh, onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

private val GREEN = Color(0xFF2E7D32)
private val ORANGE = Color(0xFFFFA000)
private val RED = Color(0xFFC62828)
private val GREY = Color(0xFF616161)

private data class Look(val background: Color, val content: Color, val headline: String, val hint: String?)

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun Screen(state: UiState, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
    val status = state.status
    val style = when (status?.level) {
        Level.GREEN -> Look(GREEN, Color.White, "Good time", "Run your appliances now")
        Level.ORANGE -> Look(ORANGE, Color.Black, "Fair time", "Only run what you need")
        Level.RED -> Look(RED, Color.White, "Bad time", "Wait if you can")
        null -> Look(GREY, Color.White, if (state.loading) "Loading…" else "No data", null)
    }
    val (background, content, label, hint) = style
    StatusBarIcons(dark = content == Color.Black)
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    Box(Modifier.fillMaxSize().background(background).safeDrawingPadding().padding(24.dp)) {
        TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopStart)) {
            Text("⚙", color = content, fontSize = 22.sp)
        }
        Text(
            "${state.region.name} (${state.region.utility})",
            color = content,
            fontSize = 16.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
        )
        TextButton(onClick = { showHelp = true }, modifier = Modifier.align(Alignment.TopEnd)) {
            Text("?", color = content, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, color = content, fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            hint?.let { Text(it, color = content, fontSize = 22.sp, textAlign = TextAlign.Center) }
            if (status != null) {
                Text("%.1f Rp/kWh".format(status.slot.price * 100), color = content, fontSize = 20.sp)
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

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
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
        Text("Each 15-minute price is compared with the rest of today. Refresh checks for new prices, at most every 5 minutes.")
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
