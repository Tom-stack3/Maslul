package com.maslul.app

import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.Ranking
import com.maslul.app.data.Reroute
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.live.LiveTripService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RerouteTest {
    private val t0 = Instant.parse("2026-10-22T05:00:00Z")
    private fun at(min: Int) = t0.plusSeconds(min * 60L)
    private fun stop(name: String, km: Double) = StopCall("s$name", name, null, 32.0 + km / 111.195, 34.8, null, null)

    private fun bus(line: String, from: String, fromKm: Double, to: String, toKm: Double, start: Int, end: Int) = Leg(
        TransitMode.BUS, stop(from, fromKm), stop(to, toKm), at(start), at(end), null, null, line, null, null, null,
        null, "trip-$line-$start", null, emptyList(), emptyList(),
    )

    private fun itin(id: String, vararg legs: Leg) =
        Itinerary(id, legs.first().start, legs.last().end, legs.count { it.mode.isTransit } - 1, legs.toList())

    /** Bus A to the change at C, then bus B on. */
    private val a = bus("A", "Home", 0.0, "C", 5.0, 10, 25)
    private val b = bus("B", "C", 5.0, "Work", 10.0, 30, 45)
    private val current = itin("cur", a, b)

    /** [leg] tracked live, [lateMin] behind its timetable. */
    private fun late(leg: Leg, lateMin: Long) = LiveCall(
        LiveStatus.LIVE, leg.start, leg.start.plusSeconds(lateMin * 60), lateMin * 60,
        alightExpected = leg.end.plusSeconds(lateMin * 60),
    )

    @Test
    fun aLateFirstBusMissesTheChange() {
        // A gets to C at 25; B leaves at 30: on time it connects.
        assertNull(LiveTripService.missedConnection(current, at(12)) { null })
        // Running 8 minutes late, A gets to C at 33, after B has gone.
        val calls = mapOf(a.rideKey to late(a, 8))
        assertEquals(1, LiveTripService.missedConnection(current, at(12)) { calls[it.rideKey] })
    }

    @Test
    fun offersOtherWaysThatCanStillBeMade() {
        val calls = mapOf(a.rideKey to late(a, 8))
        val liveOf = { l: Leg -> calls[l.rideKey] }
        val score = { i: Itinerary -> Ranking.score(i, false) }
        // Staying on A and changing to the next B at 40: arrives 55.
        val nextB = itin("nextB", a, bus("B", "C", 5.0, "Work", 10.0, 40, 55))
        // Another line straight there from where you are, at 15: arrives 50.
        val direct = itin("direct", bus("D", "Home", 0.0, "Work", 10.0, 15, 50))
        // A bus that's gone already.
        val gone = itin("gone", bus("E", "Home", 0.0, "Work", 10.0, 5, 35))
        val chosen = Reroute.choose(current, listOf(current, nextB, direct, gone), at(12), riding = a.tripId, liveOf = liveOf, score = score)
        // The current way itself isn't an "other" way, and a missed one isn't offered.
        assertEquals(listOf("direct", "nextB"), chosen.map { it.id })
    }

    @Test
    fun atMostTwo() {
        val score = { i: Itinerary -> Ranking.score(i, false) }
        val ways = (0 until 5).map { k -> itin("w$k", bus("L$k", "Home", 0.0, "Work", 10.0, 15 + k, 50 + k)) }
        assertEquals(listOf("w0", "w1"), Reroute.choose(current, ways, at(12), null, { null }, score).map { it.id })
    }
}
