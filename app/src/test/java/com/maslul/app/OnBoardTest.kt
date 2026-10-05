package com.maslul.app

import com.maslul.app.data.Departure
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.OnBoard
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.data.TripTimeline
import com.maslul.app.data.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OnBoardTest {
    private val now = Instant.parse("2026-10-05T07:30:00Z")
    private fun at(min: Int) = now.plusSeconds(min * 60L)
    private fun p(km: Double) = GeoPoint(32.0 + km / 111.195, 34.8)

    /** Stops every 1 km northward, one minute apart, starting at minute [first]. */
    private fun calls(first: Int, n: Int = 6) = (0 until n).map { i ->
        StopCall("s$i", "Stop $i", "$i", p(i.toDouble()).lat, 34.8, at(first + i), at(first + i))
    }

    private fun timeline(c: List<StopCall>) = TripTimeline(c.map { it.point }, LongArray(c.size) { it * 60L }, c.map { it.point })

    @Test
    fun nextStopIsTheOneAheadOfWhereYouAre() {
        val c = calls(-3)
        // 2.4 km along: past stop 2, so stop 3 is next — whatever the timetable says.
        assertEquals(3, OnBoard.nextStop(c, timeline(c), p(2.4), null, now))
        // Pulling in to a stop counts as there: plan from the one after.
        assertEquals(3, OnBoard.nextStop(c, timeline(c), p(1.99), null, now))
        assertEquals(2, OnBoard.nextStop(c, timeline(c), p(1.9), null, now))
    }

    @Test
    fun withoutAFixOnTheRouteItFallsBackToTheTimetable() {
        val c = calls(-3)
        // Far off the route (a bad fix): by time, stops 0–3 are due by now, so stop 4 is next.
        assertEquals(4, OnBoard.nextStop(c, timeline(c), GeoPoint(31.0, 35.5), null, now))
        assertEquals(4, OnBoard.nextStop(c, null, null, null, now))
        // After the last stop's time, the last stop.
        assertEquals(5, OnBoard.nextStop(c, null, null, null, at(30)))
    }

    private fun vehicle(km: Double, line: String = "100", journey: String = "1001", ageSec: Long = 30) = Vehicle(
        line, journey, Instant.parse("2026-10-05T07:00:00Z"), p(km).lat, p(km).lon, null, null, now.minusSeconds(ageSec), null, null, "v$journey",
    )

    private fun departure(trip: String, line: String, scheduled: Instant, stopKm: Double = 0.2) = Departure(
        tripId = trip, routeId = "il-Israel-MOT_$line", mode = TransitMode.BUS, lineLabel = line, headsign = "Haifa", agency = null,
        routeColor = null, scheduled = scheduled, stop = StopCall("x", "Here", null, p(stopKm).lat, 34.8, null, scheduled),
    )

    @Test
    fun aRideTrackedFarAwayIsntYours() {
        val trip = "20261005_10:00_il-Israel-MOT_1001_051026"
        val far = mapOf("100" to listOf(vehicle(20.0)))
        val near = mapOf("100" to listOf(vehicle(0.3)))
        assertTrue(OnBoard.fromDepartures(p(0.0), listOf(200.0 to departure(trip, "100", at(-2))), far, now).isEmpty())
        val c = OnBoard.fromDepartures(p(0.0), listOf(200.0 to departure(trip, "100", at(-2))), near, now).single()
        assertTrue(c.live)
        assertEquals(300.0, c.distanceM, 5.0)
    }

    @Test
    fun timetableRidesMustBeDueAroundNow() {
        val deps = listOf(
            200.0 to departure("20261005_10:00_il-Israel-MOT_1_051026", "7", at(-5)),
            200.0 to departure("20261005_10:00_il-Israel-MOT_2_051026", "8", at(-60)),
            200.0 to departure("20261005_10:00_il-Israel-MOT_3_051026", "9", at(40)),
            1500.0 to departure("20261005_10:00_il-Israel-MOT_4_051026", "10", at(0)),
        )
        val out = OnBoard.fromDepartures(p(0.0), deps, emptyMap(), now)
        assertEquals(listOf("7"), out.map { it.lineLabel })
        assertNull(out.single().vehicle)
    }

    @Test
    fun liveVehiclesNearestFirstThenTheTimetable() {
        val deps = listOf(
            100.0 to departure("20261005_10:00_il-Israel-MOT_1_051026", "7", at(0)),
            300.0 to departure("20261005_10:00_il-Israel-MOT_1001_051026", "100", at(-1)),
        )
        val ranked = OnBoard.rank(OnBoard.fromDepartures(p(0.0), deps, mapOf("100" to listOf(vehicle(0.4))), now), now)
        assertEquals(listOf("100", "7"), ranked.map { it.lineLabel })
    }

    @Test
    fun oldPositionsReachFurther() {
        // A position two minutes old may be a kilometre and a half behind the bus you're on.
        val fresh = vehicle(0.0, ageSec = 10)
        val old = vehicle(0.0, ageSec = 120)
        assertTrue(OnBoard.reach(old, now) > OnBoard.reach(fresh, now) + 1000)
        val byLine = mapOf("1" to listOf(vehicle(1.5, "1", "a", ageSec = 120)), "2" to listOf(vehicle(1.5, "2", "b", ageSec = 5)))
        assertEquals(listOf("1"), OnBoard.nearVehicles(p(0.0), byLine, now).map { it.lineRef })
    }

    @Test
    fun triesEveryStopLeftOnAShortRideAndASpreadOnALongOne() {
        val short = calls(0, 8)
        assertEquals(listOf(3, 4, 5, 6, 7), OnBoard.alightStops(short, 2, p(100.0)))
        val long = calls(0, 40)
        val picks = OnBoard.alightStops(long, 1, p(23.2))
        assertTrue(picks.size <= 12)
        assertTrue(23 in picks)
        assertEquals(39, picks.last())
        assertTrue(picks.all { it > 1 })
    }

    @Test
    fun stayingOnIsTheTripBetweenTwoOfItsStops() {
        val c = calls(0, 6)
        val trip = com.maslul.app.data.Leg(TransitMode.BUS, c.first(), c.last(), at(0), at(5), null, "Haifa", "480", null, null, null,
            null, "trip-480", null, c.subList(1, 5), c.map { it.point })
        val ride = OnBoard.ride(trip, c, timeline(c), 2, 4)
        assertEquals("Stop 2", ride.from.name)
        assertEquals("Stop 4", ride.to.name)
        assertEquals(at(2), ride.start)
        assertEquals(at(4), ride.end)
        assertEquals(listOf("Stop 3"), ride.intermediateStops.map { it.name })
        assertEquals("trip-480", ride.tripId)
        val itin = OnBoard.compose(ride, null)
        assertEquals(0, itin.transfers)
        assertEquals(at(4), itin.end)
    }

    @Test
    fun theBusIsAtLeastAsFarAsItsLastReport() {
        val c = calls(-3)
        val t = timeline(c)
        // Your fix matches just before stop 2; the bus last reported past stop 3.
        val progress = TripTimeline.Progress(vehicle(3.5), 3500.0, 0, 3, List(c.size) { null }, false)
        assertEquals(4, OnBoard.nextStop(c, t, p(1.5), progress, now))
        // And when your fix is the further one, it wins.
        assertEquals(5, OnBoard.nextStop(c, t, p(4.5), progress, now))
    }

    @Test
    fun aTrainSignedWithItsNumberHasNoDestination() {
        assertEquals("", OnBoard.placeName("785"))
        assertEquals("Haifa", OnBoard.placeName("Haifa"))
    }
}
