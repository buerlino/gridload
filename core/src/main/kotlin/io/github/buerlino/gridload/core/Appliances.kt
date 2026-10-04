package io.github.buerlino.gridload.core

import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import java.time.Instant

/*
 * The appliances panel (design in CLAUDE.md, "Appliances"): each appliance is measured once, from
 * the recorder's quarter hours plus the jump in the live draw at its start. For each, the app says
 * whether starting it now reaches the limit or a later start is clearly cheaper.
 */

/** [min] minutes at [kw] above the household's base draw. */
@Serializable
data class Piece(val min: Double, val kw: Double)

/**
 * A measured appliance; its name is the key. [curve] is its extra draw, piece after piece from
 * the start. [canWait]: the price counts too, not only the peak. [delayMinutes]: the step of the
 * appliance's start delay (0 = none), so the advice is a delay to set on it. [countsForLimit]:
 * its heaviest quarter hour sets the floor, a part of the limit ([peakFloor]).
 */
@Serializable
data class Appliance(
    val name: String,
    val curve: List<Piece>,
    val canWait: Boolean = true,
    val delayMinutes: Int = 0,
    val countsForLimit: Boolean = true,
)

/** A curve's run time, its energy and its power (the highest piece). */
val List<Piece>.minutes: Double get() = sumOf { it.min }
val List<Piece>.kwh: Double get() = sumOf { it.min * it.kw } / 60
val List<Piece>.kw: Double get() = maxOf { it.kw }

/**
 * The curve's heaviest quarter hour on its own, as an average kW: the most it draws in any 15
 * minutes, so wherever in a quarter hour it starts. The most is where the 15 minutes start or end
 * at a change between pieces.
 */
val List<Piece>.heaviestQuarterKw: Double get() =
    runningFold(0.0) { t, piece -> t + piece.min }.flatMap { listOf(it, it - 15) }.maxOf { kwhBetween(it, it + 15) } * 4

/** The floor's margin above the biggest appliance, for the house's base draw and some variation. */
const val FLOOR_MARGIN = 1.2

/** The floor, [kw], and the [appliance] it comes from. */
data class Floor(val kw: Double, val appliance: String)

/**
 * The floor, a part of the limit ([peakLimit]): the heaviest quarter hour of the biggest appliance
 * that counts for it, plus [FLOOR_MARGIN]. That appliance sets a peak this high by itself, so it
 * shouldn't wait for one; only stacking others on it should. Null with none.
 */
fun peakFloor(appliances: List<Appliance>): Floor? =
    appliances.filter { it.countsForLimit }.maxByOrNull { it.curve.heaviestQuarterKw }
        ?.let { Floor(it.curve.heaviestQuarterKw * FLOOR_MARGIN, it.name) }

/** Tap water's temperature, for [waterShare]. */
private const val TAP_CELSIUS = 15.0

/**
 * How much of a kettle's measured run (with [measuredLitres] heated to 100 °C) another amount
 * takes: [litres] heated to [celsius]. Heating water takes energy in proportion to the amount and
 * the temperature rise, at the element's fixed power, so only the run time changes.
 */
fun waterShare(measuredLitres: Double, litres: Double, celsius: Int): Double =
    litres / measuredLitres * (celsius - TAP_CELSIUS) / (100 - TAP_CELSIUS)

/**
 * A variant of this curve that runs [minutes] in all: its start stays as measured, and its last
 * phase is stretched (the last piece runs longer) or cut (the pieces past [minutes] are dropped).
 * An oven keeps its preheat and cycles longer; a kettle is one phase at fixed power.
 */
fun List<Piece>.withRunTime(minutes: Double): List<Piece> {
    require(minutes > 0) { "No run time: $minutes" }
    var t = 0.0
    val kept = mutableListOf<Piece>()
    for (piece in this) {
        if (t >= minutes) break
        kept += piece.copy(min = minOf(piece.min, minutes - t))
        t += piece.min
    }
    return if (t < minutes) kept.dropLast(1) + last().copy(min = last().min + minutes - t) else kept
}

/**
 * Parses `appliances.json`, or an exported copy; throws on anything else. Names are the key, so
 * each comes once; the last piece draws something, so a variant has something to stretch.
 */
