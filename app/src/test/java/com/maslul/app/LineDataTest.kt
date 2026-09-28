package com.maslul.app

import com.maslul.app.data.AppJson
import com.maslul.app.data.Departure
import com.maslul.app.data.IsraelZone
import com.maslul.app.data.Ride
import com.maslul.app.data.RideStopDto
import com.maslul.app.data.StopCall
import com.maslul.app.data.StrideApi
import com.maslul.app.data.TransitMode
import com.maslul.app.data.TransitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The line screen's stop pattern and timetable, as parsed from Stride and Transitous. */
class LineDataTest {

    @Test
    fun parsesStrideRideStopsInOrder() {
        // Real Stride rows (trimmed), deliberately out of order and with a duplicated row.
        val json = """
        [{"id":5639903815,"gtfs_stop_id":48804300,"gtfs_ride_id":157928872,"arrival_time":"2026-09-28T04:04:10+00:00",
          "departure_time":"2026-09-28T04:04:10+00:00","stop_sequence":3,"gtfs_ride__journey_ref":"279000_270926",
          "gtfs_stop__code":32270,"gtfs_stop__lat":32.0901,"gtfs_stop__lon":34.8662,"gtfs_stop__name":"C","gtfs_stop__city":"פתח תקווה"},
         {"id":5639903813,"gtfs_stop_id":48819360,"gtfs_ride_id":157928872,"arrival_time":"2026-09-28T04:00:00+00:00",
          "departure_time":"2026-09-28T04:00:00+00:00","stop_sequence":1,"pickup_type":0,"drop_off_type":1,"shape_dist_traveled":null,
          "gtfs_ride__journey_ref":"279000_270926","gtfs_stop__date":"2026-09-28","gtfs_stop__code":31345,"gtfs_stop__lat":32.092044,
          "gtfs_stop__lon":34.865337,"gtfs_stop__name":"מסוף יותם ויואב/רציפים","gtfs_stop__city":"פתח תקווה","gtfs_route__line_ref":1081},
         {"id":5639903814,"gtfs_stop_id":48804282,"gtfs_ride_id":157928872,"arrival_time":"2026-09-28T04:01:59+00:00",
          "departure_time":"2026-09-28T04:01:59+00:00","stop_sequence":2,"gtfs_stop__code":32269,"gtfs_stop__lat":32.091161,
          "gtfs_stop__lon":34.8655,"gtfs_stop__name":"B"},
         {"id":5639903814,"gtfs_stop_id":48804282,"gtfs_ride_id":157928872,"arrival_time":"2026-09-28T04:01:59+00:00",
          "departure_time":"2026-09-28T04:01:59+00:00","stop_sequence":2,"gtfs_stop__code":32269,"gtfs_stop__lat":32.091161,
          "gtfs_stop__lon":34.8655,"gtfs_stop__name":"B"}]
        """.trimIndent()
        val stops = StrideApi.lineStops(AppJson.decodeFromString<List<RideStopDto>>(json))
        assertEquals(listOf(1, 2, 3), stops.map { it.sequence })
        assertEquals(listOf(0L, 119L, 250L), stops.map { it.offsetSec })
        assertEquals("31345", stops[0].code)
        assertEquals("מסוף יותם ויואב/רציפים", stops[0].name)
        assertEquals(48819360L, stops[0].gtfsStopId)
        assertEquals(null, stops[1].city)
    }

    @Test
    fun emptyStrideDayYieldsNoStops() {
        // Stride lists a day's rides before loading their stops: gtfs_ride_stops returns [].
        assertTrue(StrideApi.lineStops(AppJson.decodeFromString<List<RideStopDto>>("[]")).isEmpty())
    }

