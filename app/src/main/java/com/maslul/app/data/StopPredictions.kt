package com.maslul.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The Ministry of Transport's own predicted arrivals at a stop (its SIRI stop-monitoring
 * service), through curlbus.app, which relays them keyless. The national snapshot
 * [LiveRepository] reads has where each vehicle is, not when it reaches a stop: these are the
 * times the Ministry's model expects, including for trips whose vehicle isn't in the snapshot.
 */
class StopPredictions(
    private val base: String = "https://curlbus.app",
    private val clock: () -> Instant = Instant::now,
) {
    /** When trip [gtfsTripId] (e.g. "22892643_181026") is expected at the stop, as of [recordedAt]. */
    data class Prediction(val gtfsTripId: String, val eta: Instant, val recordedAt: Instant)

    private class Entry(val at: Instant, val result: CompletableDeferred<Map<String, Prediction>>)

    private val cache = HashMap<String, Entry>()

    /** Predictions at the stop with code [stopCode], by GTFS trip id; empty when there are none or the service is down. */
    suspend fun at(stopCode: String): Map<String, Prediction> {
        val now = clock()
        val (entry, mine) = synchronized(cache) {
            val e = cache[stopCode]
            if (e != null && now.isBefore(e.at.plusSeconds(TTL_SEC))) e to false
            else Entry(now, CompletableDeferred()).also { cache[stopCode] = it } to true
        }
        // One request per stop at a time: the rest wait for its answer.
        if (mine) entry.result.complete(runCatching { fetch(stopCode, now) }.getOrDefault(emptyMap()))
        return entry.result.await()
    }

    private suspend fun fetch(stopCode: String, now: Instant): Map<String, Prediction> {
        val url = base.toHttpUrl().newBuilder().addPathSegment(stopCode).build()
        val dto = AppJson.decodeFromString<ResponseDto>(Http.getString(url, mapOf("Accept" to "application/json")))
        return parse(dto.visits[stopCode].orEmpty(), now)
    }

    @Serializable
    class VisitDto(val trip_id: String? = null, val eta: String? = null, val timestamp: String? = null)

    @Serializable
    class ResponseDto(val visits: Map<String, List<VisitDto>> = emptyMap())

    companion object {
        /** How long a stop's answer is reused: the Ministry updates about every 30 s. */
        const val TTL_SEC = 30L
        /** A prediction last updated longer ago than this is a leftover, not a forecast. */
        const val MAX_AGE_SEC = 10 * 60L

        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX")

        fun time(s: String?): Instant? = s?.let { runCatching { OffsetDateTime.parse(it, TIME).toInstant() }.getOrNull() }

        /** The freshest prediction of each trip, leaving out stale ones. */
        fun parse(visits: List<VisitDto>, now: Instant): Map<String, Prediction> =
            visits.mapNotNull { v ->
                val trip = v.trip_id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val eta = time(v.eta) ?: return@mapNotNull null
                val at = time(v.timestamp) ?: return@mapNotNull null
                Prediction(trip, eta, at).takeIf { now.epochSecond - at.epochSecond <= MAX_AGE_SEC }
            }
                .groupBy { it.gtfsTripId }
                .mapValues { (_, ps) -> ps.maxBy { it.recordedAt } }
    }
}
