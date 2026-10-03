package com.maslul.app.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

val IsraelZone: ZoneId = ZoneId.of("Asia/Jerusalem")

data class GeoPoint(val lat: Double, val lon: Double)

enum class TransitMode {
    WALK, BUS, TRAIN, LIGHT_RAIL, METRO, CABLE_CAR, FERRY, OTHER;

    val isTransit get() = this != WALK

    /** One vehicle of this mode, as riders say it: "train is close". */
    val vehicle: String
        get() = when (this) {
            BUS -> "bus"
            TRAIN, LIGHT_RAIL, METRO -> "train"
            CABLE_CAR -> "cable car"
            FERRY -> "ferry"
            WALK, OTHER -> "ride"
        }

    /** Several vehicles of this mode: "Next trains". */
    val vehicles: String
        get() = when (this) {
            BUS -> "buses"
            FERRY -> "ferries"
            else -> vehicle + "s"
        }

    companion object {
        /** [vehicles] for a mix of rides: their mode's word when they share one, else "rides". */
        fun vehiclesOf(rides: List<Leg>): String = rides.map { it.mode }.distinct().singleOrNull()?.vehicles ?: "rides"

        /** Maps a MOTIS mode string to the modes that exist in Israel. */
        fun fromMotis(mode: String?): TransitMode = when (mode) {
            "WALK" -> WALK
            "BUS", "COACH" -> BUS
            "RAIL", "HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_FAST_RAIL",
            "REGIONAL_RAIL", "SUBURBAN" -> TRAIN
            "TRAM" -> LIGHT_RAIL
            "SUBWAY", "METRO", "FUNICULAR" -> METRO
            "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR" -> CABLE_CAR
            "FERRY" -> FERRY
            else -> OTHER
        }

        /** Maps a GTFS route_type to a mode. */
        fun fromGtfsRouteType(type: String?): TransitMode = when (type?.toIntOrNull()) {
            0 -> LIGHT_RAIL
            1 -> METRO
            2 -> TRAIN
            3 -> BUS
            4 -> FERRY
            5, 6 -> CABLE_CAR
            7 -> METRO
            else -> OTHER
        }
    }
}

enum class PlaceKind { CURRENT_LOCATION, ADDRESS, POI, STOP, HOME, WORK, PIN }

@Serializable
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val subtitle: String? = null,
    val kind: PlaceKind = PlaceKind.POI,
    val stopId: String? = null,
) {
    val point get() = GeoPoint(lat, lon)
    /** Identity used to dedupe suggestions and recents. */
    val key get() = "${name.trim()}|${"%.4f".format(lat)}|${"%.4f".format(lon)}"
}

data class StopCall(
    val stopId: String?,
    val name: String,
    val code: String?,
    val lat: Double,
    val lon: Double,
    val scheduledArrival: Instant?,
    val scheduledDeparture: Instant?,
    val track: String? = null,
    /** Floor within a multi-level station (e.g. Tel Aviv Central), when given. */
    val floor: String? = null,
) {
    val point get() = GeoPoint(lat, lon)
    /** "Platform 13", "Platform 5, floor 6" — where to stand at a big station. */
    val platformLabel: String?
        get() = listOfNotNull(track?.let { "Platform $it" }, floor?.let { if (track != null) "floor $it" else "Floor $it" })
            .joinToString(", ").ifEmpty { null }
    val scheduledTime: Instant? get() = scheduledDeparture ?: scheduledArrival
}

data class WalkStep(val instruction: String, val distanceM: Double)

data class Leg(
    val mode: TransitMode,
    val from: StopCall,
    val to: StopCall,
    val start: Instant,
    val end: Instant,
    val distanceM: Double?,
    val headsign: String?,
    val routeShortName: String?,
    val routeLongName: String?,
    val routeColor: Int?,
    val routeTextColor: Int?,
    val agencyName: String?,
    val tripId: String?,
    val routeId: String?,
    val intermediateStops: List<StopCall>,
    val geometry: List<GeoPoint>,
    val steps: List<WalkStep> = emptyList(),
    val alerts: List<String> = emptyList(),
    /** GTFS agency_id (== Stride operator_ref); with [agencyName] picks the operator colour. */
    val agencyId: String? = null,
    /** Other lines riding between the same two stops, for a combined option (see [Combine]). */
    val alternatives: List<Leg> = emptyList(),
) {
    val durationSec get() = end.epochSecond - start.epochSecond
    val combined get() = alternatives.isNotEmpty()
    /** This ride and its alternatives, soonest departure first. */
    val options: List<Leg> get() = if (alternatives.isEmpty()) listOf(this) else (alternatives + this).sortedBy { it.start }
    /** Stable identity of one ride, for live-state maps. */
    val rideKey: String get() = tripId ?: "$lineLabel@${start.epochSecond}"
    val lineLabel: String
        get() = routeShortName?.takeIf { it.isNotBlank() && it != "NaN" }
            ?: when (mode) {
                TransitMode.TRAIN -> "Train"
                TransitMode.CABLE_CAR -> "Cable"
                else -> "—"
            }
    /** GTFS route_id == SIRI LineRef. */
    val lineRef: String? get() = TripIds.lineRef(routeId)
}

