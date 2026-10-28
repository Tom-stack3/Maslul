package com.maslul.app

import com.maslul.app.data.AppJson
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.PastSearch
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.data.Vehicle
import com.maslul.app.data.WalkStep
import com.maslul.app.data.with
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PastSearchTest {
    private val t0 = Instant.parse("2026-10-28T07:00:00Z")
    private fun at(min: Int) = t0.plusSeconds(min * 60L)
    private val a = StopCall("sA", "Stop A", "21", 32.0, 34.8, at(10), at(11), track = "3")
    private val b = StopCall("sB", "Stop B", null, 32.05, 34.8, at(30), null)
    private val home = Place("Home street 1", 32.0, 34.79, kind = PlaceKind.PIN)
    private val work = Place("Work tower", 32.05, 34.81)

    private fun bus(line: String, start: Int) = Leg(
        TransitMode.BUS, a, b, at(start), at(start + 20), 5000.0, "Center", line, null, 0x1E88E5, null, "Dan",
        "trip-$line", "route-$line", listOf(a.copy(name = "Middle")), listOf(GeoPoint(32.0, 34.8), GeoPoint(32.05, 34.8)),
        alerts = listOf("Detour"), agencyId = "5",
    )

    private fun search(to: Place, line: String = "16") = PastSearch(
        from = home, to = to, at = t0, time = at(5), arriveBy = false,
        itineraries = listOf(Itinerary("i$line", at(5), at(35), 0, listOf(
            Leg(TransitMode.WALK, a, a, at(5), at(10), 300.0, null, null, null, null, null, null, null, null,
                emptyList(), emptyList(), steps = listOf(WalkStep("Turn left", 120.0))),
            bus(line, 10).copy(alternatives = listOf(bus("92", 14))),
        ))),
        live = mapOf("trip-16" to LiveCall(LiveStatus.LIVE, at(10), at(13), delaySec = 180, stopsAway = 4,
            vehicle = Vehicle("route-16", "j1", at(0), 32.01, 34.8, 90f, 30, at(4), 1200.0, "21", "v7"))),
    )

    @Test
    fun keepsTheOptionsAsTheyWere() {
        val s = search(work)
        val back = AppJson.decodeFromString<PastSearch>(AppJson.encodeToString(s))
        assertEquals(s, back)
        // The bus that had already left by now is still there, with the other line that was offered.
        assertEquals(listOf("16", "92"), back.itineraries.single().transitLegs.single().options.map { it.lineLabel })
        assertEquals(at(10), back.itineraries.single().legs.last().start)
        // And the bus running late, as the times shown followed it.
        assertEquals(at(13), back.live.getValue("trip-16").expected)
    }

    @Test
    fun oneSearchPerDestinationNewestFirst() {
        val other = Place("Gym", 32.1, 34.9)
        val list = emptyList<PastSearch>().with(search(work, "16")).with(search(other)).with(search(work, "5"))
        assertEquals(listOf(work.key, other.key), list.map { it.key })
        assertEquals("5", list.first().itineraries.single().transitLegs.single().lineLabel)
        assertEquals(3, (1..5).fold(emptyList<PastSearch>()) { acc, i -> acc.with(search(Place("P$i", 32.0, 34.0 + i)), max = 3) }.size)
    }
}
