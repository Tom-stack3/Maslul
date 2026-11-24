package com.maslul.app.data

import java.time.Instant

/** A bus or train you might be on right now. */
data class BoardCandidate(
    /** Transitous trip id, when known (timetable matches); live-only ones are looked up when picked. */
    val tripId: String?,
    val lineRef: String?,
    val lineLabel: String,
    val mode: TransitMode,
    val headsign: String,
    val agencyName: String?,
    val agencyId: String?,
    val routeColor: Int?,
    /** Its live vehicle, when tracked. */
    val vehicle: Vehicle?,
    /** From you to its live vehicle, or else to the stop near you where it's due around now. */
    val distanceM: Double,
    /** That stop and when it's due there, for rides known from the timetable. */
    val stopName: String? = null,
    val stopTime: Instant? = null,
    /** Which way it's going near you (degrees from north): its live vehicle's heading, or from that stop to the next. */
    val bearing: Float? = null,
    /** The stop it calls at after [stopName], which tells its direction apart at a stop both ways share. */
    val nextStopName: String? = null,
) {
    val live get() = vehicle != null
    val key get() = tripId ?: "${lineRef}|${vehicle?.journeyRef}|${vehicle?.originDeparture?.epochSecond}"
}

/** Where to pick up a trip you're already on: its next stop. */
data class OnBoardStart(val trip: Leg, val nextIndex: Int, val next: StopCall)

/**
 * Working out which ride you're on from where you are: rides due around now at the stops
 * around you (from the timetable), and live vehicles close to you. A ride whose live vehicle is
 * far away isn't yours, whatever the timetable says.
 */
object OnBoard {
    /** How far back and ahead of now a ride may be due at a stop near you. */
    const val BEFORE_SEC = 25 * 60L
    const val AFTER_SEC = 15 * 60L
    /** Stops farther than this aren't "around you". */
    const val STOP_RADIUS_M = 1000.0
    /** A timetable ride a minute off counts like being this much farther away, when ordering. */
    private const val M_PER_MIN = 60.0

    /**
     * How far from you a vehicle you're on can be reported: its position is up to a couple of
     * minutes old, and an intercity bus on the highway covers ~25 m/s. Its reported speed when
     * it's faster still; ~20 m/s when it has none.
     */
    fun reach(v: Vehicle, now: Instant): Double {
        val age = (now.epochSecond - v.recordedAt.epochSecond).coerceAtLeast(0)
        val speed = maxOf(DEFAULT_MPS, (v.velocityKmh ?: 0) / 3.6)
        return 350.0 + minOf(4000.0, age * speed)
    }

    private const val DEFAULT_MPS = 20.0

    /** Whether [q] is a line number ("125", "1א") rather than a destination: those are found anywhere. */
    fun isLineNumber(q: String) = q.any(Char::isDigit) && q.all { it.isDigit() || it in 'א'..'ת' }

    /** Live vehicles within [reach] of [here], nearest first. */
    fun nearVehicles(here: GeoPoint, byLine: Map<String, List<Vehicle>>, now: Instant): List<Vehicle> =
        byLine.values.flatten()
            .filter { GeoMath.distance(here, it.point) <= reach(it, now) }
            .sortedBy { GeoMath.distance(here, it.point) }

    /**
     * Candidates from departures at the stops around you ([stopDistance] to each departure's
     * stop), matched against live vehicles: one per trip, at its most likely stop.
     */
    fun fromDepartures(
        here: GeoPoint,
        departures: List<Pair<Double, Departure>>,
        byLine: Map<String, List<Vehicle>>,
        now: Instant,
    ): List<BoardCandidate> {
        val out = HashMap<String, Pair<Double, BoardCandidate>>()
        for ((stopDistance, d) in departures) {
            if (stopDistance > STOP_RADIUS_M) continue
            val dt = d.scheduled.epochSecond - now.epochSecond
            if (dt < -BEFORE_SEC || dt > AFTER_SEC) continue
            val lineRef = TripIds.lineRef(d.routeId)
            val parsed = TripIds.parse(d.tripId)
            val v = lineRef?.let { byLine[it] }.orEmpty().forTrip(parsed?.tripNumber, parsed?.originDeparture)
            val vd = v?.let { GeoMath.distance(here, it.point) }
            // Tracked somewhere else: not the one you're on.
            if (v != null && vd!! > reach(v, now) + 500) continue
            val c = BoardCandidate(
                tripId = d.tripId, lineRef = lineRef, lineLabel = d.lineLabel, mode = d.mode, headsign = placeName(d.headsign),
                agencyName = d.agency, agencyId = d.agencyId, routeColor = d.routeColor, vehicle = v,
                distanceM = vd ?: stopDistance, stopName = d.stop.name, stopTime = d.scheduled,
                bearing = v?.bearing ?: d.next?.let { GeoMath.bearing(d.stop.point, it.point) }, nextStopName = d.next?.name,
            )
            val score = score(c, now)
            val prev = out[d.tripId]
            if (prev == null || score < prev.first) out[d.tripId] = score to c
        }
        return out.values.map { it.second }
    }

    /** A live vehicle of [route] not known from the timetable around you: its trip is looked up when picked. */
    fun tracked(route: LineRoute, v: Vehicle, here: GeoPoint) = BoardCandidate(
        null, v.lineRef, route.label, route.mode, placeName(route.destination), route.agency, route.operatorRef.toString(), null, v,
        GeoMath.distance(here, v.point), bearing = v.bearing,
    )

