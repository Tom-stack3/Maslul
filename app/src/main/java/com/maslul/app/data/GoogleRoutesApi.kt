package com.maslul.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Google's Routes API, with the user's own key (Settings → Advanced): driving times in live and
 * predicted traffic, as Google Maps has them. Without a key, [Traffic]'s rush-hour table is used.
 * https://developers.google.com/maps/documentation/routes/compute_route_matrix
 */
class GoogleRoutesApi(
    private val key: String,
    /** The app's package and signing certificate (SHA-1, hex), so a key restricted to Maslul works. */
    private val app: Pair<String, String>? = null,
    private val url: String = "https://routes.googleapis.com/distanceMatrix/v2:computeRouteMatrix",
) {
    /**
     * Driving seconds from [from] to each of [to], leaving at [at] (now, when that's past: Google
     * only predicts ahead). Null where Google found no route.
     */
    suspend fun drivingSec(from: GeoPoint, to: List<GeoPoint>, at: Instant, now: Instant = Instant.now()): List<Long?> {
        if (to.isEmpty()) return emptyList()
        val body = MatrixRequest(
            origins = listOf(Waypoint.of(from)),
            destinations = to.map(Waypoint::of),
            // A minute ahead at least: by the time the request arrives, "now" is already past.
            departureTime = at.takeIf { it.isAfter(now.plusSeconds(60)) }?.toString(),
            travelMode = "DRIVE",
            routingPreference = "TRAFFIC_AWARE",
        )
        val request = Request.Builder().url(url)
            .header("X-Goog-Api-Key", key)
            .header("X-Goog-FieldMask", "originIndex,destinationIndex,duration,condition")
            .apply { app?.let { (pkg, cert) -> header("X-Android-Package", pkg).header("X-Android-Cert", cert) } }
            .post(AppJson.encodeToString(body).toRequestBody(JSON))
            .build()
        val text = Http.execute(request).use { r ->
            withContext(Dispatchers.IO) {
                if (!r.isSuccessful) throw HttpException(r.code, "HTTP ${r.code} from Google Routes")
                r.body!!.string()
            }
        }
        val out = arrayOfNulls<Long>(to.size)
        AppJson.decodeFromString<List<Element>>(text).forEach { e ->
            if (e.condition == "ROUTE_EXISTS") e.duration?.removeSuffix("s")?.toLongOrNull()?.let { out[e.destinationIndex] = it }
        }
        return out.toList()
    }

    @Serializable
    private data class MatrixRequest(
        val origins: List<Waypoint>,
        val destinations: List<Waypoint>,
        val departureTime: String?,
        // No defaults: those aren't sent.
        val travelMode: String,
        val routingPreference: String,
    )

    @Serializable
    private data class Waypoint(val waypoint: Location) {
        companion object {
            fun of(p: GeoPoint) = Waypoint(Location(LatLngWrap(LatLng(p.lat, p.lon))))
        }
    }

    @Serializable private data class Location(val location: LatLngWrap)
    @Serializable private data class LatLngWrap(val latLng: LatLng)
    @Serializable private data class LatLng(val latitude: Double, val longitude: Double)

    @Serializable
    private data class Element(
        val originIndex: Int = 0,
        val destinationIndex: Int = 0,
        /** "1234s". */
        val duration: String? = null,
        val condition: String? = null,
    )

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
