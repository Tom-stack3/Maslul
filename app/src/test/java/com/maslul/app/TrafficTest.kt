package com.maslul.app

import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.PlanResult
import com.maslul.app.data.StopCall
import com.maslul.app.data.Traffic
import com.maslul.app.data.TransitMode
import com.maslul.app.data.TransitousApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

class TrafficTest {
    /** Israel time, as "2026-10-27T08:00" (a Tuesday; winter time, UTC+2). */
    private fun il(local: String) = OffsetDateTime.parse("$local:00+02:00").toInstant()
    private val highway = 90 / 3.6
    private val town = 25 / 3.6

    private fun stop(name: String) = StopCall(null, name, null, 32.0, 34.8, null, null)
    private fun leg(mode: TransitMode, start: Instant, min: Int, km: Double? = null) =
        Leg(mode, stop("a"), stop("b"), start, start.plusSeconds(min * 60L), km?.times(1000), null, null, null, null, null, null, null, null,
            emptyList(), emptyList())
    private fun itin(id: String, vararg legs: Leg) = Itinerary(id, legs.first().start, legs.last().end, 0, legs.toList())
    private fun req(time: Instant, arriveBy: Boolean = false) =
        TransitousApi.PlanRequest(GeoPoint(32.0, 34.8), GeoPoint(31.8, 35.2), time = time, arriveBy = arriveBy, preTransitModes = "CAR")

    @Test
    fun rushHourIsSlowerThanTheNight() {
        assertTrue(Traffic.factor(il("2026-10-27T08:00"), highway) > Traffic.factor(il("2026-10-27T03:00"), highway) + 0.1)
        assertTrue(Traffic.factor(il("2026-10-27T17:00"), town) > Traffic.factor(il("2026-10-27T03:00"), town) + 0.1)
        // Empty roads at night: about as Transitous times them.
        assertEquals(1.0, Traffic.factor(il("2026-10-27T03:00"), highway), 0.1)
    }

    @Test
    fun saturdayMorningIsQuieterThanSundayMorning() {
        assertTrue(Traffic.factor(il("2026-10-25T08:00"), highway) > Traffic.factor(il("2026-10-31T08:00"), highway))
    }

    @Test
    fun theFactorChangesSmoothlyBetweenHours() {
        // Each hour's value is at its middle; on the hour, halfway to the one before.
        val a = Traffic.factor(il("2026-10-27T06:30"), highway)
        val b = Traffic.factor(il("2026-10-27T07:00"), highway)
        val c = Traffic.factor(il("2026-10-27T07:30"), highway)
        assertEquals((a + c) / 2, b, 0.001)
        assertTrue(a < c)
    }

    @Test
    fun aDriveInRushHourGetsThereLater() {
        val start = il("2026-10-27T08:00")
        val car = Traffic.drive(leg(TransitMode.CAR, start, 40, km = 50.0))
        assertEquals(start, car.start)
        assertTrue(car.durationSec > 40 * 60L)
    }

    @Test
    fun aLiftToABusLeavesEarlierAndIsDroppedWhenThatsTooEarly() {
        val asked = il("2026-10-27T08:00")
        // One lift leaves right away, one half an hour later; both catch their bus.
        val tight = itin("tight", leg(TransitMode.CAR, asked, 20, km = 15.0), leg(TransitMode.BUS, asked.plusSeconds(20 * 60L), 30))
        val loose = itin("loose", leg(TransitMode.CAR, asked.plusSeconds(30 * 60L), 20, km = 15.0), leg(TransitMode.BUS, asked.plusSeconds(50 * 60L), 30))
        val out = Traffic.plan(PlanResult(listOf(tight, loose), null, null, null), req(asked)).itineraries
        assertEquals(listOf("loose"), out.map { it.id })
        val lift = out.single()
        // Still at the bus on time; leaving earlier for the traffic.
        assertEquals(loose.legs[1].start, lift.legs[0].end)
        assertTrue(lift.start.isBefore(loose.start))
        assertEquals(lift.legs[0].start, lift.start)
    }

    @Test
    fun drivingAllTheWayGetsInLaterOrLeavesEarlier() {
        val t = il("2026-10-27T08:00")
        val car = itin("car", leg(TransitMode.CAR, t, 40, km = 50.0), leg(TransitMode.WALK, t.plusSeconds(40 * 60L), 2))
        val leaving = Traffic.plan(PlanResult(emptyList(), null, null, null, car), req(t)).carOnly!!
        assertEquals(t, leaving.start)
        assertTrue(leaving.end.isAfter(car.end))
        // The walk at the end moves along with the drive.
        assertEquals(leaving.legs[0].end, leaving.legs[1].start)
        assertEquals(leaving.end, leaving.legs[1].end)

        val arriving = Traffic.plan(PlanResult(emptyList(), null, null, null, car), req(car.end, arriveBy = true)).carOnly!!
        assertEquals(car.end, arriving.end)
        assertTrue(arriving.start.isBefore(t))
    }

    @Test
    fun weekdayPeaksAreAsSlowAsGoogleMapsTypicallySays() {
        // Netanya to Hadera on Route 2, and Ra'anana to Azrieli: typically 1.6× and 2.5× the night's at 08:00.
        val netanya = GeoPoint(32.32, 34.85)
        val hadera = GeoPoint(32.43, 34.92)
        val raanana = GeoPoint(32.18, 34.87)
        val azrieli = GeoPoint(32.07, 34.79)
        assertEquals(1.6, Traffic.factor(il("2026-10-27T08:00"), highway, netanya, hadera), 0.15)
        assertEquals(2.2, Traffic.factor(il("2026-10-27T08:00"), highway, raanana, azrieli), 0.3)
        // Passing through Tel Aviv counts too; Friday and Saturday stay as the buses measured, outside it.
        val rishon = GeoPoint(31.96, 34.80)
        assertTrue(Traffic.factor(il("2026-10-27T17:00"), highway, raanana, rishon) > 2.0)
        assertEquals(Traffic.factor(il("2026-10-30T11:00"), highway), Traffic.factor(il("2026-10-30T11:00"), highway, netanya, hadera), 0.001)
        assertTrue(Traffic.factor(il("2026-10-30T11:00"), highway, raanana, azrieli) > Traffic.factor(il("2026-10-30T11:00"), highway) + 0.1)
        // Empty roads are empty everywhere.
        assertEquals(1.0, Traffic.factor(il("2026-10-27T03:00"), highway, raanana, azrieli), 0.05)
    }

    @Test
    fun aLiftOfferedInRushHourReachesLessFar() {
        assertTrue(Traffic.freeFlowMinutes(20, il("2026-10-27T08:00")) < 20)
        assertTrue(Traffic.freeFlowMinutes(20, il("2026-10-27T03:00")) in 18..21)
    }
}
