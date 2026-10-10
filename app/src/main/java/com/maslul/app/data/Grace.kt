package com.maslul.app.data

/**
 * A little leeway on the limits the user sets. Someone who'll drive you 10 minutes won't mind 12
 * if it saves you half an hour, and a 23-minute walk to a bus that leaves now beats a 20-minute
 * one to a bus an hour later. Planning allows [MINUTES] more than each limit; an option that uses
 * them is kept only when it's worth it.
 */
object Grace {
    const val MINUTES = 3
    /** How much sooner an option over a limit must get there than every option within the limits. */
    const val GAIN_SEC = 10 * 60L

    /** Walking to the first stop or from the last one for longer than [maxWalkMinutes]. */
    fun overWalk(itin: Itinerary, maxWalkMinutes: Int): Boolean {
        val first = itin.legs.indexOfFirst { it.mode.isTransit }
        if (first < 0) return false
        val last = itin.legs.indexOfLast { it.mode.isTransit }
        val access = itin.legs.subList(0, first).filter { it.mode == TransitMode.WALK }.sumOf { it.durationSec }
        val egress = itin.legs.subList(last + 1, itin.legs.size).filter { it.mode == TransitMode.WALK }.sumOf { it.durationSec }
        return maxOf(access, egress) > maxWalkMinutes * 60L
    }

    /** A lift that drives longer than the [liftMinutes] the driver offered. */
    fun overLift(itin: Itinerary, liftMinutes: Int?): Boolean =
        liftMinutes != null && itin.legs.any { it.mode == TransitMode.CAR && it.durationSec > liftMinutes * 60L }

    /**
     * Keeps the options that stretch a limit ([over]) only when they get there [GAIN_SEC] sooner
     * (arriving by a time: leave that much later) than every option within the limits, or when
     * nothing is within them.
     */
    fun filter(options: List<Itinerary>, arriveBy: Boolean, over: (Itinerary) -> Boolean): List<Itinerary> {
        val within = options.filterNot(over)
        if (within.isEmpty() || within.size == options.size) return options
        val latest = within.maxOf { it.start }
        val soonest = within.minOf { it.end }
        fun worth(itin: Itinerary) = if (arriveBy) !itin.start.isBefore(latest.plusSeconds(GAIN_SEC))
            else !itin.end.isAfter(soonest.minusSeconds(GAIN_SEC))
        return options.filter { !over(it) || worth(it) }
    }
}