data class Itinerary(
    val id: String,
    val start: Instant,
    val end: Instant,
    val transfers: Int,
    val legs: List<Leg>,
) {
    val durationSec get() = end.epochSecond - start.epochSecond
    val transitLegs get() = legs.filter { it.mode.isTransit }
    val firstTransit get() = legs.firstOrNull { it.mode.isTransit }
    val walkSec get() = legs.filter { it.mode == TransitMode.WALK }.sumOf { it.durationSec }
    val walkMeters get() = legs.filter { it.mode == TransitMode.WALK }.sumOf { it.distanceM ?: 0.0 }
}

data class PlanResult(
    val itineraries: List<Itinerary>,
    val walkOnly: Itinerary?,
    val nextCursor: String?,
    val previousCursor: String?,
)

/** A departure from a stop, as shown on a stop board. */
data class Departure(
    val tripId: String,
    val routeId: String?,
    val mode: TransitMode,
    val lineLabel: String,
    val headsign: String,
    val agency: String?,
    val routeColor: Int?,
    val scheduled: Instant,
    val stop: StopCall,
    val agencyId: String? = null,
)

/** One gtfs route (a direction/alternative of a line) from Stride. */
@Serializable
data class LineRoute(
    val gtfsRouteId: Long,
    val lineRef: Long,
    val operatorRef: Long,
    val shortName: String,
    val longName: String,
    val mkt: String,
    val direction: String,
    val alternative: String,
    val agency: String,
    val mode: TransitMode,
    val date: String,
) {
    val origin: String get() = LongName.parse(longName).first
    val destination: String get() = LongName.parse(longName).second
    val label: String
        get() = shortName.takeIf { it.isNotBlank() && it != "NaN" }
            ?: if (mode == TransitMode.TRAIN) "Train" else "—"
}

/** A line groups all its directions/alternatives (same operator + mkt). */
data class Line(val routes: List<LineRoute>) {
    val main get() = routes.first()
}

data class LineStop(
    val gtfsStopId: Long,
    val code: String,
    val name: String,
    val city: String?,
    val lat: Double,
    val lon: Double,
    val sequence: Int,
    /** Scheduled offset from the trip's first departure. */
    val offsetSec: Long,
)

/** A scheduled trip of a line. [tripId] is its Transitous trip id, when already known. */
data class Ride(val journeyRef: String, val departure: Instant, val tripId: String? = null) {
    val tripNumber get() = journeyRef.substringBefore('_')
}

data class Vehicle(
    val lineRef: String,
    val journeyRef: String?,
    val originDeparture: Instant,
    val lat: Double,
    val lon: Double,
    val bearing: Float?,
    val velocityKmh: Int?,
    val recordedAt: Instant,
    val distanceFromStart: Double?,
    val nextStopCode: String?,
    val vehicleRef: String?,
) {
    val point get() = GeoPoint(lat, lon)
}

/** Parses Stride's long names: "Origin-City<->Destination-City-1#". */
object LongName {
    fun parse(longName: String): Pair<String, String> {
        val parts = longName.split("<->")
        if (parts.size < 2) return longName to ""
        val origin = parts[0].trim()
        // Destination ends with "-<direction><alternative>" e.g. "-1#" or "-20"; strip it.
        val dest = parts[1].trim().replace(Regex("-[0-9]*[#0-9A-Za-z]?$"), "").trim()
        return prettify(origin) to prettify(dest)
    }

    /** "Stop-City" -> "Stop, City" (city is after the last dash). */
    private fun prettify(s: String): String {
        val i = s.lastIndexOf('-')
        if (i <= 0 || i == s.length - 1) return s
        val stop = s.substring(0, i).trim()
        val city = s.substring(i + 1).trim()
        return if (stop.contains(city)) stop else "$stop, $city"
    }
}
