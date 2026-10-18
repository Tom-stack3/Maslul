package com.maslul.app

import com.maslul.app.data.AppJson
import com.maslul.app.data.Freshness
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveCalls
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.freshness
import com.maslul.app.data.recordedAt
import com.maslul.app.data.StopPredictions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class StopPredictionsTest {
    // Trimmed from a real answer for stop 25609.
    private val json = """
        {"errors": null, "visits": {"25609": [
          {"trip_id": "27533011_181026", "eta": "2026-10-18 21:16:00+03:00", "timestamp": "2026-10-18 21:13:53+03:00", "line_name": "5"},
          {"trip_id": "584698490_181026", "eta": "2026-10-18 21:20:00+03:00", "timestamp": "2026-10-18 21:13:10+03:00"},
          {"trip_id": "584698490_181026", "eta": "2026-10-18 21:24:00+03:00", "timestamp": "2026-10-18 10:39:21+03:00"},
          {"trip_id": "44236685_181026", "eta": "2026-10-18 21:51:00+03:00", "timestamp": "2026-10-18 11:51:30+03:00"}
        ]}}
    """.trimIndent()

    @Test
    fun keepsEachTripsFreshestPredictionAndDropsLeftovers() {
        val dto = AppJson.decodeFromString<StopPredictions.ResponseDto>(json)
        val p = StopPredictions.parse(dto.visits.getValue("25609"), Instant.parse("2026-10-18T18:14:30Z"))
        // A morning leftover of a trip that has a fresh prediction, and one with no fresh one at all, are dropped.
        assertEquals(setOf("27533011_181026", "584698490_181026"), p.keys)
        assertEquals(Instant.parse("2026-10-18T18:16:00Z"), p.getValue("27533011_181026").eta)
        assertEquals(Instant.parse("2026-10-18T18:20:00Z"), p.getValue("584698490_181026").eta)
    }

    private val now = Instant.parse("2026-10-18T18:14:00Z")
    private fun call(status: LiveStatus, expected: Instant? = null, alight: Instant? = null) =
        LiveCall(status, scheduled = now.plusSeconds(300), expected = expected, alightExpected = alight)
    private fun prediction(etaMin: Long) = StopPredictions.Prediction("t", now.plusSeconds(etaMin * 60), now.minusSeconds(20))

    @Test
    fun theMinistrysTimeReplacesTheEstimateAndMovesTheArrivalWithIt() {
        val c = LiveCalls.withPrediction(call(LiveStatus.LIVE, now.plusSeconds(240), alight = now.plusSeconds(900)), prediction(6), now)
        // 6 minutes as the Ministry says, plus the minute and a half it usually runs early.
        val due = now.plusSeconds(360 + 90)
        assertEquals(due, c.best)
        assertEquals(now.plusSeconds(900 + 450 - 240), c.alightExpected)
        assertEquals(150L, c.delaySec)
        assertEquals(now.minusSeconds(20), c.recordedAt)
    }

    @Test
    fun aTripWithoutAVehicleIsLiveWhenTheMinistryPredictsIt() {
        val c = LiveCalls.withPrediction(call(LiveStatus.UNTRACKED), prediction(8), now)
        assertEquals(LiveStatus.LIVE, c.status)
        assertEquals(LiveCalls.ministryCorrected(now.plusSeconds(480)), c.best)
        assertEquals(Freshness.LIVE, c.freshness(now))
    }

    @Test
    fun aPassedBusStaysPassedUnlessTheMinistryStillExpectsItAWhile() {
        assertEquals(LiveStatus.PASSED, LiveCalls.withPrediction(call(LiveStatus.PASSED), prediction(1), now).status)
        assertEquals(LiveStatus.LIVE, LiveCalls.withPrediction(call(LiveStatus.PASSED), prediction(4), now).status)
        // No prediction, or one already in the past: unchanged.
        assertEquals(LiveStatus.UNTRACKED, LiveCalls.withPrediction(call(LiveStatus.UNTRACKED), null, now).status)
        assertEquals(LiveStatus.UNTRACKED, LiveCalls.withPrediction(call(LiveStatus.UNTRACKED), prediction(-3), now).status)
    }
}
