package com.maslul.app.data

import java.time.Instant

/**
 * Several lines often ride between the same two stops (e.g. 16, 92 and 5 all go from A to B).
 * Instead of listing each as its own route, such options are folded into one whose transit
 * leg carries the other lines as [Leg.alternatives], each keeping its own times and trip.
 */
object Combine {
    /** Stops closer than this are the same place (opposite platforms, same pole). */
    private const val NEAR_M = 60.0
    /** Same-named stops closer than this are one stop area. */
    private const val AREA_M = 150.0
    /** Alternatives must leave within this long after the option's own ride. */
    const val WINDOW_SEC = 25 * 60L
    /** A first ride may also be one that leaves up to this much earlier. */
    private const val EARLIER_SEC = 3 * 60L
    const val MAX_ALTERNATIVES = 7
    /** Buses of one line offered per ride (the next few, including a late earlier one). */
    const val PER_LINE = 3
    /** Earlier-scheduled rides are kept as candidates this far back: they may be running late. */
    const val LATE_SEC = TransitRepository.LATE_WINDOW_SEC
    /** Earlier-scheduled rides of one line kept as late candidates (the most recent ones). */
    const val LATE_PER_LINE = 6
    /** Leeway when judging whether a bus can still be caught. */
    private const val CATCH_SLACK_SEC = 90L
    /** Hurrying (a brisk walk or a jog) covers a walk in this share of its time. */
    private const val HURRY_SHARE = 0.5
    /** Leeway when judging whether a bus can be caught by hurrying: a bus a couple of minutes out is worth running for. */
    private const val HURRY_SLACK_SEC = 120L

    fun sameStop(a: StopCall, b: StopCall): Boolean {
        if (a.stopId != null && a.stopId == b.stopId) return true
        val d = GeoMath.distance(a.point, b.point)
        return d < NEAR_M || (d < AREA_M && a.name.isNotBlank() && a.name.trim().equals(b.name.trim(), ignoreCase = true))
    }

    /** Both legs board and alight at the same stops. */
    fun sameRide(a: Leg, b: Leg) = a.mode.isTransit && b.mode.isTransit && sameStop(a.from, b.from) && sameStop(a.to, b.to)

    /** Same vehicle run (not just the same line). */
    fun sameTrip(a: Leg, b: Leg) =
        if (a.tripId != null || b.tripId != null) a.tripId == b.tripId else a.lineLabel == b.lineLabel && a.start == b.start

    /** A line as riders see it: same number from the same operator. */
    fun lineKey(l: Leg) = "${l.mode}|${l.lineLabel}|${l.agencyName.orEmpty()}"

    /**
     * Index (into [Itinerary.legs] of [a]) of the single transit leg where [a] and [b] ride
     * different trips between the same stops, when they are otherwise the same journey.
     */
    fun differingLeg(a: Itinerary, b: Itinerary): Int? {
        val ta = a.legs.withIndex().filter { it.value.mode.isTransit }
        val tb = b.legs.filter { it.mode.isTransit }
        if (ta.size != tb.size || ta.isEmpty()) return null
        var diff: Int? = null
        for (k in ta.indices) {
            val x = ta[k].value
            val y = tb[k]
            if (!sameRide(x, y)) return null
            if (sameTrip(x, y)) continue
            if (diff != null) return null
            diff = ta[k].index
        }
        return diff
    }

    /**
     * Folds options that differ only in the line taken on one leg into one option. [list] is
     * ordered best first; the best of each group stays the main ride, so no departure is lost —
     * the others become alternatives on that leg (one per line, each line's soonest).
     */
    fun merge(list: List<Itinerary>): List<Itinerary> {
        val groups = ArrayList<Itinerary>()
        for (x in list) {
            var merged = false
            for (g in groups.indices) {
                val primary = groups[g]
                val i = differingLeg(primary, x) ?: continue
                val leg = primary.legs[i]
                val alt = x.legs.filter { it.mode.isTransit }[primary.legs.take(i).count { it.mode.isTransit }]
                if (leg.options.any { lineKey(it) == lineKey(alt) }) continue
                if (Math.abs(alt.start.epochSecond - leg.start.epochSecond) > WINDOW_SEC) continue
                if (leg.alternatives.size >= MAX_ALTERNATIVES) continue
                groups[g] = withLeg(primary, i, leg.copy(alternatives = leg.alternatives + alt.copy(alternatives = emptyList())))
                merged = true
                break
            }
            if (!merged) groups += x
        }
        return groups
    }

