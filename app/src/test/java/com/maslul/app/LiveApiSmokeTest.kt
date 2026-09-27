package com.maslul.app

import com.maslul.app.data.GeoPoint
import com.maslul.app.data.IsraelZone
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.TransitRepository
import com.maslul.app.data.TransitousApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun stopBoardHasDepartures() = runBlocking {
        val stops = repo.nearbyStops(GeoPoint(32.0741, 34.7922))
        assertTrue(stops.isNotEmpty())
        val board = repo.stopBoard(stops.first().stopId!!)
        println("${stops.first().name}: ${board.size} departures, live=${board.count { it.live?.status == LiveStatus.LIVE }}")
        board.take(5).forEach { println("  ${it.departure.lineLabel} → ${it.departure.headsign} ${it.live?.status} ${it.live?.best}") }
    }
}
