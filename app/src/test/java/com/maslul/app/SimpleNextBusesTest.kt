package com.maslul.app

import com.maslul.app.data.Combine
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.ui.simple.nextBuses
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SimpleNextBusesTest {
    private val t0 = Instant.parse("2026-09-28T07:00:00Z")
    private fun at(min: Int) = t0.plusSeconds(min * 60L)
    private fun stop(name: String, km: Double) = StopCall("s$name", name, null, 32.0 + km / 111.195, 34.8, null, null)

    private fun bus(line: String, start: Int, from: String = "A", fromKm: Double = 0.2) = Leg(
        TransitMode.BUS, stop(from, fromKm), stop("B", 5.0), at(start), at(start + 20), null, null, line, null, null, null,
        null, "trip-$line-$start", null, emptyList(), emptyList(),
    )

    private fun walk(start: Int, end: Int) = Leg(
        TransitMode.WALK, StopCall(null, "", null, 32.0, 34.8, null, null), stop("A", 0.2), at(start), at(end), 200.0, null, null,
        null, null, null, null, null, null, emptyList(), emptyList(),
    )

    private fun option(first: Leg) = Itinerary("o", at(first.start.epochSecond.toInt() / 60 - t0.epochSecond.toInt() / 60 - 3), first.end, 0,
        listOf(walk(7, 10), first))

    @Test
    fun listsTheBusToTakeAndTheNextOnesSoonestFirst() {
        // Line 18 at :10, line 25 at :15 between the same stops, and the 18s after it from a later lookup.
        val itin = Combine.addAlternatives(option(bus("18", 10)), 1, listOf(bus("25", 15)), at(5))
        val after = listOf(bus("18", 40), bus("18", 70), bus("18", 41, from = "Elsewhere", fromKm = 3.0))
        val buses = nextBuses(itin, at(5), { null }) { after }
        assertEquals(listOf("18" to at(10), "25" to at(15), "18" to at(40)), buses.map { it.leg.lineLabel to it.time })
    }

    @Test
    fun aBusThatCantBeMadeGivesWayToTheNextOne() {
        // At :09 the 3-minute walk gets you there after the :10 bus has gone.
        val itin = option(bus("18", 10))
        val buses = nextBuses(itin, at(9), { null }) { listOf(bus("18", 40), bus("18", 70)) }
        assertEquals(listOf(at(40), at(70)), buses.map { it.time })
    }
}
