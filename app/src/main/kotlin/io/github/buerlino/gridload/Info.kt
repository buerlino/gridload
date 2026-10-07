package io.github.buerlino.gridload

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.buerlino.gridload.core.BASE_LOAD_DAYS
import io.github.buerlino.gridload.core.Country
import io.github.buerlino.gridload.core.CountryHelp
import io.github.buerlino.gridload.core.LimitPart
import io.github.buerlino.gridload.core.PowerUnit
import io.github.buerlino.gridload.core.REGIONS
import io.github.buerlino.gridload.core.Region
import io.github.buerlino.gridload.core.countryHelp
import io.github.buerlino.gridload.core.peakBilled
import java.time.Instant

// All of the app's help in one shape: a title and a few short lines, one idea each, some led by a
// bold term. Each text is built for what's switched on, so it never explains what isn't there.
// The help (?) explains the colours and each panel; a setting's ⓘ explains the setting.

/** One line of help: [text], led by a bold [term] ("**Add-on**: the rest per kWh…") when set. */
internal class HelpLine(val text: String, val term: String? = null)

/** What a ⓘ explains, or a topic in the help (?). */
internal class Info(val title: String, val lines: List<HelpLine>)

private fun info(title: String, vararg lines: HelpLine?) = Info(title, lines.filterNotNull())
private fun line(text: String) = HelpLine(text)
private fun term(term: String, text: String) = HelpLine(text, term)

// The help (?) and the first start.

/**
 * The help (?), a screen of its own for the width: the colours always, then a folded topic for
 * prices and one for each panel that shows, the only place the panels are explained.
 */
@Composable
internal fun HelpScreen(state: UiState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val help = countryHelp(state.region.country, Instant.now())
    val topics = listOfNotNull(
        "⚡" to pricesHelp(state, help),
        ("📈" to CURVE_HELP).takeIf { state.showCurve },
        ("📊" to peakHelp(state, help)).takeIf { state.showPeak },
        ("🔌" to APPLIANCES_HELP).takeIf { state.showPeak && state.appliancesEnabled },
        ("📅" to historyHelp(state)).takeIf { state.showPeak },
    )
    Page {
        TitleRow("How it works", onBack)
        HelpContent(help, topics, nextGood = !state.showCurve)
    }
}

/**
 * The colours and what they're compared with, the green dot of the [nextGood] time while it
 * shows, then [topics] folded, each with its emoji. Shared by the help (?) and the first start,
 * which has only the prices: nothing else is on yet.
 */
@Composable
internal fun HelpContent(help: CountryHelp, topics: List<Pair<String, Info>>, nextGood: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GridLoad shows when electricity is cheap, based on ${help.price}.")
        Text("Cheap usually means the grid has power to spare, like at midday when solar peaks.")
        LegendRow(GREEN, "Good time", "Cheap. Run your appliances now.")
        LegendRow(ORANGE, "Fair time", "Average. Only run what you need.")
        LegendRow(RED, "Bad time", "Expensive. Wait if you can.")
        Text("Compared with the next 24 hours, so red means a cheaper time is coming.")
        help.tomorrow?.let { Text(it) }
        if (nextGood) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GreenDot(LocalContentColor.current)
                Text("The next good time.")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { topics.forEach { (emoji, info) -> Topic(emoji, info) } }
        Text("ⓘ explains what's next to it.")
    }
}

/** The first start's help: the prices only, for [country] (null: not known yet); the price curve is on by default, so no green dot. */
@Composable
internal fun WelcomeHelp(country: Country?) {
    val help = countryHelp(country, Instant.now())
    HelpContent(help, listOf("⚡" to pricesHelp(null, help)), nextGood = false)
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

/** A topic in the help, folded until tapped, on the same light grey card as a Settings section. */
@Composable
private fun Topic(emoji: String, info: Info) {
    var open by rememberSaveable(info.title) { mutableStateOf(false) }
    Surface(color = GROUP, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fold(open, onToggle = { open = !open }) { Text("$emoji ${info.title}", fontWeight = FontWeight.Bold) }
            if (open) HelpLines(info.lines)
        }
    }
}

