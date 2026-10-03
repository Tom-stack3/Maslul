package com.maslul.app

import com.maslul.app.data.GeoMath
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.IsraelZone
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.Ranking
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.data.TransitRepository
import com.maslul.app.data.TransitousApi
import com.maslul.app.data.TripIds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assume
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Hits the real, free APIs end-to-end. Requires network access; this is what proves the
 * parsers match what the services actually return today.
 */
class LiveApiSmokeTest {
    private val repo = TransitRepository()

    @Test
    fun plansATripInTelAviv() = runBlocking {
        val r = repo.plan(TransitousApi.PlanRequest(GeoPoint(32.0741, 34.7922), GeoPoint(32.0853, 34.7818)))
        assertTrue("expected itineraries", r.itineraries.isNotEmpty())
        val leg = r.itineraries.first().firstTransit!!
        assertTrue(leg.tripId!!.contains("il-Israel-MOT_"))
        assertTrue(leg.geometry.size > 2)
        println("First route: ${r.itineraries.first().legs.joinToString(" > ") { "${it.mode} ${it.routeShortName ?: ""}" }}")
        val live = repo.legLive(leg)
        println("Live for ${leg.lineLabel}: ${live?.status} expected=${live?.expected} delay=${live?.delaySec}")
    }

    @Test
    fun geocodesHebrewAndEnglish() = runBlocking {
        val he = repo.searchPlaces("דיזנגוף סנטר", GeoPoint(32.08, 34.78))
        val en = repo.searchPlaces("azrieli", GeoPoint(32.08, 34.78))
        println("he: ${he.take(3).map { it.name }} en: ${en.take(3).map { it.name }}")
        assertTrue(he.isNotEmpty() && en.isNotEmpty())
    }

    @Test
    fun findsStreetWithUnmappedHouseNumber() = runBlocking {
        val r = repo.searchPlaces("המגינים 14 פתח תקווה", GeoPoint(32.0741, 34.7922))
        println("house number: ${r.take(4).map { "${it.name} (${it.subtitle})" }}")
        val top = r.first()
        assertTrue(top.name.startsWith("המגינים") && top.name.contains("14"))
        // The street is in Ein Ganim, Petah Tikva.
        assertTrue(GeoMath.distance(top.point, GeoPoint(32.087, 34.897)) < 1500)
        val exact = repo.searchPlaces("דיזנגוף 50 תל אביב", null)
        println("exact: ${exact.take(3).map { "${it.name} (${it.subtitle})" }}")
        assertTrue(exact.first().name.contains("50"))
    }

    @Test
    fun ranksAndFlagsTransfers() = runBlocking {
        // North Tel Aviv → Azrieli: a mix of direct and one-transfer options.
        val r = repo.plan(TransitousApi.PlanRequest(GeoPoint(32.0980, 34.7780), GeoPoint(32.0745, 34.7920)))
        val ranked = Ranking.rank(r.itineraries, arriveBy = false)
        assertTrue(ranked.isNotEmpty())
        ranked.forEach { i ->
            val lines = i.transitLegs.joinToString(">") { it.lineLabel }
            println("${i.start}–${i.end} t${i.transfers} $lines risk=${Ranking.riskiest(i)?.slackSec}")
        }
    }

    @Test
    fun findsRidesAfterATransfer() = runBlocking {
        // Tel Aviv → Haifa: usually a change, whose next rides tell the wait if it's missed.
        val r = repo.plan(TransitousApi.PlanRequest(GeoPoint(32.0775, 34.7746), GeoPoint(32.7614, 35.0195)))
        val leg = r.itineraries.first { it.transitLegs.size > 1 }.transitLegs[1]
        val after = repo.ridesAfter(leg, TransitMode.entries.filter { it.isTransit }.toSet())
        println("after ${leg.lineLabel} ${leg.start}: ${after.map { "${it.lineLabel} ${it.start}" }}")
        assertTrue(after.all { it.start.isAfter(leg.start) })
    }

    @Test
    fun searchesStops() = runBlocking {
        val r = repo.searchStops("סבידור", GeoPoint(32.0741, 34.7922))
        println("stops: ${r.take(4).map { "${it.name} ${it.stopId}" }}")
        assertTrue(r.isNotEmpty() && r.all { it.stopId != null })
    }

    @Test
    fun findsNearbyLines() = runBlocking {
        val here = GeoPoint(32.0741, 34.7922)
        val lines = repo.nearbyLines(here, repo.nearbyStops(here))
        assertTrue(lines.isNotEmpty())
        lines.take(5).forEach { println("${it.departure.lineLabel} → ${it.departure.headsign} at ${it.stop.name} ${it.distanceM.toInt()} m ${it.next}") }
        val route = repo.lineRoute(lines.first { it.departure.mode == TransitMode.BUS }.departure.routeId)
        println("resolved: ${route?.label} ${route?.destination}")
        assertTrue(route != null)
    }

