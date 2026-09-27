package com.maslul.app.data

import java.time.Instant

/**
 * The scheduled shape of a trip pattern: stop positions along the route geometry and each
 * stop's scheduled offset from the trip's first departure. One timeline serves every trip
 * of the same pattern, so a line's live vehicles can all be estimated against it.
 */
class TripTimeline(
    val stops: List<GeoPoint>,
    val offsetsSec: LongArray,
    shape: List<GeoPoint>,
) {
    val line: List<GeoPoint> = if (shape.size >= 2) shape else stops
    private val cum = GeoMath.cumulative(line)
    val totalM: Double = cum.lastOrNull() ?: 0.0

    /** Distance along [line] of each stop, forced monotonic so loops don't confuse order. */
    val stopAlong: DoubleArray = DoubleArray(stops.size).also { out ->
        var prev = 0.0
        for (i in stops.indices) {
            val hint = if (i == 0) 0.0 else prev + GeoMath.distance(stops[i - 1], stops[i])
            val p = GeoMath.project(line, cum, stops[i], hintAlongM = hint, snapM = 60.0)
            val along = (p?.alongM ?: hint).coerceAtLeast(prev)
            out[i] = along
            prev = along
        }
    }

    data class Progress(
        val vehicle: Vehicle,
        val alongM: Double,
        val delaySec: Long,
        /** Index of the last stop the vehicle has passed, -1 before the first. */
        val lastPassed: Int,
        /** Predicted time at each stop; null for stops already passed. */
        val predicted: List<Instant?>,
        val stale: Boolean,
    ) {
        fun stopsAway(index: Int) = index - lastPassed
    }

    /** Scheduled offset (sec from origin departure) at a distance along the route. */
    fun offsetAt(alongM: Double): Double {
        if (stops.isEmpty()) return 0.0
        if (alongM <= stopAlong[0]) return offsetsSec[0].toDouble()
        for (i in 0 until stops.size - 1) {
            val a = stopAlong[i]
            val b = stopAlong[i + 1]
            if (alongM <= b) {
                val frac = if (b - a < 1) 1.0 else (alongM - a) / (b - a)
                return offsetsSec[i] + frac * (offsetsSec[i + 1] - offsetsSec[i])
            }
        }
        return offsetsSec.last().toDouble()
    }

    fun estimate(v: Vehicle, origin: Instant, now: Instant): Progress? {
        if (stops.size < 2) return null
        val cumLine = cum
        val proj = GeoMath.project(line, cumLine, v.point, hintAlongM = v.distanceFromStart, snapM = 80.0)
        val along = when {
            proj != null && proj.offsetM <= 400 -> proj.alongM
            v.distanceFromStart != null -> v.distanceFromStart.coerceIn(0.0, totalM)
            else -> return null
        }
        val atOrigin = along - stopAlong[0] < 60
        val rawDelay = if (atOrigin) {
            // Waiting at the terminal: late only if it's already past departure time.
            (v.recordedAt.epochSecond - (origin.epochSecond + offsetsSec[0])).coerceAtLeast(0)
        } else {
            v.recordedAt.epochSecond - (origin.epochSecond + offsetAt(along).toLong())
        }
        val delay = rawDelay.coerceIn(-10 * 60L, 90 * 60L)

        var lastPassed = -1
        for (i in stops.indices) if (stopAlong[i] < along - 30) lastPassed = i
        if (atOrigin) lastPassed = -1

        val predicted = stops.indices.map { j ->
            if (j <= lastPassed) {
                null
            } else {
                val byDelay = origin.epochSecond + offsetsSec[j] + delay
                // Never faster than ~60 km/h from the reported position, never in the past.
                val byDistance = v.recordedAt.epochSecond + ((stopAlong[j] - along).coerceAtLeast(0.0) / 17.0).toLong()
                Instant.ofEpochSecond(maxOf(byDelay, byDistance, now.epochSecond))
            }
        }
        return Progress(
            vehicle = v,
            alongM = along,
            delaySec = delay,
            lastPassed = lastPassed,
            predicted = predicted,
            stale = now.epochSecond - v.recordedAt.epochSecond > 180,
        )
    }

    /** Where to draw a vehicle marker: its reported position, snapped onto the route if near. */
    fun pointAt(alongM: Double): GeoPoint {
        if (line.isEmpty()) return GeoPoint(0.0, 0.0)
        for (i in 0 until line.size - 1) {
            if (alongM <= cum[i + 1]) {
                val seg = cum[i + 1] - cum[i]
                val t = if (seg <= 0) 0.0 else (alongM - cum[i]) / seg
                val a = line[i]
                val b = line[i + 1]
                return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
        }
        return line.last()
    }

    /** The part of [line] between two distances along it. */
    fun slice(fromM: Double, toM: Double): List<GeoPoint> {
        if (line.size < 2 || toM <= fromM) return emptyList()
        val out = arrayListOf(pointAt(fromM))
        for (i in line.indices) if (cum[i] > fromM && cum[i] < toM) out += line[i]
        out += pointAt(toM)
        return out
    }

    companion object {
        /** Timeline for a trip returned by the planner (all stops with scheduled times). */
        fun fromTrip(trip: Leg): TripTimeline? {
            val calls = listOf(trip.from) + trip.intermediateStops + trip.to
            val times = calls.map { it.scheduledTime }
            val t0 = times.firstOrNull() ?: return null
            if (times.any { it == null }) return null
            return TripTimeline(
                stops = calls.map { it.point },
                offsetsSec = LongArray(calls.size) { times[it]!!.epochSecond - t0.epochSecond },
                shape = trip.geometry,
            )
        }

        fun fromLineStops(stops: List<LineStop>, shape: List<GeoPoint>) = TripTimeline(
            stops = stops.map { GeoPoint(it.lat, it.lon) },
            offsetsSec = LongArray(stops.size) { stops[it].offsetSec },
            shape = shape,
        )
    }
}

/** Where a live vehicle still has to go before reaching the boarding stop. */
data class LiveApproach(val path: List<GeoPoint>, val stops: List<GeoPoint>)

enum class LiveStatus {
    /** Vehicle is tracked and heading to the stop. */
    LIVE,
    /** Trip hasn't started yet; showing the timetable. */
    SCHEDULED,
    /** Trip should be running but isn't reporting. */
    UNTRACKED,
    /** The vehicle already passed the stop. */
    PASSED,
}

data class LiveCall(
    val status: LiveStatus,
    val scheduled: Instant,
    val expected: Instant?,
    val delaySec: Long = 0,
    val stopsAway: Int? = null,
    val vehicle: Vehicle? = null,
    val alightExpected: Instant? = null,
    val progress: TripTimeline.Progress? = null,
) {
    val best: Instant get() = expected ?: scheduled
}

object LiveCalls {
    /**
     * Live state of one trip at stop [boardIndex] (and optionally [alightIndex]).
     * [vehicle] is the vehicle matched to this trip, if any.
     */
    fun compute(
        timeline: TripTimeline,
        origin: Instant,
        boardIndex: Int,
        alightIndex: Int?,
        vehicle: Vehicle?,
        now: Instant,
    ): LiveCall {
        val scheduled = origin.plusSeconds(timeline.offsetsSec[boardIndex])
        if (vehicle == null) {
            val status = if (now.isBefore(origin.plusSeconds(60))) LiveStatus.SCHEDULED else LiveStatus.UNTRACKED
            return LiveCall(status, scheduled, null)
        }
        val p = timeline.estimate(vehicle, origin, now) ?: return LiveCall(LiveStatus.UNTRACKED, scheduled, null, vehicle = vehicle)
        if (p.lastPassed >= boardIndex) {
            return LiveCall(LiveStatus.PASSED, scheduled, null, p.delaySec, 0, vehicle, alightIndex?.let { p.predicted[it] }, p)
        }
        return LiveCall(
            status = LiveStatus.LIVE,
            scheduled = scheduled,
            expected = p.predicted[boardIndex],
            delaySec = p.delaySec,
            stopsAway = p.stopsAway(boardIndex),
            vehicle = vehicle,
            alightExpected = alightIndex?.let { p.predicted[it] },
            progress = p,
        )
    }
}
