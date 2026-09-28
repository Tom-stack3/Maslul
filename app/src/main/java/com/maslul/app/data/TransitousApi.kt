package com.maslul.app.data

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Client for Transitous (https://transitous.org), a free community-run MOTIS instance that
 * routes on the Ministry of Transport's official GTFS feed.
 */
class TransitousApi(private val base: String = "https://api.transitous.org") {

    data class PlanRequest(
        val from: GeoPoint,
        val to: GeoPoint,
        val time: Instant? = null,
        val arriveBy: Boolean = false,
        val modes: Set<TransitMode> = TransitMode.entries.filter { it.isTransit }.toSet(),
        val walkSpeedMps: Double = 1.3,
        val maxWalkMinutes: Int = 15,
        val wheelchair: Boolean = false,
        val maxTransfers: Int? = null,
        val pageCursor: String? = null,
        val language: String? = null,
        /** Buffer for every change, so a slightly late bus doesn't break the connection. */
        val minTransferMinutes: Int = 2,
        val additionalTransferMinutes: Int = 1,
        val numItineraries: Int = 8,
        /** Route from/to these stops exactly instead of the coordinates. */
        val fromStopId: String? = null,
        val toStopId: String? = null,
    )

    suspend fun plan(req: PlanRequest): PlanResult {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/v6/plan")
            .addQueryParameter("fromPlace", req.fromStopId ?: "${req.from.lat},${req.from.lon}")
            .addQueryParameter("toPlace", req.toStopId ?: "${req.to.lat},${req.to.lon}")
            .addQueryParameter("numItineraries", req.numItineraries.toString())
            .addQueryParameter("minTransferTime", req.minTransferMinutes.toString())
            .addQueryParameter("additionalTransferTime", req.additionalTransferMinutes.toString())
            .addQueryParameter("arriveBy", req.arriveBy.toString())
            .addQueryParameter("pedestrianSpeed", req.walkSpeedMps.toString())
            .addQueryParameter("maxPreTransitTime", (req.maxWalkMinutes * 60).toString())
            .addQueryParameter("maxPostTransitTime", (req.maxWalkMinutes * 60).toString())
            .apply {
                req.time?.let { addQueryParameter("time", it.toString()) }
                motisModes(req.modes)?.let { addQueryParameter("transitModes", it) }
                if (req.wheelchair) addQueryParameter("pedestrianProfile", "WHEELCHAIR")
                req.maxTransfers?.let { addQueryParameter("maxTransfers", it.toString()) }
                req.pageCursor?.let { addQueryParameter("pageCursor", it) }
                req.language?.let { addQueryParameter("language", it) }
            }
            .build()
        val dto = Http.getJson<PlanDto>(url)
        return PlanResult(
            itineraries = dto.itineraries.map { it.toDomain() }.filter { it.legs.any { l -> l.mode.isTransit } },
            walkOnly = dto.direct.firstOrNull { it.legs.all { l -> l.mode == "WALK" } }?.toDomain(),
            nextCursor = dto.nextPageCursor,
            previousCursor = dto.previousPageCursor,
        )
    }

    /** Full trip (all stops + shape) as a single transit leg. */
    suspend fun trip(tripId: String, language: String? = null): Leg? {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/v6/trip")
            .addQueryParameter("tripId", tripId)
            .apply { language?.let { addQueryParameter("language", it) } }
            .build()
        val dto = Http.getJson<ItineraryDto>(url)
        return dto.legs.firstOrNull { it.mode != "WALK" }?.toDomain()
    }

    suspend fun geocode(text: String, near: GeoPoint?, language: String? = null): List<Place> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/v1/geocode")
            .addQueryParameter("text", text)
            .apply {
                near?.let {
                    addQueryParameter("place", "${it.lat},${it.lon}")
                    addQueryParameter("placeBias", "1")
                }
                language?.let { addQueryParameter("language", it) }
            }
            .build()
        return Http.getJson<List<MatchDto>>(url)
            .filter { it.country == null || it.country == "IL" || it.country == "PS" }
            .map { it.toPlace() }
    }

    suspend fun reverseGeocode(p: GeoPoint): Place? {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/v1/reverse-geocode")
            .addQueryParameter("place", "${p.lat},${p.lon}")
            .addQueryParameter("numResults", "3")
            .build()
        return Http.getJson<List<MatchDto>>(url).firstOrNull()?.toPlace()
    }

    suspend fun nearbyStops(center: GeoPoint, radiusDeg: Double = 0.006): List<Place> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/v6/map/stops")
            .addQueryParameter("min", "${center.lat - radiusDeg},${center.lon - radiusDeg}")
            .addQueryParameter("max", "${center.lat + radiusDeg},${center.lon + radiusDeg}")
            .build()
        return Http.getJson<List<PlaceDto>>(url)
            .filter { it.stopId != null }
            .map {
                Place(
                    name = it.name, lat = it.lat, lon = it.lon, kind = PlaceKind.STOP, stopId = it.stopId,
                    subtitle = listOfNotNull(it.stopCode?.let { c -> "#$c" }, cityFromDescription(it.description))
                        .joinToString(" · ").ifBlank { null },
                )
            }
            .sortedBy { GeoMath.distance(center, it.point) }
    }

    /** Upcoming departures at a stop. */
    suspend fun stopTimes(stopId: String, time: Instant? = null, count: Int = 30, language: String? = null): List<Departure> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/v6/stoptimes")
            .addQueryParameter("stopId", stopId)
            .addQueryParameter("n", count.toString())
            .apply {
                time?.let { addQueryParameter("time", it.toString()) }
                language?.let { addQueryParameter("language", it) }
            }
            .build()
        return Http.getJson<StopTimesDto>(url).stopTimes.mapNotNull { it.toDomain() }
    }

    companion object {
        fun motisModes(modes: Set<TransitMode>): String? {
            val all = TransitMode.entries.filter { it.isTransit }.toSet()
            if (modes.containsAll(all - TransitMode.OTHER)) return null
            return modes.flatMap {
                when (it) {
                    TransitMode.BUS -> listOf("BUS", "COACH")
                    TransitMode.TRAIN -> listOf("RAIL")
                    TransitMode.LIGHT_RAIL -> listOf("TRAM")
                    TransitMode.METRO -> listOf("SUBWAY", "FUNICULAR")
                    TransitMode.CABLE_CAR -> listOf("AERIAL_LIFT")
                    TransitMode.FERRY -> listOf("FERRY")
                    else -> emptyList()
                }
            }.joinToString(",").ifBlank { null }
        }

        /** MOT descriptions look like "רחוב: X עיר: Y רציף: ...". */
        fun cityFromDescription(desc: String?): String? =
            desc?.let { Regex("""עיר:\s*(.*?)\s*(רציף:|$)""").find(it)?.groupValues?.get(1)?.trim() }
                ?.takeIf { it.isNotBlank() }

        fun parseColor(hex: String?): Int? {
            val h = hex?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6) return null
            return h.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        }

        fun instant(s: String?): Instant? = s?.let {
            runCatching { Instant.parse(it) }.getOrElse { runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull() }
        }
    }
}