fun parseAppliances(text: String): List<Appliance> = json.decodeFromString<List<Appliance>>(text).also { list ->
    for (a in list) {
        require(a.name.isNotBlank() && a.curve.isNotEmpty() && a.curve.last().kw > 0) { "Not an appliance: ${a.name}" }
        require(a.curve.all { it.min > 0 && it.kw >= 0 && it.min.isFinite() && it.kw.isFinite() }) { "Not a curve: ${a.name}" }
    }
    require(list.distinctBy { it.name }.size == list.size) { "A name comes twice" }
}

fun appliancesJson(appliances: List<Appliance>): String = json.encodeToString(appliances)

/** An import: each of [imported] replaces the appliance of the same name in place, the others are added. */
fun mergeAppliances(existing: List<Appliance>, imported: List<Appliance>): List<Appliance> {
    val byName = imported.associateBy { it.name }
    return existing.map { byName[it.name] ?: it } + imported.filter { new -> existing.none { it.name == new.name } }
}

/** The measured appliances in one small file, `appliances.json`. Blocking; call off the main thread. */
class ApplianceFile(private val file: File) {
    fun load(): List<Appliance> = if (file.exists()) parseAppliances(file.readText()) else emptyList()

    fun save(appliances: List<Appliance>) = file.writeAtomically(appliancesJson(appliances))
}

/** kWh the curve draws from [from] to [to] minutes after its start. */
internal fun List<Piece>.kwhBetween(from: Double, to: Double): Double {
    var t = 0.0
    var kwh = 0.0
    for (piece in this) {
        val overlap = minOf(to, t + piece.min) - maxOf(from, t)
        if (overlap > 0) kwh += overlap * piece.kw / 60
        t += piece.min
    }
    return kwh
}

