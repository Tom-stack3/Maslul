package com.maslul.app.data

import java.time.Instant

/**
 * Other ways to finish a trip under way, from where the user is now: for when a ride is late, a
 * connection will be missed, or a bus was missed altogether.
 */
object Reroute {
    /** Ways offered at a time. */
    const val OPTIONS = 2

    /**
     * Of [candidates], the ones worth offering instead of [current] at [now], best first: catchable as
     * the rides are expected to run ([liveOf]), connecting all the way, and not just the rides
     * [current] still has to go. [riding] is the trip the user is on, which needs no catching.
     */
    fun choose(
        current: Itinerary,
        candidates: List<Itinerary>,
        now: Instant,
        riding: String?,
        liveOf: (Leg) -> LiveCall?,
        score: (Itinerary) -> Double,
    ): List<Itinerary> {
        val ahead = rides(current, now)
        return candidates
            .filter { firstCatchable(it, now, riding, liveOf) && Combine.connects(it, now, liveOf) }
            .filter { rides(it, now) != ahead }
            .let(Combine::distinctRides)
            .sortedBy { score(Combine.expected(it, riding, liveOf)) }
            .take(OPTIONS)
    }

    /** The rides of [itin] not yet over at [now]. */
    private fun rides(itin: Itinerary, now: Instant) = itin.transitLegs.filter { it.end.isAfter(now) }.map { it.rideKey }

    private fun firstCatchable(itin: Itinerary, now: Instant, riding: String?, liveOf: (Leg) -> LiveCall?): Boolean {
        val i = itin.legs.indexOfFirst { it.mode.isTransit }.takeIf { it >= 0 } ?: return true
        val first = itin.legs[i]
        if (riding != null && first.tripId == riding) return true
        return Combine.catchable(first, liveOf(first), Combine.reachBy(itin, i, now, liveOf = liveOf))
    }
}
