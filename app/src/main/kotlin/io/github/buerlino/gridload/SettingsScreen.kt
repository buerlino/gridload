package io.github.buerlino.gridload

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region

/** First start: the help, then Next, then the mode choice. Peak load mode is not built yet. */
@Composable
fun FirstStartScreen(onDone: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }
    BackHandler(enabled = step > 0) { step = 0 }
    Page {
        if (step == 0) {
            Text("Welcome to GridLoad", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            HelpContent()
            Button(onClick = { step = 1 }, modifier = Modifier.align(Alignment.End)) { Text("Next") }
        } else {
            Text("Choose a mode", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("You can change it later in Settings.")
            ModeChoice(onSelectSpot = onDone)
        }
    }
}

@Composable
fun SettingsScreen(region: Region, onSelectRegion: (Region) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text("Mode", fontWeight = FontWeight.Bold)
        ModeChoice(onSelectSpot = {}, selected = true)
        Text("Region", fontWeight = FontWeight.Bold)
        Text("Prices come from your local electricity utility. More regions are planned.")
        RegionPicker(region, onSelectRegion)
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

/**
 * The two modes. Only spot price exists so far; peak load is shown as coming soon.
 * The emoji are placeholders until the mode logos are decided.
 */
@Composable
private fun ModeChoice(onSelectSpot: () -> Unit, selected: Boolean = false) {
    ModeCard("⚡", "Spot price", "Shows when electricity is cheap, so you know when to run your appliances.", selected, onSelectSpot)
    ModeCard("📊", "Peak load (coming soon)", "Tracks your appliances and helps keep your monthly peak low.", false, null)
}

/** A mode option; `onClick == null` shows it disabled. */
@Composable
private fun ModeCard(logo: String, title: String, text: String, selected: Boolean, onClick: (() -> Unit)?) {
    val enabled = onClick != null
    OutlinedCard(
        onClick = onClick ?: {},
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder(enabled),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(logo, fontSize = 32.sp)
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(text)
            }
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
