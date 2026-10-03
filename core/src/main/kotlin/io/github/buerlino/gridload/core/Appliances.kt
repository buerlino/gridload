package io.github.buerlino.gridload.core

import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import java.time.Instant

/*
 * The appliances panel (design in research/appliances.md): each appliance is measured once, from
 * the recorder's quarter hours plus the jump in the live draw at its start. For each, the app says
 * whether starting it now sets a new monthly peak or a later start is clearly cheaper.
 */

/** [min] minutes at [kw] above the household's base draw. */
@Serializable
data class Piece(val min: Double, val kw: Double)

/**
 * A measured appliance; its name is the key. [curve] is its extra draw, piece after piece from
 * the start. [canWait]: the price counts too, not only the peak. [delayMinutes]: the step of the
 * appliance's start delay (0 = none), so the advice is a delay to set on it.
 */
@Serializable
data class Appliance(
    val name: String,
    val curve: List<Piece>,
    val canWait: Boolean = true,
    val delayMinutes: Int = 0,
) {
    val minutes: Double get() = curve.sumOf { it.min }
    val kwh: Double get() = curve.sumOf { it.min * it.kw } / 60
    val kw: Double get() = curve.maxOf { it.kw }
}

/** Parses `appliances.json`, or an exported copy; throws on anything else. */
fun parseAppliances(text: String): List<Appliance> = json.decodeFromString<List<Appliance>>(text).onEach { a ->
    require(a.name.isNotBlank() && a.curve.isNotEmpty() && a.curve.all { it.min > 0 && it.kw >= 0 }) { "Not an appliance: ${a.name}" }
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

private fun minutes(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 60_000.0

private fun Appliance.endIfStarted(start: Instant): Instant = start.plusMillis((minutes * 60_000).toLong())

sealed interface Measurement {
    /** The recorder hasn't saved the last quarter hour of the run yet; it's due at [at]. */
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
 * Start was tapped, [done] when Done was; [beforeKw] the live draw just before the start,
 * [highestKw] the highest seen since, until Done.
 */
@Serializable
data class Measuring(
    val appliance: Appliance,
    val start: Long,
    val beforeKw: Double,
    val highestKw: Double,
    val done: Long? = null,
) {
    /** The result from the recorder's [quarters]; null until Done. */
    fun result(quarters: List<Quarter>): Measurement? =
        done?.let { measure(Instant.ofEpochSecond(start), Instant.ofEpochSecond(it), beforeKw, highestKw, quarters) }
}

fun parseMeasuring(text: String): Measuring = json.decodeFromString(text)

fun measuringJson(measuring: Measuring): String = json.encodeToString(measuring)

/** A quarter hour's extra energy below this is quiet: a 40 W average (a fridge cycle is ~0.008 kWh). */
private const val QUIET_KWH = 0.01

/**
 * The curve of a run from Start ([start]) to Done ([done]), from the recorder's [quarters]. The
 * base draw is the quarter hour before the start's, else [beforeKw], the live draw just before the
 * start. Each quarter's extra energy is spread evenly over the part the run covers; in the last
 * one the appliance runs at its jump in the live draw ([highestKw] − [beforeKw]) from the
 * quarter's start, at most to its end, so a 3-minute kettle stays 3 minutes. Quiet quarters at the
 * end are dropped.
 */
internal fun measure(start: Instant, done: Instant, beforeKw: Double, highestKw: Double, quarters: List<Quarter>): Measurement {
    val byStart = quarters.associateBy { it.start }
    val first = quarterStart(start)
    val last = quarterStart(done)
    val starts = generateSequence(first) { it.plusSeconds(QUARTER_SECONDS) }.takeWhile { it <= last }.toList()
    val missing = starts.firstOrNull { it !in byStart }
    if (missing != null) {
        return if (quarters.any { it.start > missing }) Measurement.Gap else Measurement.Pending(last.plusSeconds(QUARTER_SECONDS))
    }
    val base = byStart[first.minusSeconds(QUARTER_SECONDS)]?.kw ?: beforeKw
    val extra = starts.map { (byStart.getValue(it).kwh - base / 4).coerceAtLeast(0.0) }.dropLastWhile { it < QUIET_KWH }
    if (extra.isEmpty()) return Measurement.NoDraw
    val jump = highestKw - beforeKw
    return Measurement.Result(extra.mapIndexed { i, kwh ->
        val covered = if (i == 0) minutes(start, first.plusSeconds(QUARTER_SECONDS)) else 15.0
        val min = if (i == extra.lastIndex && jump > 0) minOf(covered, kwh * 60 / jump) else covered
        Piece(min, kwh * 60 / min)
    })
}

/** What the peak check needs now. */
data class PeakNow(
    /** This quarter hour's projection, as the peak window shows it. */
    val projection: Projection,
    /** The draw now, assumed for the later quarter hours. */
    val drawKw: Double,
    /** From [peakLine]; null when there's no line, so the peak never blocks. */
    val line: Double?,
)

/** Whether starting at [start] keeps every quarter hour of the run below the peak window's warning. */
internal fun Appliance.fitsPeak(start: Instant, peak: PeakNow): Boolean {
    val line = peak.line ?: return true
    val end = endIfStarted(start)
    return generateSequence(quarterStart(start)) { it.plusSeconds(QUARTER_SECONDS) }.takeWhile { it < end }.all { q ->
        val house = if (q == peak.projection.start) peak.projection.kw else peak.drawKw
        val added = curve.kwhBetween(minutes(start, q), minutes(start, q.plusSeconds(QUARTER_SECONDS))) * 4
        !isPeakWarning(house + added, line)
    }
}

/** The run's price for a start at [start], weighted by its kWh; null unless the whole run lies within [slots]. */
internal fun Appliance.runPrice(start: Instant, slots: List<PriceSlot>): Double? {
    if (slots.isEmpty() || start < slots.first().start.toInstant() || endIfStarted(start) > slots.last().end.toInstant()) return null
    val cost = slots.sumOf { curve.kwhBetween(minutes(start, it.start.toInstant()), minutes(start, it.end.toInstant())) * it.price }
    return cost / kwh
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
    /** Fine to start now. [pricesEnd]: the known prices end there, before the run would, so only the peak was judged. */
    data class Ok(val pricesEnd: Instant? = null) : Advice
    /** Starting now sets a new monthly peak; [at] is the first start that's fine, null if none is within the window. */
    data class NewPeak(val at: Instant?) : Advice
    /** A later start is clearly cheaper; [at] is the first that's fine. */
    data class Cheaper(val at: Instant) : Advice
}

/**
 * Whether to start [appliance] now. A start is fine when no quarter hour of the run reaches the
 * peak window's warning and, if it can wait, its run price is in the cheapest third of all
 * candidate starts' (the main colour's thirds). Only runs within the known prices compete; when
 * even starting now runs past them, the price isn't judged. When every cheaper start sets a new
 * peak, now is the best that fits.
 */
fun advise(appliance: Appliance, now: Instant, peak: PeakNow, slots: List<PriceSlot>): Advice {
    val starts = appliance.candidateStarts(now)
    val prices = if (appliance.canWait) starts.associateWith { appliance.runPrice(it, slots) } else emptyMap()
    val known = prices.values.filterNotNull()
    val judgePrice = prices[now] != null
    // A small margin, so equal prices weighted in a different order still count as equal.
    val cheapest = if (judgePrice) known.min() + (known.max() - known.min()) / 3 + 1e-9 else 0.0
    fun fine(start: Instant) = (!judgePrice || prices.getValue(start).let { it != null && it <= cheapest }) && appliance.fitsPeak(start, peak)
    val first = starts.firstOrNull(::fine)
    return when {
        first == now -> Advice.Ok(pricesEnd = slots.lastOrNull()?.end?.toInstant()?.takeIf { appliance.canWait && !judgePrice })
        !appliance.fitsPeak(now, peak) -> Advice.NewPeak(first)
        first != null -> Advice.Cheaper(first)
        else -> Advice.Ok()
    }
}
