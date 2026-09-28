package io.github.buerlino.gridload

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
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
                Screen(state, onRefresh = viewModel::refresh, onSelectRegion = viewModel::selectRegion)
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
private fun Screen(state: UiState, onRefresh: () -> Unit, onSelectRegion: (Region) -> Unit) {
    val status = state.status
    val style = when (status?.level) {
        Level.GREEN -> Look(GREEN, Color.White, "Good time", "Run your appliances now")
        Level.ORANGE -> Look(ORANGE, Color.Black, "Fair time", "Only run what you need")
        Level.RED -> Look(RED, Color.White, "Bad time", "Wait if you can")
        null -> Look(GREY, Color.White, if (state.loading) "Loading…" else "No data", null)
    }
    val (background, content, label, hint) = style
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    Box(Modifier.fillMaxSize().background(background).padding(24.dp)) {
        RegionPicker(
            state.region,
            content,
            onSelectRegion,
            Modifier.align(Alignment.TopCenter),
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
private fun RegionPicker(region: Region, color: Color, onSelect: (Region) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) {
            Text("${region.name} (${region.utility}) ▾", color = color, fontSize = 18.sp)
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

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How GridLoad works") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The colour shows whether electricity is cheap right now compared with the rest of today. Cheap times are when the grid has the most to spare, for example at midday when solar power peaks.")
                LegendRow(GREEN, "Good time", "Cheap. Run the dishwasher, washing machine or EV charger now.")
                LegendRow(ORANGE, "Fair time", "Average price. Only run what you need.")
                LegendRow(RED, "Bad time", "Expensive and busy. Wait if you can.")
                Text("Each 15-minute price is compared with today's lowest and highest: the cheapest third is green, the most expensive third red.")
                Text("Region: prices come from your local electricity utility, so pick your region at the top. Only Central Switzerland (CKW) is available for now.")
                Text("Today's prices are loaded once and kept. Refresh checks for new data, at most every 5 minutes.")
            }
        },
    )
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