private fun minutesBetween(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 60_000.0

private fun Appliance.endIfStarted(start: Instant): Instant = start.plusMillis((curve.minutes * 60_000).toLong())

sealed interface Measurement {
    /** The recorder hasn't saved the quarter hours of the run yet; the last is due at [at]. */
    data class Pending(val at: Instant) : Measurement
    /** A quarter hour of the run is missing from the recording: measure again. */
    data object Gap : Measurement
    /** Nothing above the base draw. */
    data object NoDraw : Measurement
    data class Result(val curve: List<Piece>) : Measurement
}

/**
 * A measurement under way, kept in the prefs until it's saved or discarded. [appliance] is what
 * the user entered (with its old curve when measured again). Times in epoch seconds: [start] when
 * Start was tapped, [done] when Done was ([doneAt]); [beforeKw] the live draw just before the
 * start, for the jump shown while it runs; [baseKw] the average draw in the start's quarter hour
 * before it ([Projection.baseKw]); [doneKwh] the energy in Done's quarter hour up to Done
 * ([Projection.usedKwh]), so the draw after Done doesn't count.
 */
@Serializable
data class Measuring(
    val appliance: Appliance,
    val start: Long,
    val beforeKw: Double,
    val baseKw: Double? = null,
    val done: Long? = null,
    val doneKwh: Double? = null,
) {
    /** The result from the recorder's [quarters]; null until Done. */
    fun result(quarters: List<Quarter>): Measurement? =
        done?.let { measure(Instant.ofEpochSecond(start), Instant.ofEpochSecond(it), beforeKw, baseKw, doneKwh, quarters) }
}

fun parseMeasuring(text: String): Measuring = json.decodeFromString(text)

fun measuringJson(measuring: Measuring): String = json.encodeToString(measuring)

/** A reading older than this (the app was in the background) says nothing about Done. */
private val FRESH = Duration.ofSeconds(15)

/**
 * Done tapped at [now]: when the run ended, and the energy used in that quarter hour up to then
 * ([Measuring.doneKwh]), from the last [projection] when it's fresh and exact. A fresh reading
 * from just before a boundary (Done in a quarter's first seconds) puts Done at the boundary, so
 * the recorder's quarter covers the run up to it.
 */
fun doneAt(now: Instant, projection: Projection?): Pair<Instant, Double?> {
    val p = projection?.takeIf { Duration.between(it.time, now) < FRESH } ?: return now to null
    return when {
        p.end <= now -> p.end to null
        p.start == quarterStart(now.minusSeconds(1)) -> now to p.usedKwh
        else -> now to null
    }
}

/** A quarter hour's extra energy below this is quiet: a 40 W average (a fridge cycle is ~0.008 kWh). */
private const val QUIET_KWH = 0.01

/**
 * The curve of a run from Start ([start]) to Done ([done]), from the recorder's [quarters]. The
 * base draw is [baseKw], the average in the start's quarter hour before it, else the quarter hour
 * before, else [beforeKw], the live draw just before the start. Done's quarter hour is the one
 * Done ends (a Done on a boundary ends the quarter before); with [doneKwh] its energy up to Done
 * is known, so it needs no line from the recorder. Each quarter's extra energy is spread evenly
 * over the part of it between Start and Done. Quiet quarters at the end are dropped.
 */
internal fun measure(start: Instant, done: Instant, beforeKw: Double, baseKw: Double?, doneKwh: Double?, quarters: List<Quarter>): Measurement {
    val byStart = quarters.associateBy { it.start }
    val first = quarterStart(start)
    val last = quarterStart(done.minusSeconds(1))
    val starts = generateSequence(first) { it.plusSeconds(QUARTER_SECONDS) }.takeWhile { it <= last }.toList()
    val recorded = if (doneKwh != null) starts - last else starts
    val missing = recorded.firstOrNull { it !in byStart }
    if (missing != null) {
        return if (quarters.any { it.start > missing }) Measurement.Gap else Measurement.Pending(recorded.last().plusSeconds(QUARTER_SECONDS))
    }
    val base = baseKw ?: byStart[first.minusSeconds(QUARTER_SECONDS)]?.kw ?: beforeKw
    val extra = starts.map { q ->
        val kwh = if (q == last && doneKwh != null) doneKwh - base * minutesBetween(q, done) / 60 else byStart.getValue(q).kwh - base / 4
        minutesBetween(maxOf(start, q), minOf(done, q.plusSeconds(QUARTER_SECONDS))) to kwh.coerceAtLeast(0.0)
    }.filter { (min, _) -> min > 0 }.dropLastWhile { (_, kwh) -> kwh < QUIET_KWH }
    if (extra.isEmpty()) return Measurement.NoDraw
    return Measurement.Result(extra.map { (min, kwh) -> Piece(min, kwh * 60 / min) })
}

/** What the peak check needs now. */
data class PeakNow(
    /** This quarter hour's projection, as the peak window shows it. */
    val projection: Projection,
    /** The limit's kW ([peakLimit]); null when there's no limit, so the peak never blocks. */
    val line: Double?,
    /** The month's highest recorded quarter hour's kW, whether or not it's part of the limit ([isNewPeak]). */
    val highestKw: Double? = null,
)

/** A quarter hour of a run: the household's average ([houseKw]) and the appliance's on top ([addedKw]). */
data class QuarterLoad(val start: Instant, val houseKw: Double, val addedKw: Double) {
    val kw: Double get() = houseKw + addedKw
}

/**
 * Each quarter hour the run touches if started at [start]: the household (this quarter: the peak
 * window's projection; later ones: the draw it assumes ahead, [Projection.aheadKw]) plus the curve's energy in it as an average kW.
 * The peak check and the peak window's preview both use it, so they always agree.
 */
fun Appliance.quarterLoads(start: Instant, peak: PeakNow): List<QuarterLoad> {
    val end = endIfStarted(start)
    return generateSequence(quarterStart(start)) { it.plusSeconds(QUARTER_SECONDS) }.takeWhile { it < end }.map { q ->
        val house = if (q == peak.projection.start) peak.projection.kw else peak.projection.aheadKw
        QuarterLoad(q, house, curve.kwhBetween(minutesBetween(start, q), minutesBetween(start, q.plusSeconds(QUARTER_SECONDS))) * 4)
    }.toList()
}

/** Whether starting at [start] keeps every quarter hour of the run below the limit. */
internal fun Appliance.fitsPeak(start: Instant, peak: PeakNow): Boolean {
    val line = peak.line ?: return true
    return quarterLoads(start, peak).none { isPeakWarning(it.kw, line) }
}

/** Whether starting at [start] has a quarter hour that reaches both the limit and the month's highest. */
private fun Appliance.setsNewPeak(start: Instant, peak: PeakNow): Boolean {
    val line = peak.line ?: return false
    return quarterLoads(start, peak).any { isPeakWarning(it.kw, line) && isNewPeak(it.kw, peak.highestKw) }
}

/** The run's price for a start at [start], weighted by its kWh; null unless the whole run lies within [slots]. */
internal fun Appliance.runPrice(start: Instant, slots: List<PriceSlot>): Double? {
    if (slots.isEmpty() || start < slots.first().start.toInstant() || endIfStarted(start) > slots.last().end.toInstant()) return null
    val cost = slots.sumOf { curve.kwhBetween(minutesBetween(start, it.start.toInstant()), minutesBetween(start, it.end.toInstant())) * it.price }
    return cost / curve.kwh
}

/** Now, then each quarter hour, or each step of the start delay, within the [WINDOW]. */
internal fun Appliance.candidateStarts(now: Instant): List<Instant> {
    val later = if (delayMinutes > 0) {
        generateSequence(now.plusSeconds(delayMinutes * 60L)) { it.plusSeconds(delayMinutes * 60L) }
    } else {
        generateSequence(quarterStart(now).plusSeconds(QUARTER_SECONDS)) { it.plusSeconds(QUARTER_SECONDS) }
    }
    return listOf(now) + later.takeWhile { it < now + WINDOW }
}

sealed interface Advice {
    /** The first start that's fine, when it isn't now; null if none is within the window. */
    val at: Instant?

    /** Fine to start now. [pricesMissing]: the known prices end before the run would, so only the peak was judged. */
    data class Ok(val pricesMissing: Boolean = false) : Advice {
        override val at: Instant? get() = null
    }
    /** Starting now reaches the limit; [newPeak] when it also reaches the month's highest ([isNewPeak]). */
    data class OverLimit(override val at: Instant?, val newPeak: Boolean = true) : Advice
    /** A later start is clearly cheaper; [saving] is what waiting for it saves, in the slots' prices. */
    data class Cheaper(override val at: Instant, val saving: Double) : Advice
}

/**
 * Whether to start [appliance] now. A start is fine when no quarter hour of the run reaches the
 * limit and, if it can wait, its run price is in the cheapest third of all
 * candidate starts' (the main colour's thirds, up to [colourTop]). Only runs within the known prices compete; when
 * even starting now runs past them, the price isn't judged. When every cheaper start reaches the
 * limit, now is the best that fits. With no prices at all, one that can wait gets no advice (null),
 * unless starting now reaches the limit.
 */
fun advise(appliance: Appliance, now: Instant, peak: PeakNow, slots: List<PriceSlot>, spot: Boolean): Advice? {
    val starts = appliance.candidateStarts(now)
    val prices = if (appliance.canWait) starts.associateWith { appliance.runPrice(it, slots) } else emptyMap()
    val known = prices.values.filterNotNull()
    val judgePrice = prices[now] != null
    // A small margin, so equal prices weighted in a different order still count as equal.
    val cheapest = if (judgePrice) known.min() + (colourTop(known, spot) - known.min()) / 3 + 1e-9 else 0.0
    fun fine(start: Instant) = (!judgePrice || prices.getValue(start).let { it != null && it <= cheapest }) && appliance.fitsPeak(start, peak)
    val first = starts.firstOrNull(::fine)
    return when {
        first == now -> if (appliance.canWait && slots.isEmpty()) null else Advice.Ok(pricesMissing = appliance.canWait && !judgePrice)
        !appliance.fitsPeak(now, peak) -> Advice.OverLimit(first, appliance.setsNewPeak(now, peak))
        first != null -> Advice.Cheaper(first, (prices.getValue(now)!! - prices.getValue(first)!!) * appliance.curve.kwh)
        else -> Advice.Ok()
    }
}
