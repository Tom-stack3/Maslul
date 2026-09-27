package com.maslul.app

import com.maslul.app.data.LiveRepository
import com.maslul.app.data.LongName
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.Polyline
import com.maslul.app.data.TransitMode
import com.maslul.app.data.TransitRepository
import com.maslul.app.data.TransitousApi
import com.maslul.app.data.TripIds
import com.maslul.app.data.cleanHeadsign
import com.maslul.app.data.forTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ParsingTest {

    @Test
    fun decodesGooglePolylineAtPrecision5() {
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6)
        assertEquals(-120.2, pts[0].lon, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6)
        assertEquals(-126.453, pts[2].lon, 1e-6)
    }

    @Test
    fun parsesTransitousTripIds() {
        val p = TripIds.parse("20260927_21:20_il-Israel-MOT_585026147_270926")!!
        assertEquals(LocalDate.of(2026, 9, 27), p.serviceDate)
        assertEquals(Instant.parse("2026-09-27T18:20:00Z"), p.originDeparture) // IDT = UTC+3
        assertEquals("585026147", p.tripNumber)
        assertEquals("585026147_270926", p.gtfsTripId)
    }

    @Test
    fun parsesTripIdsPastMidnight() {
        val p = TripIds.parse("20260927_25:10_il-Israel-MOT_1_270926")!!
        assertEquals(Instant.parse("2026-09-27T22:10:00Z"), p.originDeparture)
    }

    @Test
    fun buildsTripIdsRoundTrip() {
        val date = LocalDate.of(2026, 9, 27)
        val id = TripIds.build(date, Instant.parse("2026-09-27T20:50:00Z"), "25271287_270926")
        assertEquals("20260927_23:50_il-Israel-MOT_25271287_270926", id)
        // After midnight, the hour keeps counting (GTFS style).
        val late = TripIds.build(date, Instant.parse("2026-09-27T22:05:00Z"), "9_270926")
        assertEquals("20260927_25:05_il-Israel-MOT_9_270926", late)
        assertEquals(Instant.parse("2026-09-27T22:05:00Z"), TripIds.parse(late)!!.originDeparture)
    }

    @Test
    fun buildsTripIdsFromStrideJourneyRefs() {
        // An after-midnight ride listed under the 27th belongs to the 26th's service day.
        val id = TripIds.fromJourney("1401323_260926", Instant.parse("2026-09-26T21:10:00Z"), LocalDate.of(2026, 9, 27))
        assertEquals("20260926_24:10_il-Israel-MOT_1401323_260926", id)
    }

    @Test
    fun rejectsForeignTripIds() {
        assertNull(TripIds.parse("20260927_21:20_de-DELFI_123"))
        assertNull(TripIds.parse(null))
        assertEquals("7020", TripIds.lineRef("il-Israel-MOT_7020"))
        assertNull(TripIds.lineRef("de-x_1"))
    }

    @Test
    fun parsesStrideLongNames() {
        val (o, d) = LongName.parse("ת.רכבת תל אביב - סבידור/רציפים B-תל אביב יפו<->ת. מרכזית ירושלים/הורדה-ירושלים-1#")
        assertEquals("ת.רכבת תל אביב - סבידור/רציפים B, תל אביב יפו", o)
        assertEquals("ת. מרכזית ירושלים/הורדה", d)

        val (_, d2) = LongName.parse("הדסה עין כרם-ירושלים<->נווה יעקב - צפון-ירושלים-10")
        assertEquals("נווה יעקב - צפון, ירושלים", d2)

        val (o3, d3) = LongName.parse("נתב''ג-נמל תעופה בן גוריון<->נהריה-נהריה")
        assertEquals("נתב''ג, נמל תעופה בן גוריון", o3)
        assertEquals("נהריה", d3)
    }

    @Test
    fun cleansHeadsigns() {
        assertEquals("אוניברסיטת ת\"א, תל אביב יפו", cleanHeadsign("תל אביב יפו_אוניברסיטת ת\"א"))
        assertEquals("ת. מרכזית ירושלים", cleanHeadsign("ירושלים_ת. מרכזית ירושלים"))
        assertEquals("Haifa", cleanHeadsign("Haifa"))
    }

    @Test
    fun mapsModes() {
        assertEquals(TransitMode.LIGHT_RAIL, TransitMode.fromMotis("TRAM"))
        assertEquals(TransitMode.TRAIN, TransitMode.fromMotis("REGIONAL_RAIL"))
        assertEquals(TransitMode.CABLE_CAR, TransitMode.fromMotis("AERIAL_LIFT"))
        assertEquals(TransitMode.TRAIN, TransitMode.fromGtfsRouteType("2"))
        assertEquals(null, TransitousApi.motisModes(setOf(TransitMode.BUS, TransitMode.TRAIN, TransitMode.LIGHT_RAIL,
            TransitMode.METRO, TransitMode.CABLE_CAR, TransitMode.FERRY)))
        assertEquals("BUS,COACH", TransitousApi.motisModes(setOf(TransitMode.BUS)))
    }

    @Test
    fun parsesCityFromMotDescription() {
        assertEquals("תל אביב יפו", TransitousApi.cityFromDescription("רחוב: דרך נמיר 25 עיר: תל אביב יפו רציף:  קומה:"))
        assertNull(TransitousApi.cityFromDescription(null))
    }

    @Test
    fun mergesPlacesWithoutDuplicates() {
        val a = listOf(Place("דיזנגוף סנטר", 32.0754, 34.7749), Place("Stop A", 32.0, 34.0, kind = PlaceKind.STOP))
        val b = listOf(Place("דיזנגוף סנטר", 32.0756, 34.7751), Place("Other", 31.0, 35.0))
        val m = TransitRepository.mergePlaces(a, b)
        assertEquals(listOf("דיזנגוף סנטר", "Stop A", "Other"), m.map { it.name })
    }

    @Test
    fun parsesSiriSnapshot() {
        val json = """
        {"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-09-27T21:42:00+03:00",
          "StopMonitoringDelivery":{"MonitoredStopVisit":[
            {"RecordedAtTime":"2026-09-27T21:41:24+03:00","MonitoredVehicleJourney":{"LineRef":"17903",
              "FramedVehicleJourneyRef":{"DataFrameRef":"2026-09-27","DatedVehicleJourneyRef":"586166398"},
              "OperatorRef":"25","OriginAimedDepartureTime":"2026-09-27T21:25:00+03:00",
              "VehicleLocation":{"Longitude":"34.901882","Latitude":"32.095497"},"Bearing":"355","Velocity":"4",
              "VehicleRef":"40224003","MonitoredCall":{"StopPointRef":"32226","Order":"18","DistanceFromStop":"5497"}}},
            {"RecordedAtTime":"2026-09-27T21:10:00+03:00","MonitoredVehicleJourney":{"LineRef":"1",
              "OriginAimedDepartureTime":"2026-09-27T21:00:00+03:00","VehicleLocation":{"Longitude":"34.8","Latitude":"32.1"}}},
            {"RecordedAtTime":"2026-09-27T21:41:00+03:00","MonitoredVehicleJourney":{"LineRef":"2",
              "OriginAimedDepartureTime":"2026-09-27T21:30:00+03:00","VehicleLocation":{"Longitude":"34.8","Latitude":"32.1"},
              "MonitoredCall":{"Order":"5","DistanceFromStop":"0"}}}
          ]}}}}
        """.trimIndent()
        val vs = LiveRepository.parseJson(json.byteInputStream(), Instant.parse("2026-09-27T18:42:00Z"))
        // The 32-minute-old "ghost" on line 1 is dropped.
        assertEquals(listOf("17903", "2"), vs.map { it.lineRef })
        val v = vs[0]
        assertEquals("586166398", v.journeyRef)
        assertEquals(Instant.parse("2026-09-27T18:25:00Z"), v.originDeparture)
        assertEquals(32.095497, v.lat, 1e-9)
        assertEquals(355f, v.bearing)
        assertEquals(5497.0, v.distanceFromStart!!, 0.0)
        // "0" past the first stop means "unknown".
        assertNull(vs[1].distanceFromStart)
        assertEquals(v, vs.forTrip("586166398", null))
        assertEquals(vs[1], vs.forTrip(null, Instant.parse("2026-09-27T18:30:20Z")))
    }
}