    /**
     * Adds rides from [rides] (direct rides found between the leg's two stops) as
     * alternatives on leg [legIndex], keeping only those that still fit the journey: after
     * the previous connection, in time for the next one, and not before [now]. Rides
     * scheduled up to [LATE_SEC] too early are kept too (the latest [LATE_PER_LINE] of each
     * line) — a late bus may still be catchable, which [catchable] decides once live data is
     * in — but only for a ride that's about to happen: a bus planned for tomorrow can't be
     * running late yet.
     */
    fun addAlternatives(itin: Itinerary, legIndex: Int, rides: List<Leg>, now: Instant? = null): Itinerary {
        val leg = itin.legs.getOrNull(legIndex)?.takeIf { it.mode.isTransit } ?: return itin
        val before = itin.legs.subList(0, legIndex)
        val after = itin.legs.subList(legIndex + 1, itin.legs.size)
        val prev = before.indexOfLast { it.mode.isTransit }
        val next = after.indexOfFirst { it.mode.isTransit }
        val walkBefore = before.drop(prev + 1).sumOf { it.durationSec }
        val earliest = if (prev >= 0) {
            before[prev].end.plusSeconds(walkBefore)
        } else {
            val e = leg.start.minusSeconds(EARLIER_SEC)
            now?.plusSeconds(walkBefore)?.takeIf { it > e } ?: e
        }
        val latestEnd = if (next >= 0) after[next].start.minusSeconds(after.take(next).sumOf { it.durationSec }) else null
        val latestStart = leg.start.plusSeconds(WINDOW_SEC)
        val soon = now == null || !now.plusSeconds(LATE_SEC).isBefore(earliest)
        val lateFrom = if (soon) earliest.minusSeconds(LATE_SEC) else earliest

        val candidates = rides
            .filter { sameRide(it, leg) && leg.options.none { o -> sameTrip(o, it) } }
            .filter { !it.start.isBefore(lateFrom) && !it.start.isAfter(latestStart) }
            .filter { latestEnd == null || !it.end.isAfter(latestEnd) }
            .distinctBy { it.rideKey }
        val (late, upcoming) = candidates.partition { it.start.isBefore(earliest) }
        // The next few of each line, soonest first.
        val perLine = leg.options.groupingBy(::lineKey).eachCount().toMutableMap()
        val soonest = upcoming.sortedBy { it.start }
            .filter { o -> (perLine[lineKey(o)] ?: 0).let { n -> if (n < PER_LINE) { perLine[lineKey(o)] = n + 1; true } else false } }
            .take((MAX_ALTERNATIVES - leg.alternatives.size).coerceAtLeast(0))
        // Possibly-late ones, most recent first: the one due just before you get there is the likeliest
        // to still come, not one from an hour and a half ago. [catchable] decides which are offered.
        val latePerLine = HashMap<String, Int>()
        val recent = late.sortedByDescending { it.start }
            .filter { o -> latePerLine.merge(lineKey(o), 1, Int::plus)!! <= LATE_PER_LINE }
        val added = (soonest + recent).sortedBy { it.start }.map { it.copy(alternatives = emptyList()) }
        if (added.isEmpty()) return itin
        return withLeg(itin, legIndex, leg.copy(alternatives = leg.alternatives + added))
    }

    /**
     * Rides between the same two stops that leave after [leg] (any line), soonest first: what's
     * left if it's missed. [found] adds rides looked up separately to the leg's own options.
     */
    fun ridesAfter(leg: Leg, found: List<Leg>, count: Int = 2): List<Leg> =
        (leg.options + found)
            .filter { sameRide(it, leg) && it.start.isAfter(leg.start) && !sameTrip(it, leg) }
            .distinctBy { it.rideKey }
            .sortedBy { it.start }
            .take(count)

