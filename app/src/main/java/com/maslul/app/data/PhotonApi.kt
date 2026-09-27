package com.maslul.app.data

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Photon (komoot) OSM geocoder: good coverage of named places (malls, hospitals, …). */
class PhotonApi(private val base: String = "https://photon.komoot.io") {

    suspend fun search(text: String, near: GeoPoint?): List<Place> {
        val url = base.toHttpUrl().newBuilder()
            .addPathSegments("api/")
            .addQueryParameter("q", text)
            .addQueryParameter("limit", "8")
            // Israel + West Bank bounding box
            .addQueryParameter("bbox", "34.2,29.4,35.95,33.4")
            .apply {
                near?.let {
                    addQueryParameter("lat", it.lat.toString())
                    addQueryParameter("lon", it.lon.toString())
                }
            }
            .build()
        return Http.getJson<FeatureCollection>(url).features.mapNotNull { it.toPlace() }
    }

    @Serializable
    class FeatureCollection(val features: List<Feature> = emptyList())

    @Serializable
    class Geometry(val coordinates: List<Double> = emptyList())

    @Serializable
    class Props(
        val name: String? = null,
        val street: String? = null,
        val housenumber: String? = null,
        val city: String? = null,
        val district: String? = null,
        val osm_key: String? = null,
        val osm_value: String? = null,
    )

    @Serializable
    class Feature(val geometry: Geometry = Geometry(), val properties: Props = Props()) {
        fun toPlace(): Place? {
            val c = geometry.coordinates
            if (c.size < 2) return null
            val p = properties
            val address = listOfNotNull(p.street, p.housenumber).joinToString(" ").ifBlank { null }
            val name = p.name ?: address ?: return null
            // Photon's bus stops lack MOT ids; Transitous returns proper stops, so skip these.
            if (p.osm_value == "bus_stop" || p.osm_value == "platform") return null
            val subtitle = listOfNotNull(if (p.name != null) address else null, p.city).joinToString(", ").ifBlank { null }
            return Place(
                name = name,
                lat = c[1],
                lon = c[0],
                subtitle = subtitle,
                kind = if (p.name == null) PlaceKind.ADDRESS else PlaceKind.POI,
            )
        }
    }
}
