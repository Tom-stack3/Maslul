package com.maslul.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate

data class LineDetail(
    val route: LineRoute,
    val stops: List<LineStop>,
    val shape: List<GeoPoint>,
    val rides: List<Ride>,
    val timeline: TripTimeline,
)

/** A live vehicle on a line, with its estimated progress along the line's stops. */
data class LineVehicle(val vehicle: Vehicle, val progress: TripTimeline.Progress, val ride: Ride?)

/** Next arrival at one stop of a line. */
data class StopArrival(val time: Instant, val live: Boolean, val delaySec: Long, val journeyRef: String?)

data class BoardEntry(val departure: Departure, val live: LiveCall?)

class TransitRepository(
    val transitous: TransitousApi = TransitousApi(),
    private val photon: PhotonApi = PhotonApi(),
    private val stride: StrideApi = StrideApi(),
    val live: LiveRepository = LiveRepository(),
) {
    private val tripCache = object : LinkedHashMap<String, Leg>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Leg>?) = size > 80
    }

    var language: String? = null

    // ---- Places ---------------------------------------------------------------------

    suspend fun searchPlaces(text: String, near: GeoPoint?): List<Place> = coroutineScope {
        val a = async { runCatching { transitous.geocode(text, near, language) }.getOrDefault(emptyList()) }
        val b = async { runCatching { photon.search(text, near) }.getOrDefault(emptyList()) }
        mergePlaces(a.await(), b.await())
    }

    suspend fun reverseGeocode(p: GeoPoint): Place? = runCatching { transitous.reverseGeocode(p) }.getOrNull()

    suspend fun nearbyStops(p: GeoPoint): List<Place> = transitous.nearbyStops(p)

    // ---- Routing --------------------------------------------------------------------

    suspend fun plan(req: TransitousApi.PlanRequest): PlanResult = transitous.plan(req.copy(language = language))

    suspend fun trip(tripId: String): Leg? {
        synchronized(tripCache) { tripCache[tripId] }?.let { return it }
        return runCatching { transitous.trip(tripId, language) }.getOrNull()
            ?.also { synchronized(tripCache) { tripCache[tripId] = it } }
    }

    /** Live state of a transit leg: where its vehicle is and when it reaches the boarding stop. */
    suspend fun legLive(leg: Leg, now: Instant = Instant.now()): LiveCall? {
        val tripId = leg.tripId ?: return null
        val parsed = TripIds.parse(tripId) ?: return null
        val trip = trip(tripId) ?: return null
        val timeline = TripTimeline.fromTrip(trip) ?: return null
        val calls = listOf(trip.from) + trip.intermediateStops + trip.to
        val board = indexOfStop(calls, leg.from) ?: return null
        val alight = indexOfStop(calls, leg.to, from = board + 1)
        val vehicles = leg.lineRef?.let { live.vehiclesForLine(it) }.orEmpty()
        val v = vehicles.forTrip(parsed.tripNumber, parsed.originDeparture)
        val origin = calls.first().scheduledTime ?: parsed.originDeparture
        return LiveCalls.compute(timeline, origin, board, alight, v, now)
    }

    /** The full trip plus its live vehicle progress, for the trip screen. */
    suspend fun tripLive(tripId: String, now: Instant = Instant.now()): Pair<Leg, TripTimeline.Progress?>? {
        val trip = trip(tripId) ?: return null
        val parsed = TripIds.parse(tripId)
        val timeline = TripTimeline.fromTrip(trip) ?: return trip to null
        val v = trip.lineRef?.let { live.vehiclesForLine(it) }.orEmpty().forTrip(parsed?.tripNumber, parsed?.originDeparture)
        val origin = trip.from.scheduledTime ?: parsed?.originDeparture ?: return trip to null
        return trip to v?.let { timeline.estimate(it, origin, now) }
    }

    // ---- Stops ----------------------------------------------------------------------

    suspend fun stopBoard(stopId: String, now: Instant = Instant.now()): List<BoardEntry> {
        val deps = transitous.stopTimes(stopId, count = 40, language = language)
            .filter { it.scheduled.isAfter(now.minusSeconds(20 * 60)) }
        val snapshot = live.snapshot()
        return coroutineScope {
            deps.map { d ->
                async {
                    val lineRef = TripIds.lineRef(d.routeId)
                    val parsed = TripIds.parse(d.tripId)
                    val soon = d.scheduled.isBefore(now.plusSeconds(60 * 60))
                    val vehicle = lineRef?.let { snapshot?.byLine?.get(it) }.orEmpty()
                        .forTrip(parsed?.tripNumber, parsed?.originDeparture)
                    val liveCall = if (soon && parsed != null && vehicle != null) {
                        runCatching {
                            val trip = trip(d.tripId) ?: return@runCatching null
                            val tl = TripTimeline.fromTrip(trip) ?: return@runCatching null
                            val calls = listOf(trip.from) + trip.intermediateStops + trip.to
                            val idx = indexOfStop(calls, d.stop) ?: return@runCatching null
                            LiveCalls.compute(tl, trip.from.scheduledTime ?: parsed.originDeparture, idx, null, vehicle, now)
                        }.getOrNull()
                    } else if (parsed != null && soon) {
                        val status = if (now.isBefore(parsed.originDeparture.plusSeconds(60))) LiveStatus.SCHEDULED else LiveStatus.UNTRACKED
                        LiveCall(status, d.scheduled, null)
                    } else {
                        null
                    }
                    BoardEntry(d, liveCall)
                }
            }.awaitAll()
                .filter { it.live?.status != LiveStatus.PASSED }
                .filter { (it.live?.best ?: it.departure.scheduled).isAfter(now.minusSeconds(60)) }
                .sortedBy { it.live?.best ?: it.departure.scheduled }
        }
    }

    // ---- Lines ----------------------------------------------------------------------

    suspend fun searchLines(query: String, mode: TransitMode?): List<Line> {
        val today = LocalDate.now(IsraelZone)
        var routes = stride.searchRoutes(query, today, mode)
        // Today's feed is loaded shortly after midnight; fall back to yesterday meanwhile.
        if (routes.isEmpty()) routes = stride.searchRoutes(query, today.minusDays(1), mode)
        if (mode != null) routes = routes.filter { it.mode == mode }
        return groupLines(routes)
    }

    suspend fun lineVariants(route: LineRoute): List<LineRoute> {
        val date = LocalDate.parse(route.date)
        return runCatching { stride.lineVariants(route.operatorRef, route.mkt, date) }.getOrDefault(listOf(route))
            .ifEmpty { listOf(route) }
            .sortedWith(compareBy({ it.direction }, { it.alternative }))
    }

    /** Refreshes a (possibly saved, older) route to today's gtfs route id. */
    suspend fun currentRoute(route: LineRoute): LineRoute {
        val today = LocalDate.now(IsraelZone)
        if (route.date == today.toString()) return route
        return runCatching { stride.routesByLineRef(listOf(route.lineRef), today).firstOrNull() }.getOrNull() ?: route
    }

    suspend fun lineDetail(route: LineRoute): LineDetail {
        val date = LocalDate.parse(route.date)
        val ride = stride.rides(route.gtfsRouteId).firstOrNull() ?: error("No trips for this line today")
        val stops = stride.rideStops(ride.id, date)
        if (stops.isEmpty()) error("No stops found for this line")
        val rides = runCatching { stride.departures(route.gtfsRouteId, stops.first().gtfsStopId, date) }.getOrDefault(emptyList())
        // Prefer a daytime ride; any ride of the pattern shares the shape.
        val shape = rides.sortedBy { if (it.departure.atZone(IsraelZone).hour < 5) 1 else 0 }.take(2)
            .firstNotNullOfOrNull { r -> trip(TripIds.fromJourney(r.journeyRef, r.departure, date))?.geometry?.takeIf { it.size >= 2 } }
            .orEmpty()
        return LineDetail(route, stops, shape, rides, TripTimeline.fromLineStops(stops, shape))
    }

    suspend fun lineVehicles(detail: LineDetail, now: Instant = Instant.now()): List<LineVehicle> {
        val vehicles = live.vehiclesForLine(detail.route.lineRef.toString())
        val byRef = detail.rides.associateBy { it.tripNumber }
        return vehicles.mapNotNull { v ->
            val ride = v.journeyRef?.let(byRef::get)
                ?: detail.rides.firstOrNull { Math.abs(it.departure.epochSecond - v.originDeparture.epochSecond) < 60 }
            val p = detail.timeline.estimate(v, ride?.departure ?: v.originDeparture, now) ?: return@mapNotNull null
            if (p.lastPassed >= detail.stops.lastIndex) null else LineVehicle(v, p, ride)
        }.sortedByDescending { it.progress.alongM }
    }

    /** Upcoming arrivals at stop [index]: live vehicles first, then the timetable. */
    fun arrivalsAt(detail: LineDetail, vehicles: List<LineVehicle>, index: Int, now: Instant, limit: Int = 4): List<StopArrival> {
        val out = ArrayList<StopArrival>()
        val liveRefs = HashSet<String>()
        for (lv in vehicles) {
            lv.vehicle.journeyRef?.let(liveRefs::add)
            val t = lv.progress.predicted.getOrNull(index) ?: continue
            out += StopArrival(t, true, lv.progress.delaySec, lv.vehicle.journeyRef)
        }
        val offset = detail.stops[index].offsetSec
        for (r in detail.rides) {
            if (r.tripNumber in liveRefs) continue
            val t = r.departure.plusSeconds(offset)
            // Trips that should have left over 3 min ago but aren't tracked are probably gone.
            if (t.isAfter(now) && r.departure.isAfter(now.minusSeconds(180))) out += StopArrival(t, false, 0, r.journeyRef)
        }
        return out.sortedBy { it.time }.take(limit)
    }

    companion object {
        fun indexOfStop(calls: List<StopCall>, target: StopCall, from: Int = 0): Int? {
            for (i in from until calls.size) if (target.stopId != null && calls[i].stopId == target.stopId) return i
            // Fall back to the geographically closest stop.
            var best = -1
            var bestD = Double.MAX_VALUE
            for (i in from until calls.size) {
                val d = GeoMath.distance(calls[i].point, target.point)
                if (d < bestD) { bestD = d; best = i }
            }
            return best.takeIf { it >= 0 && bestD < 150 }
        }

        /** Interleaves two ranked result lists, dropping near-duplicates. */
        fun mergePlaces(primary: List<Place>, secondary: List<Place>): List<Place> {
            val out = ArrayList<Place>()
            val maxLen = maxOf(primary.size, secondary.size)
            for (i in 0 until maxLen) {
                listOfNotNull(primary.getOrNull(i), secondary.getOrNull(i)).forEach { p ->
                    val dup = out.any { o ->
                        o.name.equals(p.name, ignoreCase = true) && GeoMath.distance(o.point, p.point) < 300
                    }
                    if (!dup) out += p
                }
            }
            return out.take(14)
        }
    }
}