// ---- DTOs -------------------------------------------------------------------------------

@Serializable
class PlaceDto(
    val name: String = "",
    val stopId: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val arrival: String? = null,
    val departure: String? = null,
    val scheduledArrival: String? = null,
    val scheduledDeparture: String? = null,
    val stopCode: String? = null,
    val track: String? = null,
    val scheduledTrack: String? = null,
    val description: String? = null,
) {
    fun toStopCall() = StopCall(
        stopId = stopId,
        name = name.takeIf { it != "START" && it != "END" } ?: "",
        code = stopCode,
        lat = lat,
        lon = lon,
        scheduledArrival = TransitousApi.instant(scheduledArrival ?: arrival),
        scheduledDeparture = TransitousApi.instant(scheduledDeparture ?: departure),
        track = track ?: scheduledTrack,
    )
}

@Serializable
class PolylineDto(val points: String = "", val precision: Int = 6)

@Serializable
class StepDto(
    val relativeDirection: String? = null,
    val distance: Double = 0.0,
    val streetName: String? = null,
)

@Serializable
class AlertDto(val headerText: String = "", val descriptionText: String = "")

@Serializable
class LegDto(
    val mode: String = "WALK",
    val from: PlaceDto = PlaceDto(),
    val to: PlaceDto = PlaceDto(),
    val startTime: String = "",
    val endTime: String = "",
    val scheduledStartTime: String? = null,
    val scheduledEndTime: String? = null,
    val distance: Double? = null,
    val headsign: String? = null,
    val routeId: String? = null,
    val routeColor: String? = null,
    val routeTextColor: String? = null,
    val agencyName: String? = null,
    val tripId: String? = null,
    val routeShortName: String? = null,
    val routeLongName: String? = null,
    val displayName: String? = null,
    val intermediateStops: List<PlaceDto> = emptyList(),
    val legGeometry: PolylineDto = PolylineDto(),
    val steps: List<StepDto> = emptyList(),
    val alerts: List<AlertDto> = emptyList(),
) {
    fun toDomain(): Leg {
        val mode = TransitMode.fromMotis(mode)
        val geometry = runCatching { Polyline.decode(legGeometry.points, legGeometry.precision) }.getOrDefault(emptyList())
        return Leg(
            mode = mode,
            from = from.toStopCall(),
            to = to.toStopCall(),
            // Israel has no realtime in Transitous; scheduled times are the baseline.
            start = TransitousApi.instant(scheduledStartTime ?: startTime) ?: Instant.EPOCH,
            end = TransitousApi.instant(scheduledEndTime ?: endTime) ?: Instant.EPOCH,
            distanceM = distance ?: if (mode == TransitMode.WALK && geometry.size > 1) GeoMath.cumulative(geometry).last() else null,
            headsign = headsign?.let { cleanHeadsign(it) },
            routeShortName = routeShortName ?: displayName,
            routeLongName = routeLongName,
            routeColor = TransitousApi.parseColor(routeColor),
            routeTextColor = TransitousApi.parseColor(routeTextColor),
            agencyName = agencyName,
            tripId = tripId,
            routeId = routeId,
            intermediateStops = intermediateStops.map { it.toStopCall() },
            geometry = geometry,
            steps = steps.filter { it.distance > 0 }.map { WalkStep(describeStep(it), it.distance) },
            alerts = alerts.map { listOf(it.headerText, it.descriptionText).filter(String::isNotBlank).joinToString(": ") }
                .filter { it.isNotBlank() },
        )
    }

    private fun describeStep(s: StepDto): String {
        val street = s.streetName?.takeIf { it.isNotBlank() }
        val dir = when (s.relativeDirection) {
            "LEFT" -> "Turn left"
            "RIGHT" -> "Turn right"
            "SLIGHTLY_LEFT" -> "Bear left"
            "SLIGHTLY_RIGHT" -> "Bear right"
            "HARD_LEFT" -> "Sharp left"
            "HARD_RIGHT" -> "Sharp right"
            "UTURN_LEFT", "UTURN_RIGHT" -> "Make a U-turn"
            "DEPART" -> "Head out"
            "ELEVATOR" -> "Take the elevator"
            "STAIRS" -> "Take the stairs"
            "CIRCLE_CLOCKWISE", "CIRCLE_COUNTERCLOCKWISE" -> "Take the roundabout"
            else -> "Continue"
        }
        return if (street != null) "$dir on $street" else dir
    }
}

