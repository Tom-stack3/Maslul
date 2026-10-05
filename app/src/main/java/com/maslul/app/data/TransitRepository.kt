package com.maslul.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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

/** No driving route between the places of a [RideOffer]. */
class NoDriveException : IllegalStateException("Couldn't find a driving route for this ride.")

/** Neither the line's own day nor recent days have stop data for it. */
class LineUnavailableException : IllegalStateException("No stop data for this line right now. Please try again later.")

/** A live vehicle on a line, with its estimated progress along the line's stops. */
data class LineVehicle(val vehicle: Vehicle, val progress: TripTimeline.Progress, val ride: Ride?)

/** Next arrival at one stop of a line. */
data class StopArrival(
    val time: Instant,
    val live: Boolean,
    val delaySec: Long,
    val journeyRef: String?,
    /** When the live vehicle last reported its position; null for timetable arrivals. */
    val recordedAt: Instant? = null,
)

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

    private val rideCache = object : LinkedHashMap<String, List<Leg>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Leg>>?) = size > 60
    }

    private val carCache = object : LinkedHashMap<String, Leg>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Leg>?) = size > 24
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

    /** Stops and stations matching [text], biased towards [near]. */
    suspend fun searchStops(text: String, near: GeoPoint?): List<Place> =
        transitous.geocode(text, near, language, type = "STOP").filter { it.kind == PlaceKind.STOP && it.stopId != null }

    // ---- Routing --------------------------------------------------------------------

    suspend fun plan(req: TransitousApi.PlanRequest): PlanResult = transitous.plan(req.copy(language = language))

    /**
     * Direct rides (any line) between [leg]'s boarding and alighting stops around its time,
     * so an option can offer every line that makes the same hop. Cached per 15-minute bucket.
     */
    suspend fun rideAlternatives(leg: Leg, modes: Set<TransitMode>): List<Leg> {
        // The user's own shuttle isn't in the open data; its timetable is (see [ShuttleSchedule]).
        if (leg.shuttleId != null) return emptyList()
        val key = rideBucket(leg)
        synchronized(rideCache) { rideCache[key] }?.let { return it }
        val req = TransitousApi.PlanRequest(
            from = leg.from.point,
            to = leg.to.point,
            fromStopId = leg.from.stopId,
            toStopId = leg.to.stopId,
            // From well before the ride: an earlier bus running late may come first.
            time = leg.start.minusSeconds(Combine.LATE_SEC),
            modes = modes,
            maxWalkMinutes = 3,
            maxTransfers = 0,
            minTransferMinutes = 0,
            additionalTransferMinutes = 0,
            numItineraries = 24,
            // Cover every ride from the late window up to the latest alternative.
            searchWindowSec = (Combine.LATE_SEC + Combine.WINDOW_SEC + 10 * 60).toInt(),
            language = language,
        )
        val rides = transitous.plan(req).itineraries
            .mapNotNull { it.transitLegs.singleOrNull() }
            .filter { Combine.sameRide(it, leg) }
        synchronized(rideCache) { rideCache[key] = rides }
        return rides
    }

    /**
     * The next direct rides (any line) between [leg]'s stops after it leaves: how long the wait
     * is if it's missed. Looks up to an hour ahead; cached per ride.
     */
    suspend fun ridesAfter(leg: Leg, modes: Set<TransitMode>): List<Leg> {
        if (leg.shuttleId != null) return emptyList()
        val key = "after|${leg.from.stopId ?: leg.from.point}|${leg.to.stopId ?: leg.to.point}|${leg.start.epochSecond}"
        synchronized(rideCache) { rideCache[key] }?.let { return it }
        val req = TransitousApi.PlanRequest(
            from = leg.from.point,
            to = leg.to.point,
            fromStopId = leg.from.stopId,
            toStopId = leg.to.stopId,
            time = leg.start.plusSeconds(60),
            modes = modes,
            maxWalkMinutes = 3,
            maxTransfers = 0,
            minTransferMinutes = 0,
            additionalTransferMinutes = 0,
            numItineraries = 5,
            searchWindowSec = 60 * 60,
            language = language,
        )
        val rides = transitous.plan(req).itineraries
            .mapNotNull { it.transitLegs.singleOrNull() }
            .let { Combine.ridesAfter(leg.copy(alternatives = emptyList()), it, count = 5) }
        synchronized(rideCache) { rideCache[key] = rides }
        return rides
    }

    // ---- Rides, shuttles and being on board --------------------------------------------

    /** Driving route from [from] to [to] (a single car leg with its geometry), leaving at [at]. */
    suspend fun carRoute(from: GeoPoint, to: GeoPoint, at: Instant? = null): Leg? {
        val key = "%.4f,%.4f>%.4f,%.4f".format(from.lat, from.lon, to.lat, to.lon)
        synchronized(carCache) { carCache[key] }?.let { c ->
            // Same road; only the times move.
            val shift = at?.let { it.epochSecond - c.start.epochSecond } ?: 0
            return c.copy(start = c.start.plusSeconds(shift), end = c.end.plusSeconds(shift))
        }
        val req = TransitousApi.PlanRequest(
            from = from, to = to, time = at, directModes = "CAR", maxDirectMinutes = 6 * 60, directOnly = true, numItineraries = 1,
            language = language,
        )
        val car = transitous.plan(req).carOnly?.legs?.firstOrNull { it.mode == TransitMode.CAR } ?: return null
        synchronized(carCache) { carCache[key] = car }
        return car
    }

    /**
     * Options for a lift with a driver heading to [driverDest]: get out at the drop-off along the
     * way that gets you to [req]'s destination best, then continue by transit. [score] ranks.
     */
    suspend fun rideAlong(
        req: TransitousApi.PlanRequest,
        driverDest: GeoPoint,
        score: (Itinerary) -> Double,
        /** The options found so far, each time a drop-off is done: the first ones show while the rest load. */
        progress: (List<Itinerary>) -> Unit = {},
    ): List<Itinerary> = coroutineScope {
        val leave = (req.time ?: Instant.now()).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val route = carRoute(req.from, driverDest, leave) ?: throw NoDriveException()
        val found = Found(score, progress)
        val gate = Semaphore(FAN_OUT)
        // The likeliest drop-offs first (by where you're going, and the driver's end), so they show soonest.
        RidePlanner.dropOffs(route, req.to).withIndex()
            .sortedBy { (_, d) -> GeoMath.distance(d.point, req.to) }
            .map { (i, drop) ->
                async {
                    gate.withPermit {
                        val car = RidePlanner.carLeg(route, drop, leave)
                        val r = onward(req.copy(from = drop.point, fromStopId = null, time = car.end.plusSeconds(RidePlanner.STOP_SEC)))
                            ?: return@withPermit
                        val walk = r.walkOnly?.takeIf { it.durationSec <= req.maxWalkMinutes * 60L }
                        found.add((r.itineraries.sortedBy(score).take(3) + listOfNotNull(walk)).map { RidePlanner.compose(car, it, "ride$i:${it.id}") })
                    }
                }
            }.awaitAll()
        found.result()
    }

    /** Options gathered from several starting points, reported as they come in (see [rideAlong], [planOnBoard]). */
    private class Found(private val score: (Itinerary) -> Double, private val progress: (List<Itinerary>) -> Unit) {
        private val all = ArrayList<Itinerary>()

        fun add(options: List<Itinerary>) {
            if (options.isEmpty()) return
            val now = synchronized(all) { all += options; result() }
            progress(now)
        }

        fun result(): List<Itinerary> = synchronized(all) { RidePlanner.distinct(RidePlanner.inReach(all.toList()), score) }
    }

    /** Trips that take one of the user's [shuttles] between [req]'s endpoints. */
    suspend fun shuttleOptions(req: TransitousApi.PlanRequest, shuttles: List<Shuttle>, now: Instant = Instant.now()): List<Itinerary> =
        coroutineScope {
            ShuttleSchedule.relevant(shuttles, req.from, req.to)
                .map { s -> async { runCatching { shuttleOptions(req, s, now) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten()
        }

    /**
     * Up to two trips riding [s]: getting to it (planned to arrive just before it leaves), the
     * shuttle, and on from it; with its next departures as alternatives.
     */
    private suspend fun shuttleOptions(req: TransitousApi.PlanRequest, s: Shuttle, now: Instant): List<Itinerary> = coroutineScope {
        val geometry = async { runCatching { carRoute(s.from.point, s.to.point)?.geometry }.getOrNull().orEmpty() }
        val t = req.time ?: now
        val accessM = GeoMath.distance(req.from, s.from.point)
        val walkSec = (accessM * 1.3 / req.walkSpeedMps).toLong()
        // A lower bound on getting to the shuttle, so departures that can't be made aren't tried.
        val reachSec = when {
            ShuttleSchedule.isNear(req.from, s.from.point) -> 0L
            walkSec <= req.maxWalkMinutes * 60L -> walkSec
            else -> (accessM / 20).toLong()
        }
        val ride = s.rideMinutes * 60L
        val departures = if (req.arriveBy) ShuttleSchedule.departures(s, t.minusSeconds(4 * 3600L), t.minusSeconds(ride)).reversed()
            else ShuttleSchedule.departures(s, t.plusSeconds(reachSec), t.plusSeconds(4 * 3600L))
        val out = ArrayList<Itinerary>()
        for (dep in departures.take(2)) {
            val leg = ShuttleSchedule.leg(s, dep)
            val access = async { accessTo(req, s, dep) }
            val egress = async { egressFrom(req, s, leg.end, if (req.arriveBy) t else null) }
            val a = access.await() ?: continue
            val e = egress.await() ?: continue
            // Leaving after now (or the chosen time), arriving in time.
            if ((a.firstOrNull()?.start ?: dep).isBefore((if (req.arriveBy) now else t).minusSeconds(60))) continue
            val legs = a + leg + e
            out += Itinerary("shuttle:${s.id}:${dep.epochSecond}", legs.first().start, legs.last().end,
                legs.count { it.mode.isTransit } - 1, legs)
            if (out.size >= 2) break
        }
        val path = geometry.await()
        val later = ShuttleSchedule.departures(s, out.minOfOrNull { it.start } ?: t, t.plusSeconds(5 * 3600L))
            .map { ShuttleSchedule.leg(s, it, path) }
        // Two shuttles making the same connection: the later one, with less waiting at the other end.
        out.groupBy { itin -> itin.transitLegs.filter { it.shuttleId == null }.map { it.rideKey } }
            .values.map { g -> g.maxBy { it.legs.first().start } }
            .map { itin ->
            val i = itin.legs.indexOfFirst { it.shuttleId == s.id }
            val drawn = if (path.isEmpty()) itin else itin.copy(legs = itin.legs.toMutableList().also { it[i] = it[i].copy(geometry = path) })
            Combine.addAlternatives(drawn, i, later, now)
        }
    }

    /**
     * The next few ways on from [req]'s start at its time, for one of several starting points tried
     * (drop-offs, stops to get off at). Transitous serves a client's requests one at a time, so
     * these go [FAN_OUT] at once and are kept small; one that stalls is given up on.
     */
    private suspend fun onward(req: TransitousApi.PlanRequest, count: Int = 3): PlanResult? =
        withTimeoutOrNull(ONWARD_TIMEOUT_MS) {
            runCatching {
                plan(req.copy(arriveBy = false, pageCursor = null, numItineraries = count, searchWindowSec = 20 * 60))
            }.getOrNull()
        }

    /** Getting to [s]'s first stop by [dep] (latest start first): no legs when you're there already, null when it can't be made. */
    private suspend fun accessTo(req: TransitousApi.PlanRequest, s: Shuttle, dep: Instant): List<Leg>? {
        if (ShuttleSchedule.isNear(req.from, s.from.point)) return emptyList()
        val by = dep.minusSeconds(60)
        val r = plan(req.copy(to = s.from.point, toStopId = null, time = by, arriveBy = true, pageCursor = null, numItineraries = 3))
        // A direct walk comes timed from the request; walk so as to arrive just before the shuttle.
        val walk = r.walkOnly?.takeIf { it.durationSec <= req.maxWalkMinutes * 60L }?.let { shifted(it, by.epochSecond - it.end.epochSecond) }
        return (r.itineraries + listOfNotNull(walk))
            .filter { !it.legs.last().end.isAfter(dep) }
            .maxWithOrNull(compareBy<Itinerary> { it.legs.first().start }.thenBy { -it.transfers })
            ?.legs
    }

    /** From [s]'s last stop to the destination, leaving at [arrive] (and in by [by] when given). */
    private suspend fun egressFrom(req: TransitousApi.PlanRequest, s: Shuttle, arrive: Instant, by: Instant?): List<Leg>? {
        if (ShuttleSchedule.isNear(s.to.point, req.to)) return emptyList()
        val r = plan(req.copy(from = s.to.point, fromStopId = null, time = arrive.plusSeconds(60), arriveBy = false, pageCursor = null,
            numItineraries = 3))
        val walk = r.walkOnly?.takeIf { it.durationSec <= req.maxWalkMinutes * 60L }?.let { shifted(it, arrive.epochSecond - it.start.epochSecond) }
        return (r.itineraries + listOfNotNull(walk))
            .filter { by == null || !it.legs.last().end.isAfter(by) }
            .minByOrNull { Ranking.score(it, false, req.walkSpeedMps) }
            ?.legs
    }

    private fun shifted(itin: Itinerary, sec: Long) = itin.copy(
        start = itin.start.plusSeconds(sec), end = itin.end.plusSeconds(sec),
        legs = itin.legs.map { it.copy(start = it.start.plusSeconds(sec), end = it.end.plusSeconds(sec)) },
    )

    /** Rides you might be on at [here]: timetable rides due around now at the stops around you, and live vehicles close by. */
    suspend fun boardCandidates(here: GeoPoint, now: Instant = Instant.now()): List<BoardCandidate> = coroutineScope {
        val snapshot = async { live.snapshot() }
        val stops = runCatching { transitous.nearbyStops(here, 0.01) }.getOrDefault(emptyList())
            .filter { it.stopId != null && GeoMath.distance(here, it.point) <= OnBoard.STOP_RADIUS_M }
            .take(8)
        val departures = stops.map { st ->
            async {
                val d = GeoMath.distance(here, st.point)
                runCatching {
                    transitous.stopTimes(st.stopId!!, now.minusSeconds(OnBoard.BEFORE_SEC), count = 40, language = language,
                        windowSec = OnBoard.BEFORE_SEC + OnBoard.AFTER_SEC)
                }.getOrDefault(emptyList()).map { d to it }
            }
        }.awaitAll().flatten()
        val byLine = snapshot.await()?.byLine.orEmpty()
        val timetabled = OnBoard.fromDepartures(here, departures, byLine, now)
        val matched = timetabled.mapNotNull { it.vehicle }.toSet()
        val loose = OnBoard.nearVehicles(here, byLine, now).filter { it !in matched }.take(20)
        // Live vehicles the timetable didn't account for (e.g. on a highway): named from their line.
        val routes = if (loose.isEmpty()) emptyMap() else runCatching {
            val refs = loose.mapNotNull { it.lineRef.toLongOrNull() }.distinct()
            val today = LocalDate.now(IsraelZone)
            stride.routesByLineRef(refs, today).ifEmpty { stride.routesByLineRef(refs, today.minusDays(1)) }
        }.getOrDefault(emptyList()).associateBy { it.lineRef.toString() }
        val tracked = loose.mapNotNull { v ->
            val r = routes[v.lineRef] ?: return@mapNotNull null
            BoardCandidate(null, v.lineRef, r.label, r.mode, OnBoard.placeName(r.destination), r.agency, r.operatorRef.toString(), null, v,
                GeoMath.distance(here, v.point))
        }
        OnBoard.rank(timetabled + tracked, now)
    }

    /** The Transitous trip of [c] (a live vehicle's, found through the stop it's heading to), or null. */
    suspend fun boardTripId(c: BoardCandidate, now: Instant = Instant.now()): String? {
        c.tripId?.let { return it }
        val v = c.vehicle ?: return null
        val nearby = runCatching { transitous.nearbyStops(v.point, 0.02) }.getOrDefault(emptyList())
        val next = v.nextStopCode?.let { code -> nearby.firstOrNull { it.subtitle?.substringBefore(" ") == "#$code" } }
        for (stop in (listOfNotNull(next) + nearby.take(3)).distinctBy { it.stopId }) {
            val stopId = stop.stopId ?: continue
            val deps = runCatching {
                transitous.stopTimes(stopId, now.minusSeconds(90 * 60), count = 60, language = language, windowSec = 3 * 3600L)
            }.getOrDefault(emptyList())
            val sameLine = deps.filter { TripIds.lineRef(it.routeId) == v.lineRef }
            val match = sameLine.firstOrNull { TripIds.parse(it.tripId)?.tripNumber == v.journeyRef }
                ?: sameLine.firstOrNull { d ->
                    TripIds.parse(d.tripId)?.let { Math.abs(it.originDeparture.epochSecond - v.originDeparture.epochSecond) < 60 } == true
                }
            if (match != null) return match.tripId
        }
        return null
    }

    /**
     * Ways on from the trip being ridden ([start]): staying on to one of its later stops and
     * going on from there, or getting off at the next stop to change. The planner alone won't
     * reliably offer staying on: from the next stop, a faster line leaving that minute beats it.
     */
    suspend fun planOnBoard(
        req: TransitousApi.PlanRequest,
        start: OnBoardStart,
        score: (Itinerary) -> Double,
        progress: (List<Itinerary>) -> Unit = {},
    ): List<Itinerary> =
        coroutineScope {
            val trip = start.trip
            val calls = listOf(trip.from) + trip.intermediateStops + trip.to
            val timeline = TripTimeline.fromTrip(trip)
            val tripId = trip.tripId
            val found = Found(score, progress)
            val gate = Semaphore(FAN_OUT)
            // Every option starts on this bus, from the stop it last passed: they all start now, so they
            // compare by when they get there (getting off to wait for a later one loses to staying on).
            val from = (start.nextIndex - 1).coerceAtLeast(0)
            val toNext = if (from < start.nextIndex) OnBoard.ride(trip, calls, timeline, from, start.nextIndex, tripId) else null
            val next = calls[start.nextIndex]
            // The stop nearest where you're going first, then changing at the next stop, then the rest.
            val stops = OnBoard.alightStops(calls, start.nextIndex, req.to).sortedBy { GeoMath.distance(calls[it].point, req.to) }
            val stays = stops.take(1) + listOf(-1) + stops.drop(1)
            stays.map { k ->
                async {
                    gate.withPermit {
                        if (k < 0) {
                            // Getting off at the next stop to change there.
                            val at = (next.scheduledArrival ?: next.scheduledDeparture ?: return@withPermit).plusSeconds(OnBoard.CHANGE_SEC)
                            val r = onward(req.copy(fromStopId = next.stopId, time = at), count = 4) ?: return@withPermit
                            found.add(r.itineraries.filter { it.transitLegs.none { l -> l.tripId == tripId } }.sortedBy(score).take(4)
                                .map { c -> toNext?.let { OnBoard.compose(it, c) } ?: c })
                            return@withPermit
                        }
                        val ride = OnBoard.ride(trip, calls, timeline, from, k, tripId)
                        if (ShuttleSchedule.isNear(calls[k].point, req.to)) { found.add(listOf(OnBoard.compose(ride, null))); return@withPermit }
                        val r = onward(req.copy(from = calls[k].point, fromStopId = calls[k].stopId, time = ride.end.plusSeconds(60)))
                            ?: return@withPermit
                        val walk = r.walkOnly?.takeIf { it.durationSec <= req.maxWalkMinutes * 60L }
                        // Boarding this same trip again from a later stop is just staying on longer.
                        found.add((r.itineraries.filter { it.transitLegs.none { l -> l.tripId == tripId } }.sortedBy(score).take(2) + listOfNotNull(walk))
                            .map { OnBoard.compose(ride, it) })
                    }
                }
            }.awaitAll()
            found.result()
        }

    /** Where trip [tripId] (which the user is on, at [here]) goes next. */
    suspend fun onBoardStart(tripId: String, here: GeoPoint?, now: Instant = Instant.now()): OnBoardStart? {
        val trip = trip(tripId) ?: return null
        val calls = listOf(trip.from) + trip.intermediateStops + trip.to
        val timeline = TripTimeline.fromTrip(trip)
        val parsed = TripIds.parse(tripId)
        val v = trip.lineRef?.let { live.vehiclesForLine(it) }.orEmpty().forTrip(parsed?.tripNumber, parsed?.originDeparture)
        val origin = trip.from.scheduledTime ?: parsed?.originDeparture
        val progress = if (timeline != null && v != null && origin != null) timeline.estimate(v, origin, now) else null
        val i = OnBoard.nextStop(calls, timeline, here, progress, now)
        return OnBoardStart(trip.copy(tripId = tripId), i, calls[i])
    }

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

    /**
     * Whether [leg] is worth a live lookup at [now]: it hasn't finished on the timetable
     * (with [graceSec] leeway), or it's scheduled within the late window and its vehicle is on
     * the road — a bus running late whose timetable slot is long gone. Only reads the
     * snapshot, so it's cheap to ask for every late candidate.
     */
    suspend fun worthLive(leg: Leg, now: Instant, graceSec: Long = 0): Boolean {
        if (leg.end.isAfter(now.minusSeconds(graceSec))) return true
        if (leg.start.isBefore(now.minusSeconds(LATE_WINDOW_SEC))) return false
        val parsed = TripIds.parse(leg.tripId) ?: return false
        val vehicles = leg.lineRef?.let { live.vehiclesForLine(it) }.orEmpty()
        return vehicles.forTrip(parsed.tripNumber, parsed.originDeparture) != null
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
            // The whole window, however busy the stop: a count alone can end well short of now.
            runCatching { transitous.stopTimes(stopId, now.minusSeconds(LATE_WINDOW_SEC), count = 10, language = language, windowSec = LATE_WINDOW_SEC) }
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
            out += StopArrival(t, true, lv.progress.delaySec, lv.vehicle.journeyRef, lv.vehicle.recordedAt)
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
        /** Requests at once when trying several starting points (see [onward]). */
        private const val FAN_OUT = 2
        private const val ONWARD_TIMEOUT_MS = 15_000L

        /** How far back to look for late trips whose scheduled time at a stop already passed. */
        const val LATE_WINDOW_SEC = 90 * 60L

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

        fun rideBucket(leg: Leg) =
            "${leg.from.stopId ?: leg.from.point}|${leg.to.stopId ?: leg.to.point}|${leg.start.epochSecond / 900}"

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
                    // Same name close by, or the same name and subtitle within a large site (a campus's
                    // several entries): rows nobody could tell apart.
                    val dup = out.any { o ->
                        o.name.equals(p.name, ignoreCase = true) && GeoMath.distance(o.point, p.point).let { d ->
                            d < 300 || (d < 1500 && o.subtitle.orEmpty() == p.subtitle.orEmpty())
                        }
                    }
                    if (!dup) out += p
                }
            }
            return out.take(14)
        }
    }
}