    /**
     * When you can be at leg [legIndex]'s boarding stop: [now] plus the walk there for the
     * first ride, else when the previous ride gets in (live when tracked: a late bus gets in
     * late, whatever its timetable says) plus the walk between.
     */
    fun reachBy(itin: Itinerary, legIndex: Int, now: Instant, hurry: Boolean = false, liveOf: (Leg) -> LiveCall? = { null }): Instant {
        val before = itin.legs.subList(0, legIndex.coerceIn(0, itin.legs.size))
        val prev = before.indexOfLast { it.mode.isTransit }
        val walk = before.drop(prev + 1).sumOf { it.durationSec }.let { if (hurry) (it * HURRY_SHARE).toLong() else it }
        return (if (prev >= 0) maxOf(arrives(before[prev], liveOf(before[prev])), now) else now).plusSeconds(walk)
    }

    /**
     * [itin] as it's expected to go: each live-tracked ride at the times it's expected to board
     * and arrive, with the walks moving along (also after a ride was switched to another bus, which
     * leaves the walk between rides on the old one's times). The legs keep the timetable; this is
     * what to show (times, duration, transfer slack) once a late bus is in the plan.
     */
    fun expected(itin: Itinerary, liveOf: (Leg) -> LiveCall?): Itinerary {
        val legs = itin.legs.toMutableList()
        val transit = legs.indices.filter { legs[it].mode.isTransit }
        if (transit.isEmpty()) return itin
        for (i in transit) {
            val l = legs[i]
            val c = liveOf(l)?.takeIf { it.status == LiveStatus.LIVE } ?: continue
            val s = boards(l, c)
            legs[i] = l.copy(start = s, end = maxOf(arrives(l, c), s))
        }
        // Walks to the first ride end as it boards; every other walk starts when the ride before it gets in.
        for (i in transit.first() - 1 downTo 0) {
            val next = legs[i + 1].start
            legs[i] = legs[i].copy(start = next.minusSeconds(legs[i].durationSec), end = next)
        }
        for (i in transit.first() + 1 until legs.size) {
            if (legs[i].mode.isTransit) continue
            val prev = legs[i - 1].end
            legs[i] = legs[i].copy(start = prev, end = prev.plusSeconds(legs[i].durationSec))
        }
        if (legs == itin.legs) return itin
        return itin.copy(start = legs.first().start, end = legs.last().end, legs = legs)
    }

    /** When [option] reaches the boarding stop: live when tracked, else the timetable. */
    fun boards(option: Leg, call: LiveCall?): Instant =
        call?.takeIf { it.status == LiveStatus.LIVE }?.best ?: option.start

    /** When [option] gets to the alighting stop, live when tracked. */
    fun arrives(option: Leg, call: LiveCall?): Instant {
        val c = call?.takeIf { it.status == LiveStatus.LIVE } ?: return option.end
        return c.alightExpected ?: option.end.plusSeconds(c.best.epochSecond - option.start.epochSecond)
    }

    /**
     * Whether [option] can still be caught by someone at the stop at [reachBy]: it hasn't
     * passed, and it gets there after them. An earlier-scheduled bus only counts when it's
     * tracked live (so it's known to be running late).
     */
    fun catchable(option: Leg, call: LiveCall?, reachBy: Instant): Boolean {
        if (call?.status == LiveStatus.PASSED) return false
        val live = call?.status == LiveStatus.LIVE
        if (!live && option.start.isBefore(reachBy.minusSeconds(CATCH_SLACK_SEC))) return false
        return !boards(option, call).isBefore(reachBy.minusSeconds(CATCH_SLACK_SEC))
    }

    /**
     * Whether [option] can be caught only by hurrying to the stop: it's tracked live and hasn't
     * passed, and it comes before you'd walk there but not before you could hurry there.
     */
    fun onlyByHurrying(itin: Itinerary, legIndex: Int, option: Leg, now: Instant, call: LiveCall?, liveOf: (Leg) -> LiveCall? = { null }): Boolean {
        if (call?.status != LiveStatus.LIVE || catchable(option, call, reachBy(itin, legIndex, now, liveOf = liveOf))) return false
        return !boards(option, call).isBefore(reachBy(itin, legIndex, now, hurry = true, liveOf = liveOf).minusSeconds(HURRY_SLACK_SEC))
    }