    @Test
    fun buildsTimetableFromTransitousDepartures() {
        val date = LocalDate.of(2026, 9, 28)
        val dayStart = date.atStartOfDay(IsraelZone).toInstant()
        fun dep(tripId: String, routeId: String?, at: String) = Departure(
            tripId = tripId, routeId = routeId, mode = TransitMode.BUS, lineLabel = "5", headsign = "", agency = null,
            routeColor = null, scheduled = Instant.parse(at),
            stop = StopCall("il-Israel-MOT_1", "", null, 0.0, 0.0, null, Instant.parse(at)),
        )
        val deps = listOf(
            dep("20260928_07:10_il-Israel-MOT_585694720_280926", "il-Israel-MOT_4212", "2026-09-28T04:10:00Z"),
            dep("20260928_06:40_il-Israel-MOT_585694713_280926", "il-Israel-MOT_4212", "2026-09-28T03:40:00Z"),
            // Same trip listed twice (e.g. two platforms of one stop).
            dep("20260928_06:40_il-Israel-MOT_585694713_280926", "il-Israel-MOT_4212", "2026-09-28T03:40:00Z"),
            // Other direction / other line at the same stop.
            dep("20260928_06:45_il-Israel-MOT_1_280926", "il-Israel-MOT_4213", "2026-09-28T03:45:00Z"),
            // Next service day.
            dep("20260929_06:40_il-Israel-MOT_585694799_290926", "il-Israel-MOT_4212", "2026-09-29T03:40:00Z"),
            // Not an MOT trip.
            dep("20260928_06:50_de-DELFI_1", "il-Israel-MOT_4212", "2026-09-28T03:50:00Z"),
        )
        val rides = TransitRepository.ridesFromDepartures(deps, 4212, dayStart)
        assertEquals(listOf("585694713_280926", "585694720_280926"), rides.map { it.journeyRef })
        assertEquals(Instant.parse("2026-09-28T03:40:00Z"), rides[0].departure)
        assertEquals("585694713", rides[0].tripNumber)
        assertEquals("20260928_06:40_il-Israel-MOT_585694713_280926", rides[0].tripId)
        // Listed at the second stop (first is an operational stop), 2 min into the line.
        val second = TransitRepository.ridesFromDepartures(deps, 4212, dayStart, offsetSec = 120)
        assertEquals(Instant.parse("2026-09-28T03:38:00Z"), second[0].departure)
    }

    @Test
    fun matchesStrideRidesToTransitousTripsAcrossFeeds() {
        fun dep(tripId: String, routeId: String, at: String) = Departure(
            tripId = tripId, routeId = routeId, mode = TransitMode.BUS, lineLabel = "16", headsign = "", agency = null,
            routeColor = null, scheduled = Instant.parse(at),
            stop = StopCall("il-Israel-MOT_44161", "", null, 0.0, 0.0, null, Instant.parse(at)),
        )
        val deps = listOf(
            dep("20260928_06:40_il-Israel-MOT_584685696_280926", "il-Israel-MOT_1081", "2026-09-28T03:40:00Z"),
            dep("20260928_07:00_il-Israel-MOT_279000_280926", "il-Israel-MOT_1081", "2026-09-28T04:00:00Z"),
            dep("20260928_07:00_il-Israel-MOT_1_280926", "il-Israel-MOT_1082", "2026-09-28T04:00:00Z"),
        )
        // Stride's journey ref carries its own feed's suffix (27/09), Transitous's the next day's.
        val stride = Ride("279000_270926", Instant.parse("2026-09-28T04:00:00Z"))
        assertEquals("20260928_07:00_il-Israel-MOT_279000_280926", TransitRepository.matchTripId(deps, stride, 1081))
        // Renumbered trip: fall back to the same line at the same time.
        val renumbered = Ride("999_270926", Instant.parse("2026-09-28T03:40:00Z"))
        assertEquals("20260928_06:40_il-Israel-MOT_584685696_280926", TransitRepository.matchTripId(deps, renumbered, 1081))
        assertEquals(null, TransitRepository.matchTripId(deps, Ride("999_270926", Instant.parse("2026-09-28T05:00:00Z")), 1081))
        // Matched at a later stop by the ride's departure plus the stop's offset.
        val early = Ride("998_270926", Instant.parse("2026-09-28T03:35:00Z"))
        assertEquals("20260928_06:40_il-Israel-MOT_584685696_280926", TransitRepository.matchTripId(deps, early, 1081, offsetSec = 300))
    }
}