// The pieces every help uses.

/** [lines] one under the other, each term in bold. */
@Composable
internal fun HelpLines(lines: List<HelpLine>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lines.forEach { line ->
            Text(
                buildAnnotatedString {
                    line.term?.let {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(it) }
                        append(": ")
                    }
                    append(line.text)
                },
            )
        }
    }
}

@Composable
internal fun InfoDialog(info: Info, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text(info.title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { HelpLines(info.lines) } },
    )
}

/** A label with ⓘ: tapping it opens [info]. */
@Composable
internal fun InfoLabel(text: String, info: Info, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    var open by remember { mutableStateOf(false) }
    if (open) InfoDialog(info, onDismiss = { open = false })
    Text(
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Normal)) { append(" ⓘ") }
        },
        modifier.clickable { open = true }.padding(vertical = 4.dp),
        style = style,
    )
}

/** ⓘ on its own, in a title row: tapping it opens [info]. */
@Composable
internal fun InfoButton(info: Info) {
    var open by remember { mutableStateOf(false) }
    if (open) InfoDialog(info, onDismiss = { open = false })
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Explain", role = Role.Button) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Text("ⓘ", Modifier.semantics { contentDescription = "About ${info.title}" }, color = INK, fontSize = 20.sp)
    }
}

// The help's topics: the prices and the panels.

/** The prices beyond the colours; with [state] (null on the first start) the cost line while it shows. */
private fun pricesHelp(state: UiState?, help: CountryHelp): Info {
    val cost = state != null && state.whatwattEnabled && (!state.region.isSpot || state.priceAddOn != null)
    return Info(
        "Prices",
        listOfNotNull(
            line("Pull down to refresh. ↻ is dimmed while a refresh would be too soon."),
            line("The line below the price: what you draw now, and what it costs per hour.").takeIf { cost },
            line("On a fixed price, the colour saves nothing. It still shows when the grid has power to spare."),
        ) + help.priceLines.map(::line),
    )
}

internal val CURVE_HELP = info(
    "Price curve",
    line("The hours the colour is compared with, each ¼ hour in its colour."),
    line("Faded bars are over. The line marks now."),
    line("Until tomorrow's prices are out, it shows today."),
    term("Green for now", "green until the last price known."),
)

/** The peak window's topic: the bars, the limit and the alarm, plus the countdown and the preview where they're on. */
internal fun peakHelp(state: UiState, help: CountryHelp): Info {
    val unit = state.powerUnit.id
    return info(
        "Peak load",
        line("Some grid tariffs bill the month's highest ¼ hour: your average $unit over 15 minutes."),
        help.peakLines.takeIf { it.isNotEmpty() }?.let { peakTerm(it.joinToString(" ")) },
        term("Now", "this ¼ hour so far, plus your draw until it ends."),
        term("$unit free", "what you can still switch on before the line."),
        term("Line", "your limit (Settings → 📊 Mode → Limit). At it, the bar turns red and the phone vibrates. Share tells your household."),
        term("min left", "until the next ¼ hour.").takeIf { state.countdown },
        term("Blue", "an appliance you tapped, started now. Red is what goes over the line.")
            .takeIf { state.appliancesEnabled && state.appliances.isNotEmpty() },
    )
}

/** "Peak: billed by CKW." from the core's "Billed by CKW.", so the line says what's billed. */
private fun peakTerm(billed: String) = term("Peak", billed.replaceFirstChar { it.lowercase() })

internal val APPLIANCES_HELP = info(
    "Appliances",
    line("Add one with +. GridLoad measures it once: what it draws, and for how long."),
    term("OK", "fine to switch on now."),
    term("WAIT", "it would reach the limit, or a later start is clearly cheaper. The row says when, and what waiting saves."),
    line("⏱ sets a timer for that time."),
    line("Tap a row to see its run in the bars above. Hold it to edit."),
    line("A row looks at the whole run, so it can differ from the colour."),
)

