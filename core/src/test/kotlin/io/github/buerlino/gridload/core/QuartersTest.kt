package io.github.buerlino.gridload.core

import java.nio.file.Files
import java.time.Instant
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuartersTest {
    private fun at(time: String) = Instant.parse("2026-10-02T${time}Z")

    @Test
    fun interpolatesTheRegisterAtTheBoundaries() {
        val recorder = QuarterRecorder()
        assertNull(recorder.add(at("15:59:56"), 100.000))
        // First boundary seen at 16:00: 100.000 + 0.004 * 4/8; no quarter yet.
        assertNull(recorder.add(at("16:00:04"), 100.004))
        assertNull(recorder.add(at("16:07:00"), 100.300))
        assertNull(recorder.add(at("16:14:58"), 100.600))
        // 16:15: 100.600 + 0.010 * 2/5 = 100.604, minus 100.002.
        assertEquals(Quarter(at("16:00:00"), 0.602), recorder.add(at("16:15:03"), 100.610))
    }

    @Test
    fun aReadingOnTheBoundaryCounts() {
        val recorder = QuarterRecorder()
        recorder.add(at("15:59:56"), 100.000)
        recorder.add(at("16:00:00"), 100.002)
        recorder.add(at("16:14:56"), 100.500)
        assertEquals(Quarter(at("16:00:00"), 0.5), recorder.add(at("16:15:00"), 100.502))
    }

    @Test
    fun repeatedReadingsAreIgnored() {
        val recorder = QuarterRecorder()
        recorder.add(at("15:59:56"), 100.000)
        recorder.add(at("16:00:04"), 100.004)
        recorder.add(at("16:14:58"), 100.600)
        assertNull(recorder.add(at("16:14:58"), 100.600))
        assertEquals(Quarter(at("16:00:00"), 0.602), recorder.add(at("16:15:03"), 100.610))
    }

    @Test
    fun aGapAtABoundaryLeavesBothQuartersOut() {
        val recorder = QuarterRecorder()
        recorder.add(at("15:59:56"), 100.000)
        recorder.add(at("16:00:04"), 100.004)
        recorder.add(at("16:14:00"), 100.500)
        assertNull(recorder.add(at("16:16:00"), 100.600)) // 2 min apart: 16:15 is unknown
        recorder.add(at("16:29:58"), 101.000)
        assertNull(recorder.add(at("16:30:02"), 101.004)) // 16:15 to 16:30 has no start
        recorder.add(at("16:44:58"), 101.500)
        assertEquals(Quarter(at("16:30:00"), 0.5), recorder.add(at("16:45:02"), 101.504))
    }

    @Test
    fun aRegisterGoingBackwardsStartsOver() {
        val recorder = QuarterRecorder()
        recorder.add(at("15:59:56"), 100.000)
        recorder.add(at("16:00:04"), 100.004)
        assertNull(recorder.add(at("16:14:58"), 5.000))
        assertNull(recorder.add(at("16:15:02"), 5.004))
        recorder.add(at("16:29:58"), 5.500)
        assertEquals(Quarter(at("16:15:00"), 0.5), recorder.add(at("16:30:02"), 5.504))
    }

    @Test
    fun storesOneFilePerLocalMonth() {
        val dir = Files.createTempDirectory("quarters").toFile()
        try {
            val store = QuarterStore(dir)
            // 30 Sep 22:00 UTC is already October in Zurich.
            store.add(Quarter(Instant.parse("2026-09-30T22:00:00Z"), 0.25))
            store.add(Quarter(Instant.parse("2026-09-30T21:45:00Z"), 0.5))
            store.add(Quarter(Instant.parse("2026-09-30T22:15:00Z"), 0.125))
            store.add(Quarter(Instant.parse("2026-09-30T22:00:00Z"), 0.3)) // replaces
            assertEquals(
                """{"2026-10-01T00:00+02:00":0.3,"2026-10-01T00:15+02:00":0.125}""",
                dir.resolve("2026-10.json").readText(),
            )
            assertEquals(
                listOf(Quarter(Instant.parse("2026-09-30T22:00:00Z"), 0.3), Quarter(Instant.parse("2026-09-30T22:15:00Z"), 0.125)),
                store.month(YearMonth.of(2026, 10)),
            )
            assertEquals(listOf(Quarter(Instant.parse("2026-09-30T21:45:00Z"), 0.5)), store.month(YearMonth.of(2026, 9)))
            assertEquals(emptyList(), store.month(YearMonth.of(2026, 11)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