    /** Live vehicles of [routes] (a line's directions and alternatives), wherever they are; nearest first. */
    fun ofLine(here: GeoPoint, routes: List<LineRoute>, byLine: Map<String, List<Vehicle>>, max: Int = 12): List<BoardCandidate> =
        routes.distinctBy { it.lineRef }
            .flatMap { r -> byLine[r.lineRef.toString()].orEmpty().map { tracked(r, it, here) } }
            .sortedBy { it.distanceM }
            .take(max)

    /** [name], unless it's a code rather than a place (trains are signed with their number: "785"). */
    fun placeName(name: String) = name.takeIf { n -> n.any(Char::isLetter) }.orEmpty()

    /** Farther off than this from the way you're moving, a ride is going the other way. */
    private const val AGAINST_DEG = 110f

    /**
     * Whether [c] goes the other way from where you're [heading] (degrees from north, null when
     * not known): both directions of a line stop at the same station, often minutes apart.
     */
    fun against(c: BoardCandidate, heading: Float?): Boolean {
        val b = c.bearing ?: return false
        return heading != null && Math.abs(GeoMath.angleDiff(heading, b)) > AGAINST_DEG
    }

    /**
     * Lower is likelier: live vehicles by distance first, then timetable rides by distance and
     * time; rides going the other way from your [heading] after all of them.
     */
    fun score(c: BoardCandidate, now: Instant, heading: Float? = null): Double =
        (if (against(c, heading)) 1_000_000.0 else 0.0) +
            if (c.live) c.distanceM
            else 100_000.0 + c.distanceM + M_PER_MIN * Math.abs((c.stopTime ?: now).epochSecond - now.epochSecond) / 60.0

    fun rank(list: List<BoardCandidate>, now: Instant, heading: Float? = null): List<BoardCandidate> =
        list.distinctBy { it.key }.sortedBy { score(it, now, heading) }

    /** Time to step off and get to another ride at the same stop. */
    const val CHANGE_SEC = 120L
    private const val MAX_ALIGHT = 6

    /**
     * Stops (indexes into [calls]) after [next] to try getting off at: all of them on a short
     * remainder, else an even spread plus the one nearest [dest] and the last.
     */
    fun alightStops(calls: List<StopCall>, next: Int, dest: GeoPoint): List<Int> {
        val rest = (next + 1..calls.lastIndex).toList()
        if (rest.size <= MAX_ALIGHT) return rest
        val step = rest.size.toDouble() / (MAX_ALIGHT - 2)
        val spread = (0 until MAX_ALIGHT - 2).map { rest[(it * step).toInt()] }
        val nearest = rest.minBy { GeoMath.distance(calls[it].point, dest) }
        return (spread + nearest + calls.lastIndex).distinct().sorted()
    }

    /** The part of [trip] from stop [from] to stop [to] (indexes into [calls]): staying on it. */
    fun ride(trip: Leg, calls: List<StopCall>, timeline: TripTimeline?, from: Int, to: Int, tripId: String? = trip.tripId): Leg {
        val a = calls[from]
        val b = calls[to]
        val path = timeline?.slice(timeline.stopAlong[from], timeline.stopAlong[to]).orEmpty()
        return trip.copy(
            from = a,
            to = b,
            start = a.scheduledDeparture ?: a.scheduledArrival ?: trip.start,
            end = b.scheduledArrival ?: b.scheduledDeparture ?: trip.end,
            intermediateStops = calls.subList(from + 1, to),
            geometry = path.ifEmpty { calls.subList(from, to + 1).map { it.point } },
            distanceM = null,
            tripId = tripId,
            alternatives = emptyList(),
        )
    }

    /** Staying on for [ride], then [rest] from where it gets off, as one trip. */
    fun compose(ride: Leg, rest: Itinerary?): Itinerary {
        val legs = listOf(ride) + rest?.legs.orEmpty()
        return Itinerary("board:${ride.to.stopId ?: ride.to.name}:${rest?.id.orEmpty()}", ride.start, legs.last().end,
            legs.count { it.mode.isTransit } - 1, legs)
    }

    /**
     * Index (into [calls]) of the trip's next stop: from where you are along its route when
     * you're on it, else from its live vehicle, else from the timetable.
     */
    fun nextStop(calls: List<StopCall>, timeline: TripTimeline?, here: GeoPoint?, progress: TripTimeline.Progress?, now: Instant): Int {
        val last = calls.lastIndex
        if (timeline != null && here != null && timeline.line.size >= 2) {
            // Near the vehicle's (or the timetable's) position, so a route looping back on itself doesn't confuse it.
            val hint = progress?.alongM ?: calls.indexOfFirst { (it.scheduledTime ?: now).isAfter(now) }
                .takeIf { it >= 0 }?.let { timeline.stopAlong[it] }
            // Only stretches the route really passes twice (within 60 m) are told apart by the hint; a late bus is behind the timetable.
            val p = GeoMath.project(timeline.line, GeoMath.cumulative(timeline.line), here, hintAlongM = hint, snapM = 60.0)
            if (p != null && p.offsetM <= 250) {
                val i = timeline.stopAlong.indexOfFirst { it > p.alongM + 25 }.let { if (it < 0) last else it.coerceIn(0, last) }
                // The bus is at least as far as its last report, which may be ahead of a fix matched a little short.
                return maxOf(i, progress?.let { it.lastPassed + 1 } ?: 0).coerceAtMost(last)
            }
        }
        if (progress != null) return (progress.lastPassed + 1).coerceIn(0, last)
        val i = calls.indexOfFirst { (it.scheduledTime ?: Instant.MAX).isAfter(now) }
        return if (i < 0) last else i
    }
}
