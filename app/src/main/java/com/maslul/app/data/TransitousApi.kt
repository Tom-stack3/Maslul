package com.maslul.app.data

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import com.maslul.app.i18n.S

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
        val maxWalkMinutes: Int = DEFAULT_MAX_WALK_MINUTES,
        val wheelchair: Boolean = false,
        val maxTransfers: Int? = null,
        val pageCursor: String? = null,
        val language: String? = null,
        /** Buffer for every change, so a slightly late bus doesn't break the connection. */
        val minTransferMinutes: Int = 2,
        val additionalTransferMinutes: Int = 1,
        val numItineraries: Int = 8,
        /** How far past [time] to look, in seconds (MOTIS default: 2 h). */
        val searchWindowSec: Int? = null,
        /** Route from/to these stops exactly instead of the coordinates. */
        val fromStopId: String? = null,
        val toStopId: String? = null,
        /** How to reach the first stop, e.g. "CAR_DROPOFF" for a lift (MOTIS default: walking). */
        val preTransitModes: String? = null,
        /** Longest time to reach the first stop, when not [maxWalkMinutes] (e.g. how far a driver will go). */
        val maxPreTransitMinutes: Int? = null,
        /** Door-to-door modes without transit, e.g. "WALK,CAR" (MOTIS default: walking). */
        val directModes: String? = null,
        val maxDirectMinutes: Int? = null,
        /** Only the direct ([directModes]) route: no transit is searched. */
        val directOnly: Boolean = false,
    )

    suspend fun plan(req: PlanRequest): PlanResult {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/v6/plan")
            .addQueryParameter("fromPlace", req.fromStopId ?: "${req.from.lat},${req.from.lon}")
            .addQueryParameter("toPlace", req.toStopId ?: "${req.to.lat},${req.to.lon}")
            .addQueryParameter("numItineraries", req.numItineraries.toString())
            .apply { req.searchWindowSec?.let { addQueryParameter("searchWindow", it.toString()) } }
            .addQueryParameter("minTransferTime", req.minTransferMinutes.toString())
            .addQueryParameter("additionalTransferTime", req.additionalTransferMinutes.toString())
            .addQueryParameter("arriveBy", req.arriveBy.toString())
            .addQueryParameter("pedestrianSpeed", req.walkSpeedMps.toString())
            .addQueryParameter("maxPreTransitTime", ((req.maxPreTransitMinutes ?: req.maxWalkMinutes) * 60).toString())
            .addQueryParameter("maxPostTransitTime", (req.maxWalkMinutes * 60).toString())
            .apply {
                req.time?.let { addQueryParameter("time", queryTime(it)) }
                // Transit modes of WALK alone leave nothing to ride, so only the direct route is searched.
                (if (req.directOnly) "WALK" else motisModes(req.modes))?.let { addQueryParameter("transitModes", it) }
                req.preTransitModes?.let { addQueryParameter("preTransitModes", it) }
                req.directModes?.let { addQueryParameter("directModes", it) }
                req.maxDirectMinutes?.let { addQueryParameter("maxDirectTime", (it * 60).toString()) }
                if (req.wheelchair) addQueryParameter("pedestrianProfile", "WHEELCHAIR")
                req.maxTransfers?.let { addQueryParameter("maxTransfers", it.toString()) }
                req.pageCursor?.let { addQueryParameter("pageCursor", it) }
                req.language?.let { addQueryParameter("language", it) }
            }
            .build()
        val dto = Http.getJson<PlanDto>(url)
        return PlanResult(
            itineraries = dto.itineraries.map { it.toDomain() }
                // A lift is one drive: a car again after walking would need the car to be waiting there.
                .filter { it.legs.any { l -> l.mode.isTransit } && it.legs.count { l -> l.mode == TransitMode.CAR } <= 1 },
            walkOnly = dto.direct.firstOrNull { it.legs.all { l -> l.mode == "WALK" } }?.toDomain(),
            nextCursor = dto.nextPageCursor,
            previousCursor = dto.previousPageCursor,
            carOnly = dto.direct.firstOrNull { it.legs.any { l -> l.mode == "CAR" } }?.toDomain(),
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

    /** Place search; [type] ("STOP", "ADDRESS" or "PLACE") limits the kind of results. */
    suspend fun geocode(text: String, near: GeoPoint?, language: String? = null, type: String? = null): List<Place> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/v1/geocode")
            .addQueryParameter("text", text)
            .apply {
                type?.let { addQueryParameter("type", it) }
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
    /** Departures from [stopId]: at least [count], and all of those within [windowSec] of [time] when given. */
    suspend fun stopTimes(stopId: String, time: Instant? = null, count: Int = 30, language: String? = null, windowSec: Long? = null): List<Departure> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/v6/stoptimes")
            .addQueryParameter("stopId", stopId)
            .addQueryParameter("n", count.toString())
            .apply {
                time?.let { addQueryParameter("time", queryTime(it)) }
                windowSec?.let { addQueryParameter("window", it.toString()) }
                language?.let { addQueryParameter("language", it) }
            }
            .build()
        return Http.getJson<StopTimesDto>(url).stopTimes.mapNotNull { it.toDomain() }
    }

    companion object {
        // Israel's GTFS has no platform_code; the platform and floor sit in stop_desc instead:
        // "רחוב: יהושע חנקין עיר: באר שבע רציף: 13  קומה: " (empty when the stop has none).
        private val PLATFORM = Regex("""רציף:\s*([^\s:]+)(?=\s|$)""")
        private val FLOOR = Regex("""קומה:\s*([^\s:]+)(?=\s|$)""")

        fun platformOf(description: String?): String? = description?.let { PLATFORM.find(it)?.groupValues?.get(1) }

        /** Floor of a multi-level station; ground level ("0") isn't worth mentioning. */
        fun floorOf(description: String?): String? =
            description?.let { FLOOR.find(it)?.groupValues?.get(1) }?.takeIf { it != "0" }

        /**
         * MOTIS silently ignores a `time` with fractional seconds and answers from the start of
         * the service day instead, so always send whole seconds.
         */
        fun queryTime(t: Instant): String = t.truncatedTo(ChronoUnit.SECONDS).toString()

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
        track = track ?: scheduledTrack ?: TransitousApi.platformOf(description),
        floor = TransitousApi.floorOf(description),
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
    val agencyId: String? = null,
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
        // Israel has no realtime in Transitous; scheduled times are the baseline.
        val start = TransitousApi.instant(scheduledStartTime ?: startTime) ?: Instant.EPOCH
        val end = TransitousApi.instant(scheduledEndTime ?: endTime) ?: Instant.EPOCH
        return Leg(
            mode = mode,
            from = from.toStopCall(),
            to = to.toStopCall(),
            start = start,
            // A walk after a car drop-off can come back a few seconds "shorter than nothing".
            end = if (!mode.isTransit && end.isBefore(start)) start else end,
            distanceM = distance ?: if (!mode.isTransit && geometry.size > 1) GeoMath.cumulative(geometry).last() else null,
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
            agencyId = agencyId,
        )
    }

    private fun describeStep(s: StepDto): String {
        val street = s.streetName?.takeIf { it.isNotBlank() }
        return S.walkStep(s.relativeDirection, street)
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
        val legs = legs.map { it.toDomain() }.toMutableList()
        // After a car drop-off MOTIS pads the drive by a couple of minutes and takes them back with
        // a walk of negative length: the car really gets there when that walk "ends".
        for (i in 1 until legs.size) {
            val raw = this.legs[i]
            val end = TransitousApi.instant(raw.scheduledEndTime ?: raw.endTime) ?: continue
            if (legs[i - 1].mode == TransitMode.CAR && end.isBefore(legs[i].start)) {
                legs[i - 1] = legs[i - 1].copy(end = end)
                legs[i] = legs[i].copy(start = end, end = end)
            }
        }
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
    val agencyId: String? = null,
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
            ?: if (mode == TransitMode.TRAIN) S.mode(TransitMode.TRAIN) else "—"
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
            agencyId = agencyId,
        )
    }
}

@Serializable
class StopTimesDto(val stopTimes: List<StopTimeDto> = emptyList())
