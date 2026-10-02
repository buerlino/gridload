package io.github.buerlino.gridload

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import java.util.Locale

/**
 * The setup guide: on first start the help, then Next; then the mode, the region and the
 * whatwatt (Done switches it on, Skip off). Peak load mode needs a whatwatt, so skipping it
 * means spot price mode. From Settings
 * ([onClose] set) it starts at the mode and can be left with back.
 */
@Composable
fun SetupGuide(
    mode: Mode,
    region: Region,
    whatwattAddress: String?,
    whatwattTestResult: String?,
    onWhatwattEnabled: (Boolean) -> Unit,
    onWhatwattAddress: (String) -> Unit,
    onTestWhatwatt: () -> Unit,
    onWhatwattPermissionDenied: () -> Unit,
    onDone: (Mode, Region) -> Unit,
    onClose: (() -> Unit)?,
) {
    val firstStep = if (onClose == null) 0 else 1
    var step by rememberSaveable { mutableIntStateOf(firstStep) }
    var chosenMode by rememberSaveable { mutableStateOf(mode) }
    var chosenRegion by rememberSaveable { mutableStateOf(region.id) }
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
                Text("Pick the utility that supplies your electricity.")
                REGIONS.forEach { r ->
                    OptionCard(selected = onClose != null && r.id == chosenRegion, onClick = { chosenRegion = r.id; step = 3 }) {
                        Text(r.name, fontWeight = FontWeight.Bold)
                        Text(r.utility)
                    }
                }
            }
            else -> {
                val chosen = REGIONS.first { it.id == chosenRegion }
                val skip = whatwattAddress.isNullOrBlank()
                Title("Connect a whatwatt", onClose)
                WhatwattFields(whatwattAddress, whatwattTestResult, onWhatwattAddress, onTestWhatwatt, onWhatwattPermissionDenied)
                if (skip && chosenMode == Mode.PEAK) Text("Peak load mode needs a whatwatt. Without one, GridLoad shows the spot price.")
                Button(
                    onClick = { onWhatwattEnabled(!skip); onDone(if (skip) Mode.SPOT else chosenMode, chosen) },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(if (skip) "Skip" else "Done") }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: UiState,
    whatwattTestResult: String?,
    onSelectMode: (Mode) -> Unit,
    onSelectRegion: (Region) -> Unit,
    onWhatwattEnabled: (Boolean) -> Unit,
    onWhatwattAddress: (String) -> Unit,
    onTestWhatwatt: () -> Unit,
    onWhatwattPermissionDenied: () -> Unit,
    onGoal: (Double?) -> Unit,
    onScaleWithoutReading: (Boolean) -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("whatwatt", fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Switch(checked = state.whatwattEnabled, onCheckedChange = onWhatwattEnabled)
        }
        if (state.whatwattEnabled) {
            WhatwattFields(state.whatwattAddress, whatwattTestResult, onWhatwattAddress, onTestWhatwatt, onWhatwattPermissionDenied)
            if (state.mode == Mode.PEAK) PeakSettings(state, onGoal, onScaleWithoutReading)
        }
        OutlinedButton(onClick = onOpenGuide) { Text("Open the setup guide") }
    }
}

/**
 * What a whatwatt is for, the device address, "Test connection", and the last test's result.
 * Shared by the setup guide and Settings, so the explanation lives only here. Android 17
 * (API 37) needs the local network permission for the device; it's asked for on the first test.
 */
@Composable
private fun WhatwattFields(
    address: String?,
    testResult: String?,
    onAddress: (String) -> Unit,
    onTest: () -> Unit,
    onPermissionDenied: () -> Unit,
) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onTest() else onPermissionDenied()
    }
    var text by rememberSaveable { mutableStateOf(address.orEmpty()) }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("📟", fontSize = 32.sp)
        Text("With a whatwatt Go on your smart meter, GridLoad shows what you use right now and what it costs.")
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onAddress(it) },
        label = { Text("Address, e.g. 192.168.1.50") },
        supportingText = { Text("Reserve this address in your router so it stays the same.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = {
            if (Build.VERSION.SDK_INT >= 37 &&
                context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
            ) {
                permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            } else {
                onTest()
            }
        },
        enabled = text.isNotBlank(),
    ) { Text("Test connection") }
    testResult?.let { Text(it) }
}

/**
 * Peak load mode's goal, pre-filled with last month's highest seen quarter hour, and whether
 * the scale stays without a reading. A blank goal falls back to last month's highest.
 */
@Composable
private fun PeakSettings(state: UiState, onGoal: (Double?) -> Unit, onScaleWithoutReading: (Boolean) -> Unit) {
    var text by rememberSaveable { mutableStateOf(state.effectiveGoalKw?.let { "%.1f".format(Locale.ROOT, it) }.orEmpty()) }
    val goal = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val invalid = text.isNotBlank() && goal == null
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            val kw = it.replace(',', '.').toDoubleOrNull()?.takeIf { v -> v > 0 }
            if (it.isBlank() || kw != null) onGoal(kw)
        },
        label = { Text("Goal, kW") },
        supportingText = {
            Text(
                when {
                    invalid -> "Enter a number of kW, e.g. 3.5"
                    state.lastMonthHighest != null -> "Last month's highest seen: %.1f kW".format(state.lastMonthHighest.kw)
                    else -> "The peak you'd like to stay under this month."
                },
            )
        },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Show the scale without a reading", Modifier.weight(1f))
        Switch(checked = state.scaleWithoutReading, onCheckedChange = onScaleWithoutReading)
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