    @Test
    fun liveSnapshotHasVehicles() = runBlocking {
        val s = repo.live.snapshot()
        println("Snapshot ${s?.minute}: ${s?.vehicleCount} vehicles on ${s?.byLine?.size} lines")
        assertTrue((s?.vehicleCount ?: 0) > 100)
    }

    @Test
    fun loadsLineWithScheduleAndLiveVehicles() = runBlocking {
        val lines = repo.searchLines("480", null)
        assertTrue(lines.isNotEmpty())
        val route = lines.first().main
        val d = repo.lineDetail(route)
        assertTrue(d.stops.size >= 2)
        assertTrue(d.rides.isNotEmpty())
        println("480 ${route.origin} → ${route.destination}: ${d.stops.size} stops, ${d.rides.size} rides, shape ${d.shape.size} pts")
        val v = repo.lineVehicles(d)
        println("Live vehicles on 480: ${v.size} ${v.map { it.progress.delaySec }}")
        println("Service date ${LocalDate.now(IsraelZone)}")
    }

    @Test
    fun loadsStopsForCommonLinesInBothDirections() = runBlocking {
        // Stride sometimes has a day's rides without their stops; every line must still show stops.
        for (q in listOf("16", "92", "5", "1")) {
            for (line in repo.searchLines(q, null).take(4)) {
                for (variant in repo.lineVariants(repo.currentRoute(line.main)).take(2)) {
                    val d = repo.lineDetail(variant)
                    println("$q ${variant.gtfsRouteId} dir ${variant.direction}: ${d.stops.size} stops, ${d.rides.size} rides, shape ${d.shape.size}")
                    assertTrue(d.stops.size >= 2)
                    assertTrue(d.stops.zipWithNext().all { (a, b) -> a.sequence < b.sequence && a.offsetSec <= b.offsetSec })
                    if (d.rides.isNotEmpty()) assertTrue("shape for ${variant.gtfsRouteId}", d.shape.size >= 2)
                }
            }
        }
        // Timetable rides open as Transitous trips.
        val d16 = repo.lineDetail(repo.currentRoute(repo.searchLines("16", null).first().main))
        assertTrue(repo.trip(repo.rideTripId(d16, d16.rides.first())) != null)
        // A line opened from a stop board resolves through its GTFS route id.
        val here = GeoPoint(32.0741, 34.7922)
        val dep = repo.nearbyLines(here, repo.nearbyStops(here)).first { it.departure.mode == TransitMode.BUS }.departure
        val d = repo.lineDetail(repo.lineRoute(dep.routeId)!!)
        println("From stop: ${dep.lineLabel} → ${d.stops.size} stops, ${d.rides.size} rides")
        assertTrue(d.stops.size >= 2)
    }

    @Test
    fun stopBoardHasDepartures() = runBlocking {
        val stops = repo.nearbyStops(GeoPoint(32.0741, 34.7922))
        assertTrue(stops.isNotEmpty())
        val board = repo.stopBoard(stops.first().stopId!!)
        val late = board.count { it.live?.status == LiveStatus.LIVE && it.departure.scheduled.isBefore(java.time.Instant.now()) }
        println("${stops.first().name}: ${board.size} departures, live=${board.count { it.live?.status == LiveStatus.LIVE }}, late earlier trips=$late")
        board.take(5).forEach { println("  ${it.departure.lineLabel} → ${it.departure.headsign} ${it.live?.status} ${it.live?.best}") }
    }

    @Test
    fun lateBusWhoseSlotIsLongGoneStillGetsALiveLookup() = runBlocking {
        // Any vehicle on the road whose trip set off a while ago: boarding it at its first stop
        // was due long before now, yet it's worth a live lookup. A trip of a line with nothing on the road isn't.
        val now = Instant.now()
        val repo = TransitRepository()
        val v = repo.live.snapshot()?.byLine?.values?.flatten()
            ?.firstOrNull { it.journeyRef != null && it.originDeparture.isBefore(now.minusSeconds(20 * 60)) && it.originDeparture.isAfter(now.minusSeconds(80 * 60)) }
        Assume.assumeNotNull(v) // e.g. on Shabbat
        val stop = StopCall("s", "S", null, v!!.lat, v.lon, null, null)
        fun ride(journeyRef: String, lineRef: String = v.lineRef) = Leg(
            TransitMode.BUS, stop, stop, v.originDeparture, v.originDeparture.plusSeconds(300), null, null, "1", null, null, null, null,
            TripIds.build(v.originDeparture.atZone(IsraelZone).toLocalDate(), v.originDeparture, journeyRef), "il-Israel-MOT_$lineRef",
            emptyList(), emptyList(),
        )
        assertTrue(repo.worthLive(ride(v.journeyRef!!), now))
        assertFalse(repo.worthLive(ride("1", lineRef = "999999999"), now))
    }
}
