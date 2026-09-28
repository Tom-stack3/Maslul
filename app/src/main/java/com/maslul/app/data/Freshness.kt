package com.maslul.app.data

import java.time.Instant

/**
 * How much to trust an arrival time, shown Moovit-style:
 * [LIVE] green with an animated signal, [STALE] amber, [SCHEDULED] plain timetable.
 *
 * The MOT SIRI feed publishes one national snapshot a minute, ~30 s after the minute, and
 * vehicles report every ~30–60 s, so a healthy position is usually 30–100 s old by the time
 * it's on screen. Anything past [LIVE_MAX_AGE_SEC] means the vehicle stopped reporting.
 */
enum class Freshness {
    LIVE,
    STALE,
    SCHEDULED;

    companion object {
        /** A position at most this old counts as live. */
        const val LIVE_MAX_AGE_SEC = 150L

        /** Positions older than this are dropped from the feed entirely (see [LiveRepository]). */
        const val MAX_AGE_SEC = LiveRepository.MAX_AGE_SEC

        fun ageSec(recordedAt: Instant, now: Instant): Long = (now.epochSecond - recordedAt.epochSecond).coerceAtLeast(0)

        fun isStale(recordedAt: Instant, now: Instant): Boolean = ageSec(recordedAt, now) > LIVE_MAX_AGE_SEC

        /** Freshness of a live position recorded at [recordedAt]; [SCHEDULED] if there is none. */
        fun of(recordedAt: Instant?, now: Instant): Freshness = when {
            recordedAt == null -> SCHEDULED
            isStale(recordedAt, now) -> STALE
            else -> LIVE
        }
    }
}

/** When the matched vehicle last reported, if this call is tracked live. */
val LiveCall.recordedAt: Instant? get() = vehicle?.takeIf { status == LiveStatus.LIVE }?.recordedAt

fun LiveCall?.freshness(now: Instant): Freshness = Freshness.of(this?.recordedAt, now)

fun TripTimeline.Progress.freshness(now: Instant): Freshness = Freshness.of(vehicle.recordedAt, now)

fun StopArrival.freshness(now: Instant): Freshness = Freshness.of(recordedAt?.takeIf { live }, now)
