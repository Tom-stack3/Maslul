package com.maslul.app

import com.maslul.app.data.Combine
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CombineTest {
    private val t0 = Instant.parse("2026-09-28T07:00:00Z")
    private fun at(min: Int) = t0.plusSeconds(min * 60L)
    private fun stop(name: String, km: Double, id: String? = "s$name") = StopCall(id, name, null, 32.0 + km / 111.195, 34.8, null, null)

    private fun bus(line: String, from: String, fromKm: Double, to: String, toKm: Double, start: Int, end: Int, fromId: String? = "s$from") = Leg(
        TransitMode.BUS, stop(from, fromKm, fromId), stop(to, toKm), at(start), at(end), null, null, line, null, null, null,
        null, "trip-$line-$start", null, emptyList(), emptyList(),
    )

    private fun walk(fromKm: Double, toKm: Double, start: Int, end: Int) = Leg(
        TransitMode.WALK, stop("w", fromKm, null), stop("w2", toKm, null), at(start), at(end), 200.0, null, null, null, null, null,
        null, null, null, emptyList(), emptyList(),
    )

    private fun itin(id: String, vararg legs: Leg) =
        Itinerary(id, legs.first().start, legs.last().end, legs.count { it.mode.isTransit } - 1, legs.toList())

    private fun direct(line: String, start: Int, end: Int) =
        itin(line + "@" + start, walk(0.0, 0.2, start - 3, start), bus(line, "A", 0.2, "B", 5.0, start, end), walk(5.0, 5.2, end, end + 3))

    @Test
    fun combinesDifferentLinesBetweenSameStops() {
        val out = Combine.merge(listOf(direct("16", 10, 30), direct("92", 12, 33), direct("5", 15, 34)))
        assertEquals(1, out.size)
        val leg = out.single().transitLegs.single()
        assertEquals("16", leg.lineLabel)
        assertEquals(listOf("16", "92", "5"), leg.options.map { it.lineLabel })
        assertTrue(leg.combined)
        // Each line keeps its own times.
        assertEquals(at(12), leg.alternatives.first { it.lineLabel == "92" }.start)
        assertEquals(at(33), leg.alternatives.first { it.lineLabel == "92" }.end)
    }

    @Test
    fun keepsBestAsMainAndListsOptionsBySoonest() {
        // The best-ranked option comes first even if another line leaves earlier.
        val out = Combine.merge(listOf(direct("92", 12, 25), direct("16", 10, 30)))
        val leg = out.single().transitLegs.single()
        assertEquals("92", leg.lineLabel)
        assertEquals("92@12", out.single().id)
        assertEquals(listOf("16", "92"), leg.options.map { it.lineLabel })
    }

    @Test
    fun laterRunOfSameLineStaysSeparate() {
        val out = Combine.merge(listOf(direct("16", 10, 30), direct("16", 25, 45), direct("92", 12, 33)))
        assertEquals(listOf("16@10", "16@25"), out.map { it.id })
        assertEquals(listOf("16", "92"), out[0].transitLegs.single().options.map { it.lineLabel })
        assertFalse(out[1].transitLegs.single().combined)
    }

    @Test
    fun differentStopsOrFarApartTimesDoNotCombine() {
        val elsewhere = itin("x", bus("7", "C", 1.5, "B", 5.0, 11, 31))
        val muchLater = direct("92", 60, 80)
        assertEquals(3, Combine.merge(listOf(direct("16", 10, 30), elsewhere, muchLater)).size)
    }

    @Test
    fun sameStopAreaCombines() {
        // Different stop ids (opposite platforms) but same name, 80 m apart.
        val a = itin("a", bus("16", "A", 0.2, "B", 5.0, 10, 30))
        val b = itin("b", bus("92", "A", 0.28, "B", 5.0, 12, 32, fromId = "sA-2"))
        assertTrue(Combine.sameStop(a.legs[0].from, b.legs[0].from))
        assertEquals(1, Combine.merge(listOf(a, b)).size)
    }

    @Test
    fun combinesOnlyTheLegThatDiffers() {
        val train = bus("Train", "B", 5.0, "F", 50.0, 40, 70)
        val a = itin("a", bus("16", "A", 0.0, "B", 5.0, 10, 30), walk(5.0, 5.0, 30, 35), train)
        val b = itin("b", bus("92", "A", 0.0, "B", 5.0, 14, 33), walk(5.0, 5.0, 33, 38), train)
        assertEquals(0, Combine.differingLeg(a, b))
        val out = Combine.merge(listOf(a, b)).single()
        assertEquals(listOf("16", "92"), out.legs[0].options.map { it.lineLabel })
        assertFalse(out.legs[2].combined)

        // Different on both rides: two separate journeys.
        val c = itin("c", bus("92", "A", 0.0, "B", 5.0, 14, 33), bus("Train", "B", 5.0, "F", 50.0, 55, 85))
        assertNull(Combine.differingLeg(a, c))
        assertEquals(2, Combine.merge(listOf(a, c)).size)
    }

    @Test
    fun addsFoundRidesThatStillMakeTheConnection() {
        val a = itin("a", bus("16", "A", 0.0, "B", 5.0, 10, 30), walk(5.0, 5.1, 30, 33), bus("Train", "C", 5.1, "F", 50.0, 40, 70))
        val rides = listOf(
            bus("16", "A", 0.0, "B", 5.0, 10, 30), // itself
            bus("92", "A", 0.0, "B", 5.0, 12, 34), // fits: 34 + 3 min walk ≤ 40
            bus("5", "A", 0.0, "B", 5.0, 14, 38), // misses the train
            bus("92", "A", 0.0, "B", 5.0, 13, 35), // second run of 92: kept, the next few of a line are offered
            bus("40", "A", 0.0, "B", 5.0, 2, 22), // scheduled earlier: kept in case it's running late
            bus("41", "A", 0.0, "B", 5.0, -30, -10), // far too early to be merely late
            bus("7", "Z", 3.0, "B", 5.0, 12, 30), // other stop
        )
        val leg = Combine.addAlternatives(a, 0, rides).legs[0]
        assertEquals(listOf("40", "16", "92", "92"), leg.options.map { it.lineLabel })
        assertEquals(listOf(12, 13).map { at(it) }, leg.alternatives.filter { it.lineLabel == "92" }.map { it.start })

        // A later ride's alternatives must leave after the previous one arrives (unless live says they're late).
        val withTrains = Combine.addAlternatives(
            a, 2,
            listOf(bus("Train2", "C", 5.1, "F", 50.0, 31, 60), bus("Train3", "C", 5.1, "F", 50.0, 36, 66)),
        )
        assertEquals(listOf("Train3", "Train"), Combine.catchableOptions(withTrains, 2, at(0)) { null }.map { it.lineLabel })
    }

    @Test
    fun firstRideAlternativesNotInThePast() {
        val a = direct("16", 10, 30)
        val rides = listOf(bus("92", "A", 0.2, "B", 5.0, 8, 29))
        assertEquals(listOf("92", "16"), Combine.catchableOptions(Combine.addAlternatives(a, 1, rides), 1, at(0)) { null }.map { it.lineLabel })
        // Now is 07:07 and the walk to the stop takes 3 min: the 07:08 can't be caught (without live data saying it's late).
        val now = Combine.addAlternatives(a, 1, rides, now = at(7))
        assertEquals(listOf("16"), Combine.catchableOptions(now, 1, at(7)) { null }.map { it.lineLabel })
    }

    @Test
    fun choosingAnotherLineMovesTheTrip() {
        val merged = Combine.merge(listOf(direct("16", 10, 30), direct("92", 12, 33))).single()
        val alt = merged.legs[1].alternatives.single()
        val chosen = Combine.choose(merged, 1, alt)
        assertEquals("92", chosen.legs[1].lineLabel)
        assertEquals(listOf("16", "92"), chosen.legs[1].options.map { it.lineLabel })
        // Walks before and after move with the ride.
        assertEquals(at(9), chosen.start)
        assertEquals(at(36), chosen.end)
        assertEquals(merged.id, chosen.id)
        assertSame(merged, Combine.choose(merged, 1, merged.legs[1]))
    }

    // ---- Catching an earlier bus that's running late ----

    private fun liveAt(l: Leg, board: Int) =
        LiveCall(LiveStatus.LIVE, l.start, at(board), delaySec = (board * 60L) - (l.start.epochSecond - t0.epochSecond))

    /** Planned 16 at 10:12 (min 12); the 10:02 one (min 2) is running late and reaches the stop at min 8. */
    private fun lateCase(): Triple<Itinerary, Leg, Leg> {
        val planned = direct("16", 12, 30)
        val late = bus("16", "A", 0.2, "B", 5.0, 2, 20)
        return Triple(Combine.addAlternatives(planned, 1, listOf(late), now = at(4)), planned.legs[1], late)
    }

    @Test
    fun keepsEarlierScheduledRideOfSameLineAsCandidate() {
        val (itin, _, late) = lateCase()
        assertEquals(listOf("trip-16-2", "trip-16-12"), itin.legs[1].options.map { it.tripId })
        assertEquals(late.start, itin.legs[1].options.first().start)
    }

    @Test
    fun switchesToLateEarlierBusThatComesFirst() {
        val (itin, planned, late) = lateCase()
        val live = mapOf(late.rideKey to liveAt(late, 8), planned.rideKey to liveAt(planned, 12))
        // At min 4 with a 3-minute walk you're at the stop by 7 — the late bus comes at 8.
        val picked = Combine.pickBest(itin, at(4), { live[it.rideKey] })
        assertEquals("trip-16-2", picked.legs[1].tripId)
        assertEquals(listOf("trip-16-12"), picked.legs[1].alternatives.map { it.tripId })
    }

    @Test
    fun ignoresLateBusYouCannotReach() {
        val (itin, planned, late) = lateCase()
        val live = mapOf(late.rideKey to liveAt(late, 8), planned.rideKey to liveAt(planned, 12))
        // At min 7 you'd reach the stop at 10: the late bus is gone by then.
        assertSame(itin, Combine.pickBest(itin, at(7), { live[it.rideKey] }))
        assertEquals(listOf("trip-16-12"), Combine.catchableOptions(itin, 1, at(7)) { live[it.rideKey] }.map { it.tripId })
    }

    @Test
    fun earlierScheduledBusWithoutLiveDataIsNotOffered() {
        val (itin, _, _) = lateCase()
        // No live data: the 10:02 has presumably left, so only the planned bus is catchable.
        assertEquals(listOf("trip-16-12"), Combine.catchableOptions(itin, 1, at(4)) { null }.map { it.tripId })
        assertSame(itin, Combine.pickBest(itin, at(4), { null }))
    }

    @Test
    fun passedBusIsNotOffered() {
        val (itin, planned, late) = lateCase()
        val live = mapOf(late.rideKey to LiveCall(LiveStatus.PASSED, late.start, null), planned.rideKey to liveAt(planned, 12))
        assertEquals(listOf("trip-16-12"), Combine.catchableOptions(itin, 1, at(4)) { live[it.rideKey] }.map { it.tripId })
    }

    @Test
    fun keepsUpToThreeBusesPerLine() {
        val planned = direct("16", 12, 30)
        val more = listOf(14, 18, 22, 26).map { bus("16", "A", 0.2, "B", 5.0, it, it + 18) }
        val leg = Combine.addAlternatives(planned, 1, more, now = at(4)).legs[1]
        assertEquals(listOf(12, 14, 18).map { at(it) }, leg.options.map { it.start })
    }
}
