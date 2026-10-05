package com.maslul.app.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/**
 * A shuttle with a fixed timetable that the open data doesn't know about, e.g. a company bus
 * from the office to a train station. Trip planning offers it like any other ride.
 */
@Serializable
data class Shuttle(
    val id: String,
    val name: String,
    val from: Place,
    val to: Place,
    /** Departure times from [from], in minutes after midnight, ascending. */
    val times: List<Int>,
    val rideMinutes: Int = 20,
    /** Days it runs, as [DayOfWeek] values (1 = Monday … 7 = Sunday). */
    val days: Set<Int> = WORK_WEEK,
) {
    companion object {
        /** Sunday to Thursday. */
        val WORK_WEEK = setOf(7, 1, 2, 3, 4)
    }
}

object ShuttleSchedule {
    /** Colour of shuttle rides, so they stand apart from the operators' lines. */
    const val COLOR = 0xFF7C3AED.toInt()
    const val AGENCY = "My shuttle"
    /** Walking this far or less to or from a shuttle stop needs no planning. */
    private const val SAME_PLACE_M = 80.0

    /** "7:30, 08:15 17:00" → [450, 495, 1020]; null if any part isn't a time. */
    fun parseTimes(text: String): List<Int>? {
        val parts = text.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = parts.map { p ->
            val m = Regex("""^(\d{1,2})[:.](\d{2})$""").matchEntire(p) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return null
            h * 60 + min
        }
        return out.distinct().sorted()
    }

    /** Nothing typed but spaces and separators. */
    fun isEmptyTimes(text: String) = text.all { it.isWhitespace() || it in ",;" }

    /** What a shuttle still needs before it can be saved, as short phrases in form order; empty when it's complete. */
    fun missing(name: String, from: Place?, to: Place?, times: String, rideMinutes: Int?, days: Set<Int>): List<String> = buildList {
        if (name.isBlank()) add("a name")
        if (from == null) add("where it leaves from")
        if (to == null) add("where it goes")
        if (from != null && to != null && isNear(from.point, to.point)) add("two different stops")
        if (parseTimes(times) == null) add(if (isEmptyTimes(times)) "departure times" else "departure times like 07:30")
        if (rideMinutes == null) add("how long the ride takes")
        if (days.isEmpty()) add("a day it runs")
    }

    /** "Still needed: a name and how long the ride takes." */
    fun missingText(missing: List<String>): String? = when (missing.size) {
        0 -> null
        1 -> "Still needed: ${missing[0]}."
        else -> "Still needed: ${missing.dropLast(1).joinToString(", ")} and ${missing.last()}."
    }

    fun formatTime(minutes: Int) ="%02d:%02d".format(minutes / 60, minutes % 60)

    fun formatTimes(times: List<Int>) = times.joinToString(", ", transform = ::formatTime)

    /** "Sun–Thu", "Every day", "Fri, Sat". */
    fun formatDays(days: Set<Int>): String {
        if (days.size == 7) return "Every day"
        if (days == Shuttle.WORK_WEEK) return "Sun–Thu"
        // Week order as in Israel: Sunday first.
        return listOf(7, 1, 2, 3, 4, 5, 6).filter { it in days }
            .joinToString(", ") { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
    }

    /** Departures of [s] from [from] (inclusive) up to [until], soonest first. */
    fun departures(s: Shuttle, from: Instant, until: Instant): List<Instant> {
        if (s.times.isEmpty() || s.days.isEmpty()) return emptyList()
        val out = ArrayList<Instant>()
        var day = from.atZone(IsraelZone).toLocalDate()
        val last = until.atZone(IsraelZone).toLocalDate()
        while (!day.isAfter(last)) {
            if (day.dayOfWeek.value in s.days) {
                s.times.map { at(day, it) }.filter { !it.isBefore(from) && !it.isAfter(until) }.forEach(out::add)
            }
            day = day.plusDays(1)
        }
        return out
    }

    private fun at(day: LocalDate, minutes: Int): Instant = day.atStartOfDay(IsraelZone).plusMinutes(minutes.toLong()).toInstant()

    /** A ride on [s] leaving at [departure], drawn along [geometry] when known. */
    fun leg(s: Shuttle, departure: Instant, geometry: List<GeoPoint> = emptyList()): Leg {
        val end = departure.plusSeconds(s.rideMinutes * 60L)
        return Leg(
            mode = TransitMode.BUS,
            from = StopCall(null, s.from.name, null, s.from.lat, s.from.lon, null, departure),
            to = StopCall(null, s.to.name, null, s.to.lat, s.to.lon, end, null),
            start = departure,
            end = end,
            distanceM = null,
            headsign = s.to.name,
            routeShortName = s.name,
            routeLongName = null,
            routeColor = COLOR,
            routeTextColor = null,
            agencyName = AGENCY,
            tripId = null,
            routeId = null,
            intermediateStops = emptyList(),
            geometry = geometry.ifEmpty { listOf(s.from.point, s.to.point) },
            shuttleId = s.id,
        )
    }

    /** The next [count] rides of [s] after [leg] (one of its rides): the wait if it's missed. */
    fun ridesAfter(s: Shuttle, leg: Leg, count: Int = 2): List<Leg> =
        departures(s, leg.start.plusSeconds(60), leg.start.plusSeconds(18 * 3600L)).take(count).map { leg(s, it, leg.geometry) }

    /** [ridesAfter] for a shuttle ride, looking its shuttle up in [shuttles]; null if it's gone. */
    fun ridesAfter(shuttles: List<Shuttle>, leg: Leg, count: Int = 2): List<Leg>? =
        shuttles.firstOrNull { it.id == leg.shuttleId }?.let { ridesAfter(it, leg, count) }

    fun isNear(a: GeoPoint, b: GeoPoint) = GeoMath.distance(a, b) <= SAME_PLACE_M

    /**
     * Shuttles worth trying between [origin] and [dest], most promising first: they leave no
     * farther away than the destination is, and don't head off the wrong way. A shuttle that
     * barely gets you closer can still be the way to the train, so planning decides the rest.
     */
    fun relevant(shuttles: List<Shuttle>, origin: GeoPoint, dest: GeoPoint, max: Int = 3): List<Shuttle> {
        val direct = GeoMath.distance(origin, dest)
        if (direct < 300) return emptyList()
        return shuttles
            .filter { it.times.isNotEmpty() && it.days.isNotEmpty() }
            .map { s -> s to (GeoMath.distance(origin, s.from.point) to GeoMath.distance(s.to.point, dest)) }
            .filter { (s, d) ->
                val (access, egress) = d
                // A shuttle to (or from) the train can sit a little "behind" the trip, either end.
                access < direct * 1.25 + 2000 && egress < direct * 1.25 + 2000 && access + egress < direct * 1.6 + 3000 &&
                    // Not a loop: it mustn't take you back to where you started, or leave from by where you're going.
                    GeoMath.distance(origin, s.to.point) >= GeoMath.distance(origin, s.from.point) / 2 &&
                    GeoMath.distance(s.from.point, dest) >= egress / 2
            }
            .sortedBy { (_, d) -> d.first + d.second }
            .take(max)
            .map { it.first }
    }
}
