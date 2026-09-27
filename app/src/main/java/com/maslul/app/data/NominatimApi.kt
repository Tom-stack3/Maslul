package com.maslul.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * OSM Nominatim, only as a last resort for address queries the other geocoders miss.
 * Its usage policy allows at most one request per second, with an identifying User-Agent.
 */
class NominatimApi(private val base: String = "https://nominatim.openstreetmap.org") {
    private val lock = Mutex()
    private var lastCall = 0L

    suspend fun search(text: String): List<Place> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("search")
            .addQueryParameter("q", text)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("countrycodes", "il,ps")
            .addQueryParameter("limit", "5")
            .build()
        val rows = lock.withLock {
            val wait = lastCall + 1100 - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try { Http.getJson<List<Result>>(url) } finally { lastCall = System.currentTimeMillis() }
        }
        return rows.mapNotNull { it.toPlace() }
    }

    @Serializable
    class Address(
        val road: String? = null,
        val house_number: String? = null,
        val city: String? = null,
        val town: String? = null,
        val village: String? = null,
    )

    @Serializable
    class Result(
        val lat: String = "",
        val lon: String = "",
        val name: String? = null,
        val addresstype: String? = null,
        val address: Address = Address(),
    ) {
        fun toPlace(): Place? {
            val a = address
            val city = a.city ?: a.town ?: a.village
            val house = listOfNotNull(a.road, a.house_number).joinToString(" ").takeIf { a.house_number != null && a.road != null }
            val title = house ?: name?.takeIf { it.isNotBlank() } ?: a.road ?: return null
            return Place(
                name = title,
                lat = lat.toDoubleOrNull() ?: return null,
                lon = lon.toDoubleOrNull() ?: return null,
                subtitle = city,
                kind = if (house != null || addresstype == "road") PlaceKind.ADDRESS else PlaceKind.POI,
            )
        }
    }
}
