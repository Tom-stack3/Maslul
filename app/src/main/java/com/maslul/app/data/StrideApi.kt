package com.maslul.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.LocalDate

/**
 * Open Bus Stride API (Hasadna / The Public Knowledge Workshop): a free mirror of the MOT
 * GTFS feed, used here for line search and daily line schedules.
 */
class StrideApi(private val base: String = "https://open-bus-stride-api.hasadna.org.il") {

    suspend fun searchRoutes(query: String, date: LocalDate, mode: TransitMode? = null): List<LineRoute> {
        val q = query.trim()
        val b = base.toHttpUrl().newBuilder()
            .addPathSegments("gtfs_routes/list")
            .addQueryParameter("date_from", date.toString())
            .addQueryParameter("date_to", date.toString())
            .addQueryParameter("limit", "200")
        when {
            q.isEmpty() && mode == TransitMode.TRAIN -> b.addQueryParameter("route_type", "2")
            q.isEmpty() && mode == TransitMode.LIGHT_RAIL -> b.addQueryParameter("route_type", "0")
            q.isEmpty() -> return emptyList()
            q.all { it.isDigit() || it in "אבגדהוזחטיכלמנסעפצקרשת" } && q.any { it.isDigit() } ->
                b.addQueryParameter("route_short_name", q)
            else -> b.addQueryParameter("route_long_name_contains", q)
        }
        mode?.let { m ->
            gtfsRouteType(m)?.let { if (q.isNotEmpty()) b.addQueryParameter("route_type", it) }
        }
        return Http.getJson<List<RouteDto>>(b.build()).map { it.toDomain() }
    }

    suspend fun routesByLineRef(lineRefs: List<Long>, date: LocalDate): List<LineRoute> {
        if (lineRefs.isEmpty()) return emptyList()
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("gtfs_routes/list")
            .addQueryParameter("date_from", date.toString())
            .addQueryParameter("date_to", date.toString())
            .addQueryParameter("line_refs", lineRefs.joinToString(","))
            .addQueryParameter("limit", "50")
            .build()
        return Http.getJson<List<RouteDto>>(url).map { it.toDomain() }
    }

