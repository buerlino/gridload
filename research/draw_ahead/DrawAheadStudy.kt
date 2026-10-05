// The draw-ahead study (research/draw_ahead.md), as run on 2026-10-05. Not compiled: copy it into
// core/src/test/kotlin/io/github/buerlino/gridload/core/ (it needs MeterTrace there) and run
//   STUDY_OUT=/tmp/study.md ./gradlew :core:test --tests '*DrawAheadStudy*'
// then delete the copy again. AVERAGE is the draw ahead as it was then (the latest reading until
// the readings span a minute); "glance" is the app opened seconds before.
package io.github.buerlino.gridload.core

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test

/**
 * The draw-ahead study (Part 2): synthetic household traces through the app's own code, with
 * each policy for the house in the current quarter's remainder ("now") and in the later quarters.
 * Not a regression test: run once with `--tests '*DrawAheadStudy*'`, results in [OUT].
 */
class DrawAheadStudy {
    private val OUT = File(System.getenv("STUDY_OUT") ?: "build/draw-ahead-study.md")
    private val t0 = Instant.parse("2026-10-05T08:00:00Z")
    private val line = 2.87
    private val appliances = listOf(
        Appliance("Kettle 1 L", listOf(Piece(3.067, 1.7707)), canWait = false),
        Appliance("Cooking", listOf(Piece(12.617, 2.335), Piece(15.0, 1.5292), Piece(5.517, 0.2251)), canWait = false),
        Appliance("Dishwasher", listOf(Piece(3.05, 0.023), Piece(15.0, 1.3267), Piece(15.0, 0.8859), Piece(15.0, 0.0), Piece(15.0, 0.7047), Piece(15.0, 0.3359)), canWait = false),
    )

    /** A load on for [from]..[to] seconds at [kw]. */
    private data class On(val from: Double, val to: Double, val kw: Double)

    private fun steady(from: Double, minutes: Double, kw: Double) = listOf(On(from, from + minutes * 60, kw))

    /** Switching on and off at [kw], each on and off time drawn from its range in seconds. */
    private fun cycling(random: Random, from: Double, minutes: Double, kw: Double, on: IntRange, off: IntRange): List<On> {
        val end = from + minutes * 60
        val list = mutableListOf<On>()
        var t = from
        while (t < end) {
            val a = minOf(end, t + random.nextInt(on.first, on.last + 1))
            list += On(t, a, kw)
            t = a + random.nextInt(off.first, off.last + 1)
        }
        return list
    }

    /** The night's base and a fridge: 60 W, and 90 W more for 12 of every 40 minutes. */
    private fun base(random: Random, hours: Double): List<On> {
        val list = mutableListOf(On(0.0, hours * 3600, 0.06))
        var t = random.nextDouble(0.0, 2400.0)
        while (t < hours * 3600) {
            list += On(t, t + 720, 0.09)
            t += 2400
        }
        return list
    }

    /** One household event, from [at] seconds; the minutes it lasts. */
    private class Event(val name: String, val minutes: Double, val loads: (Random, Double) -> List<On>)