/** The history panel's topic, with the base load line while it shows. */
internal fun historyHelp(state: UiState) = info(
    "History",
    line("Each day's highest ¼ hour this month. Red: the month's highest. Dark: today."),
    line("Tap the chart for each day's ¼ hours, and last month."),
    term("Base load", "what always draws, like the fridge, the router and standby.").takeIf { state.baseLoadEnabled },
)

internal val HISTORY_SCREEN_HELP = info(
    "History",
    line("Each day's highest ¼ hour. Red: the month's highest. Dark: today."),
    line("Tap a day to see its ¼ hours below. ‹ › switch the month."),
    line("The line is today's limit, so last month has none."),
)

// Settings: the sections' and the settings' ⓘ, only for the parts that show.

/** For [country]'s regions, or all of them before a country is picked. */
internal fun regionInfo(country: Country?): Info {
    val regions = REGIONS.filter { country == null || it.country == country }
    val utility = regions.any { !it.isSpot }
    return info(
        "Region",
        line("Pick the utility on your electricity bill: GridLoad uses its dynamic tariff.").takeIf { utility },
        line((if (utility) "Elsewhere, pick" else "Pick") + " where you live: GridLoad uses the day-ahead market price.")
            .takeIf { regions.any { it.isSpot } },
    )
}

internal val PRICE_INFO = info(
    "Your price",
    line("The market price is only part of what you pay."),
    term("Add-on", "the rest per kWh without VAT, from your bill (your supplier's markup, the grid fee per kWh and levies)."),
    term("VAT", "preset for your country."),
    line("The colour doesn't depend on them."),
)

internal val CURVE_INFO = info(
    "Price curve",
    line("Shows the prices the colour is compared with on the main screen, each ¼ hour as a bar in its colour."),
)

internal val MEASUREMENT_INFO = info(
    "Measurement",
    line("A whatwatt Go reads your smart meter."),
    line("GridLoad then shows what you draw now and what it costs, and can watch your monthly peak."),
    line("It works while your phone is on your home Wi-Fi."),
)

/** With whether [region] bills the peak. */
internal fun modeInfo(region: Region) = info(
    "Mode",
    term("Peak load off", "the price and what you draw now."),
    term("Peak load on", "also what you can still switch on, your appliances and the month so far. It needs GridLoad's recorder on the whatwatt, with an SD card."),
    peakBilled(region, Instant.now())?.let(::peakTerm) ?: line("Your region doesn't bill a peak, so the limit is just your own cap."),
)

internal val RECORDER_INFO = info(
    "Recorder",
    line("A small script GridLoad puts in the whatwatt's one script slot."),
    line("It saves every ¼ hour to the SD card, also while GridLoad is closed, so the month's highest is complete when you open it."),
    term("Gaps", "¼ hours it missed. They're missing from the month's highest too."),
    line("Peak load needs it."),
)

/** The floor's name, "Biggest appliance + 20%", with the [margin] set. */
internal fun floorLabel(margin: Int) = "Biggest appliance + $margin%"

/**
 * The limit's [parts] that show, each with why it's there: "billed anyway" only where [region]
 * bills the peak already, and WAIT only while the [appliances] panel shows.
 */
internal fun limitInfo(parts: List<LimitPart>, unit: PowerUnit, region: Region, appliances: Boolean, margin: Int): Info {
    val today = Instant.now().atZone(region.zone).toLocalDate()
    val billed = region.minimumKw != null && region.peakFrom?.let { today >= it } != false
    return info(
        "Limit",
        line("Where GridLoad warns: the red bar, \"${unit.id} free\"" + (if (appliances) ", the vibration and WAIT." else " and the vibration.")),
        line("It's the highest of the parts switched on."),
        term(
            "Month's highest",
            (if (billed) "billed anyway." else "the most you drew this month.") + " Switched off, the limit can sit below it: a personal cap.",
        ),
        term(floorLabel(margin), "lets it run alone; only stacking others on it warns. The % leaves room for the rest of the house.")
            .takeIf { LimitPart.FLOOR in parts },
        term("Tariff minimum", if (billed) "billed anyway." else "the least your tariff will bill.").takeIf { LimitPart.MINIMUM in parts },
        line("Only above the month's highest does a warning say it sets a new peak."),
    )
}