    /** All directions / alternatives of a line on the given day. */
    suspend fun lineVariants(operatorRef: Long, mkt: String, date: LocalDate): List<LineRoute> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("gtfs_routes/list")
            .addQueryParameter("date_from", date.toString())
            .addQueryParameter("date_to", date.toString())
            .addQueryParameter("operator_refs", operatorRef.toString())
            .addQueryParameter("route_mkt", mkt)
            .addQueryParameter("limit", "50")
            .build()
        return Http.getJson<List<RouteDto>>(url).map { it.toDomain() }
    }

    suspend fun rides(gtfsRouteId: Long): List<RideDto> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("gtfs_rides/list")
            .addQueryParameter("gtfs_route_id", gtfsRouteId.toString())
            .addQueryParameter("limit", "5")
            .build()
        return Http.getJson(url)
    }

    /** Ordered stops of one ride, with offsets from its first departure. */
    suspend fun rideStops(rideId: Long, date: LocalDate): List<LineStop> {
        val (from, to) = dayWindow(date)
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("gtfs_ride_stops/list")
            .addQueryParameter("gtfs_ride_ids", rideId.toString())
            .addQueryParameter("arrival_time_from", from.toString())
            .addQueryParameter("arrival_time_to", to.toString())
            .addQueryParameter("limit", "300")
            .build()
        return lineStops(Http.getJson(url))
    }

    /** Every departure of the route from its first stop during the service day. */
    suspend fun departures(gtfsRouteId: Long, firstStopId: Long, date: LocalDate): List<Ride> {
        val (from, to) = dayWindow(date)
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("gtfs_ride_stops/list")
            .addQueryParameter("gtfs_ride__gtfs_route_id", gtfsRouteId.toString())
            .addQueryParameter("gtfs_stop_ids", firstStopId.toString())
            .addQueryParameter("arrival_time_from", from.toString())
            .addQueryParameter("arrival_time_to", to.toString())
            .addQueryParameter("limit", "1000")
            .build()
        return Http.getJson<List<RideStopDto>>(url)
            .mapNotNull { r ->
                val t = TransitousApi.instant(r.departure_time ?: r.arrival_time) ?: return@mapNotNull null
                Ride(r.gtfs_ride__journey_ref ?: return@mapNotNull null, t)
            }
            .distinctBy { it.journeyRef }
            .sortedBy { it.departure }
    }

    /** Service day in Israel runs past midnight (GTFS times > 24:00). */
    private fun dayWindow(date: LocalDate): Pair<Instant, Instant> {
        val start = date.atStartOfDay(IsraelZone).toInstant()
        return start to start.plusSeconds(30 * 3600L)
    }

    companion object {
        /** Ride-stop rows (any order) → the ride's ordered stops with offsets from its first departure. */
        fun lineStops(rows: List<RideStopDto>): List<LineStop> {
            val sorted = rows.distinctBy { it.stop_sequence }.sortedBy { it.stop_sequence }
            val first = sorted.firstOrNull()?.let { TransitousApi.instant(it.departure_time ?: it.arrival_time) }
                ?: return emptyList()
            return sorted.map {
                val t = TransitousApi.instant(it.arrival_time ?: it.departure_time) ?: first
                LineStop(
                    gtfsStopId = it.gtfs_stop_id,
                    code = it.gtfs_stop__code?.toString() ?: "",
                    name = it.gtfs_stop__name ?: "",
                    city = it.gtfs_stop__city,
                    lat = it.gtfs_stop__lat ?: 0.0,
                    lon = it.gtfs_stop__lon ?: 0.0,
                    sequence = it.stop_sequence,
                    offsetSec = t.epochSecond - first.epochSecond,
                )
            }
        }

        fun gtfsRouteType(m: TransitMode): String? = when (m) {
            TransitMode.BUS -> "3"
            TransitMode.TRAIN -> "2"
            TransitMode.LIGHT_RAIL -> "0"
            TransitMode.CABLE_CAR -> "6"
            else -> null
        }
    }
}

@Serializable
class RouteDto(
    val id: Long,
    val date: String = "",
    val line_ref: Long = 0,
    val operator_ref: Long = 0,
    val route_short_name: String? = null,
    val route_long_name: String? = null,
    val route_mkt: String? = null,
    val route_direction: String? = null,
    val route_alternative: String? = null,
    val agency_name: String? = null,
    val route_type: String? = null,
) {
    fun toDomain() = LineRoute(
        gtfsRouteId = id,
        lineRef = line_ref,
        operatorRef = operator_ref,
        shortName = route_short_name ?: "",
        longName = route_long_name ?: "",
        mkt = route_mkt ?: "",
        direction = route_direction ?: "",
        alternative = route_alternative ?: "",
        agency = agency_name ?: "",
        mode = TransitMode.fromGtfsRouteType(route_type),
        date = date,
    )
}

@Serializable
class RideDto(val id: Long, val gtfs_route_id: Long = 0, val journey_ref: String? = null)

@Serializable
class RideStopDto(
    val gtfs_stop_id: Long = 0,
    val gtfs_ride_id: Long = 0,
    val arrival_time: String? = null,
    val departure_time: String? = null,
    val stop_sequence: Int = 0,
    val gtfs_ride__journey_ref: String? = null,
    val gtfs_stop__code: Long? = null,
    val gtfs_stop__lat: Double? = null,
    val gtfs_stop__lon: Double? = null,
    val gtfs_stop__name: String? = null,
    val gtfs_stop__city: String? = null,
    @SerialName("gtfs_route__line_ref") val lineRef: Long? = null,
)

/** Groups directions/alternatives into lines and orders them sensibly. */
fun groupLines(routes: List<LineRoute>): List<Line> =
    routes.groupBy { "${it.operatorRef}|${it.mkt}" }
        .values
        .map { rs -> Line(rs.sortedWith(compareBy({ it.direction }, { it.alternative != "#" && it.alternative != "0" }, { it.alternative }))) }
        .sortedWith(
            compareBy<Line>(
                { it.main.shortName.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE },
                { it.main.shortName },
                { it.main.agency },
            ),
        )
