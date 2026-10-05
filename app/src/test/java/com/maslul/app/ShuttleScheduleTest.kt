package com.maslul.app

import com.maslul.app.data.GeoPoint
import com.maslul.app.data.IsraelZone
import com.maslul.app.data.Place
import com.maslul.app.data.Shuttle
import com.maslul.app.data.ShuttleSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ShuttleScheduleTest {
    private fun place(name: String, km: Double, eastKm: Double = 0.0) = Place(name, 32.0 + km / 111.195, 34.8 + eastKm / 94.3)
    private fun local(s: String) = LocalDateTime.parse(s).atZone(IsraelZone).toInstant()

    private val office = place("Office", 0.0)
    private val station = place("Station", 6.0)
    private val shuttle = Shuttle("s1", "Office shuttle", office, station, listOf(7 * 60 + 30, 8 * 60, 17 * 60 + 15), rideMinutes = 15)

    @Test
    fun parsesTimesLeniently() {
        assertEquals(listOf(450, 495, 1020), ShuttleSchedule.parseTimes("8:15, 07:30 17.00"))
        assertEquals(listOf(450), ShuttleSchedule.parseTimes("07:30, 07:30"))
        assertNull(ShuttleSchedule.parseTimes("07:30, soon"))
        assertNull(ShuttleSchedule.parseTimes("24:00"))
        assertNull(ShuttleSchedule.parseTimes("  "))
        assertEquals("07:30, 17:00", ShuttleSchedule.formatTimes(listOf(450, 1020)))
    }

    @Test
    fun departuresFollowTheDaysItRuns() {
        // Thursday 2026-10-08 evening: the next ones are Sunday morning (no shuttle on Friday or Saturday).
        val out = ShuttleSchedule.departures(shuttle, local("2026-10-08T18:00"), local("2026-10-11T23:00"))
        assertEquals(listOf(local("2026-10-11T07:30"), local("2026-10-11T08:00"), local("2026-10-11T17:15")), out)
        val sameDay = ShuttleSchedule.departures(shuttle, local("2026-10-05T07:45"), local("2026-10-05T12:00"))
        assertEquals(listOf(local("2026-10-05T08:00")), sameDay)
    }

    @Test
    fun aRideHasItsShuttlesTimesAndNoLiveTrip() {
        val leg = ShuttleSchedule.leg(shuttle, local("2026-10-05T08:00"))
        assertEquals(local("2026-10-05T08:15"), leg.end)
        assertEquals("Office shuttle", leg.lineLabel)
        assertEquals("s1", leg.shuttleId)
        assertNull(leg.tripId)
        assertEquals(listOf(local("2026-10-05T17:15")), ShuttleSchedule.ridesAfter(shuttle, leg, 1).map { it.start })
    }

    @Test
    fun onlyShuttlesThatHelpAreTried() {
        val home = place("Home", 30.0)
        // Office → station → home: the shuttle takes you most of the way's start, toward home.
        assertEquals(listOf(shuttle), ShuttleSchedule.relevant(listOf(shuttle), office.point, home.point))
        // The other way round it only takes you further away.
        assertTrue(ShuttleSchedule.relevant(listOf(shuttle), home.point, office.point).isEmpty())
        // A short hop next door doesn't need one.
        assertTrue(ShuttleSchedule.relevant(listOf(shuttle), office.point, GeoPoint(office.lat + 0.001, office.lon)).isEmpty())
        // From somewhere else across town toward the station area it's still worth checking.
        val elsewhere = place("Elsewhere", 0.5, eastKm = 1.0)
        assertEquals(listOf(shuttle), ShuttleSchedule.relevant(listOf(shuttle), elsewhere.point, place("Beyond", 9.0).point))
    }

    @Test
    fun daysReadLikeAWeek() {
        assertEquals("Sun–Thu", ShuttleSchedule.formatDays(Shuttle.WORK_WEEK))
        assertEquals("Every day", ShuttleSchedule.formatDays((1..7).toSet()))
        assertEquals("Sun, Fri", ShuttleSchedule.formatDays(setOf(5, 7)))
    }

    @Test
    fun aShuttleFromTheStationIsTriedForTheWayBack() {
        // Home → train to the station → shuttle to the office, though the station is a bit farther from home.
        val home = place("Home", -60.0)
        val back = shuttle.copy(id = "b", from = station, to = office)
        assertEquals(listOf(back), ShuttleSchedule.relevant(listOf(back), home.point, office.point))
    }

    @Test
    fun aShuttleThatLoopsBackIsntTried() {
        // From the station, riding the office shuttle would only bring you back to the station.
        val far = place("Far", -50.0, eastKm = 20.0)
        assertTrue(ShuttleSchedule.relevant(listOf(shuttle), station.point, far.point).isEmpty())
    }

    private fun missing(
        name: String = "Office shuttle",
        from: Place? = office,
        to: Place? = station,
        times: String = "07:30, 17:00",
        rideMinutes: Int? = 15,
        days: Set<Int> = Shuttle.WORK_WEEK,
    ) = ShuttleSchedule.missing(name, from, to, times, rideMinutes, days)

    @Test
    fun aCompleteShuttleNeedsNothing() {
        assertEquals(emptyList<String>(), missing())
        assertNull(ShuttleSchedule.missingText(missing()))
        // One departure and one day are enough; so are the shortest and longest rides.
        assertEquals(emptyList<String>(), missing(times = "7:05", days = setOf(5), rideMinutes = 5))
        assertEquals(emptyList<String>(), missing(rideMinutes = 120))
    }

    @Test
    fun anUnsetRideTimeIsCalledOut() {
        assertEquals(listOf("how long the ride takes"), missing(rideMinutes = null))
        assertEquals("Still needed: how long the ride takes.", ShuttleSchedule.missingText(missing(rideMinutes = null)))
    }

    @Test
    fun everythingMissingIsListedInFormOrder() {
        val all = missing(name = "  ", from = null, to = null, times = "", rideMinutes = null, days = emptySet())
        assertEquals(
            listOf("a name", "where it leaves from", "where it goes", "departure times", "how long the ride takes", "a day it runs"),
            all,
        )
        assertEquals(
            "Still needed: a name, where it leaves from, where it goes, departure times, how long the ride takes and a day it runs.",
            ShuttleSchedule.missingText(all),
        )
        assertEquals("Still needed: a name and how long the ride takes.",
            ShuttleSchedule.missingText(missing(name = "", rideMinutes = null)))
    }

    @Test
    fun badTimesAndTheSameStopTwiceAreMissing() {
        assertEquals(listOf("departure times like 07:30"), missing(times = "07:30, soon"))
        assertEquals(listOf("departure times like 07:30"), missing(times = "25:00"))
        assertEquals(listOf("departure times"), missing(times = " , "))
        // Both ends at the same stop (within a few dozen metres) isn't a shuttle.
        assertEquals(listOf("two different stops"), missing(to = place("Office gate", 0.05)))
        assertEquals(emptyList<String>(), missing(to = place("Down the road", 0.3)))
    }
}
