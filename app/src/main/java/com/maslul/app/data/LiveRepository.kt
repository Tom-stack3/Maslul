package com.maslul.app.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.brotli.dec.BrotliInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Live vehicle positions from the Ministry of Transport SIRI feed.
 *
 * Hasadna's SIRI requester publishes the complete national snapshot once a minute, about
 * 30 s after the minute, as brotli-compressed JSON (~175 KB). This is the freshest keyless
 * source of MOT realtime data (Stride's ETL lags 10–20 minutes).
 */
class LiveRepository(
    private val base: String = "https://open-bus-siri-requester.hasadna.org.il",
    private val clock: () -> Instant = Instant::now,
) {
    class Snapshot(val minute: Instant, val fetchedAt: Instant, val byLine: Map<String, List<Vehicle>>) {
        val vehicleCount get() = byLine.values.sumOf { it.size }
    }

    private val mutex = Mutex()
    @Volatile private var cached: Snapshot? = null
    @Volatile private var lastAttempt: Instant = Instant.EPOCH

    /** Latest snapshot, refreshed at most every ~20 s. Returns the stale one on failure. */
    suspend fun snapshot(): Snapshot? = mutex.withLock {
        val now = clock()
        val c = cached
        // A newer file can only exist once the cached minute is more than ~80 s old.
        val newerDue = c == null || now.isAfter(c.minute.plusSeconds(80))
        if (c != null && (!newerDue || now.isBefore(lastAttempt.plusSeconds(20)))) return c
        lastAttempt = now
        val fresh = runCatching { withContext(Dispatchers.IO) { fetchLatest(now, c?.minute) } }.getOrNull()
        if (fresh != null) cached = fresh
        cached
    }

    suspend fun vehiclesForLine(lineRef: String): List<Vehicle> = snapshot()?.byLine?.get(lineRef).orEmpty()

    private suspend fun fetchLatest(now: Instant, have: Instant?): Snapshot? {
        val current = now.truncatedTo(ChronoUnit.MINUTES)
        for (back in 0L..3L) {
            val minute = current.minus(back, ChronoUnit.MINUTES)
            if (have != null && !minute.isAfter(have)) return null
            val url = base + "/" + pathFmt.format(minute.atOffset(ZoneOffset.UTC)) + ".br"
            val resp = Http.execute(Request.Builder().url(url).build())
            resp.use {
                if (it.isSuccessful) {
                    val vehicles = it.body!!.byteStream().use { s -> parse(s, minute) }
                    return Snapshot(minute, clock(), vehicles.groupBy(Vehicle::lineRef))
                }
            }
        }
        return null
    }

    companion object {
        private val pathFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd/HH/mm")
        /** Vehicles that stopped reporting linger in the feed; drop them. */
        const val MAX_AGE_SEC = 5 * 60L

        fun parse(brotli: InputStream, snapshotTime: Instant): List<Vehicle> =
            parseJson(BrotliInputStream(brotli), snapshotTime)

        /** Streams the snapshot, collecting every MonitoredStopVisit wherever it is nested. */
        fun parseJson(json: InputStream, snapshotTime: Instant): List<Vehicle> {
            val out = ArrayList<Vehicle>(7000)
            JsonReader(InputStreamReader(json, Charsets.UTF_8)).use { r -> walk(r, out, snapshotTime) }
            return out
        }

        private fun walk(r: JsonReader, out: MutableList<Vehicle>, t: Instant) {
            when (r.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        val name = r.nextName()
                        if (name == "MonitoredStopVisit") {
                            if (r.peek() == JsonToken.BEGIN_ARRAY) {
                                r.beginArray()
                                while (r.hasNext()) visit(r, t)?.let(out::add)
                                r.endArray()
                            } else {
                                visit(r, t)?.let(out::add)
                            }
                        } else {
                            walk(r, out, t)
                        }
                    }
                    r.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    while (r.hasNext()) walk(r, out, t)
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }

        private class Fields {
            var recorded: String? = null
            var lineRef: String? = null
            var journeyRef: String? = null
            var origin: String? = null
            var lat: Double? = null
            var lon: Double? = null
            var bearing: Float? = null
            var velocity: Int? = null
            var vehicleRef: String? = null
            var stopRef: String? = null
            var order: Int? = null
            var distance: Double? = null
        }

        private fun visit(r: JsonReader, t: Instant): Vehicle? {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return null }
            val f = Fields()
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "RecordedAtTime" -> f.recorded = str(r)
                    "MonitoredVehicleJourney" -> journey(r, f)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val line = f.lineRef ?: return null
            val lat = f.lat ?: return null
            val lon = f.lon ?: return null
            if (lat == 0.0 || lon == 0.0) return null
            val recorded = time(f.recorded) ?: return null
            if (t.epochSecond - recorded.epochSecond > MAX_AGE_SEC) return null
            val origin = time(f.origin) ?: return null
            // "0" past the first stop is the feed's "no fix" value.
            val distance = f.distance?.takeIf { it > 0 || f.order == 1 }
            return Vehicle(
                lineRef = line,
                journeyRef = f.journeyRef,
                originDeparture = origin,
                lat = lat,
                lon = lon,
                bearing = f.bearing,
                velocityKmh = f.velocity,
                recordedAt = recorded,
                distanceFromStart = distance,
                nextStopCode = f.stopRef,
                vehicleRef = f.vehicleRef,
            )
        }

        private fun journey(r: JsonReader, f: Fields) {
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "LineRef" -> f.lineRef = str(r)
                    "OriginAimedDepartureTime" -> f.origin = str(r)
                    "Bearing" -> f.bearing = str(r)?.toFloatOrNull()
                    "Velocity" -> f.velocity = str(r)?.toDoubleOrNull()?.toInt()
                    "VehicleRef" -> f.vehicleRef = str(r)
                    "FramedVehicleJourneyRef" -> obj(r) { k -> if (k == "DatedVehicleJourneyRef") f.journeyRef = str(r) else r.skipValue() }
                    "VehicleLocation" -> obj(r) { k ->
                        when (k) {
                            "Latitude" -> f.lat = str(r)?.toDoubleOrNull()
                            "Longitude" -> f.lon = str(r)?.toDoubleOrNull()
                            else -> r.skipValue()
                        }
                    }
                    "MonitoredCall" -> obj(r) { k ->
                        when (k) {
                            "StopPointRef" -> f.stopRef = str(r)
                            "Order" -> f.order = str(r)?.toIntOrNull()
                            "DistanceFromStop" -> f.distance = str(r)?.toDoubleOrNull()
                            else -> r.skipValue()
                        }
                    }
                    else -> r.skipValue()
                }
            }
            r.endObject()
        }

        private inline fun obj(r: JsonReader, onKey: (String) -> Unit) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return }
            r.beginObject()
            while (r.hasNext()) onKey(r.nextName())
            r.endObject()
        }

        private fun str(r: JsonReader): String? = when (r.peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
            else -> { r.skipValue(); null }
        }

        private fun time(s: String?): Instant? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
    }
}

/** Finds the vehicle serving a specific trip. */
fun List<Vehicle>.forTrip(tripNumber: String?, originDeparture: Instant?): Vehicle? =
    firstOrNull { tripNumber != null && it.journeyRef == tripNumber }
        ?: originDeparture?.let { o -> firstOrNull { Math.abs(it.originDeparture.epochSecond - o.epochSecond) < 60 } }