    private val events = listOf(
        Event("Quiet (base + fridge)", 0.0) { _, _ -> emptyList() },
        Event("Kettle 2 kW, 3 min", 3.0) { _, at -> steady(at, 3.0, 2.0) },
        Event("Microwave 1.2 kW, 2 min", 2.0) { _, at -> steady(at, 2.0, 1.2) },
        Event("Hob 2 kW cycling 15–30 s, 20 min", 20.0) { r, at -> cycling(r, at, 20.0, 2.0, 15..30, 15..30) },
        Event("Oven: preheat 10 min, cycling 40 min", 50.0) { r, at -> steady(at, 10.0, 2.5) + cycling(r, at + 600, 40.0, 2.5, 30..60, 60..120) },
        Event("Cooking (like 5 Oct 18:15–18:45)", 40.0) { r, at ->
            steady(at, 30.0, 0.45) + cycling(r, at, 28.0, 1.8, 40..90, 10..25) + cycling(r, at + 300, 20.0, 1.5, 15..30, 30..60) +
                cycling(r, at + 28 * 60, 12.0, 1.8, 10..20, 40..60)
        },
        Event("Dishwasher heating phases", 75.0) { _, at ->
            steady(at + 180, 10.0, 2.0) + steady(at + 780, 5.0, 0.1) + steady(at + 1080, 6.0, 2.0) + steady(at + 1440, 9.0, 0.1) +
                steady(at + 2700, 5.0, 2.0) + steady(at + 3000, 10.0, 0.05) + steady(at + 3600, 2.5, 2.0)
        },
        Event("Tumble dryer 2.3 kW cycling, 80 min", 80.0) { r, at -> cycling(r, at, 70.0, 2.3, 60..120, 10..30) + steady(at + 4200, 10.0, 0.2) },
        Event("Oven + hob + kettle (a real new peak)", 50.0) { r, at ->
            steady(at, 10.0, 2.5) + cycling(r, at + 600, 40.0, 2.5, 30..60, 60..120) + cycling(r, at, 20.0, 2.0, 15..30, 15..30) + steady(at + 300, 3.0, 2.0)
        },
    )

    /** Sums the loads into the meter's segments. */
    private fun segments(loads: List<On>): List<Pair<Instant, Double>> {
        val points = (loads.flatMap { listOf(it.from, it.to) } + 0.0).filter { it >= 0 }.distinct().sorted()
        return points.map { p -> t0.plusMillis((p * 1000).toLong()) to loads.filter { it.from <= p && p < it.to }.sumOf { it.kw } }
            .fold(mutableListOf()) { acc, s -> if (acc.lastOrNull()?.second != s.second) acc += s; acc }
    }

    enum class NowPolicy { AVERAGE, LATEST, MIN, SO_FAR, MAX }
    enum class LaterPolicy { AVERAGE, LATEST, LAST_QUARTER, MIN, SO_FAR, PROJECTION, MIN_PROJECTION, SINCE_LAST_START, MIN_SINCE, BASE }

    private class Tally {
        var missed = 0
        var falsely = 0
        var n = 0
        var absError = 0.0
        var over = 0.0
        var under = 0.0
        fun error(e: Double) {
            n++
            absError += abs(e)
            over = maxOf(over, e)
            under = minOf(under, e)
        }
    }

    private fun minutes(polls: Int, runs: Int) = polls * 5.0 / 60 / runs

