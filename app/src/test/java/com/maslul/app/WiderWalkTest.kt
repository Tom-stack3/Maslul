package com.maslul.app

import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.StopCall
import com.maslul.app.data.TransitMode
import com.maslul.app.data.WiderWalk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WiderWalkTest {
    // Friday 16:00 in Israel.
    private val asked = Instant.parse("2026-10-09T13:00:00Z")
    private fun at(min: Long) = asked.plusSeconds(min * 60)

    private fun itin(id: String, leave: Long, arrive: Long, mode: TransitMode = TransitMode.BUS): Itinerary {
        val s = StopCall("s", "S", null, 32.0, 34.8, null, null)
        val leg = Leg(mode, s, s, at(leave), at(arrive), null, null, "66", null, null, null, null, null, null, emptyList(), emptyList())
        return Itinerary(id, at(leave), at(arrive), 0, listOf(leg))
    }

    @Test
    fun looksFurtherOnlyWhenNothingLeavesSoon() {
        // Tomorrow evening's bus, after Shabbat: nothing soon.
        assertTrue(WiderWalk.needed(listOf(itin("sat", 26 * 60, 27 * 60)), asked, false, 20))
        // Nothing at all.
        assertTrue(WiderWalk.needed(emptyList(), asked, false, 20))
        // A bus in an hour and a half is soon enough.
        assertFalse(WiderWalk.needed(listOf(itin("soon", 90, 130)), asked, false, 20))
        // A walk the planner already allows that far needs no second look.
        assertFalse(WiderWalk.needed(emptyList(), asked, false, WiderWalk.MINUTES))
        // A drive (a lift) isn't a bus to catch.
        assertTrue(WiderWalk.needed(listOf(itin("car", 5, 30, TransitMode.CAR)), asked, false, 20))
    }

    @Test
    fun arrivingByATimeLooksAtArrivals() {
        // Arriving by 22:00 Friday, the last bus nearby gets in at 15:00: that's hours early.
        val by = at(6 * 60)
        assertTrue(WiderWalk.needed(listOf(itin("early", -60, 0)), by, true, 20))
        assertFalse(WiderWalk.needed(listOf(itin("ok", 4 * 60 + 30, 5 * 60 + 10)), by, true, 20))
    }

    @Test
    fun keepsOnlyWhatGetsThereClearlySooner() {
        val usual = listOf(itin("sat", 26 * 60, 27 * 60))
        val wide = listOf(
            itin("kiryat-ono", 40, 95),         // Today, through Kiryat Ono: far sooner.
            itin("sat", 26 * 60, 27 * 60),      // The same option again.
            itin("sat-walk", 25 * 60 + 50, 26 * 60 + 50), // Ten minutes better isn't worth the walk.
        )
        assertEquals(listOf("kiryat-ono"), WiderWalk.better(wide, usual, false).map { it.id })
        // With nothing usual, anything that rides transit helps.
        assertEquals(listOf("kiryat-ono", "sat", "sat-walk"), WiderWalk.better(wide, emptyList(), false).map { it.id })
    }

    @Test
    fun arrivingByATimeKeepsWhatLeavesClearlyLater() {
        val usual = listOf(itin("early", -60, 0))
        val wide = listOf(itin("later", 3 * 60, 4 * 60), itin("bit-later", -50, 10))
        assertEquals(listOf("later"), WiderWalk.better(wide, usual, true).map { it.id })
    }
}
