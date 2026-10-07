package io.github.buerlino.gridload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.buerlino.gridload.core.Level
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
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
        setContent {
            MaterialTheme(colorScheme = NEUTRAL) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showGuide by rememberSaveable { mutableStateOf(false) }
                var showHistory by rememberSaveable { mutableStateOf(false) }
                var showHelp by rememberSaveable { mutableStateOf(false) }
                // Where Settings opens: the region in the top bar opens the region list.
                var settingsAt by remember { mutableStateOf(SettingsAt.TOP) }
                // Here rather than in Screen, so it's where it was after Settings or the history.
                val panelScroll = rememberScrollState()
                val collapse = remember { Collapse() }
                when {
                    !state.firstStartDone || showGuide -> SetupGuide(
                        state, viewModel,
                        onDone = { region -> viewModel.finishSetup(region); showGuide = false; showSettings = false },
                        onClose = if (state.firstStartDone) ({ showGuide = false }) else null,
                    )
                    showSettings -> SettingsScreen(
                        state, viewModel,
                        at = settingsAt,
                        onOpenGuide = { showGuide = true },
                        onBack = { showSettings = false; settingsAt = SettingsAt.TOP },
                    )
                    showHistory -> HistoryScreen(state, onBack = { showHistory = false })
                    showHelp -> HelpScreen(state, onBack = { showHelp = false })
                    else -> Screen(
                        state, viewModel, panelScroll, collapse,
                        onRefresh = viewModel::refresh,
                        onOpenSettings = { at -> settingsAt = at; showSettings = true },
                        onToggleCurve = { viewModel.setCurveOpen(!state.curveOpen) },
                        onTogglePeak = { viewModel.setPeakOpen(!state.peakOpen) },
                        onToggleHistory = { viewModel.setHistoryOpen(!state.historyOpen) },
                        onOpenHistory = { showHistory = true },
                        onOpenHelp = { showHelp = true },
                    )
                }
            }
        }
    }
}

internal val GREEN = Color(0xFF2E7D32)
internal val ORANGE = Color(0xFFFFA000)
internal val RED = Color(0xFFC62828)
private val GREY = Color(0xFF616161)

/**
 * Settings, the guide, dialogs and sheets: white, light grey and the panels' dark ink, always
 * light (user, 2026-10-03), so the colours stay reserved for good, fair and bad.
 */
private val NEUTRAL = lightColorScheme(
    primary = INK,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E8EB),
    onPrimaryContainer = INK,
    secondary = INK,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8EB),
    onSecondaryContainer = INK,
    tertiary = INK,
    onTertiary = Color.White,
    background = Color.White,
    onBackground = INK,
    surface = Color.White,
    onSurface = INK,
    surfaceVariant = Color(0xFFEDEEF0),
    onSurfaceVariant = MUTED,
    surfaceTint = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE6E8EB),
    outline = Color(0xFF8A9199),
    outlineVariant = Color(0xFFD5D8DB),
    error = RED,
)

private data class Look(val background: Color, val content: Color, val headline: String)

internal val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** A time still to come: "14:15" ([today] before it), or "tomorrow 10:00" when it isn't today. */
internal fun comingTime(at: Instant, today: String = ""): String {
    val local = at.atZone(ZoneId.systemDefault())
    return (if (local.toLocalDate() == LocalDate.now()) today else "tomorrow ") + timeFormat.format(local)
}

