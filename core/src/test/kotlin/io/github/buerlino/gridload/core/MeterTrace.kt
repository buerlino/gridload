package io.github.buerlino.gridload.core

import java.time.Duration
import java.time.Instant
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * A household meter for tests: it draws each segment's kW from the segment's time on, from a
 * register at [startKwh] at the first segment. It's read like the Kamstrup through the whatwatt:
 * every [interval] seconds (first at [phase]), the time in whole seconds, the register in 0.001
 * kWh steps, the power as drawn at that moment.
 */
internal class MeterTrace(
    private val startKwh: Double,
    private val segments: List<Pair<Instant, Double>>,
    private val interval: Double = 4.25,
    private val phase: Double = 0.0,
) {
    val origin: Instant = segments.first().first
    private val starts = segments.map { seconds(it.first) }

    /** One reading: the meter's [time], its [kwh] register and the [kw] drawn then; [at] is its true time. */
    data class Reading(val at: Double, val time: Instant, val kwh: Double, val kw: Double)

    private fun seconds(time: Instant) = Duration.between(origin, time).toMillis() / 1000.0

    private fun instant(s: Double) = origin.plusMillis((s * 1000).roundToLong())

    fun kwAt(s: Double): Double = segments[starts.indexOfLast { it <= s }.coerceAtLeast(0)].second

    /** The register as the meter counts it, before the 0.001 kWh steps. */
    fun exactKwh(s: Double): Double {
        var kwh = startKwh
        for (i in segments.indices) {
            val from = starts[i]
            val to = minOf(s, starts.getOrElse(i + 1) { Double.MAX_VALUE })
            if (to > from) kwh += segments[i].second * (to - from) / 3600
        }
        return kwh
    }

    fun exactKwh(time: Instant) = exactKwh(seconds(time))

    /** The energy truly drawn in the quarter hour starting at [start]. */
    fun quarterKwh(start: Instant) = exactKwh(start.plusSeconds(QUARTER_SECONDS)) - exactKwh(start)

    /** The draw truly averaged from [from] to [to]. */
    fun averageKw(from: Instant, to: Instant) = (exactKwh(to) - exactKwh(from)) / (seconds(to) - seconds(from)) * 3600

    fun reading(k: Int): Reading {
        val at = phase + k * interval
        val time = origin.plusSeconds(floor(at).toLong())
        // A tiny nudge, so a register exactly on a step isn't floored a step lower by rounding.
        return Reading(at, time, floor(exactKwh(at) * 1000 + 1e-7) / 1000, kwAt(at))
    }

    /** The newest reading by the true second [s]: what `/api/v1/report` answers then. */
    fun latest(s: Double): Reading = reading(floor((s - phase) / interval).toInt().coerceAtLeast(0))

    fun latest(time: Instant) = latest(seconds(time))

    /**
     * The recorder's lines up to [s], as `gridload_recorder.be` writes them: each boundary's
     * register interpolated between the readings either side (at most 30 s apart), rounded to
     * 4 decimals as in the day file. Keyed by the quarter's end, which is where the next begins.
     */
    fun lines(s: Double): Map<Instant, Double> {
        val ends = mutableMapOf<Instant, Double>()
        var previous: Reading? = null
        var seenBoundary: Long? = null
        var k = 0
        while (true) {
            val r = reading(k++)
            if (r.at > s) break
            val p = previous
            previous = r
            if (p == null || r.time <= p.time) continue
            val pt = p.time.epochSecond
            val t = r.time.epochSecond
            val b = (pt / QUARTER_SECONDS + 1) * QUARTER_SECONDS
            if (b > t) continue
            if (t - pt > 30) {
                seenBoundary = null
                continue
            }
            val eb = p.kwh + (r.kwh - p.kwh) * (b - pt) / (t - pt)
            if (seenBoundary == b - QUARTER_SECONDS) ends[Instant.ofEpochSecond(b)] = Math.round(eb * 10_000) / 10_000.0
            seenBoundary = b
        }
        return ends
    }

    /**
     * The app as `WhatwattMeter.read` runs it: the newest reading, the draw ahead ([drawAhead],
     * with [averaged] as Settings → Draw ahead), and the projection from the recorder's last line
     * once the app has copied it, [copyAfter] seconds after the quarter's end. A new [App] is the
     * app just opened.
     */
    inner class App(private val averaged: Boolean = true, private val copyAfter: Long = 20) {
        val average = DrawAverage()
        val projector = QuarterProjector()
        private var lines = emptyMap<Instant, Double>()
        private var linesUntil = Double.NEGATIVE_INFINITY

        /** The newest reading at phone time [s], and the projection the app shows. */
        fun poll(s: Double): Pair<Reading, Projection> {
            val r = latest(s)
            val avg = average.add(r.time, r.kwh)
            // A line is written with the first reading after its boundary, long before it's copied.
            if (s > linesUntil) {
                linesUntil = s + 6 * 3600
                lines = lines(linesUntil)
            }
            val copied = lines.filterKeys { seconds(it) + copyAfter <= s }.maxByOrNull { it.key }?.toPair()
            val (ahead, from) = drawAhead(averaged, avg, r.kw, r.time, r.kwh, copied)
            return r to projector.project(r.time, r.kwh, ahead, copied).copy(aheadFrom = from)
        }

        fun poll(time: Instant) = poll(seconds(time))

        /** Polls every [every] seconds from [from] to [to], and returns the last projection. */
        fun run(from: Instant, to: Instant, every: Long = 5): Projection {
            var t = from
            var last: Projection? = null
            while (t <= to) {
                last = poll(t).second
                t = t.plusSeconds(every)
            }
            return last!!
        }
    }
}
