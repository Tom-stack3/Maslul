package com.maslul.app

import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Place
import com.maslul.app.data.Ranking
import com.maslul.app.data.RidePlanner
import com.maslul.app.data.Shuttle
import com.maslul.app.data.TransitMode
import com.maslul.app.data.TransitRepository
import com.maslul.app.data.RideOffer
import com.maslul.app.data.TransitousApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** Rides, shuttles and planning from on board, against the real services (needs network access). */
class RideShuttleSmokeTest {
    private val repo = TransitRepository()
    private val telAviv = GeoPoint(32.0853, 34.7818)
    private val haifa = GeoPoint(32.7940, 34.9896)
    private fun describe(i: com.maslul.app.data.Itinerary) =
        i.legs.joinToString(" > ") { "${it.mode} ${it.routeShortName ?: ""} ${it.to.name.take(16)} ${it.durationSec / 60}m" }

    @Test
    fun drivesWithGeometry() = runBlocking {
        val car = repo.carRoute(telAviv, haifa)!!
        assertEquals(TransitMode.CAR, car.mode)
        assertTrue(car.geometry.size > 50)
        assertTrue(car.durationSec in 40 * 60L..150 * 60L)
    }

    @Test
    fun findsWhereToGetOutAlongTheDriversWay() = runBlocking {
        // Driver heads to Haifa; you're going to Kfar Yona, inland from Netanya.
        val req = TransitousApi.PlanRequest(telAviv, GeoPoint(32.3170, 34.9354))
        val options = repo.rideAlong(req, haifa, { Ranking.score(it, false) })
        options.take(4).forEach { println("ride: drop ${RidePlanner.dropOffName(it)} · ${describe(it)}") }
        assertTrue(options.isNotEmpty())
        assertTrue(options.all { it.legs.first().mode == TransitMode.CAR })
        assertTrue(options.all { o -> o.legs.zipWithNext().all { (a, b) -> !b.start.isBefore(a.start) } })
    }

    @Test
    fun plansALiftWithinAFewMinutes() = runBlocking {
        val r = repo.plan(TransitousApi.PlanRequest(telAviv, GeoPoint(31.2518, 34.7913), preTransitModes = RideOffer.WITHIN_MODES,
            maxPreTransitMinutes = 15, directModes = "WALK,CAR", maxDirectMinutes = 15))
        r.itineraries.take(3).forEach { println("within 15: ${describe(it)}") }
        assertTrue(r.itineraries.isNotEmpty())
        val car = r.itineraries.first().legs.first()
        assertEquals(TransitMode.CAR, car.mode)
        assertTrue(car.durationSec <= 16 * 60)
        assertTrue(r.itineraries.all { it.legs.all { l -> !l.end.isBefore(l.start) } })
    }

    @Test
    fun aLiftIsOneDrive() = runBlocking {
        // From Mitzpe Gvulot, the site's track isn't drivable: "car, walk across, car again" isn't a lift.
        val site = GeoPoint(31.2051214, 34.4554327)
        val beerSheva = GeoPoint(31.242886, 34.798546)
        val r = repo.plan(TransitousApi.PlanRequest(site, beerSheva, preTransitModes = RideOffer.WITHIN_MODES,
            maxPreTransitMinutes = 30, directModes = "WALK,CAR", maxDirectMinutes = 30, maxWalkMinutes = 30))
        r.itineraries.take(3).forEach { println("Gvulot within 30: ${describe(it)}") }
        assertTrue(r.itineraries.isNotEmpty())
        assertTrue(r.itineraries.all { it.legs.count { l -> l.mode == TransitMode.CAR } == 1 && it.legs.first().mode == TransitMode.CAR })
    }

    @Test
    fun combinesAShuttleWithTheTrain() = runBlocking {
        // An office shuttle from Ramat HaHayal to Tel Aviv University station, every 20 minutes all day.
        val office = Place("Office", 32.1093, 34.8396)
        val station = Place("University station", 32.1037, 34.8047)
        val shuttle = Shuttle("s", "Office", office, station, (0 until 24 * 60 step 20).toList(), rideMinutes = 12, days = (1..7).toSet())
        val req = TransitousApi.PlanRequest(office.point, haifa)
        val options = repo.shuttleOptions(req, listOf(shuttle))
        options.forEach { println("shuttle: ${describe(it)}") }
        assertTrue(options.isNotEmpty())
        val ride = options.first().legs.first { it.shuttleId == "s" }
        assertTrue(ride.geometry.size > 2)
        assertTrue(options.first().transitLegs.size >= 2)
    }

    @Test
    fun findsRidesAroundYouAndPlansFromTheNextStop() = runBlocking {
        // Arlozorov terminal by Tel Aviv Savidor station: always something around.
        val here = GeoPoint(32.0838, 34.7980)
        val candidates = repo.boardCandidates(here)
        candidates.take(6).forEach { println("candidate: ${it.mode} ${it.lineLabel} to ${it.headsign} live=${it.live} ${it.distanceM.toInt()} m") }
        assertTrue(candidates.isNotEmpty())
        // One with a few stops still to go (some end right here).
        val found = candidates.filter { it.tripId != null }.firstNotNullOfOrNull { c ->
            repo.onBoardStart(c.tripId!!, here)?.takeIf { it.nextIndex + 4 <= it.trip.intermediateStops.size + 1 }?.let { c to it }
        }
        // In the small hours there may be none: skipped (not passed) then.
        Assume.assumeTrue("no ride around with stops still to go (night?)", found != null)
        val (c, start) = found!!
        println("next stop: ${start.next.name} #${start.nextIndex}")
        assertFalse(start.next.stopId.isNullOrBlank())
        // Going somewhere further down its own route: staying on is among the options.
        val calls = listOf(start.trip.from) + start.trip.intermediateStops + start.trip.to
        val ahead = calls[start.nextIndex + 4]
        val score = { it: com.maslul.app.data.Itinerary -> Ranking.score(it, false) }
        val req = TransitousApi.PlanRequest(start.next.point, ahead.point, fromStopId = start.next.stopId)
        val near = repo.planOnBoard(req, start, score)
        near.take(3).forEach { println("from on board to ${ahead.name}: ${describe(it)} stays=${it.firstTransit?.tripId == c.tripId}") }
        assertTrue(near.any { it.firstTransit?.tripId == c.tripId })
        val far = repo.planOnBoard(req.copy(to = haifa), start, score)
        far.take(3).forEach { println("from on board to Haifa: ${describe(it)} stays=${it.firstTransit?.tripId == c.tripId}") }
        assertTrue(far.isNotEmpty())
    }
}