/** MOT headsigns are "City_Destination"; show "Destination, City". */
fun cleanHeadsign(h: String): String {
    val i = h.indexOf('_')
    if (i <= 0) return h
    val city = h.substring(0, i)
    val dest = h.substring(i + 1)
    return if (dest.contains(city)) dest else "$dest, $city"
}

@Serializable
class ItineraryDto(
    val id: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val transfers: Int = 0,
    val legs: List<LegDto> = emptyList(),
) {
    fun toDomain(): Itinerary {
        val legs = legs.map { it.toDomain() }
        return Itinerary(
            id = id.ifBlank { legs.joinToString { "${it.tripId}${it.start}" } },
            start = legs.firstOrNull()?.start ?: TransitousApi.instant(startTime) ?: Instant.EPOCH,
            end = legs.lastOrNull()?.end ?: TransitousApi.instant(endTime) ?: Instant.EPOCH,
            transfers = transfers,
            legs = legs,
        )
    }
}

@Serializable
class PlanDto(
    val itineraries: List<ItineraryDto> = emptyList(),
    val direct: List<ItineraryDto> = emptyList(),
    val nextPageCursor: String? = null,
    val previousPageCursor: String? = null,
)

@Serializable
class AreaDto(val name: String = "", val default: Boolean = false, val adminLevel: Double? = null)

@Serializable
class MatchDto(
    val type: String = "PLACE",
    val name: String = "",
    val id: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val street: String? = null,
    val houseNumber: String? = null,
    val country: String? = null,
    val areas: List<AreaDto> = emptyList(),
) {
    fun toPlace(): Place {
        val city = areas.firstOrNull { it.default }?.name ?: areas.lastOrNull()?.name
        val kind = when (type) {
            "STOP" -> PlaceKind.STOP
            "ADDRESS" -> PlaceKind.ADDRESS
            else -> PlaceKind.POI
        }
        val title = if (type == "ADDRESS") listOfNotNull(street, houseNumber).joinToString(" ").ifBlank { name } else name
        return Place(
            name = title,
            lat = lat,
            lon = lon,
            subtitle = city,
            kind = kind,
            stopId = if (type == "STOP") id else null,
        )
    }
}

@Serializable
class StopTimeDto(
    val place: PlaceDto = PlaceDto(),
    val mode: String = "BUS",
    val headsign: String = "",
    val tripId: String = "",
    val routeId: String? = null,
    val routeShortName: String? = null,
    val displayName: String? = null,
    val agencyName: String? = null,
    val routeColor: String? = null,
    val cancelled: Boolean = false,
    val tripCancelled: Boolean = false,
) {
    fun toDomain(): Departure? {
        if (cancelled || tripCancelled) return null
        val call = place.toStopCall()
        val time = call.scheduledDeparture ?: call.scheduledArrival ?: return null
        val mode = TransitMode.fromMotis(mode)
        val label = (routeShortName ?: displayName)?.takeIf { it.isNotBlank() && it != "NaN" }
            ?: if (mode == TransitMode.TRAIN) "Train" else "—"
        return Departure(
            tripId = tripId,
            routeId = routeId,
            mode = mode,
            lineLabel = label,
            headsign = cleanHeadsign(headsign),
            agency = agencyName,
            routeColor = TransitousApi.parseColor(routeColor),
            scheduled = time,
            stop = call,
        )
    }
}

@Serializable
class StopTimesDto(val stopTimes: List<StopTimeDto> = emptyList())
