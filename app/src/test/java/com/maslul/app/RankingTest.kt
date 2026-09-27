package com.maslul.app

import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.Ranking
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RankingTest {
    private val t0 = Instant.parse("2026-09-27T21:00:00Z")
    private fun at(min: Int) = t0.plusSeconds(min * 60L)
    private fun stop(name: String, km: Double) = StopCall("s$name", name, null, 32.0 + km / 111.195, 34.8, null, null)

    private fun bus(line: String, from: String, fromKm: Double, to: String, toKm: Double, start: Int, end: Int) = Leg(
        TransitMode.BUS, stop(from, fromKm), stop(to, toKm), at(start), at(end), null, null, line, null, null, null,
        null, "trip-$line-$start", null, emptyList(), emptyList(),
    )

    private fun walk(fromKm: Double, toKm: Double, start: Int, end: Int, meters: Double) = Leg(
        TransitMode.WALK, stop("w", fromKm), stop("w2", toKm), at(start), at(end), meters, null, null, null, null, null,
        null, null, null, emptyList(), emptyList(),
    )

    private fun itin(id: String, vararg legs: Leg) =
        Itinerary(id, legs.first().start, legs.last().end, legs.count { it.mode.isTransit } - 1, legs.toList())

    // Night trip from the bug report: three buses with short walks between them…
    private val threeBuses = itin(
        "22-143-56",
        walk(0.0, 0.3, 0, 4, 300.0),
        bus("22", "A", 0.3, "B", 3.0, 4, 14),
        walk(3.0, 3.15, 14, 16, 150.0),
        bus("143", "C", 3.15, "D", 5.0, 16, 22),
        walk(5.0, 5.3, 22, 26, 300.0),
        bus("56", "E", 5.3, "F", 8.0, 26, 34),
        walk(8.0, 8.2, 34, 37, 200.0),
    )

    // …versus a direct bus arriving a few minutes later.
    private val direct = itin(
        "64",
        walk(0.0, 0.3, 2, 6, 300.0),
        bus("64", "A", 0.3, "F", 8.0, 6, 38),
        walk(8.0, 8.2, 38, 41, 200.0),
    )

    @Test
    fun measuresSlackAfterWalking() {
        val t = Ranking.transfers(threeBuses)
        assertEquals(2, t.size)
        // 150 m at 1.3 m/s ≈ 115 s of the 2 min connection → 5 s spare.
        assertEquals(5L, t[0].slackSec)
        assertTrue(t[0].tight && t[0].walking)
        assertEquals("143", t[0].next.lineLabel)
        assertEquals(Ranking.transfers(threeBuses).minBy { it.slackSec }, Ranking.riskiest(threeBuses))
        assertNull(Ranking.riskiest(direct))
    }

    @Test
    fun comfortableSameStopTransferIsNotTight() {
        val it = itin("x", bus("1", "A", 0.0, "B", 2.0, 0, 10), bus("2", "B", 2.0, "C", 4.0, 16, 25))
        val t = Ranking.transfers(it).single()
        assertEquals(360L, t.slackSec)
        assertFalse(t.tight || t.walking)
    }

    @Test
    fun directBeatsRiskyChainArrivingSlightlyEarlier() {
        assertTrue(threeBuses.end < direct.end)
        val ranked = Ranking.rank(listOf(threeBuses, direct), arriveBy = false)
        assertEquals(listOf("64", "22-143-56"), ranked.map { it.id })
    }

    @Test
    fun muchFasterTransferStillWins() {
        val fast = itin(
            "fast",
            bus("1", "A", 0.0, "B", 2.0, 0, 5),
            bus("2", "B", 2.0, "F", 8.0, 12, 20),
        )
        assertEquals("fast", Ranking.rank(listOf(direct, fast), arriveBy = false).first().id)
    }

    @Test
    fun dropsDominatedOptions() {
        // Leaves earlier, arrives later, more transfers: strictly worse than the direct bus.
        val worse = itin(
            "worse",
            walk(0.0, 0.3, 0, 4, 300.0),
            bus("7", "A", 0.3, "B", 3.0, 4, 20),
            bus("8", "B", 3.0, "F", 8.0, 26, 45),
            walk(8.0, 8.2, 45, 50, 200.0),
        )
        // A later departure of the direct line is a different option and must stay.
        val laterDirect = itin("64b", walk(0.0, 0.3, 12, 16, 300.0), bus("64", "A", 0.3, "F", 8.0, 16, 48))
        assertTrue(Ranking.dominates(direct, worse))
        assertFalse(Ranking.dominates(direct, laterDirect))
        val ids = Ranking.rank(listOf(worse, direct, laterDirect), arriveBy = false).map { it.id }
        assertEquals(listOf("64", "64b"), ids)
    }

    @Test
    fun arriveByPrefersLaterDeparture() {
        val early = itin("early", bus("1", "A", 0.0, "F", 8.0, 0, 30))
        val late = itin("late", bus("2", "A", 0.0, "F", 8.0, 10, 32))
        assertEquals("late", Ranking.rank(listOf(early, late), arriveBy = true).first().id)
    }

    @Test
    fun walkWithoutDistanceUsesStraightLine() {
        val w = walk(2.0, 2.4, 10, 13, 0.0)
        val it = itin("x", bus("1", "A", 0.0, "B", 2.0, 0, 10), w, bus("2", "C", 2.4, "D", 4.0, 13, 20))
        val t = Ranking.transfers(it).single()
        assertTrue(t.walkM > 400 && t.tight)
    }
}
