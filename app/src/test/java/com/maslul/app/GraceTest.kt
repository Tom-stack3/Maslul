package com.maslul.app

import com.maslul.app.data.Grace
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GraceTest {
    private val t0 = Instant.parse("2026-10-12T05:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)

    private fun leg(mode: TransitMode, from: Long, to: Long): Leg {
        val s = StopCall("s", "S", null, 32.0, 34.8, null, null)
        return Leg(mode, s, s, at(from), at(to), null, null, "1", null, null, null, null, null, null, emptyList(), emptyList())
    }

    /** A lift (or walk) of [first] minutes, then a bus to [arrive]. */
    private fun itin(id: String, first: TransitMode, firstMin: Long, arrive: Long, walkAfter: Long = 0): Itinerary {
        val legs = listOf(leg(first, 0, firstMin), leg(TransitMode.BUS, firstMin, arrive - walkAfter)) +
            listOfNotNull(if (walkAfter > 0) leg(TransitMode.WALK, arrive - walkAfter, arrive) else null)
        return Itinerary(id, at(0), at(arrive), 0, legs)
    }

    @Test
    fun knowsWhatGoesOverALimit() {
        assertTrue(Grace.overLift(itin("12", TransitMode.CAR, 12, 60), 10))
        assertFalse(Grace.overLift(itin("10", TransitMode.CAR, 10, 60), 10))
        assertFalse(Grace.overLift(itin("12", TransitMode.CAR, 12, 60), null))
        assertTrue(Grace.overWalk(itin("w", TransitMode.WALK, 22, 60), 20))
        // Walking from the last stop counts too; a lift doesn't.
        assertTrue(Grace.overWalk(itin("e", TransitMode.CAR, 5, 60, walkAfter = 23), 20))
        assertFalse(Grace.overWalk(itin("c", TransitMode.CAR, 25, 60), 20))
    }

    @Test
    fun aLittleOverIsKeptOnlyWhenItSavesRealTime() {
        val within = itin("10-min lift", TransitMode.CAR, 10, 90)
        val muchSooner = itin("12-min lift, 30 min sooner", TransitMode.CAR, 12, 60)
        val barelySooner = itin("12-min lift, 5 min sooner", TransitMode.CAR, 12, 85)
        val kept = Grace.filter(listOf(within, muchSooner, barelySooner), false) { Grace.overLift(it, 10) }
        assertEquals(listOf("10-min lift", "12-min lift, 30 min sooner"), kept.map { it.id })
    }

    @Test
    fun withNothingWithinTheLimitsALittleOverIsBetterThanNothing() {
        val over = listOf(itin("a", TransitMode.CAR, 12, 60), itin("b", TransitMode.CAR, 13, 80))
        assertEquals(over, Grace.filter(over, false) { Grace.overLift(it, 10) })
    }

    @Test
    fun arrivingByATimeKeepsWhatLeavesClearlyLater() {
        val within = Itinerary("within", at(0), at(60), 0, listOf(leg(TransitMode.WALK, 0, 15), leg(TransitMode.BUS, 15, 60)))
        val later = Itinerary("later", at(20), at(60), 0, listOf(leg(TransitMode.WALK, 20, 42), leg(TransitMode.BUS, 42, 60)))
        val bitLater = Itinerary("bit", at(5), at(60), 0, listOf(leg(TransitMode.WALK, 5, 27), leg(TransitMode.BUS, 27, 60)))
        assertEquals(listOf("within", "later"), Grace.filter(listOf(within, later, bitLater), true) { Grace.overWalk(it, 20) }.map { it.id })
    }
}