/** What the goal does with the month's highest [highestOn] or off. */
internal fun goalInfo(highestOn: Boolean) = info(
    "Goal",
    line("A value of your own, e.g. what you expect to need this month anyway."),
    line(if (highestOn) "With Month's highest on, it can only raise the limit." else "With Month's highest off, it can sit below the month's highest: a personal cap."),
)

/** With what switching it off does to the limit while the floor (with its [margin]) is [floorOn]. */
internal fun appliancesInfo(floorOn: Boolean, margin: Int) = info(
    "Appliances",
    line("Shows your measured appliances on the main screen, each with OK or WAIT."),
    line("Off hides the panel" + (if (floorOn) " and takes ${floorLabel(margin)} out of the limit" else "") + ". The appliances stay."),
    term("Export", "saves them to a file, e.g. for a new phone."),
    term("Import", "adds them from such a file. One with the same name is replaced."),
)

internal val COUNTDOWN_INFO = info(
    "¼-hour countdown",
    line("Shows the minutes left in this ¼ hour."),
    line("Waiting a few minutes before a big appliance can keep it out of this one."),
)

internal val VIBRATE_INFO = info(
    "Vibrate at the limit",
    line("When this ¼ hour reaches the limit, the phone vibrates once, while GridLoad is open."),
    term("Unless silent", "not while the phone is silent."),
    term("Always", "in silent mode too."),
    line("Tap a choice to feel it."),
)

/** With the preview's later ¼ hours while the [appliances] panel shows. */
internal fun drawInfo(appliances: Boolean) = info(
    "Draw ahead",
    line("What GridLoad expects your house to draw for the rest of this ¼ hour" + (if (appliances) ", and after it when you tap an appliance." else ".")),
    term(
        "2-min average",
        "steady while a hob or oven switches on and off. For the first minute after you open GridLoad: this ¼ hour's average so far.",
    ),
    term("Latest reading", "follows a switch at once, but jumps with every on and off."),
)

internal val BASE_LOAD_INFO = info(
    "Base load",
    line("Shows your base load in the history, and what it comes to in a year."),
    line("It's the median of the ¼ hours between these hours over the last $BASE_LOAD_DAYS days."),
    line("Pick hours when nothing else runs: an appliance on a night timer would count."),
)

// The appliance sheet and its dialogs.

internal val MEASURE_INFO = info(
    "Measuring an appliance",
    term("Before", "the recorder must be recording, with no red line. Pick a quiet time, not while cooking."),
    term("Start", "be at home, with GridLoad open, when you switch it on. Keep it open until the jump shows, about a minute."),
    term("Then", "switch nothing else on or off until you tap Done. Lights and small devices are fine."),
    term("Run", "the programme you always use, to its end. Another programme is another appliance."),
    term("Done", "tap it as soon as it has finished. The result usually shows right after Done."),
    line("Only appliances on the whatwatt's meter count."),
)

internal val CAN_WAIT_INFO = info(
    "Can wait",
    term("On", "the price counts too, e.g. a dishwasher."),
    term("Off", "only the limit counts, e.g. a kettle."),
)

/** With a note while the floor (with its [margin]) is off, so the switch changes nothing now. */
internal fun countsInfo(floorOn: Boolean, margin: Int) = info(
    "Counts for the limit",
    line("Its heaviest ¼ hour, plus $margin%, can set the limit: ${floorLabel(margin)}."),
    line("Switch it off for one you rarely use."),
    line("${floorLabel(margin)} is off in Settings → 📊 Mode → Limit, so this changes nothing now.").takeIf { !floorOn },
)

internal val START_DELAY_INFO = info(
    "Start delay",
    line("The steps of the appliance's own delay timer, if it has one."),
    line("WAIT then says what to set, e.g. Delay 3 h."),
)

internal val VARIANT_INFO = info(
    "Variant",
    line("The same appliance with another run time, e.g. more water in the kettle."),
    line("Its start stays as measured. Its last phase runs longer or shorter."),
    term("From water", "works out a kettle's run time from the water and its temperature."),
)
