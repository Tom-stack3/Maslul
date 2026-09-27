package com.maslul.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Polyline {
    /** Decodes a Google encoded polyline with the given precision (MOTIS uses 6 or 7). */
    fun decode(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out.add(GeoPoint(lat / factor, lon / factor))
        }
        return out
    }
}

object GeoMath {
    private const val R = 6371000.0

    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Cumulative distance along the polyline at each vertex. */
    fun cumulative(line: List<GeoPoint>): DoubleArray {
        val out = DoubleArray(line.size)
        for (i in 1 until line.size) out[i] = out[i - 1] + distance(line[i - 1], line[i])
        return out
    }

    data class Projection(val alongM: Double, val offsetM: Double)

    /**
     * Projects [p] onto the polyline. Uses a local equirectangular approximation, which is
     * accurate to well under a metre at city scale. When [hintAlongM] is given, among
     * candidates within [snapM] of the line it picks the one closest to the hint (loops).
     */
    fun project(
        line: List<GeoPoint>,
        cum: DoubleArray,
        p: GeoPoint,
        hintAlongM: Double? = null,
        fromIndex: Int = 0,
        snapM: Double = 80.0,
    ): Projection? {
        if (line.size < 2) return null
        val kx = 111320.0 * cos(Math.toRadians(p.lat))
        val ky = 110540.0
        var nearest: Projection? = null
        var nearHint: Projection? = null
        for (i in fromIndex.coerceAtLeast(0) until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            val ax = (a.lon - p.lon) * kx
            val ay = (a.lat - p.lat) * ky
            val bx = (b.lon - p.lon) * kx
            val by = (b.lat - p.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val off = sqrt(cx * cx + cy * cy)
            val along = cum[i] + t * (cum[i + 1] - cum[i])
            if (nearest == null || off < nearest.offsetM) nearest = Projection(along, off)
            if (hintAlongM != null && off <= snapM &&
                (nearHint == null || Math.abs(along - hintAlongM) < Math.abs(nearHint.alongM - hintAlongM))
            ) nearHint = Projection(along, off)
        }
        return nearHint ?: nearest
    }

    fun bearing(a: GeoPoint, b: GeoPoint): Float {
        val φ1 = Math.toRadians(a.lat)
        val φ2 = Math.toRadians(b.lat)
        val dλ = Math.toRadians(b.lon - a.lon)
        val y = sin(dλ) * cos(φ2)
        val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(dλ)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }
}

/**
 * Transitous trip ids for the Israeli feed look like
 * `20260927_21:20_il-Israel-MOT_585026147_270926` — service date, the trip's first departure
 * (local time, may exceed 24:00), and the MOT trip_id.
 */
object TripIds {
    private const val PREFIX = "il-Israel-MOT_"
    private val tripRegex = Regex("""^(\d{8})_(\d{2,3}):(\d{2})_il-Israel-MOT_(.+)$""")
    private val dateFmt = DateTimeFormatter.BASIC_ISO_DATE

    data class Parsed(val serviceDate: LocalDate, val originDeparture: Instant, val gtfsTripId: String) {
        val tripNumber get() = gtfsTripId.substringBefore('_')
    }

    fun parse(tripId: String?): Parsed? {
        val m = tripRegex.matchEntire(tripId ?: return null) ?: return null
        val date = LocalDate.parse(m.groupValues[1], dateFmt)
        val minutes = m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()
        val origin = date.atStartOfDay(IsraelZone).plusMinutes(minutes).toInstant()
        return Parsed(date, origin, m.groupValues[4])
    }

    fun build(serviceDate: LocalDate, originDeparture: Instant, journeyRef: String): String {
        val midnight = serviceDate.atStartOfDay(IsraelZone).toInstant()
        val minutes = (originDeparture.epochSecond - midnight.epochSecond) / 60
        val hh = (minutes / 60).toString().padStart(2, '0')
        val mm = (minutes % 60).toString().padStart(2, '0')
        return "${serviceDate.format(dateFmt)}_$hh:${mm}_$PREFIX$journeyRef"
    }

    /**
     * Trip id for a Stride ride. Journey refs end with the service date ("_260926" = 26/09/26),
     * which differs from the calendar date for trips after midnight.
     */
    fun fromJourney(journeyRef: String, departure: Instant, fallbackDate: LocalDate): String {
        val suffix = journeyRef.substringAfterLast('_', "")
        val date = if (suffix.length == 6) {
            runCatching { LocalDate.parse(suffix, DateTimeFormatter.ofPattern("ddMMyy")) }.getOrNull()
        } else null
        return build(date ?: fallbackDate, departure, journeyRef)
    }

    fun lineRef(routeId: String?): String? =
        routeId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun stopId(motStopId: String) = PREFIX + motStopId

    fun localTime(i: Instant): LocalTime = i.atZone(IsraelZone).toLocalTime()
}
