package com.maslul.app

import com.maslul.app.data.Combine
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CombineTest {
    private val t0 = Instant.parse("2026-09-28T07:00:00Z")
    private fun at(min: Int) = t0.plusSeconds(min * 60L)
    private fun stop(name: String, km: Double, id: String? = "s$name") = StopCall(id, name, null, 32.0 + km / 111.195, 34.8, null, null)

    private fun bus(line: String, from: String, fromKm: Double, to: String, toKm: Double, start: Int, end: Int, fromId: String? = "s$from") = Leg(
        TransitMode.BUS, stop(from, fromKm, fromId), stop(to, toKm), at(start), at(end), null, null, line, null, null, null,
        null, "trip-$line-$start", null, emptyList(), emptyList(),
    )

    private fun walk(fromKm: Double, toKm: Double, start: Int, end: Int) = Leg(
        TransitMode.WALK, stop("w", fromKm, null), stop("w2", toKm, null), at(start), at(end), 200.0, null, null, null, null, null,
        null, null, null, emptyList(), emptyList(),
    )

    private fun itin(id: String, vararg legs: Leg) =
        Itinerary(id, legs.first().start, legs.last().end, legs.count { it.mode.isTransit } - 1, legs.toList())

    private fun direct(line: String, start: Int, end: Int) =
        itin(line + "@" + start, walk(0.0, 0.2, start - 3, start), bus(line, "A", 0.2, "B", 5.0, start, end), walk(5.0, 5.2, end, end + 3))

    @Test
    fun combinesDifferentLinesBetweenSameStops() {
        val out = Combine.merge(listOf(direct("16", 10, 30), direct("92", 12, 33), direct("5", 15, 34)))
        assertEquals(1, out.size)
        val leg = out.single().transitLegs.single()
        assertEquals("16", leg.lineLabel)
        assertEquals(listOf("16", "92", "5"), leg.options.map { it.lineLabel })
        assertTrue(leg.combined)
        // Each line keeps its own times.
        assertEquals(at(12), leg.alternatives.first { it.lineLabel == "92" }.start)
        assertEquals(at(33), leg.alternatives.first { it.lineLabel == "92" }.end)
    }

    @Test
    fun keepsBestAsMainAndListsOptionsBySoonest() {
        // The best-ranked option comes first even if another line leaves earlier.
        val out = Combine.merge(listOf(direct("92", 12, 25), direct("16", 10, 30)))
        val leg = out.single().transitLegs.single()
        assertEquals("92", leg.lineLabel)
        assertEquals("92@12", out.single().id)
        assertEquals(listOf("16", "92"), leg.options.map { it.lineLabel })
    }

    @Test
    fun laterRunOfSameLineStaysSeparate() {
        val out = Combine.merge(listOf(direct("16", 10, 30), direct("16", 25, 45), direct("92", 12, 33)))
        assertEquals(listOf("16@10", "16@25"), out.map { it.id })
        assertEquals(listOf("16", "92"), out[0].transitLegs.single().options.map { it.lineLabel })
        assertFalse(out[1].transitLegs.single().combined)
    }

    @Test
    fun differentStopsOrFarApartTimesDoNotCombine() {
        val elsewhere = itin("x", bus("7", "C", 1.5, "B", 5.0, 11, 31))
        val muchLater = direct("92", 60, 80)
        assertEquals(3, Combine.merge(listOf(direct("16", 10, 30), elsewhere, muchLater)).size)
    }

    @Test
    fun sameStopAreaCombines() {
        // Different stop ids (opposite platforms) but same name, 80 m apart.
        val a = itin("a", bus("16", "A", 0.2, "B", 5.0, 10, 30))
        val b = itin("b", bus("92", "A", 0.28, "B", 5.0, 12, 32, fromId = "sA-2"))
        assertTrue(Combine.sameStop(a.legs[0].from, b.legs[0].from))
        assertEquals(1, Combine.merge(listOf(a, b)).size)
    }

    @Test
    fun combinesOnlyTheLegThatDiffers() {
        val train = bus("Train", "B", 5.0, "F", 50.0, 40, 70)
        val a = itin("a", bus("16", "A", 0.0, "B", 5.0, 10, 30), walk(5.0, 5.0, 30, 35), train)
        val b = itin("b", bus("92", "A", 0.0, "B", 5.0, 14, 33), walk(5.0, 5.0, 33, 38), train)
        assertEquals(0, Combine.differingLeg(a, b))
        val out = Combine.merge(listOf(a, b)).single()
        assertEquals(listOf("16", "92"), out.legs[0].options.map { it.lineLabel })
        assertFalse(out.legs[2].combined)

        // Different on both rides: two separate journeys.
        val c = itin("c", bus("92", "A", 0.0, "B", 5.0, 14, 33), bus("Train", "B", 5.0, "F", 50.0, 55, 85))
        assertNull(Combine.differingLeg(a, c))
        assertEquals(2, Combine.merge(listOf(a, c)).size)
    }

    @Test
    fun addsFoundRidesThatStillMakeTheConnection() {
        val a = itin("a", bus("16", "A", 0.0, "B", 5.0, 10, 30), walk(5.0, 5.1, 30, 33), bus("Train", "C", 5.1, "F", 50.0, 40, 70))
        val rides = listOf(
            bus("16", "A", 0.0, "B", 5.0, 10, 30), // itself
            bus("92", "A", 0.0, "B", 5.0, 12, 34), // fits: 34 + 3 min walk ≤ 40
            bus("5", "A", 0.0, "B", 5.0, 14, 38), // misses the train
            bus("92", "A", 0.0, "B", 5.0, 13, 35), // second run of 92: kept, the next few of a line are offered
            bus("40", "A", 0.0, "B", 5.0, 2, 22), // scheduled earlier: kept in case it's running late
            bus("41", "A", 0.0, "B", 5.0, -100, -80), // far too early to be merely late
            bus("7", "Z", 3.0, "B", 5.0, 12, 30), // other stop
        )
        val leg = Combine.addAlternatives(a, 0, rides).legs[0]
        assertEquals(listOf("40", "16", "92", "92"), leg.options.map { it.lineLabel })
        assertEquals(listOf(12, 13).map { at(it) }, leg.alternatives.filter { it.lineLabel == "92" }.map { it.start })

        // A later ride's alternatives must leave after the previous one arrives (unless live says they're late).
        val withTrains = Combine.addAlternatives(
            a, 2,
            listOf(bus("Train2", "C", 5.1, "F", 50.0, 31, 60), bus("Train3", "C", 5.1, "F", 50.0, 36, 66)),
        )
        assertEquals(listOf("Train3", "Train"), Combine.catchableOptions(withTrains, 2, at(0)) { null }.map { it.lineLabel })
    }

    @Test
    fun firstRideAlternativesNotInThePast() {
        val a = direct("16", 10, 30)
        val rides = listOf(bus("92", "A", 0.2, "B", 5.0, 8, 29))
        assertEquals(listOf("92", "16"), Combine.catchableOptions(Combine.addAlternatives(a, 1, rides), 1, at(0)) { null }.map { it.lineLabel })
        // Now is 07:07 and the walk to the stop takes 3 min: the 07:08 can't be caught (without live data saying it's late).
        val now = Combine.addAlternatives(a, 1, rides, now = at(7))
        assertEquals(listOf("16"), Combine.catchableOptions(now, 1, at(7)) { null }.map { it.lineLabel })
    }

    @Test
    fun choosingAnotherLineMovesTheTrip() {
        val merged = Combine.merge(listOf(direct("16", 10, 30), direct("92", 12, 33))).single()
        val alt = merged.legs[1].alternatives.single()
        val chosen = Combine.choose(merged, 1, alt)
        assertEquals("92", chosen.legs[1].lineLabel)
        assertEquals(listOf("16", "92"), chosen.legs[1].options.map { it.lineLabel })
        // Walks before and after move with the ride.
        assertEquals(at(9), chosen.start)
        assertEquals(at(36), chosen.end)
        assertEquals(merged.id, chosen.id)
        assertSame(merged, Combine.choose(merged, 1, merged.legs[1]))
    }

    // ---- Catching an earlier bus that's running late ----

    private fun liveAt(l: Leg, board: Int) =
        LiveCall(LiveStatus.LIVE, l.start, at(board), delaySec = (board * 60L) - (l.start.epochSecond - t0.epochSecond))

    /** Planned 16 at 10:12 (min 12); the 10:02 one (min 2) is running late and reaches the stop at min 8. */
    private fun lateCase(): Triple<Itinerary, Leg, Leg> {
        val planned = direct("16", 12, 30)
        val late = bus("16", "A", 0.2, "B", 5.0, 2, 20)
        return Triple(Combine.addAlternatives(planned, 1, listOf(late), now = at(4)), planned.legs[1], late)
    }

    @Test
    fun keepsEarlierScheduledRideOfSameLineAsCandidate() {
        val (itin, _, late) = lateCase()
        assertEquals(listOf("trip-16-2", "trip-16-12"), itin.legs[1].options.map { it.tripId })
        assertEquals(late.start, itin.legs[1].options.first().start)
    }

    @Test
    fun switchesToLateEarlierBusThatComesFirst() {
        val (itin, planned, late) = lateCase()
        val live = mapOf(late.rideKey to liveAt(late, 8), planned.rideKey to liveAt(planned, 12))
        // At min 4 with a 3-minute walk you're at the stop by 7 — the late bus comes at 8.
        val picked = Combine.pickBest(itin, at(4), { live[it.rideKey] })
        assertEquals("trip-16-2", picked.legs[1].tripId)
        assertEquals(listOf("trip-16-12"), picked.legs[1].alternatives.map { it.tripId })
    }

    @Test
    fun ignoresLateBusYouCannotReach() {
        val (itin, planned, late) = lateCase()
        val live = mapOf(late.rideKey to liveAt(late, 8), planned.rideKey to liveAt(planned, 12))
        // At min 7 you'd reach the stop at 10: the late bus is gone by then.
        assertSame(itin, Combine.pickBest(itin, at(7), { live[it.rideKey] }))
        assertEquals(listOf("trip-16-12"), Combine.catchableOptions(itin, 1, at(7)) { live[it.rideKey] }.map { it.tripId })
    }

    @Test
    fun earlierScheduledBusWithoutLiveDataIsNotOffered() {
        val (itin, _, _) = lateCase()
        // No live data: the 10:02 has presumably left, so only the planned bus is catchable.
        assertEquals(listOf("trip-16-12"), Combine.catchableOptions(itin, 1, at(4)) { null }.map { it.tripId })
        assertSame(itin, Combine.pickBest(itin, at(4), { null }))
    }

    @Test
    fun passedBusIsNotOffered() {
        val (itin, planned, late) = lateCase()
        val live = mapOf(late.rideKey to LiveCall(LiveStatus.PASSED, late.start, null), planned.rideKey to liveAt(planned, 12))
        assertEquals(listOf("trip-16-12"), Combine.catchableOptions(itin, 1, at(4)) { live[it.rideKey] }.map { it.tripId })
    }

    @Test
    fun keepsUpToThreeBusesPerLine() {
        val planned = direct("16", 12, 30)
        val more = listOf(14, 18, 22, 26).map { bus("16", "A", 0.2, "B", 5.0, it, it + 18) }
        val leg = Combine.addAlternatives(planned, 1, more, now = at(4)).legs[1]
        assertEquals(listOf(12, 14, 18).map { at(it) }, leg.options.map { it.start })
    }

    /**
     * The 18 runs every 12 min and the planner's first is at min 102. The one due at min 66 is
     * running 28 min late and comes at min 94, just as you reach the stop. Older 18s, 25s and 40s
     * from the last hour and a half must not crowd it out of the candidates.
     */
    @Test
    fun keepsTheMostRecentLateCandidatesNotTheOldest() {
        val planned = direct("18", 102, 107)
        val found = listOf(30, 42, 54, 66, 78, 90, 102, 114).map { bus("18", "A", 0.2, "B", 5.0, it, it + 5) } +
            listOf(34, 49, 64, 79, 94).map { bus("25", "A", 0.2, "B", 5.0, it, it + 5) } +
            listOf(27, 47, 67, 87).map { bus("40", "A", 0.2, "B", 5.0, it, it + 5) }
        val itin = Combine.addAlternatives(planned, 1, found, now = at(92))
        assertTrue("trip-18-66" in itin.legs[1].options.map { it.tripId })
        // Live: the min-66 bus reaches the stop at 94; the planned one is 8 min late.
        val late = itin.legs[1].options.first { it.tripId == "trip-18-66" }
        val live = mapOf(late.rideKey to liveAt(late, 94), planned.legs[1].rideKey to liveAt(planned.legs[1], 110))
        assertEquals("trip-18-66", Combine.pickBest(itin, at(92), { live[it.rideKey] }).legs[1].tripId)
        // The upcoming ones are still the next few, soonest first.
        assertTrue("trip-18-114" in itin.legs[1].options.map { it.tripId })
    }

    /**
     * The walk the planner works out from the rider's position makes it pick the min-54 tram, but
     * the min-45 one is still catchable on the timetable alone. Trams every 9 min over the last hour
     * and a half must not crowd it out.
     */
    @Test
    fun offersTheEarlierTramStillCatchableOnTheTimetable() {
        val tram = { start: Int -> bus("T", "A", 0.0, "B", 9.0, start, start + 17) }
        val planned = itin("t@54", walk(0.0, 0.0, 47, 54), tram(54), walk(9.0, 9.4, 71, 78))
        val found = (-45..63 step 9).map(tram)
        val itin = Combine.addAlternatives(planned, 1, found, now = at(39))
        assertEquals(listOf(at(45), at(54)), Combine.catchableOptions(itin, 1, at(39)) { null }.take(2).map { it.start })
        assertEquals("trip-T-45", Combine.pickBest(itin, at(39), { null }).legs[1].tripId)
    }

    @Test
    fun lateCandidatesArePerLineAndMostRecentFirst() {
        val planned = direct("16", 100, 105)
        val found = (10..90 step 10).map { bus("16", "A", 0.2, "B", 5.0, it, it + 5) }
        val late = Combine.addAlternatives(planned, 1, found, now = at(90)).legs[1].alternatives.map { it.start }
        assertEquals((40..90 step 10).map { at(it) }, late)
        assertEquals(Combine.LATE_PER_LINE, late.size)
    }

    @Test
    fun ridesAfterListsLaterRidesOfAnyLineOnTheSameHop() {
        val leg = direct("16", 12, 30).legs[1].copy(alternatives = listOf(bus("16", "A", 0.2, "B", 5.0, 8, 26)))
        val found = listOf(
            bus("92", "A", 0.2, "B", 5.0, 20, 38),
            bus("16", "A", 0.2, "B", 5.0, 27, 45),
            leg, // the ride itself
            bus("5", "A", 0.2, "C", 9.0, 15, 40), // goes elsewhere
        )
        val after = Combine.ridesAfter(leg, found)
        assertEquals(listOf("92" to at(20), "16" to at(27)), after.map { it.lineLabel to it.start })
    }

    @Test
    fun plannedTripFarAheadGetsNoEarlierBusesAsLateCandidates() {
        val planned = direct("16", 12, 30)
        val earlier = bus("16", "A", 0.2, "B", 5.0, 2, 20)
        // Planning for 10:12 tomorrow: the 10:02 can't be running late yet, so it isn't offered.
        val tomorrow = Combine.addAlternatives(planned, 1, listOf(earlier), now = at(12 - 24 * 60))
        assertEquals(listOf("trip-16-12"), tomorrow.legs[1].options.map { it.tripId })
        // Planning for half an hour from now, it may still be running late.
        val soon = Combine.addAlternatives(planned, 1, listOf(earlier), now = at(-20))
        assertEquals(listOf("trip-16-2", "trip-16-12"), soon.legs[1].options.map { it.tripId })
    }

    @Test
    fun liveBusYouCouldHurryForIsShownButNotPlannedOn() {
        // 3-minute walk to the stop; it's min 9, so you'd stroll there by min 12.
        val itin = direct("16", 12, 30)
        val bus = itin.legs[1]
        val close = mapOf(bus.rideKey to liveAt(bus, 10)) // live: at the stop at min 10
        assertTrue(Combine.onlyByHurrying(itin, 1, bus, at(9), close[bus.rideKey]))
        assertEquals(emptyList<Leg>(), Combine.catchableOptions(itin, 1, at(9)) { close[it.rideKey] })
        assertEquals(listOf(bus.rideKey), Combine.catchableOptions(itin, 1, at(9), hurry = true) { close[it.rideKey] }.map { it.rideKey })
        // Too close to make even hurrying, or only on the timetable: not offered.
        assertFalse(Combine.onlyByHurrying(itin, 1, bus, at(9), liveAt(bus, 8)))
        assertFalse(Combine.onlyByHurrying(itin, 1, bus, at(11), null))
    }

    @Test
    fun hurryAllowsTwoMinutesOfSlack() {
        // A 4-minute walk (2 min hurrying); it's min 8, and the live bus is at the stop 44 s from now.
        val itin = itin("h", walk(0.0, 0.3, 8, 12), bus("16", "A", 0.3, "B", 5.0, 12, 30), walk(5.0, 5.2, 30, 33))
        val bus = itin.legs[1]
        val close = LiveCall(LiveStatus.LIVE, bus.start, at(8).plusSeconds(44))
        assertTrue(Combine.onlyByHurrying(itin, 1, bus, at(8), close))
        // More than 2 minutes before you could hurry there: gone.
        assertFalse(Combine.onlyByHurrying(itin, 1, bus, at(8), LiveCall(LiveStatus.LIVE, bus.start, at(8).minusSeconds(5))))
    }

    // ---- Times as the rides are expected to run ----

    /** Walk 2 → ride A (min 10–20) → walk 3 → ride B (min 30–45) → walk 3. */
    private fun twoRides(bAlternatives: List<Leg> = emptyList()) = itin(
        "two", walk(0.0, 0.2, 8, 10), bus("A", "A", 0.2, "B", 5.0, 10, 20), walk(5.0, 5.2, 20, 23),
        bus("B", "C", 5.2, "D", 12.0, 30, 45).copy(alternatives = bAlternatives), walk(12.0, 12.2, 45, 48),
    )

    @Test
    fun lateSecondRideDoesNotEndTheTripBeforeItStarts() {
        // A ride B due at min -5 is running 31 min late: at the stop at 26, in at 41 — sooner than the planned one.
        val lateB = bus("B", "C", 5.2, "D", 12.0, -5, 10)
        val itin = twoRides(listOf(lateB))
        val live = mapOf(lateB.rideKey to LiveCall(LiveStatus.LIVE, lateB.start, at(26), alightExpected = at(41)))
        val picked = Combine.pickBest(itin, at(5), { live[it.rideKey] })
        assertEquals("trip-B--5", picked.legs[3].tripId)
        // On the timetable that trip ends at min 13, before you'd even leave.
        assertTrue(picked.end.isBefore(picked.start.plusSeconds(10 * 60)))
        val expected = Combine.expected(picked) { live[it.rideKey] }
        assertEquals(at(8), expected.start)
        assertEquals(at(44), expected.end)
        assertEquals(at(41), expected.legs[3].end)
    }

    @Test
    fun expectedTimesFollowLiveRidesAndTheWalksMoveWithThem() {
        val itin = twoRides()
        assertSame(itin, Combine.expected(itin) { null })
        val a = itin.legs[1]
        val b = itin.legs[3]
        val live = mapOf(
            a.rideKey to LiveCall(LiveStatus.LIVE, a.start, at(14), alightExpected = at(24)),
            b.rideKey to LiveCall(LiveStatus.LIVE, b.start, at(32), alightExpected = at(47)),
        )
        val e = Combine.expected(itin) { live[it.rideKey] }
        // Start, then where each leg ends: walk 14, A 24, walk 27, B 47, walk 50.
        assertEquals(listOf(12, 14, 24, 27, 47, 50).map { at(it) }, listOf(e.start) + e.legs.map { it.end })
        assertEquals(at(32), e.legs[3].start)
        // An untracked ride keeps its timetable.
        assertEquals(b.start, Combine.expected(itin) { if (it === a) live[a.rideKey] else null }.legs[3].start)
    }

    @Test
    fun connectionUsesTheLiveArrivalOfTheRideBefore() {
        val laterB = bus("B", "C", 5.2, "D", 12.0, 50, 65)
        val itin = twoRides(listOf(laterB))
        val a = itin.legs[1]
        // On time: the planned B at 30 is caught.
        assertEquals(listOf("trip-B-30", "trip-B-50"), Combine.catchableOptions(itin, 3, at(5)) { null }.map { it.tripId })
        // A runs 20 min late and gets in at 40: the B at 30 is gone by the time you walk over.
        val late = mapOf(a.rideKey to LiveCall(LiveStatus.LIVE, a.start, at(30), alightExpected = at(40)))
        assertEquals(listOf("trip-B-50"), Combine.catchableOptions(itin, 3, at(5)) { late[it.rideKey] }.map { it.tripId })
        assertEquals("trip-B-50", Combine.pickBest(itin, at(5), { late[it.rideKey] }).legs[3].tripId)
    }

    @Test
    fun plansThatEndUpOnTheSameBusShowOnce() {
        // Two plans (the 16 at min 12 and at min 24) both switch to a late 16 that comes first.
        val late = bus("16", "A", 0.2, "B", 5.0, 2, 20)
        val a = Combine.addAlternatives(direct("16", 12, 30), 1, listOf(late), now = at(4))
        val b = Combine.addAlternatives(direct("16", 24, 42), 1, listOf(late), now = at(4))
        val live = mapOf(late.rideKey to liveAt(late, 8))
        val picked = listOf(a, b).map { Combine.pickBest(it, at(4), { l -> live[l.rideKey] }) }
        assertEquals(listOf("trip-16-2", "trip-16-2"), picked.map { it.legs[1].tripId })
        assertEquals(listOf(a.id), Combine.distinctRides(picked).map { it.id })
    }

    @Test
    fun aLateFirstRideThatMissesTheOnlyConnectionDoesNotConnect() {
        val itin = twoRides()
        val a = itin.legs[1]
        assertTrue(Combine.connects(itin, at(5)) { null })
        // A gets in at 40: the walk ends at 43, long after the only B (min 30) leaves.
        val late = mapOf(a.rideKey to LiveCall(LiveStatus.LIVE, a.start, at(30), alightExpected = at(40)))
        assertSame(itin, Combine.pickBest(itin, at(5), { late[it.rideKey] }))
        assertFalse(Combine.connects(itin, at(5)) { late[it.rideKey] })
        // With a later B to switch to, it still connects.
        val withLater = Combine.pickBest(twoRides(listOf(bus("B", "C", 5.2, "D", 12.0, 50, 65))), at(5), { late[it.rideKey] })
        assertTrue(Combine.connects(withLater, at(5)) { late[it.rideKey] })
    }

    @Test
    fun walkBetweenRidesMovesWithASwitchedRide() {
        // Planned B at 30 (walk 27–30 before it); an earlier B at 25 is switched to, no live data at all.
        val earlierB = bus("B", "C", 5.2, "D", 12.0, 25, 40)
        val planned = itin(
            "w", walk(0.0, 0.2, 8, 10), bus("A", "A", 0.2, "B", 5.0, 10, 20), walk(5.0, 5.2, 27, 30),
            bus("B", "C", 5.2, "D", 12.0, 30, 45).copy(alternatives = listOf(earlierB)), walk(12.0, 12.2, 45, 48),
        )
        val picked = Combine.pickBest(planned, at(5), { null })
        assertEquals("trip-B-25", picked.legs[3].tripId)
        val e = Combine.expected(picked) { null }
        assertEquals(at(20), e.legs[2].start)
        assertEquals(at(23), e.legs[2].end)
        assertFalse(e.legs[3].start.isBefore(e.legs[2].end))
        assertEquals(at(43), e.end)
    }
}