/**
 * The colour, the headline, the price, the next good time, and when the prices were updated;
 * pulling down refreshes. With panels to show, this part is fixed at the top with smaller text,
 * and they scroll below it: the price curve, and with peak load on the peak window, the
 * appliances and the history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(
    state: UiState,
    viewModel: MainViewModel,
    panelScroll: ScrollState,
    collapse: Collapse,
    onRefresh: () -> Unit,
    onOpenSettings: (SettingsAt) -> Unit,
    onToggleCurve: () -> Unit,
    onTogglePeak: () -> Unit,
    onToggleHistory: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val (background, content, label) = look(state)
    StatusBarIcons(dark = content == Color.Black)
    Box(Modifier.fillMaxSize().background(background)) {
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 4.dp)) {
                TopBar(state, content, onOpenSettings, onHelp = onOpenHelp)
                // Each part scrolls, so that pulling down anywhere refreshes.
                if (state.showPanels) {
                    val scope = rememberCoroutineScope()
                    // Where the peak window starts in the panels, so a preview scrolls to it.
                    var peakTop by remember { mutableIntStateOf(0) }
                    Column(Modifier.weight(1f).nestedScroll(collapse)) {
                        Spot(
                            state, label, content, collapse, onSetPrice = { onOpenSettings(SettingsAt.PRICE) },
                            Modifier.clipToBounds()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, (placeable.height - collapse.offset).roundToInt()) { placeable.place(0, 0) }
                                }
                                .onSizeChanged { collapse.full = it.height.toFloat() }
                                .verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 16.dp),
                        )
                        Column(
                            Modifier.weight(1f).fillMaxWidth().edgeShadows(panelScroll, bleed = 24.dp).verticalScroll(panelScroll),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            state.status?.takeIf { state.showCurve }?.let { PricePanel(state, it, onToggle = onToggleCurve) }
                            if (state.showPeak) {
                                PeakWindow(
                                    state, onToggle = onTogglePeak, onClosePreview = { viewModel.setPreview(null) }, onOpenSettings = { onOpenSettings(SettingsAt.TOP) },
                                    Modifier.onPlaced { peakTop = it.positionInParent().y.roundToInt() },
                                )
                                // The peak window is above the appliances, so a preview scrolls up to it.
                                if (state.appliancesEnabled) AppliancesPanel(state, viewModel, onPreview = { name ->
                                    viewModel.setPreview(name)
                                    if (name != null) scope.launch { panelScroll.animateScrollTo(peakTop) }
                                })
                                HistoryPanel(state, onToggle = onToggleHistory, onOpen = onOpenHistory)
                            }
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        Spot(state, label, content, collapse = null, onSetPrice = { onOpenSettings(SettingsAt.PRICE) }, Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight))
                    }
                }
                Updated(state, content)
            }
        }
    }
}

/**
 * A soft shadow from the strip above and the "Updated" line below onto the scrolled content,
 * shown only while content is behind that edge, so it reads as sliding under them. [bleed]
 * widens it to the screen's edges past the side padding. Goes before [verticalScroll], so it
 * draws at the visible area's edges.
 */
private fun Modifier.edgeShadows(scroll: ScrollState, bleed: Dp): Modifier = drawWithContent {
    drawContent()
    val height = 6.dp.toPx()
    // Grows in over the first 8 dp scrolled past an edge, rather than popping in.
    val ramp = 8.dp.toPx()
    val left = -bleed.toPx()
    val width = size.width + 2 * bleed.toPx()
    val top = (scroll.value / ramp).coerceAtMost(1f)
    val bottom = ((scroll.maxValue - scroll.value) / ramp).coerceAtMost(1f)
    if (top > 0f) drawRect(
        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.18f * top), 1f to Color.Transparent, endY = height),
        topLeft = Offset(left, 0f), size = Size(width, height),
    )
    if (bottom > 0f) drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.18f * bottom), startY = size.height - height, endY = size.height),
        topLeft = Offset(left, size.height - height), size = Size(width, height),
    )
}

/** The headline's size in the collapsed strip, relative to its size above the panels. */
private const val STRIP_HEADLINE = 0.75f

/**
 * How far the spot part above the panels has collapsed, in px. Scrolling the panels up first
 * shrinks it to a strip with only the headline; scrolling down grows it back once the panels
 * are at their top, before a pull reaches pull-to-refresh.
 */
private class Collapse : NestedScrollConnection {
    /** The spot part's full height and the strip's, both in px. */
    var full by mutableFloatStateOf(0f)
    var strip by mutableFloatStateOf(0f)
    private val range get() = (full - strip).coerceAtLeast(0f)
    private var collapsed by mutableFloatStateOf(0f)
    val offset get() = collapsed.coerceAtMost(range)
    val fraction get() = if (range > 0f) offset / range else 0f

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f) return Offset.Zero
        val before = offset
        collapsed = (before - available.y).coerceAtMost(range)
        return Offset(0f, before - collapsed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (available.y <= 0f) return Offset.Zero
        val before = offset
        collapsed = (before - available.y).coerceAtLeast(0f)
        return Offset(0f, before - collapsed)
    }
}

/**
 * The headline, the price, the cost now and, without the price curve, the next good time; smaller
 * and collapsing above the panels. In a spot region without an add-on, the market price and a link to the add-on's field.
 */