    /**
     * Leg [legIndex]'s options that can still be caught, soonest first. With [hurry], also live
     * buses you'd catch only by hurrying ([onlyByHurrying]): worth showing, so you can run for
     * one you see is close, but not planned on.
     */
    fun catchableOptions(itin: Itinerary, legIndex: Int, now: Instant, hurry: Boolean = false, liveOf: (Leg) -> LiveCall?): List<Leg> {
        val leg = itin.legs.getOrNull(legIndex)?.takeIf { it.mode.isTransit } ?: return emptyList()
        val by = reachBy(itin, legIndex, now, liveOf = liveOf)
        return leg.options
            .filter { catchable(it, liveOf(it), by) || (hurry && onlyByHurrying(itin, legIndex, it, now, liveOf(it), liveOf)) }
            .sortedBy { boards(it, liveOf(it)) }
    }

    /**
     * The catchable option of leg [legIndex] that gets you there first (ties: boards first),
     * or null when the current ride already is the best or nothing is catchable.
     */
    fun bestOption(itin: Itinerary, legIndex: Int, now: Instant, liveOf: (Leg) -> LiveCall?): Leg? {
        val leg = itin.legs.getOrNull(legIndex)?.takeIf { it.mode.isTransit } ?: return null
        val best = catchableOptions(itin, legIndex, now, liveOf = liveOf)
            .minWithOrNull(compareBy<Leg>({ arrives(it, liveOf(it)) }, { boards(it, liveOf(it)) })) ?: return null
        return best.takeIf { it.rideKey != leg.rideKey }
    }

    /**
     * Whether every ride after the first can still be caught, as the rides before it are
     * expected to run: a late first bus can make a connection impossible.
     */
    fun connects(itin: Itinerary, now: Instant, liveOf: (Leg) -> LiveCall?): Boolean {
        val transit = itin.legs.indices.filter { itin.legs[it].mode.isTransit }
        return transit.drop(1).all { i -> catchable(itin.legs[i], liveOf(itin.legs[i]), reachBy(itin, i, now, liveOf = liveOf)) }
    }

    /**
     * [itin] with every transit leg (except [keep]) switched to its best catchable ride, so an
     * earlier bus that's running late — or another line that comes sooner — is taken.
     */
    fun pickBest(itin: Itinerary, now: Instant, liveOf: (Leg) -> LiveCall?, keep: Set<Int> = emptySet()): Itinerary =
        itin.legs.indices.fold(itin) { acc, i ->
            if (i in keep) acc else bestOption(acc, i, now, liveOf)?.let { choose(acc, i, it) } ?: acc
        }

    /**
     * [list] without options that, once each has picked its bus, ride exactly the same vehicles
     * as a better-ranked one (two plans that both switched to the same late bus): the first stays.
     */
    fun distinctRides(list: List<Itinerary>): List<Itinerary> =
        list.distinctBy { itin -> itin.legs.filter { it.mode.isTransit }.map { it.rideKey } }

    /**
     * The itinerary riding [choice] (one of leg [legIndex]'s options) instead. Walks to the
     * first ride and from the last one move with it, so start/end times stay right.
     */
    fun choose(itin: Itinerary, legIndex: Int, choice: Leg): Itinerary {
        val leg = itin.legs.getOrNull(legIndex) ?: return itin
        if (sameTrip(leg, choice) && leg.start == choice.start) return itin
        val others = leg.options.filterNot { sameTrip(it, choice) && it.start == choice.start }.map { it.copy(alternatives = emptyList()) }
        val swapped = choice.copy(alternatives = others)
        val firstTransit = itin.legs.indexOfFirst { it.mode.isTransit }
        val lastTransit = itin.legs.indexOfLast { it.mode.isTransit }
        val dStart = choice.start.epochSecond - leg.start.epochSecond
        val dEnd = choice.end.epochSecond - leg.end.epochSecond
        val legs = itin.legs.mapIndexed { i, l ->
            when {
                i == legIndex -> swapped
                i < legIndex && legIndex == firstTransit -> l.shift(dStart)
                i > legIndex && legIndex == lastTransit -> l.shift(dEnd)
                else -> l
            }
        }
        return itin.copy(start = legs.first().start, end = legs.last().end, legs = legs)
    }

    private fun Leg.shift(sec: Long) = if (sec == 0L) this else copy(start = start.plusSeconds(sec), end = end.plusSeconds(sec))

    private fun withLeg(itin: Itinerary, i: Int, leg: Leg) = itin.copy(legs = itin.legs.toMutableList().also { it[i] = leg })
}
