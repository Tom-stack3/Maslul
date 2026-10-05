package com.maslul.app

import com.maslul.app.data.Combine
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.Ranking
import com.maslul.app.data.RidePlanner
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RidesTest {
    private val t0 = Instant.parse("2026-10-05T07:00:00Z")
    private fun at(min: Int) = t0.plusSeconds(min * 60L)
    /** A point [km] north of the start. */
    private fun p(km: Double) = GeoPoint(32.0 + km / 111.195, 34.8)
    private fun stop(name: String, km: Double, id: String? = null) = StopCall(id, name, null, p(km).lat, p(km).lon, null, null)

    /** A drive due north, [km] long, taking [min] minutes. */
    private fun drive(km: Double, min: Int) = Leg(
        TransitMode.CAR, stop("", 0.0), stop("Haifa", km), t0, at(min), km * 1000, null, null, null, null, null, null, null, null,
        emptyList(), (0..10).map { p(km * it / 10) },
    )

    private fun leg(mode: TransitMode, from: StopCall, to: StopCall, start: Int, end: Int, trip: String? = null) =
        Leg(mode, from, to, at(start), at(end), null, null, trip, null, null, null, null, trip, null, emptyList(), emptyList())

    private fun itin(id: String, vararg legs: Leg) =
        Itinerary(id, legs.first().start, legs.last().end, (legs.count { it.mode.isTransit } - 1).coerceAtLeast(0), legs.toList())

    @Test
    fun dropOffsSpreadAlongTheDriveAndEndAtTheDriversDestination() {
        val drops = RidePlanner.dropOffs(drive(80.0, 60), target = GeoPoint(40.0, 40.0))
        // Few enough to plan from without keeping the user waiting.
        assertTrue(drops.size in 4..6)
        assertEquals(drops.sortedBy { it.atSec }, drops)
        // Evenly spaced, and the last is the end of the drive.
        assertTrue(drops.zipWithNext().all { (a, b) -> b.atSec - a.atSec >= 90 })
        assertEquals(3600L, drops.last().atSec)
        assertEquals(80_000.0, drops.last().alongM, 1.0)
    }

    @Test
    fun aDropOffNearWhereYoureGoingIsAlwaysTried() {
        // Going to a place 1 km off the road at km 31: the point by it is a candidate however the samples fall.
        val target = GeoPoint(p(31.0).lat, 34.81)
        val drops = RidePlanner.dropOffs(drive(80.0, 60), target)
        assertTrue(drops.any { Math.abs(it.alongM - 31_000) < 300 })
    }

    @Test
    fun aShortDriveStillHasItsEnd() {
        val drops = RidePlanner.dropOffs(drive(2.0, 3), target = p(5.0))
        assertEquals(180L, drops.last().atSec)
        assertTrue(drops.all { it.atSec >= 90 })
    }

    @Test
    fun carLegEndsAtTheDropOff() {
        val route = drive(40.0, 30)
        val drop = RidePlanner.dropOffs(route, p(100.0)).first { it.atSec in 600..1200 }
        val car = RidePlanner.carLeg(route, drop, at(5))
        assertEquals(at(5), car.start)
        assertEquals(at(5).plusSeconds(drop.atSec), car.end)
        assertEquals(drop.point, car.geometry.last())
        assertEquals(drop.point.lat, car.to.lat, 1e-9)
        // Unnamed mid-way; the drive's end keeps the destination's name.
        assertEquals("", car.to.name)
        assertEquals("Haifa", RidePlanner.carLeg(route, RidePlanner.dropOffs(route, p(100.0)).last(), t0).to.name)
    }

    @Test
    fun composedTripStartsWithTheLift() {
        val route = drive(40.0, 30)
        val car = RidePlanner.carLeg(route, RidePlanner.dropOffs(route, p(100.0))[2], t0)
        val rest = itin("r", leg(TransitMode.WALK, stop("", 20.0), stop("Central", 20.2), 16, 19),
            leg(TransitMode.BUS, stop("Central", 20.2, "c"), stop("Far", 60.0, "f"), 20, 70, "480"))
        val trip = RidePlanner.compose(car, rest)
        assertEquals(TransitMode.CAR, trip.legs.first().mode)
        assertEquals(t0, trip.start)
        assertEquals(at(70), trip.end)
        assertEquals(0, trip.transfers)
        assertEquals("Central", RidePlanner.dropOffName(trip))
    }

    @Test
    fun keepsOneDropOffPerWayOnPreferringTheLongerLiftOnATie() {
        val route = drive(40.0, 30)
        val drops = RidePlanner.dropOffs(route, p(100.0))
        fun via(i: Int, board: Double, trip: String, end: Int): Itinerary {
            val car = RidePlanner.carLeg(route, drops[i], t0)
            return RidePlanner.compose(car, itin("$i$trip", leg(TransitMode.BUS, stop("S$i", board, "s$i"), stop("Far", 60.0, "f"), 25, end, trip)))
        }
        // The same bus boarded from two drop-offs, and a different, slower one.
        val options = listOf(via(0, 5.0, "480", 70), via(2, 15.0, "480", 70), via(1, 10.0, "400", 80))
        val out = RidePlanner.distinct(options) { Ranking.score(it, false) }
        assertEquals(2, out.size)
        assertEquals("480", out.first().transitLegs.single().tripId)
        assertEquals(drops[2].atSec, out.first().legs.first().durationSec)
    }

    @Test
    fun walkingFromTheDropOffIsntMovedToTheLift() {
        // The lift leaves when the driver does, even when the bus after it runs late.
        val car = leg(TransitMode.CAR, stop("", 0.0), stop("", 10.0), 0, 10).copy(fixed = true)
        val walk = leg(TransitMode.WALK, stop("", 10.0), stop("Central", 10.2), 18, 20)
        val bus = leg(TransitMode.BUS, stop("Central", 10.2, "c"), stop("Far", 40.0, "f"), 20, 50, "480")
        val trip = itin("t", car, walk, bus)
        val expected = Combine.expected(trip) { null }
        assertEquals(at(0), expected.legs[0].start)
        assertEquals(at(10), expected.legs[0].end)
        assertEquals(at(18), expected.legs[1].start)
        // Taking a later bus moves the walk, not the lift.
        val later = bus.copy(start = at(30), end = at(60), tripId = "480b", alternatives = emptyList())
        val chosen = Combine.choose(trip.copy(legs = listOf(car, walk, bus.copy(alternatives = listOf(later)))), 2, later)
        assertEquals(at(0), chosen.legs[0].start)
        assertEquals(at(28), chosen.legs[1].start)
        // A lift that can go any time ("up to 10 minutes") moves with the bus like a walk.
        val loose = itin("l", car.copy(fixed = false), walk, bus)
        assertEquals(at(8), Combine.expected(loose) { null }.legs[0].start)
    }

    @Test
    fun carIsntARideToCatch() {
        assertFalse(TransitMode.CAR.isTransit)
        assertEquals(TransitMode.CAR, TransitMode.fromMotis("CAR"))
        assertEquals(TransitMode.CAR, TransitMode.fromMotis("CAR_DROPOFF"))
        // A lift before the first bus isn't a transfer.
        val trip = itin("t", leg(TransitMode.CAR, stop("", 0.0), stop("", 10.0), 0, 10),
            leg(TransitMode.BUS, stop("A", 10.0, "a"), stop("B", 20.0, "b"), 12, 30, "1"))
        assertTrue(Ranking.transfers(trip).isEmpty())
    }

    @Test
    fun optionsArrivingHoursLaterAreDropped() {
        val a = itin("a", leg(TransitMode.BUS, stop("A", 0.0, "a"), stop("B", 10.0, "b"), 0, 60, "1"))
        val b = itin("b", leg(TransitMode.BUS, stop("A", 0.0, "a"), stop("B", 10.0, "b"), 0, 85, "2"))
        val c = itin("c", leg(TransitMode.BUS, stop("A", 0.0, "a"), stop("B", 10.0, "b"), 0, 380, "3"))
        // Within half the soonest trip (30 min) stays; six hours later goes.
        assertEquals(listOf("a", "b"), RidePlanner.inReach(listOf(a, b, c)).map { it.id })
    }
}