    @Test
    fun study() {
        val out = StringBuilder()
        out.appendLine("Limit $line kW (the month's highest), appliances: ${appliances.joinToString { it.name }}. Missed / false: minutes per event, averaged over 30 starts across the quarter (every 30 s), polled every 5 s.\n")
        val nowTallies = mutableMapOf<Pair<String, NowPolicy>, Tally>()
        val alarmTallies = mutableMapOf<Pair<String, NowPolicy>, Tally>()
        val laterTallies = mutableMapOf<Pair<String, LaterPolicy>, Tally>()
        val beyondTallies = mutableMapOf<Pair<String, LaterPolicy>, Tally>()
        // A glance: the app opened seconds ago, so no 2-minute average yet; the latest reading stands in.
        val laterGlance = mutableMapOf<Pair<String, LaterPolicy>, Tally>()
        val beyondGlance = mutableMapOf<Pair<String, LaterPolicy>, Tally>()
        val shapes = mutableMapOf<String, String>()
        val runs = 30
        for ((e, event) in events.withIndex()) for (offset in 0 until runs) {
            val random = Random(e * 1000 + offset)
            val at = 3600.0 + 900 + offset * 30.0
            val hours = (at + event.minutes * 60) / 3600 + 3
            val loads = base(random, hours) + event.loads(random, at)
            val meter = MeterTrace(10560.0, segments(loads), phase = random.nextDouble(0.0, 4.25))
            val truths = mutableMapOf<Instant, Double>()
            val truth = { q: Instant -> truths.getOrPut(q) { meter.quarterKwh(q) * 4 } }
            if (offset == 0) {
                val q = quarterStart(t0.plusSeconds(at.toLong()))
                shapes[event.name] = (0 until 4).joinToString(" · ") { "%.2f".format(truth(q.plusSeconds(it * QUARTER_SECONDS))) }
            }
            val lines = meter.lines(hours * 3600)
            val average = DrawAverage()
            val projectors = NowPolicy.entries.associateWith { QuarterProjector() }
            val phase = random.nextDouble(0.0, 5.0)
            // Warm: the app open from an hour before; scored from the event's start (what comes before
            // it can't be foreseen) to an hour after it ends.
            var s = 3600.0 + phase
            val from = at
            val to = at + event.minutes * 60 + 3600
            while (s < to) {
                val r = meter.latest(s)
                val now = t0.plusMillis((s * 1000).toLong())
                val avg = average.add(r.time, r.kwh)
                val aheadA = avg ?: r.kw
                val copied = lines.filterKeys { Duration.between(it, now).seconds >= 20 }
                val newest = copied.maxByOrNull { it.key }?.toPair()
                val qStart = quarterStart(r.time)
                val exact = newest != null && newest.first == qStart
                val elapsed = Duration.between(qStart, r.time).seconds
                val soFar = if (exact && elapsed >= 60) (r.kwh - newest.second) / elapsed * 3600 else aheadA
                val nowAhead = mapOf(
                    NowPolicy.AVERAGE to aheadA, NowPolicy.LATEST to r.kw, NowPolicy.MIN to minOf(aheadA, r.kw),
                    NowPolicy.SO_FAR to soFar, NowPolicy.MAX to maxOf(aheadA, r.kw),
                )
                val projections = nowAhead.mapValues { (p, ahead) -> projectors.getValue(p).project(r.time, r.kwh, ahead, newest) }
                val pA = projections.getValue(NowPolicy.AVERAGE)
                val lastQuarter = newest?.let { (end, reg) -> copied[end.minusSeconds(QUARTER_SECONDS)]?.let { (reg - it) * 4 } } ?: aheadA
                val sinceLastStart = copied[qStart.minusSeconds(QUARTER_SECONDS)]?.takeIf { exact }?.let { reg ->
                    (r.kwh - reg) / (elapsed + QUARTER_SECONDS) * 3600
                } ?: aheadA
                fun later(ahead: Double, p: Projection) = mapOf(
                    LaterPolicy.AVERAGE to ahead, LaterPolicy.LATEST to r.kw, LaterPolicy.LAST_QUARTER to lastQuarter,
                    LaterPolicy.MIN to minOf(ahead, r.kw), LaterPolicy.SO_FAR to soFar, LaterPolicy.PROJECTION to p.kw,
                    LaterPolicy.MIN_PROJECTION to minOf(ahead, p.kw), LaterPolicy.SINCE_LAST_START to sinceLastStart,
                    LaterPolicy.MIN_SINCE to minOf(ahead, sinceLastStart), LaterPolicy.BASE to 0.087,
                )
                if (s >= from) {
                    val phoneQ = quarterStart(now)
                    for ((p, projection) in projections) {
                        val alarm = alarmTallies.getOrPut(event.name to p) { Tally() }
                        val real = truth(projection.start)
                        alarm.error(projection.kw - real)
                        val predicted = isPeakWarning(projection.kw, line)
                        val actual = isPeakWarning(real, line)
                        if (actual && !predicted) alarm.missed++
                        if (predicted && !actual) alarm.falsely++
                        val tally = nowTallies.getOrPut(event.name to p) { Tally() }
                        for (a in appliances) {
                            val first = a.quarterLoads(now, PeakNow(projection, line, line)).first()
                            val pr = isPeakWarning(first.kw, line)
                            val ac = isPeakWarning(truth(first.start) + first.addedKw, line)
                            if (ac && !pr) tally.missed++
                            if (pr && !ac) tally.falsely++
                        }
                    }
                    val pLatest = projections.getValue(NowPolicy.LATEST)
                    for ((glance, values) in listOf(false to later(aheadA, pA), true to later(r.kw, pLatest))) for ((p, house) in values) {
                        val next = phoneQ.plusSeconds(QUARTER_SECONDS)
                        val tally = (if (glance) laterGlance else laterTallies).getOrPut(event.name to p) { Tally() }
                        val beyond = (if (glance) beyondGlance else beyondTallies).getOrPut(event.name to p) { Tally() }
                        tally.error(house - truth(next))
                        beyond.error(house - truth(next.plusSeconds(QUARTER_SECONDS)))
                        val peak = PeakNow((if (glance) pLatest else pA).copy(aheadKw = house), line, line)
                        for (a in appliances) {
                            val rest = a.quarterLoads(now, peak).drop(1)
                            for ((t, loads) in listOf(tally to rest.filter { it.start == next }, beyond to rest.filter { it.start > next })) {
                                val pr = loads.any { isPeakWarning(it.kw, line) }
                                val ac = loads.any { isPeakWarning(truth(it.start) + it.addedKw, line) }
                                if (ac && !pr) t.missed++
                                if (pr && !ac) t.falsely++
                            }
                        }
                    }
                }
                s += 5
            }
        }
        out.appendLine("## The events' quarters (true kW of the house, from the quarter the event starts in, first placement)\n")
        for ((name, shape) in shapes) out.appendLine("- $name: $shape")
        fun <P : Enum<P>> table(title: String, policies: List<P>, tallies: Map<Pair<String, P>, Tally>, errors: Boolean) {
            out.appendLine("\n## $title\n")
            out.appendLine("| Event | " + policies.joinToString(" | ") { it.name } + " |")
            out.appendLine("|---|" + policies.joinToString("|") { "---" } + "|")
            for (event in events) {
                out.appendLine("| ${event.name} | " + policies.joinToString(" | ") { p ->
                    val t = tallies.getValue(event.name to p)
                    val counts = "%.1f / %.1f".format(minutes(t.missed, runs), minutes(t.falsely, runs))
                    if (errors) "$counts<br>±%.2f (+%.2f/%.2f)".format(t.absError / t.n, t.over, t.under) else counts
                } + " |")
            }
            for ((group, names) in listOf(
                "Bursts (kettle, microwave)" to events.slice(1..2), "Sustained (hob, oven, cooking, dishwasher, dryer)" to events.slice(3..7), "All" to events,
            )) out.appendLine("| **$group** | " + policies.joinToString(" | ") { p ->
                val all = names.map { tallies.getValue(it.name to p) }
                "**%.1f / %.1f**".format(minutes(all.sumOf { it.missed }, runs), minutes(all.sumOf { it.falsely }, runs))
            } + " |")
        }
        table("The alarm: this quarter's projection alone (missed / false; ±mean error, max over/under, kW)", NowPolicy.entries, alarmTallies, true)
        table("This quarter with an appliance started now (missed / false, summed over the 3 appliances)", NowPolicy.entries, nowTallies, false)
        table("The next quarter with an appliance started now (missed / false over the 3 appliances; ±mean error of the next quarter's house, max over/under, kW)", LaterPolicy.entries, laterTallies, true)
        table("The quarters after the next (missed / false over the 3 appliances; ±mean error of the house in the quarter after the next, max over/under, kW)", LaterPolicy.entries, beyondTallies, true)
        table("Glance: the next quarter (as above, the app opened seconds ago)", LaterPolicy.entries, laterGlance, true)
        table("Glance: the quarters after the next", LaterPolicy.entries, beyondGlance, true)
        OUT.parentFile.mkdirs()
        OUT.writeText(out.toString())
        println(out)
    }
}
