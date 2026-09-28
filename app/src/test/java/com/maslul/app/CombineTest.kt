package com.maslul.app

import com.maslul.app.data.Combine
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
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
            bus("92", "A", 0.0, "B", 5.0, 13, 35), // second run of 92: only the soonest is kept
            bus("40", "A", 0.0, "B", 5.0, 2, 22), // leaves too early
            bus("7", "Z", 3.0, "B", 5.0, 12, 30), // other stop
        )
        val leg = Combine.addAlternatives(a, 0, rides).legs[0]
        assertEquals(listOf("16", "92"), leg.options.map { it.lineLabel })
        assertEquals(at(12), leg.alternatives.single().start)

        // A later ride's alternatives must leave after the previous one arrives.
        val later = Combine.addAlternatives(
            a, 2,
            listOf(bus("Train2", "C", 5.1, "F", 50.0, 32, 60), bus("Train3", "C", 5.1, "F", 50.0, 36, 66)),
        ).legs[2]
        assertEquals(listOf("Train3", "Train"), later.options.map { it.lineLabel })
    }

    @Test
    fun firstRideAlternativesNotInThePast() {
        val a = direct("16", 10, 30)
        val rides = listOf(bus("92", "A", 0.2, "B", 5.0, 8, 29))
        assertTrue(Combine.addAlternatives(a, 1, rides).legs[1].combined)
        // Now is 07:07 and the walk to the stop takes 3 min: the 07:08 can't be caught.
        assertFalse(Combine.addAlternatives(a, 1, rides, now = at(7)).legs[1].combined)
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
}
