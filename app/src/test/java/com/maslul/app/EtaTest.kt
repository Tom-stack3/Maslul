package com.maslul.app

import com.maslul.app.data.GeoPoint
import com.maslul.app.data.LiveCalls
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.TripTimeline
import com.maslul.app.data.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EtaTest {
    // Five stops 1 km apart due north; 2 minutes between stops.
    private val kmLat = 1000.0 / 111_195.0
    private val stops = (0..4).map { GeoPoint(32.0 + it * kmLat, 34.8) }
    private val timeline = TripTimeline(stops, longArrayOf(0, 120, 240, 360, 480), stops)
    private val origin = Instant.parse("2026-09-27T18:00:00Z")

    private fun vehicle(kmAlong: Double, recordedAt: Instant, dist: Double? = kmAlong * 1000) = Vehicle(
        lineRef = "1", journeyRef = "42", originDeparture = origin,
        lat = 32.0 + kmAlong * kmLat, lon = 34.8, bearing = 0f, velocityKmh = 30,
        recordedAt = recordedAt, distanceFromStart = dist, nextStopCode = null, vehicleRef = "v",
    )

    /** Instants within a couple of seconds (projection vs. haversine rounding). */
    private fun near(expected: Instant, actual: Instant?) {
        assertTrue("expected ~$expected but was $actual", actual != null && Math.abs(actual.epochSecond - expected.epochSecond) <= 3)
    }

    private fun near(expected: Long, actual: Long) = assertTrue("expected ~$expected but was $actual", Math.abs(actual - expected) <= 3)

    @Test
    fun stopsAreLocatedAlongTheShape() {
        assertEquals(0.0, timeline.stopAlong[0], 1.0)
        assertEquals(2000.0, timeline.stopAlong[2], 5.0)
        assertEquals(4000.0, timeline.stopAlong[4], 10.0)
    }

    @Test
    fun lateVehicleShiftsDownstreamPredictions() {
        // At 1.5 km (scheduled +180 s) but reported at +240 s → one minute late.
        val t = origin.plusSeconds(240)
        val p = timeline.estimate(vehicle(1.5, t), origin, t)!!
        near(60, p.delaySec)
        assertEquals(1, p.lastPassed)
        assertNull(p.predicted[1])
        near(origin.plusSeconds(300), p.predicted[2])
        near(origin.plusSeconds(540), p.predicted[4])
        assertEquals(1, p.stopsAway(2))
    }

    @Test
    fun earlyVehicleIsNotPredictedInThePast() {
        // At 3 km (scheduled +360 s) at +300 s → one minute early.
        val t = origin.plusSeconds(300)
        val p = timeline.estimate(vehicle(3.0, t), origin, t)!!
        near(-60, p.delaySec)
        near(origin.plusSeconds(420), p.predicted[4])
    }

    @Test
    fun waitingAtTerminalIsOnTimeUntilDeparture() {
        val t = origin.minusSeconds(120)
        val p = timeline.estimate(vehicle(0.0, t, 0.0), origin, t)!!
        assertEquals(0, p.delaySec)
        assertEquals(-1, p.lastPassed)
        assertEquals(origin.plusSeconds(120), p.predicted[1])
    }

    @Test
    fun lateDepartureFromTerminal() {
        val t = origin.plusSeconds(180)
        val p = timeline.estimate(vehicle(0.0, t, 0.0), origin, t)!!
        assertEquals(180, p.delaySec)
        assertEquals(origin.plusSeconds(300), p.predicted[1])
    }

    @Test
    fun usesReportedDistanceWhenPositionIsOffRoute() {
        val t = origin.plusSeconds(240)
        val off = vehicle(1.5, t).copy(lon = 34.9) // ~9 km east
        val p = timeline.estimate(off, origin, t)!!
        assertEquals(1500.0, p.alongM, 1.0)
    }

    @Test
    fun liveCallStatuses() {
        val t = origin.plusSeconds(240)
        val v = vehicle(1.5, t)
        val live = LiveCalls.compute(timeline, origin, 3, 4, v, t)
        assertEquals(LiveStatus.LIVE, live.status)
        near(origin.plusSeconds(420), live.expected)
        near(origin.plusSeconds(540), live.alightExpected)
        assertEquals(2, live.stopsAway)

        assertEquals(LiveStatus.PASSED, LiveCalls.compute(timeline, origin, 1, 4, v, t).status)
        assertEquals(LiveStatus.SCHEDULED, LiveCalls.compute(timeline, origin.plusSeconds(600), 1, null, null, t).status)
        assertEquals(LiveStatus.UNTRACKED, LiveCalls.compute(timeline, origin, 1, null, null, t).status)
        assertTrue(live.best.isAfter(t))
    }
}
