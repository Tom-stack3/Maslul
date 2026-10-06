package com.maslul.app

import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.live.GuideAlert
import com.maslul.app.live.TripGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TripGuideTest {
    private val kmLat = 1000.0 / 111_195.0
    private fun pt(km: Double) = GeoPoint(32.0 + km * kmLat, 34.8)
    private val t0 = Instant.parse("2026-09-27T18:00:00Z")

    private fun call(name: String, km: Double, sec: Long) =
        StopCall("s$name", name, null, pt(km).lat, pt(km).lon, t0.plusSeconds(sec), t0.plusSeconds(sec))

    // Walk 300 m to stop A, then a bus A → E with stops every km.
    private val walk = Leg(
        TransitMode.WALK, StopCall(null, "", null, pt(-0.3).lat, pt(-0.3).lon, null, null), call("A", 0.0, 240),
        t0, t0.plusSeconds(240), 300.0, null, null, null, null, null, null, null, null, emptyList(),
        listOf(pt(-0.3), pt(0.0)),
    )
    private val bus = Leg(
        TransitMode.BUS, call("A", 0.0, 300), call("E", 4.0, 780), t0.plusSeconds(300), t0.plusSeconds(780), null,
        "E", "5", null, null, null, "Dan", "t", "il-Israel-MOT_1",
        listOf(call("B", 1.0, 420), call("C", 2.0, 540), call("D", 3.0, 660)),
        listOf(pt(0.0), pt(4.0)),
    )
    private val itin = Itinerary("x", t0, t0.plusSeconds(780), 0, listOf(walk, bus))

    @Test
    fun followsTheTrip() {
        val g = TripGuide(itin, "Home")
        val s0 = g.update(pt(-0.3), t0, emptyMap())
        assertEquals(0, s0.legIndex)
        assertEquals("Walk to A", s0.title)

        val s1 = g.update(pt(0.5), t0.plusSeconds(360), emptyMap())
        assertEquals(1, s1.legIndex)
        assertTrue(s1.text.startsWith("4 stops"))

        val s2a = g.update(pt(2.5), t0.plusSeconds(600), emptyMap())
        assertTrue(s2a.text.startsWith("2 stops"))

        val s2 = g.update(pt(3.2), t0.plusSeconds(680), emptyMap())
        assertEquals(GuideAlert.GET_OFF_NEXT, s2.alert)
        assertEquals("E", s2.text)

        val s3 = g.update(pt(3.995), t0.plusSeconds(780), emptyMap())
        assertEquals(GuideAlert.ARRIVED, s3.alert)
        assertTrue(s3.finished)
    }

    @Test
    fun boardingAlertWhenRideIsClose() {
        val g = TripGuide(itin, "Home")
        val s = g.update(pt(-0.1), t0.plusSeconds(150), emptyMap())
        assertEquals(GuideAlert.BOARD_SOON, s.alert)
        assertEquals("board-1", s.alertKey)
    }

    @Test
    fun ignoresGpsJumpsBackwards() {
        val g = TripGuide(itin, "Home")
        g.update(pt(3.2), t0.plusSeconds(680), emptyMap())
        val s = g.update(pt(0.2), t0.plusSeconds(690), emptyMap())
        assertEquals(1, s.legIndex)
        assertEquals(GuideAlert.GET_OFF_NEXT, s.alert)
    }

    @Test
    fun aLiftReadsAsARideNotAWalk() {
        // A 3 km lift, then the bus from A.
        val car = walk.copy(mode = TransitMode.CAR, from = StopCall(null, "", null, pt(-3.0).lat, pt(-3.0).lon, null, null),
            start = t0.minusSeconds(300), distanceM = 3000.0, geometry = listOf(pt(-3.0), pt(-0.3)), fixed = true)
        val g = TripGuide(Itinerary("c", car.start, bus.end, 0, listOf(car, walk, bus)), "Home")
        val s = g.update(pt(-2.0), t0.minusSeconds(200), emptyMap())
        assertEquals(0, s.legIndex)
        assertEquals("Get dropped off near A", s.title)
        assertEquals("Walk to A", g.update(pt(-0.2), t0.plusSeconds(100), emptyMap()).title)
    }
}
