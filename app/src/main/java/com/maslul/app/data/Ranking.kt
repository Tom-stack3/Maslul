package com.maslul.app.data

/**
 * Ranks itineraries by how good they are to actually take, not just by arrival time:
 * every transfer costs a few minutes, and tight or walking transfers (where a bus that is a
 * little early or late means missing the connection) cost more.
 */
object Ranking {
    /** Connections with less spare time than this are flagged as tight. */
    const val TIGHT_SEC = 240L
    private const val TRANSFER_PENALTY = 300.0
    private const val WALK_WEIGHT = 0.3

    /** One change between two transit legs. */
    data class Transfer(
        /** Time left over after walking: next boarding − (alighting + walk). */
        val slackSec: Long,
        val walkM: Double,
        val next: Leg,
    ) {
        val tight get() = slackSec < TIGHT_SEC
        val walking get() = walkM >= 50
    }

    fun transfers(itin: Itinerary, walkMps: Double = 1.3): List<Transfer> {
        val out = ArrayList<Transfer>()
        var prev: Leg? = null
        var walkM = 0.0
        for (l in itin.legs) {
            if (l.mode == TransitMode.WALK) {
                // MOTIS pads transfer walks with the transfer buffer, so use distance, not duration.
                walkM += l.distanceM?.takeIf { it > 0 } ?: (GeoMath.distance(l.from.point, l.to.point) * 1.25)
                continue
            }
            prev?.let { p ->
                val walkSec = if (walkM > 0) maxOf(30.0, walkM / walkMps).toLong() else 0L
                out += Transfer(l.start.epochSecond - p.end.epochSecond - walkSec, walkM, l)
            }
            prev = l
            walkM = 0.0
        }
        return out
    }

    /** The riskiest transfer worth warning about, if any. */
    fun riskiest(itin: Itinerary, walkMps: Double = 1.3): Transfer? =
        transfers(itin, walkMps).filter { it.tight }.minByOrNull { it.slackSec }

    /** Extra "virtual seconds" for transfers and how risky they are. */
    fun riskPenalty(itin: Itinerary, walkMps: Double = 1.3): Double =
        transfers(itin, walkMps).sumOf { t ->
            var p = TRANSFER_PENALTY
            if (t.walking) p += minOf(t.walkM, 600.0) * 0.3
            if (t.tight) p += (TIGHT_SEC - t.slackSec.coerceAtLeast(-120)) * 1.5
            p
        }

    /** Lower is better: arrival (or, for arrive-by, departure) plus penalties. */
    fun score(itin: Itinerary, arriveBy: Boolean, walkMps: Double = 1.3): Double {
        val base = if (arriveBy) -itin.start.epochSecond.toDouble() else itin.end.epochSecond.toDouble()
        return base + riskPenalty(itin, walkMps) + itin.walkSec * WALK_WEIGHT
    }

    /**
     * True if [a] is at least as good as [b] on every count — leaves no earlier, arrives no
     * later, no more transfers, walking or risk — and strictly better on one.
     */
    fun dominates(a: Itinerary, b: Itinerary, walkMps: Double = 1.3): Boolean {
        if (a.start < b.start || a.end > b.end || a.transfers > b.transfers) return false
        if (a.walkSec > b.walkSec + 60) return false
        if (riskPenalty(a, walkMps) > riskPenalty(b, walkMps)) return false
        return a.end < b.end || a.start > b.start || a.transfers < b.transfers
    }

    /** Drops clearly dominated options and sorts the rest by [score]. */
    fun rank(list: List<Itinerary>, arriveBy: Boolean, walkMps: Double = 1.3): List<Itinerary> {
        val kept = list.filter { b -> list.none { a -> a !== b && dominates(a, b, walkMps) } }
        return kept.sortedWith(compareBy<Itinerary> { score(it, arriveBy, walkMps) }.thenBy { it.start })
    }
}
