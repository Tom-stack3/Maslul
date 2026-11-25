package com.maslul.app

import com.maslul.app.data.IsraelZone
import com.maslul.app.data.TransitMode
import com.maslul.app.data.TripAlert
import com.maslul.app.data.TripAlerts
import com.maslul.app.data.TripAlerts.Alert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

class TripAlertsTest {
    /** 08:30, Sunday to Thursday. */
    private val alert = TripAlert(8 * 60 + 30)
    private fun at(day: Int, h: Int, m: Int): Instant = LocalDateTime.of(2026, 10, day, h, m).atZone(IsraelZone).toInstant()
    // Thursday 22 October 2026.
    private val departure = at(22, 8, 30)

    private fun option(leave: Instant, arrive: Instant, line: String = "18", late: Long = 0) =
        TripAlerts.Option(leave, arrive, line, TransitMode.BUS, leave.plusSeconds(300), "Dizengoff", live = true, lateMinutes = late)

    @Test
    fun watchesFromHalfAnHourBeforeEveryFiveMinutes() {
        assertEquals(departure, TripAlerts.nextDeparture(alert, at(22, 6, 0)))
        assertEquals(at(22, 8, 0), TripAlerts.nextCheck(departure, at(22, 6, 0)))
        assertEquals(at(22, 8, 7), TripAlerts.nextCheck(departure, at(22, 8, 2)))
        // A few minutes past the time it still watches (a late bus), then it's over.
        assertEquals(departure, TripAlerts.nextDeparture(alert, at(22, 8, 35)))
        assertNull(TripAlerts.nextCheck(departure, at(22, 8, 41)))
    }

    @Test
    fun theNextDayIsAWorkday() {
        // After Thursday's watch: Friday and Saturday are off, so Sunday.
        assertEquals(at(25, 8, 30), TripAlerts.nextDeparture(alert, at(22, 9, 0)))
        assertNull(TripAlerts.nextDeparture(alert.copy(enabled = false), at(22, 6, 0)))
    }

    @Test
    fun saysWhenToGoOnce() {
        val usual = option(at(22, 8, 30), at(22, 9, 0))
        val start = TripAlerts.Progress(departure.epochSecond)
        // Half an hour before: nothing to say yet.
        val (none, p1) = TripAlerts.decide(start, departure, listOf(usual), at(22, 8, 0), goLeadSec = 300)
        assertTrue(none.isEmpty())
        assertEquals(at(22, 9, 0).epochSecond, p1.usualArrival)
        // Five minutes before leaving: time to go.
        val (go, p2) = TripAlerts.decide(p1, departure, listOf(usual), at(22, 8, 25), goLeadSec = 300)
        assertEquals(listOf<Alert>(Alert.GoNow(usual)), go)
        // And not again.
        assertTrue(TripAlerts.decide(p2, departure, listOf(usual), at(22, 8, 27), goLeadSec = 300).first.isEmpty())
    }

    @Test
    fun aLateRideMeansLeavingEarlier() {
        val onTime = option(at(22, 8, 30), at(22, 9, 0))
        val (_, p1) = TripAlerts.decide(TripAlerts.Progress(departure.epochSecond), departure, listOf(onTime), at(22, 8, 0), 300)
        // Ten minutes later the 18 is running 9 minutes late; the 5 leaving at 08:21 still gets in at 08:59.
        val late = option(at(22, 8, 30), at(22, 9, 9), late = 9)
        val earlier = option(at(22, 8, 21), at(22, 8, 59), line = "5")
        val (alerts, p2) = TripAlerts.decide(p1, departure, listOf(late, earlier), at(22, 8, 10), 300)
        assertEquals(listOf<Alert>(Alert.LeaveEarlier(earlier, 9, late)), alerts)
        // When it's time, it's the earlier way to go for.
        val (go, _) = TripAlerts.decide(p2, departure, listOf(late, earlier), at(22, 8, 16), 300)
        assertEquals(listOf<Alert>(Alert.GoNow(earlier)), go)
    }

    @Test
    fun aRideJustAsLateAsUsualIsNoReasonToLeaveEarlier() {
        val usual = option(at(22, 8, 30), at(22, 9, 0))
        val earlier = option(at(22, 8, 20), at(22, 8, 52), line = "5")
        val (alerts, _) = TripAlerts.decide(TripAlerts.Progress(departure.epochSecond), departure, listOf(usual, earlier), at(22, 8, 5), 300)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun nothingRunningIsSaidOnce() {
        val start = TripAlerts.Progress(departure.epochSecond)
        val (alerts, p) = TripAlerts.decide(start, departure, emptyList(), at(22, 8, 0), 300)
        assertEquals(listOf<Alert>(Alert.NoService(null)), alerts)
        assertTrue(TripAlerts.decide(p, departure, emptyList(), at(22, 8, 5), 300).first.isEmpty())
        // The first ride only an hour after the usual time counts as nothing running too, and says when it is.
        val tomorrow = option(at(22, 9, 40), at(22, 10, 10))
        assertEquals(listOf<Alert>(Alert.NoService(tomorrow)), TripAlerts.decide(start, departure, listOf(tomorrow), at(22, 8, 0), 300).first)
    }
}
