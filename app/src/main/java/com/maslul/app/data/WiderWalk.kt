package com.maslul.app.data

import java.time.Instant

/**
 * Looking further afield when nothing leaves soon within the usual walk. On a Friday afternoon
 * the buses around you may have stopped while a line a 40-minute walk away still runs; without
 * this the planner only offers tomorrow's first bus.
 */
object WiderWalk {
    /** Longest walk to or from a stop when looking further. */
    const val MINUTES = 50
    /** Nothing leaving within this long (or, arriving by a time, getting there within it) is nothing soon. */
    const val SOON_SEC = 2 * 3600L
    /** A longer walk is only worth offering when it gets you there at least this much sooner. */
    const val GAIN_SEC = 20 * 60L

    /** Whether to look further: nothing that rides transit leaves soon after [asked] (or arrives soon before it). */
    fun needed(options: List<Itinerary>, asked: Instant, arriveBy: Boolean, maxWalkMinutes: Int): Boolean {
        if (maxWalkMinutes >= MINUTES) return false
        val rides = options.filter { it.firstTransit != null }
        return if (arriveBy) rides.none { !it.end.isBefore(asked.minusSeconds(SOON_SEC)) }
        else rides.none { !it.start.isAfter(asked.plusSeconds(SOON_SEC)) }
    }

    /** The options from the wider search that get there clearly sooner (or, arriving by a time, leave clearly later) than [usual]. */
    fun better(wide: List<Itinerary>, usual: List<Itinerary>, arriveBy: Boolean): List<Itinerary> {
        val known = usual.map { it.id }.toSet()
        val fresh = wide.filter { it.id !in known && it.firstTransit != null }
        if (usual.isEmpty()) return fresh
        return if (arriveBy) {
            val latest = usual.maxOf { it.start }
            fresh.filter { !it.start.isBefore(latest.plusSeconds(GAIN_SEC)) }
        } else {
            val soonest = usual.minOf { it.end }
            fresh.filter { !it.end.isAfter(soonest.minusSeconds(GAIN_SEC)) }
        }
    }
}
