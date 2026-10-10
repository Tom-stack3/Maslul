package com.maslul.app.data

import java.time.Instant

/** Someone driving you part of the way; the rest is by transit. */
sealed interface RideOffer {
    /** The driver is going to [dest]: get out wherever on the way suits you best. */
    data class ToPlace(val dest: Place) : RideOffer

    /** The driver takes you wherever you like, up to [minutes] of driving. */
    data class Within(val minutes: Int) : RideOffer

    companion object {
        val MINUTES = listOf(5, 10, 15, 20, 30, 45)

        /**
         * How a lift [Within] a drive reaches the first stop: driven right to it. MOTIS's
         * "CAR_DROPOFF" mixes driving and walking freely: it can drop you off a few meters along, or
         * walk you across a stretch no car can use and put you back in a car on the other side.
         */
        const val WITHIN_MODES = "CAR"
    }
}

/**
 * Where along a driver's route to get out. The route is sampled every few minutes of driving
 * (plus the point closest to where you're going, and the driver's own destination); transit is
 * planned from each point for when you'd get there, and the best of those wins.
 */
object RidePlanner {
    /** A place on the driver's route to get out at, [atSec] seconds of driving in. */
    data class DropOff(val point: GeoPoint, val alongM: Double, val atSec: Long)

    /** Getting out and walking away takes a moment. */
    const val STOP_SEC = 60L
    private const val MIN_STEP_SEC = 5 * 60L
    /** Evenly spaced drop-offs; with the one nearest the destination and the end, at most 6 to plan from. */
    private const val MAX_SAMPLES = 4
    /** Drop-offs this close in driving time are the same place. */
    private const val SAME_SEC = 90L

    /**
     * Candidate drop-offs along [route] (a car leg with geometry): evenly spaced in driving time,
     * the point nearest [target], and the route's end. Soonest first.
     */
    fun dropOffs(route: Leg, target: GeoPoint): List<DropOff> {
        val line = route.geometry.ifEmpty { listOf(route.from.point, route.to.point) }
        val cum = GeoMath.cumulative(line)
        val totalM = cum.last()
        val totalSec = route.durationSec.coerceAtLeast(1)
        if (totalM <= 0.0) return listOf(DropOff(line.last(), 0.0, totalSec))
        fun at(alongM: Double) = DropOff(pointAt(line, cum, alongM), alongM, (totalSec * alongM / totalM).toLong())

        val step = maxOf(MIN_STEP_SEC, totalSec / (MAX_SAMPLES + 1))
        val out = ArrayList<DropOff>()
        var t = step
        while (t < totalSec - SAME_SEC) {
            out += at(totalM * t / totalSec)
            t += step
        }
        // The point nearest where you're going beats a sample next to it.
        GeoMath.project(line, cum, target)?.let { p ->
            val near = at(p.alongM)
            if (near.atSec >= SAME_SEC && near.atSec <= totalSec - SAME_SEC) {
                out.removeAll { Math.abs(it.atSec - near.atSec) < SAME_SEC }
                out += near
            }
        }
        out += DropOff(line.last(), totalM, totalSec)
        return out.sortedBy { it.atSec }
    }

    /** The ride from the start of [route] to [drop], leaving at [leave]. */
    fun carLeg(route: Leg, drop: DropOff, leave: Instant): Leg {
        val line = route.geometry.ifEmpty { listOf(route.from.point, route.to.point) }
        val cum = GeoMath.cumulative(line)
        val path = (listOf(line.first()) + line.indices.filter { cum[it] > 0 && cum[it] < drop.alongM }.map { line[it] } + drop.point)
        val end = leave.plusSeconds(drop.atSec)
        val atEnd = drop.alongM >= cum.last() - 1
        return route.copy(
            from = route.from.copy(scheduledDeparture = leave, scheduledArrival = null),
            to = StopCall(null, if (atEnd) route.to.name else "", null, drop.point.lat, drop.point.lon, end, null),
            start = leave,
            end = end,
            distanceM = drop.alongM,
            geometry = path,
            steps = emptyList(),
            fixed = true,
        )
    }

    /** The lift in [car] and then [rest] (planned from where it ends), as one trip. */
    fun compose(car: Leg, rest: Itinerary, id: String = "ride:${car.durationSec}:${rest.id}"): Itinerary {
        val legs = listOf(car) + rest.legs
        return Itinerary(id, car.start, maxOf(rest.end, car.end), rest.transfers, legs)
    }

    /**
     * One option per way of continuing (the same rides boarded from different drop-offs): the one
     * [score]d best, and on a tie the one that rides further, so there's less waiting by the road.
     */
    fun distinct(options: List<Itinerary>, score: (Itinerary) -> Double): List<Itinerary> =
        options.groupBy { itin -> itin.transitLegs.map { it.rideKey }.ifEmpty { listOf("street") } }
            .values.map { group ->
                group.minWithOrNull(compareBy<Itinerary>(score).thenByDescending { it.legs.first().end })!!
            }
            .sortedBy(score)

    /**
     * [options] without those getting in much later than the soonest (by over half its trip, and
     * at least [slackSec]): waiting hours for a morning bus isn't an option when you're on your way.
     */
    fun inReach(options: List<Itinerary>, slackSec: Long = 30 * 60L): List<Itinerary> {
        val best = options.minByOrNull { it.end } ?: return options
        val limit = best.end.plusSeconds(maxOf(slackSec, best.durationSec / 2))
        return options.filter { !it.end.isAfter(limit) }
    }

    /** Where a ride option gets out: the car leg's end, by name when it has one, else the stop walked to. */
    fun dropOffName(itin: Itinerary): String? {
        val i = itin.legs.indexOfFirst { it.mode == TransitMode.CAR }
        if (i < 0) return null
        val car = itin.legs[i]
        car.to.name.takeIf { it.isNotBlank() }?.let { return it }
        return itin.legs.drop(i + 1).firstOrNull { it.mode.isTransit }?.from?.name?.takeIf { it.isNotBlank() }
            ?: itin.legs.drop(i + 1).firstOrNull { it.to.name.isNotBlank() }?.to?.name
    }

    private fun pointAt(line: List<GeoPoint>, cum: DoubleArray, alongM: Double): GeoPoint {
        for (i in 0 until line.size - 1) {
            if (alongM <= cum[i + 1]) {
                val seg = cum[i + 1] - cum[i]
                val f = if (seg <= 0) 0.0 else (alongM - cum[i]) / seg
                return GeoPoint(line[i].lat + (line[i + 1].lat - line[i].lat) * f, line[i].lon + (line[i + 1].lon - line[i].lon) * f)
            }
        }
        return line.last()
    }
}
