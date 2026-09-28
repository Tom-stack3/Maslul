package com.maslul.app

import com.maslul.app.data.Freshness
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.StopArrival
import com.maslul.app.data.Vehicle
import com.maslul.app.data.freshness
import com.maslul.app.ui.components.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FreshnessTest {
    private val now = Instant.parse("2026-09-28T09:00:00Z")

    private fun vehicle(ageSec: Long) = Vehicle(
        lineRef = "1", journeyRef = "123", originDeparture = now.minusSeconds(1800), lat = 32.0, lon = 34.8,
        bearing = null, velocityKmh = null, recordedAt = now.minusSeconds(ageSec), distanceFromStart = null,
        nextStopCode = null, vehicleRef = "v1",
    )

    @Test
    fun thresholds() {
        assertEquals(Freshness.SCHEDULED, Freshness.of(null, now))
        assertEquals(Freshness.LIVE, Freshness.of(now.minusSeconds(40), now))
        assertEquals(Freshness.LIVE, Freshness.of(now.minusSeconds(Freshness.LIVE_MAX_AGE_SEC), now))
        assertEquals(Freshness.STALE, Freshness.of(now.minusSeconds(Freshness.LIVE_MAX_AGE_SEC + 1), now))
        // A clock slightly behind the feed still counts as fresh.
        assertEquals(Freshness.LIVE, Freshness.of(now.plusSeconds(5), now))
    }

    @Test
    fun liveCallFreshness() {
        val live = LiveCall(LiveStatus.LIVE, now, now.plusSeconds(300), vehicle = vehicle(30))
        assertEquals(Freshness.LIVE, live.freshness(now))
        assertEquals(Freshness.STALE, live.copy(vehicle = vehicle(240)).freshness(now))
        // Without a live vehicle (or once it passed the stop) it's timetable data.
        assertEquals(Freshness.SCHEDULED, LiveCall(LiveStatus.UNTRACKED, now, null).freshness(now))
        assertEquals(Freshness.SCHEDULED, live.copy(status = LiveStatus.PASSED).freshness(now))
        assertEquals(Freshness.SCHEDULED, (null as LiveCall?).freshness(now))
    }

    @Test
    fun stopArrivalFreshness() {
        assertEquals(Freshness.LIVE, StopArrival(now, true, 0, "1", now.minusSeconds(20)).freshness(now))
        assertEquals(Freshness.STALE, StopArrival(now, true, 0, "1", now.minusSeconds(200)).freshness(now))
        assertEquals(Freshness.SCHEDULED, StopArrival(now, false, 0, "1").freshness(now))
    }

    @Test
    fun updatedAgo() {
        assertEquals("Updated just now", Fmt.ago(now.minusSeconds(2), now))
        assertEquals("Updated 30s ago", Fmt.ago(now.minusSeconds(30), now))
        assertEquals("Updated 2 min ago", Fmt.ago(now.minusSeconds(150), now))
        assertEquals("Updated just now", Fmt.ago(now.plusSeconds(3), now))
    }
}
