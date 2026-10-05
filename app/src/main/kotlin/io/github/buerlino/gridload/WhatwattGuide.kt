package io.github.buerlino.gridload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.buerlino.gridload.core.CKW
import io.github.buerlino.gridload.core.Region

/** One page of the guide: a title, a few short lines as in the help, and optionally a link to open. */
private class GuideStep(val title: String, lines: List<String>, val link: Pair<String, String>? = null, peakLoad: HelpLine? = null) {
    val lines = lines.map(::HelpLine) + listOfNotNull(peakLoad)
}

private fun steps(region: Region) = listOf(
    GuideStep(
        "🧾 What you need",
        listOf(
            "A whatwatt Go, its adapter for your meter and its Plus licence.",
            "Your meter's key, from your grid operator." +
                if (region == CKW) " CKW: email messtechnik@ckw.ch with the meter number." else "",
            "2.4 GHz Wi-Fi at the meter.",
        ),
        peakLoad = HelpLine(
            "a microSD card in FAT32 (any size: GridLoad uses about 1 MB a year), and the whatwatt's script slot free for GridLoad's recorder.",
            "For peak load",
        ),
    ),
    GuideStep(
        "🔌 Before you power it",
        listOf(
            "Screw on the antenna.",
            "Put in the SD card.",
            "Power it from a USB-C charger, not the meter, until it's set up.",
        ),
    ),
    GuideStep(
        "📶 Connect it to your Wi-Fi",
        listOf(
            "Hold its button for 6 s, until the light blinks yellow and red.",
            "On your phone, join the Wi-Fi \"whatwatt-…\". The password is \"PW\" on its label.",
            "Open 192.168.254.1 and enter your home Wi-Fi.",
        ),
        "Open 192.168.254.1" to "http://192.168.254.1",
    ),
    GuideStep(
        "🔎 Find its address",
        listOf(
            "Put your phone back on your home Wi-Fi.",
            "In your router's list of devices, find \"whatwatt-…\" and note its address.",
            "Reserve that address in the router, so it stays the same.",
        ),
    ),
    GuideStep(
        "⬆️ Update it",
        listOf(
            "Open its address in your browser.",
            "Update the firmware. GridLoad's recorder is tested on 2.8.2.",
            "Keep it on USB power until the update is done.",
        ),
        "whatwatt Go Reference Manual" to "https://whatwatt.ch/doc/whatwatt_Go_Reference_Manual_v1.0.pdf",
    ),
    GuideStep(
        "🔑 Licence and key",
        listOf(
            "Activate the Plus licence.",
            "Under Meter, switch Encryption on and enter the key as Key 1.",
            "Leave Device Protection off. GridLoad can't log in.",
        ),
    ),
    GuideStep(
        "🔗 Connect it to the meter",
        listOf(
            "Unplug the USB charger.",
            "Plug the whatwatt into the meter with the adapter. It now runs on the meter's power.",
        ),
    ),
    GuideStep(
        "📱 Connect GridLoad",
        listOf("Enter the whatwatt's address and tap Test."),
    ),
)

/**
 * Setting up a new whatwatt, one step at a time, shown in place of the address field. The last
 * step closes it, so the address is entered and tested in the real field.
 */
@Composable
fun WhatwattGuide(region: Region, onClose: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val steps = steps(region)
    val step = steps[index]
    val last = index == steps.lastIndex
    val uriHandler = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Step ${index + 1} of ${steps.size}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = onClose) { Text("Skip") }
            }
            Text(step.title, fontWeight = FontWeight.Bold)
            HelpLines(step.lines)
            step.link?.let { (label, url) ->
                TextButton(
                    onClick = {
                        try {
                            uriHandler.openUri(url)
                        } catch (_: IllegalArgumentException) {
                            // No app on the phone opens web links.
                        }
                    },
                ) { Text(label) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) TextButton(onClick = { index-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (last) onClose() else index++ }) { Text(if (last) "Enter address" else "Next") }
            }
        }
    }
}
