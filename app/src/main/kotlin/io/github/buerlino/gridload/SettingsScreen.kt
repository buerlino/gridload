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
import androidx.compose.material3.OutlinedButton
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

/**
 * The setup guide: on first start the help, then Next; then the mode and the region. From
 * Settings ([onClose] set) it starts at the mode and can be left with back.
 */
@Composable
fun SetupGuide(mode: Mode, region: Region, onDone: (Mode, Region) -> Unit, onClose: (() -> Unit)?) {
    val firstStep = if (onClose == null) 0 else 1
    var step by rememberSaveable { mutableStateOf(firstStep) }
    var chosenMode by rememberSaveable { mutableStateOf(mode) }
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
            else -> {
                Title("Choose your region", onClose)
                Text("Pick the utility that supplies your electricity.")
                REGIONS.forEach { r ->
                    OptionCard(selected = onClose != null && r == region, onClick = { onDone(chosenMode, r) }) {
                        Text(r.name, fontWeight = FontWeight.Bold)
                        Text(r.utility)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: UiState,
    onSelectMode: (Mode) -> Unit,
    onSelectRegion: (Region) -> Unit,
    onOpenGuide: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Page {
        Title("Settings", onBack)
        Text("Mode", fontWeight = FontWeight.Bold)
        ModeChoice(state.mode, onSelectMode)
        Text("Region", fontWeight = FontWeight.Bold)
        RegionPicker(state.region, onSelectRegion)
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

/** The two modes, with emoji as logos (⚡ spot, 📊 peak). */
@Composable
private fun ModeChoice(selected: Mode?, onSelect: (Mode) -> Unit) {
    OptionCard(selected == Mode.SPOT, { onSelect(Mode.SPOT) }, "⚡") {
        Text("Spot price", fontWeight = FontWeight.Bold)
        Text("See when electricity is cheap.")
    }
    OptionCard(selected == Mode.PEAK, { onSelect(Mode.PEAK) }, "📊") {
        Text("Peak load", fontWeight = FontWeight.Bold)
        Text("Keep your monthly peak low to cut your grid bill.")
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
