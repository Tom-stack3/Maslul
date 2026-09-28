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
    /** Transitous stop id of the first stop, to look up trips departing from it. */
    val firstStopId: String? = null,
)

/** Neither the line's own day nor recent days have stop data for it. */
class LineUnavailableException : IllegalStateException("No stop data for this line right now. Please try again later.")

/** A live vehicle on a line, with its estimated progress along the line's stops. */
data class LineVehicle(val vehicle: Vehicle, val progress: TripTimeline.Progress, val ride: Ride?)

/** Next arrival at one stop of a line. */
data class StopArrival(val time: Instant, val live: Boolean, val delaySec: Long, val journeyRef: String?)

data class BoardEntry(val departure: Departure, val live: LiveCall?)

/** A line (one direction) serving a stop near the user, with its next departures there. */
data class NearbyLine(val departure: Departure, val stop: Place, val distanceM: Double, val next: List<Instant>)

class TransitRepository(
    val transitous: TransitousApi = TransitousApi(),
    private val photon: PhotonApi = PhotonApi(),
    private val stride: StrideApi = StrideApi(),
    private val nominatim: NominatimApi = NominatimApi(),
    val live: LiveRepository = LiveRepository(),
) {
    private val tripCache = object : LinkedHashMap<String, Leg>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Leg>?) = size > 80
    }

    var language: String? = null

    // ---- Places ---------------------------------------------------------------------

    suspend fun searchPlaces(text: String, near: GeoPoint?): List<Place> = coroutineScope {
        val q = AddressSearch.parse(text)
        val a = async { runCatching { transitous.geocode(q.text, near, language) }.getOrDefault(emptyList()) }
        val b = async { runCatching { photon.search(q.text, near) }.getOrDefault(emptyList()) }
        // With a house number, also look up the bare street in case the number isn't mapped.
        val c = q.number?.let { async { runCatching { photon.search(q.rest, near) }.getOrDefault(emptyList()) } }
        val merged = mergePlaces(a.await(), b.await())
        if (c == null) return@coroutineScope merged
        val candidates = merged + c.await()
        AddressSearch.resolve(q, candidates)?.let { return@coroutineScope it.take(14) }
        val n = runCatching { nominatim.search(q.text) }.getOrDefault(emptyList())
        (AddressSearch.resolve(q, n + candidates) ?: merged).take(14)
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

    /** Route and stops from a live vehicle up to [leg]'s boarding stop, so the bus isn't drawn floating. */
    suspend fun approach(leg: Leg, call: LiveCall): LiveApproach? {
        val p = call.progress?.takeIf { call.status == LiveStatus.LIVE } ?: return null
        val trip = trip(leg.tripId ?: return null) ?: return null
        val timeline = TripTimeline.fromTrip(trip) ?: return null
        val calls = listOf(trip.from) + trip.intermediateStops + trip.to
        val board = indexOfStop(calls, leg.from) ?: return null
        val path = timeline.slice(p.alongM, timeline.stopAlong[board])
        val stops = calls.subList((p.lastPassed + 1).coerceIn(0, board), board).map { it.point }
        return LiveApproach(path.ifEmpty { listOf(p.vehicle.point, leg.from.point) }, stops)
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

    suspend fun stopBoard(stopId: String, now: Instant = Instant.now()): List<BoardEntry> = coroutineScope {
        // Stop times start at the requested time, so a late bus whose timetable slot here has
        // already passed would be missing entirely; fetch the recent past too.
        val upcoming = async { transitous.stopTimes(stopId, count = 40, language = language) }
        val recent = async {
            runCatching { transitous.stopTimes(stopId, now.minusSeconds(LATE_WINDOW_SEC), count = 40, language = language) }
                .getOrDefault(emptyList())
        }
        val deps = mergeDepartures(upcoming.await(), recent.await(), now)
        val snapshot = live.snapshot()
        boardEntries(deps, snapshot, now)
    }

    private suspend fun boardEntries(deps: List<Departure>, snapshot: LiveRepository.Snapshot?, now: Instant): List<BoardEntry> {
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
            }.awaitAll().let { arrangeBoard(it, now) }
        }
    }

    // ---- Lines ----------------------------------------------------------------------

    /** Lines leaving from the stops nearest to [here], each at its closest stop. */
    suspend fun nearbyLines(here: GeoPoint, stops: List<Place>, now: Instant = Instant.now()): List<NearbyLine> = coroutineScope {
        stops.filter { it.stopId != null }.take(8)
            .map { st -> async { runCatching { st to transitous.stopTimes(st.stopId!!, count = 25, language = language) }.getOrNull() } }
            .awaitAll().filterNotNull()
            .flatMap { (st, deps) ->
                val d = GeoMath.distance(here, st.point)
                deps.filter { it.scheduled.isAfter(now.minusSeconds(60)) }.map { Triple(st, d, it) }
            }
            .groupBy { (_, _, dep) -> "${dep.routeId ?: dep.lineLabel}|${dep.headsign}" }
            .values.map { g ->
                val nearest = g.minOf { it.second }
                val atStop = g.filter { it.second == nearest }.sortedBy { it.third.scheduled }
                NearbyLine(atStop.first().third, atStop.first().first, nearest, atStop.take(3).map { it.third.scheduled })
            }
            // Group by ~100 m bands so nearby stops mix, then soonest first.
            .sortedWith(compareBy({ (it.distanceM / 100).toInt() }, { it.next.first() }))
    }

    /** Resolves a GTFS route id (from a departure) to today's Stride line route. */
    suspend fun lineRoute(routeId: String?): LineRoute? {
        val ref = TripIds.lineRef(routeId)?.toLongOrNull() ?: return null
        val today = LocalDate.now(IsraelZone)
        return stride.routesByLineRef(listOf(ref), today).firstOrNull()
            ?: stride.routesByLineRef(listOf(ref), today.minusDays(1)).firstOrNull()
    }

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
        val (stops, sameDay) = patternStops(route, date) ?: throw LineUnavailableException()
        val (strideRides, firstStopId) = coroutineScope {
            val first = async { transitStop(stops.first())?.stopId }
            // Stride's per-day stop ids only match when the stops came from the route's own day.
            val rides = if (sameDay) {
                runCatching { stride.departures(route.gtfsRouteId, stops.first().gtfsStopId, date) }.getOrDefault(emptyList())
            } else emptyList()
            rides to first.await()
        }
        val rides = strideRides.ifEmpty {
            runCatching { transitousRides(route, stops, firstStopId, date) }.getOrNull().orEmpty()
        }
        // Prefer a daytime ride; any ride of the pattern shares the shape.
        val shape = rides.sortedBy { if (it.departure.atZone(IsraelZone).hour < 5) 1 else 0 }.take(2)
            .firstNotNullOfOrNull { r -> trip(rideTripId(route, stops, firstStopId, r))?.geometry?.takeIf { it.size >= 2 } }
            .orEmpty()
        return LineDetail(route, stops, shape, rides, TripTimeline.fromLineStops(stops, shape), firstStopId)
    }

    /** Transitous trip id of one of [detail]'s rides, e.g. to open it on the trip screen. */
    suspend fun rideTripId(detail: LineDetail, ride: Ride): String =
        rideTripId(detail.route, detail.stops, detail.firstStopId, ride)

    /**
     * Stride and Transitous load different daily GTFS feeds, whose trip ids share the trip
     * number but not the feed-date suffix ("279000_270926" vs "279000_280926"), so a Stride
     * ride is looked up by trip number among the departures from its first stops.
     */
    private suspend fun rideTripId(route: LineRoute, stops: List<LineStop>, firstStopId: String?, ride: Ride): String {
        ride.tripId?.let { return it }
        for (i in 0..minOf(1, stops.lastIndex)) {
            val stopId = transitStopId(stops, i, firstStopId) ?: continue
            val at = ride.departure.plusSeconds(stops[i].offsetSec)
            val deps = runCatching { transitous.stopTimes(stopId, time = at.minusSeconds(60), count = 20, language = language) }
                .getOrNull().orEmpty()
            matchTripId(deps, ride, route.lineRef, stops[i].offsetSec)?.let { return it }
        }
        return TripIds.fromJourney(ride.journeyRef, ride.departure, LocalDate.parse(route.date))
    }

    /**
     * Transitous stop id of stop [i]. Transitous lists no departures at a terminus where
     * boarding isn't allowed (an "operational stop"), so callers also try the second stop.
     */
    private suspend fun transitStopId(stops: List<LineStop>, i: Int, firstStopId: String?): String? =
        if (i == 0) firstStopId else transitStop(stops[i])?.stopId

    /**
     * The route's ordered stops, and whether they came from [date] itself. Stride lists a day's
     * rides before it has loaded their stops (for some routes, for hours), so when the day has
     * none yet, fall back to the most recent earlier day of the same line ref (same pattern).
     */
    private suspend fun patternStops(route: LineRoute, date: LocalDate): Pair<List<LineStop>, Boolean>? {
        rideStopsOf(route.gtfsRouteId, date)?.let { return it to true }
        for (back in 1L..PATTERN_LOOKBACK_DAYS) {
            val day = date.minusDays(back)
            val stops = runCatching {
                stride.routesByLineRef(listOf(route.lineRef), day).firstOrNull()?.let { rideStopsOf(it.gtfsRouteId, day) }
            }.getOrNull()
            if (stops != null) return stops to false
        }
        return null
    }

    /** Stops of the first ride of [gtfsRouteId] that has any, or null. */
    private suspend fun rideStopsOf(gtfsRouteId: Long, date: LocalDate): List<LineStop>? {
        for (ride in stride.rides(gtfsRouteId).take(3)) {
            val stops = stride.rideStops(ride.id, date)
            if (stops.size >= 2) return stops
            // Stops are loaded per day, not per ride: one empty ride means the rest are empty too.
            if (stops.isEmpty()) return null
        }
        return null
    }

    /** The day's departures of [route] from its first stop, from Transitous, when Stride has none. */
    private suspend fun transitousRides(route: LineRoute, stops: List<LineStop>, firstStopId: String?, date: LocalDate): List<Ride> {
        val dayStart = date.atStartOfDay(IsraelZone).toInstant()
        for (i in 0..minOf(1, stops.lastIndex)) {
            val stopId = transitStopId(stops, i, firstStopId) ?: continue
            val deps = transitous.stopTimes(stopId, time = dayStart, count = 500, language = language)
            val rides = ridesFromDepartures(deps, route.lineRef, dayStart, stops[i].offsetSec)
            if (rides.isNotEmpty()) return rides
        }
        return emptyList()
    }

    /** Transitous stop for a Stride stop: Stride knows stop codes, Transitous needs the GTFS stop_id. */
    suspend fun transitStop(s: LineStop): Place? =
        runCatching { transitous.nearbyStops(GeoPoint(s.lat, s.lon), 0.0015) }.getOrDefault(emptyList())
            .firstOrNull { it.subtitle?.startsWith("#${s.code}") == true }

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
        /** How far back to look for late trips whose scheduled time at a stop already passed. */
        const val LATE_WINDOW_SEC = 30 * 60L

        /** Upcoming stop times plus recently-scheduled ones (possibly running late), without duplicates. */
        fun mergeDepartures(upcoming: List<Departure>, recent: List<Departure>, now: Instant): List<Departure> {
            val seen = HashSet<String>()
            return (recent + upcoming)
                .filter { it.scheduled.isAfter(now.minusSeconds(LATE_WINDOW_SEC)) }
                .filter { seen.add(it.tripId + "|" + it.scheduled.epochSecond) }
                .sortedBy { it.scheduled }
        }

        /**
         * Drops trips that already left (passed, or overdue and not tracked) and orders the rest
         * by best known arrival, so a late earlier trip shows ahead of the next scheduled one.
         */
        fun arrangeBoard(entries: List<BoardEntry>, now: Instant): List<BoardEntry> =
            entries
                .filter { it.live?.status != LiveStatus.PASSED }
                .filter { (it.live?.best ?: it.departure.scheduled).isAfter(now.minusSeconds(60)) }
                .sortedBy { it.live?.best ?: it.departure.scheduled }

        private const val PATTERN_LOOKBACK_DAYS = 7L

        /**
         * Departures of line [lineRef] during the service day starting at [dayStart], as rides.
         * [offsetSec] is the stop's scheduled offset from the first stop, where rides depart.
         */
        fun ridesFromDepartures(deps: List<Departure>, lineRef: Long, dayStart: Instant, offsetSec: Long = 0): List<Ride> {
            val end = dayStart.plusSeconds(30 * 3600L)
            return deps
                .filter { TripIds.lineRef(it.routeId) == lineRef.toString() }
                .mapNotNull { d -> TripIds.parse(d.tripId)?.let { Ride(it.gtfsTripId, d.scheduled.minusSeconds(offsetSec), d.tripId) } }
                .filter { !it.departure.isBefore(dayStart) && it.departure.isBefore(end) }
                .distinctBy { it.journeyRef }
                .sortedBy { it.departure }
        }

        /**
         * Transitous trip id of [ride] among departures from a stop [offsetSec] into the line:
         * same trip number, else same line at the same time.
         */
        fun matchTripId(deps: List<Departure>, ride: Ride, lineRef: Long, offsetSec: Long = 0): String? {
            val at = ride.departure.plusSeconds(offsetSec)
            return deps.firstOrNull { TripIds.parse(it.tripId)?.tripNumber == ride.tripNumber }?.tripId
                ?: deps.firstOrNull { TripIds.lineRef(it.routeId) == lineRef.toString() && it.scheduled == at }?.tripId
        }

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