@Composable
private fun Spot(state: UiState, label: String, content: Color, collapse: Collapse?, onSetPrice: () -> Unit, modifier: Modifier) {
    val small = collapse != null
    val shrink = collapse?.fraction ?: 0f
    val spacing = if (small) 6.dp else 12.dp
    val density = LocalDensity.current
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterVertically),
    ) {
        Text(
            label, color = content, fontSize = if (small) 32.sp else 44.sp, lineHeight = if (small) 38.sp else 52.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier
                .onSizeChanged { size ->
                    // The strip: the top padding, the shrunk headline and a little space below it.
                    collapse?.strip = with(density) { 4.dp.toPx() + size.height * STRIP_HEADLINE + 8.dp.toPx() }
                }
                .graphicsLayer {
                    val scale = 1f - (1f - STRIP_HEADLINE) * shrink
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        )
        // The lines below fade out as the strip closes over them.
        Column(
            Modifier.graphicsLayer { alpha = (1f - 2f * shrink).coerceAtLeast(0f) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            val textSize = if (small) 16.sp else 20.sp
            state.status?.let { status ->
                val currency = state.region.country.currency
                val price = state.yourPrice(status.slot.price)
                if (price == null) {
                    Text("Market price ${currency.perKwh(status.slot.price)}", color = content, fontSize = textSize)
                    Text("Set your price ›", Modifier.clickable(onClick = onSetPrice), color = content, fontSize = textSize)
                } else {
                    Text(currency.perKwh(price), color = content, fontSize = textSize)
                    state.meter.kw?.let { kw ->
                        Text("${state.powerUnit.format(kw)} now · ${currency.perHour(kw * price)}", color = content, fontSize = textSize)
                    }
                }
                // The price curve's header says it, with how long the green lasts.
                status.nextGreen?.takeIf { !state.showCurve }?.let { NextGoodTime(it.start, content, if (small) 16.sp else 18.sp) }
            }
            state.error?.let { Text(it, color = content, textAlign = TextAlign.Center) }
            // With peak load, the peak window says it instead.
            if (!state.showPeak) state.meter.problem?.let { Text(it, color = content) }
        }
    }
}

/** "Updated 14:02 ↻" (or a blocked refresh's notice); the ↻ is dimmed while a refresh would be blocked. */
@Composable
private fun Updated(state: UiState, content: Color) {
    var coolingDown by remember { mutableStateOf(false) }
    LaunchedEffect(state.cooldownEnd) {
        val left = state.cooldownEnd?.let { Duration.between(Instant.now(), it).toMillis() } ?: 0
        coolingDown = left > 0
        if (left > 0) {
            delay(left)
            coolingDown = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        state.notice?.let { Text(it, color = content) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.fetchedAt?.let { "Updated ${timeFormat.format(it)}" } ?: "Pull down to refresh", color = content)
            Text("↻", Modifier.alpha(if (coolingDown || state.loading) 0.35f else 1f), color = content, fontSize = 18.sp)
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
private fun TopBar(state: UiState, content: Color, onOpenSettings: (SettingsAt) -> Unit, onHelp: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = { onOpenSettings(SettingsAt.TOP) }, modifier = Modifier.align(Alignment.TopStart)) {
            Text("⚙", color = content, fontSize = 22.sp)
        }
        Text(
            state.region.label,
            color = content,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            // Clear of the ⚙ and ? buttons; long names wrap.
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp, start = 48.dp, end = 48.dp)
                .clickable { onOpenSettings(SettingsAt.REGION_LIST) }.padding(vertical = 8.dp),
        )
        TextButton(onClick = onHelp, modifier = Modifier.align(Alignment.TopEnd)) {
            Text("?", color = content, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The next good time as a green dot and "11:00" or "tomorrow 11:00". */
@Composable
private fun NextGoodTime(start: OffsetDateTime, content: Color, fontSize: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GreenDot(content)
        Text(comingTime(start.toInstant()), color = content, fontSize = fontSize)
    }
}

/** The app's green with a thin outline in [outline], so it shows on any background, the green one too. */
@Composable
internal fun GreenDot(outline: Color) {
    Box(Modifier.size(14.dp).background(GREEN, CircleShape).border(1.5.dp, outline, CircleShape))
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
