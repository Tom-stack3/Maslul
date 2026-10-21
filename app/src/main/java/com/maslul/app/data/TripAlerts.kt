package com.maslul.app.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** When a saved trip is taken: on [days], leaving at [minuteOfDay] (Israel time, e.g. 8:30 = 510). */
@Serializable
data class TripAlert(
    val minuteOfDay: Int,
    val days: Set<DayOfWeek> = WORKDAYS,
    val enabled: Boolean = true,
) {
    companion object {
        /** Sunday to Thursday, the Israeli working week. */
        val WORKDAYS: Set<DayOfWeek> =
            setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
    }
}

/**
 * Watching a saved trip around the time it's taken: from [WATCH_BEFORE_SEC] before the usual
 * departure, the trip is planned again every [EVERY_SEC] with live times, and three things are
 * worth a notification: it's time to go, it's better to leave earlier (the usual ride is running
 * late or gone, and an earlier one still gets you there on time), and nothing is running.
 */
object TripAlerts {
    const val WATCH_BEFORE_SEC = 30 * 60L
    const val EVERY_SEC = 5 * 60L
    /** Watching goes on this long after the usual departure, for a ride running late. */
    const val WATCH_AFTER_SEC = 10 * 60L
    /** Arriving this much later than usual is worth looking for an earlier way. */
    const val LATE_SEC = 5 * 60L
    /** An earlier way is only worth it if you leave at least this much earlier. */
    const val EARLIER_SEC = 3 * 60L
    /** Nothing leaving within this long after the usual time counts as no service. */
    const val NO_SERVICE_SEC = 45 * 60L

    /** The usual departure on [date], by the clock (on the day the clocks change too). */
    fun departureOn(a: TripAlert, date: LocalDate): Instant =
        date.atTime(LocalTime.of(a.minuteOfDay / 60, a.minuteOfDay % 60)).atZone(IsraelZone).toInstant()

    /** The next departure whose watch isn't over yet, or null when the alert is off. */
    fun nextDeparture(a: TripAlert, now: Instant): Instant? {
        if (!a.enabled || a.days.isEmpty()) return null
        val today = now.atZone(IsraelZone).toLocalDate()
        return (0L..7L).map { today.plusDays(it) }
            .filter { it.dayOfWeek in a.days }
            .map { departureOn(a, it) }
            .firstOrNull { it.plusSeconds(WATCH_AFTER_SEC).isAfter(now) }
    }

    /** When to look again for [departure]: when the watch starts, else in [EVERY_SEC]; null once it's over. */
    fun nextCheck(departure: Instant, now: Instant): Instant? = when {
        now.isBefore(departure.minusSeconds(WATCH_BEFORE_SEC)) -> departure.minusSeconds(WATCH_BEFORE_SEC)
        now.isBefore(departure.plusSeconds(WATCH_AFTER_SEC)) -> now.plusSeconds(EVERY_SEC)
        else -> null
    }

    /** One way there as last checked: when to leave, the first ride and when it boards, when you'd arrive. */
    data class Option(
        val leaveAt: Instant,
        val arriveAt: Instant,
        /** The first ride's line ("18") and kind. */
        val line: String,
        val mode: TransitMode,
        val boardAt: Instant,
        val stop: String,
        /** Its times are live (the vehicle is tracked or predicted), not just the timetable. */
        val live: Boolean,
        /** How late the first ride is running, as far as live data says. */
        val lateMinutes: Long = 0,
    )

    /** What was already sent for one departure, so each alert goes once. */
    @Serializable
    data class Progress(
        val departure: Long,
        /** When the usual way would get you there, as first checked. */
        val usualArrival: Long? = null,
        val sent: Set<String> = emptySet(),
    )

    sealed class Alert {
        data class GoNow(val option: Option) : Alert()
        data class LeaveEarlier(val option: Option, val earlierMin: Long, val usual: Option) : Alert()
        data class NoService(val next: Option?) : Alert()
    }

    /**
     * The alerts due at [now] for the trip's [departure], given the ways there just found
     * ([options], with live times where known) and what was sent before ([p]).
     * [goLeadSec] is how long before leaving the "time to go" alert comes.
     */
    fun decide(p: Progress, departure: Instant, options: List<Option>, now: Instant, goLeadSec: Long): Pair<List<Alert>, Progress> {
        val usable = options.filter { !it.leaveAt.isBefore(now.minusSeconds(60)) }
        // The way you'd usually take: the one leaving around the usual time that arrives first.
        val usual = usable.filter { !it.leaveAt.isBefore(departure.minusSeconds(120)) }.minByOrNull { it.arriveAt }
        if (usual == null || usual.leaveAt.isAfter(departure.plusSeconds(NO_SERVICE_SEC))) {
            if ("none" in p.sent) return emptyList<Alert>() to p
            return listOf(Alert.NoService(usual)) to p.copy(sent = p.sent + "none")
        }
        val usualArrival = p.usualArrival?.let { Instant.ofEpochSecond(it) } ?: usual.arriveAt
        var progress = p.copy(usualArrival = usualArrival.epochSecond)
        val alerts = ArrayList<Alert>()
        // Running late: the latest way that leaves earlier but still gets you there about as usual.
        val earlier = if (usual.arriveAt.isAfter(usualArrival.plusSeconds(LATE_SEC))) {
            usable.filter { it.leaveAt.isBefore(usual.leaveAt.minusSeconds(EARLIER_SEC)) && !it.arriveAt.isAfter(usualArrival.plusSeconds(120)) }
                .maxByOrNull { it.leaveAt }
        } else null
        if (earlier != null && "go" !in progress.sent) {
            val key = "earlier@${earlier.boardAt.epochSecond / 60}"
            if (key !in progress.sent) {
                val min = (departure.epochSecond - earlier.leaveAt.epochSecond + 30) / 60
                alerts += Alert.LeaveEarlier(earlier, min.coerceAtLeast(1), usual)
                progress = progress.copy(sent = progress.sent + key)
            }
        }
        val go = earlier ?: usual
        if ("go" !in progress.sent && !now.isBefore(go.leaveAt.minusSeconds(goLeadSec))) {
            alerts += Alert.GoNow(go)
            progress = progress.copy(sent = progress.sent + "go")
        }
        return alerts to progress
    }
}
