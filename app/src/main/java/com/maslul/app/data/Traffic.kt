package com.maslul.app.data

import java.time.Instant

/**
 * How much longer driving takes than on empty roads, by weekday and hour. Transitous times car
 * legs at OpenStreetMap road speeds, as if the roads were empty; this stretches them for the usual
 * traffic of their time. It knows rush hours, not today's jams.
 *
 * Measured from Open Bus (Stride) actual bus ride times, 5–28 October 2026, on 45 intercity lines
 * (mostly highway) and 45 city lines: each ride's duration over its line's uncongested one (its
 * 10th percentile), the median per weekday and hour. tools/traffic measures it again.
 */
object Traffic {
    /**
     * A drive at its time: longer in traffic. Leaving at its start, or with [keepEnd] getting there
     * at its end (an arrival time, or the bus a lift is driving to).
     */
    fun drive(car: Leg, keepEnd: Boolean = false): Leg {
        val sec = drivingSec(car.durationSec, car.distanceM, if (keepEnd) car.end else car.start, backwards = keepEnd)
        return if (keepEnd) {
            val start = car.end.minusSeconds(sec)
            car.copy(start = start, from = car.from.copy(scheduledDeparture = car.from.scheduledDeparture?.let { start }))
        } else {
            val end = car.start.plusSeconds(sec)
            car.copy(end = end, to = car.to.copy(scheduledArrival = car.to.scheduledArrival?.let { end }))
        }
    }

    /**
     * [result] of planning [req] with its car legs in traffic: a lift to a bus leaves that much
     * earlier, and is dropped when that's before [req]'s time ([now] when leaving now); driving all
     * the way gets there later (or, arriving by a time, leaves earlier).
     */
    fun plan(result: PlanResult, req: TransitousApi.PlanRequest, now: Instant = Instant.now()): PlanResult {
        val leave = (req.time ?: now).minusSeconds(60)
        val lifts = result.itineraries.mapNotNull { itin ->
            val i = itin.legs.indexOfFirst { it.mode == TransitMode.CAR }
            if (i < 0) return@mapNotNull itin
            val car = drive(itin.legs[i], keepEnd = true)
            // Anything before the drive (a walk to the car) moves back with it.
            val back = car.start.epochSecond - itin.legs[i].start.epochSecond
            val legs = itin.legs.mapIndexed { j, l -> if (j < i) l.shifted(back) else if (j == i) car else l }
            val start = legs.first().start
            if (!req.arriveBy && start.isBefore(leave)) null else itin.copy(start = start, legs = legs)
        }
        val car = result.carOnly?.let { itin ->
            val i = itin.legs.indexOfFirst { it.mode == TransitMode.CAR }
            val drive = drive(itin.legs[i], keepEnd = req.arriveBy)
            // The legs after the drive (arriving by a time: before it) keep their length and move along.
            val by = if (req.arriveBy) drive.start.epochSecond - itin.legs[i].start.epochSecond
                else drive.end.epochSecond - itin.legs[i].end.epochSecond
            val legs = itin.legs.mapIndexed { j, l -> if (j == i) drive else if (req.arriveBy == (j < i)) l.shifted(by) else l }
            itin.copy(start = legs.first().start, end = legs.last().end, legs = legs)
        }
        return result.copy(itineraries = lifts, carOnly = car)
    }

    /** Empty-road driving minutes that take about [minutes] at [at]: how far a driver offering [minutes] can take you. */
    fun freeFlowMinutes(minutes: Int, at: Instant): Int =
        maxOf(1, Math.round(minutes / factor(at, TYPICAL_LIFT_MPS)).toInt())

    /** How much longer than on empty roads a drive that averages [freeFlowMps] there takes, leaving at [at]. */
    fun factor(at: Instant, freeFlowMps: Double): Double {
        val local = at.atZone(IsraelZone)
        // Sunday = 0 ... Saturday = 6; hours measured at their middle, so in between they blend.
        val pos = (local.dayOfWeek.value % 7) * 24 + local.hour + local.minute / 60.0 - 0.5
        val i = Math.floorMod(Math.floor(pos).toInt(), WEEK)
        val f = pos - Math.floor(pos)
        fun at(table: Array<DoubleArray>, k: Int) = table[k / 24][k % 24]
        fun blend(table: Array<DoubleArray>) = at(table, i) * (1 - f) + at(table, (i + 1) % WEEK) * f
        // Fast roads are mostly highway, slow ones streets in town.
        val highway = ((freeFlowMps * 3.6 - CITY_KMH) / (HIGHWAY_KMH - CITY_KMH)).coerceIn(0.0, 1.0)
        return blend(INTERCITY) * highway + blend(CITY) * (1 - highway)
    }

    /** Driving [freeSec] (over [distanceM]) starting, or with [backwards] ending, at [at]: timed by the traffic when it leaves. */
    private fun drivingSec(freeSec: Long, distanceM: Double?, at: Instant, backwards: Boolean): Long {
        if (freeSec <= 0) return freeSec
        val mps = (distanceM ?: 0.0) / freeSec
        // Getting there by a time: leaving about this much earlier.
        val leave = if (backwards) at.minusSeconds(Math.round(freeSec * factor(at.minusSeconds(freeSec), mps))) else at
        return Math.round(freeSec * factor(leave, mps))
    }

    private fun Leg.shifted(sec: Long) = copy(start = start.plusSeconds(sec), end = end.plusSeconds(sec))

    private const val WEEK = 7 * 24
    private const val CITY_KMH = 35.0
    private const val HIGHWAY_KMH = 70.0
    /** A lift is a bit of both; for sizing how far it reaches before its route is known. */
    private const val TYPICAL_LIFT_MPS = 50 / 3.6

    // Per weekday (Sunday first) and hour of the day, by when the ride leaves. Where buses don't run
    // (late at night, Shabbat) or run only in a few towns (Shabbat daytime), filled in: see tools/traffic.

    /** Intercity lines. */
    private val INTERCITY = arrayOf(
        doubleArrayOf(1.00, 1.00, 1.00, 1.00, 1.00, 1.08, 1.22, 1.42, 1.34, 1.23, 1.19, 1.20, 1.21, 1.25, 1.33, 1.41, 1.41, 1.38, 1.30, 1.21, 1.11, 1.10, 1.10, 1.05),  // Sun
        doubleArrayOf(1.05, 1.00, 1.00, 1.00, 1.00, 1.05, 1.21, 1.40, 1.38, 1.25, 1.22, 1.23, 1.24, 1.24, 1.32, 1.44, 1.39, 1.35, 1.32, 1.23, 1.14, 1.10, 1.08, 1.04),  // Mon
        doubleArrayOf(1.10, 1.00, 1.00, 1.00, 1.00, 1.05, 1.17, 1.33, 1.28, 1.24, 1.19, 1.21, 1.23, 1.26, 1.31, 1.41, 1.39, 1.38, 1.31, 1.22, 1.12, 1.10, 1.10, 1.05),  // Tue
        doubleArrayOf(1.08, 1.00, 1.00, 1.00, 1.00, 1.06, 1.22, 1.45, 1.38, 1.30, 1.23, 1.23, 1.26, 1.31, 1.32, 1.47, 1.42, 1.41, 1.36, 1.27, 1.15, 1.11, 1.10, 1.07),  // Wed
        doubleArrayOf(1.11, 1.00, 1.00, 1.00, 1.00, 1.06, 1.19, 1.35, 1.24, 1.22, 1.18, 1.22, 1.26, 1.27, 1.36, 1.49, 1.45, 1.38, 1.32, 1.31, 1.21, 1.18, 1.17, 1.10),  // Thu
        doubleArrayOf(1.09, 1.00, 1.00, 1.00, 1.00, 1.02, 1.07, 1.17, 1.17, 1.21, 1.25, 1.37, 1.33, 1.23, 1.17, 1.14, 1.10, 1.06, 1.03, 1.00, 1.00, 1.00, 1.00, 1.00),  // Fri
        doubleArrayOf(1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.05, 1.12, 1.12, 1.12, 1.12, 1.12, 1.12, 1.12, 1.18, 1.25, 1.19, 1.21, 1.11, 1.08, 1.06),  // Sat
    )

    /** City lines. */
    private val CITY = arrayOf(
        doubleArrayOf(1.04, 1.00, 1.00, 1.00, 1.00, 1.07, 1.19, 1.53, 1.33, 1.27, 1.23, 1.24, 1.24, 1.35, 1.37, 1.47, 1.40, 1.39, 1.31, 1.30, 1.21, 1.17, 1.10, 1.06),  // Sun
        doubleArrayOf(1.00, 1.00, 1.00, 1.00, 1.00, 1.07, 1.16, 1.51, 1.32, 1.26, 1.23, 1.27, 1.29, 1.36, 1.36, 1.42, 1.43, 1.35, 1.35, 1.30, 1.19, 1.17, 1.09, 1.07),  // Mon
        doubleArrayOf(1.00, 1.00, 1.00, 1.00, 1.00, 1.07, 1.14, 1.37, 1.29, 1.26, 1.26, 1.28, 1.29, 1.34, 1.33, 1.43, 1.36, 1.34, 1.35, 1.27, 1.19, 1.15, 1.10, 1.09),  // Tue
        doubleArrayOf(1.00, 1.00, 1.00, 1.00, 1.00, 1.06, 1.17, 1.54, 1.39, 1.29, 1.31, 1.32, 1.33, 1.42, 1.38, 1.50, 1.43, 1.38, 1.38, 1.32, 1.23, 1.17, 1.10, 1.10),  // Wed
        doubleArrayOf(1.03, 1.00, 1.00, 1.00, 1.00, 1.07, 1.17, 1.46, 1.27, 1.30, 1.25, 1.28, 1.32, 1.38, 1.39, 1.46, 1.40, 1.36, 1.40, 1.36, 1.26, 1.19, 1.14, 1.15),  // Thu
        doubleArrayOf(1.06, 1.00, 1.00, 1.00, 1.00, 1.04, 1.10, 1.27, 1.26, 1.32, 1.33, 1.54, 1.41, 1.28, 1.21, 1.18, 1.17, 1.06, 1.03, 1.00, 1.00, 1.00, 1.00, 1.00),  // Fri
        doubleArrayOf(1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.05, 1.12, 1.12, 1.12, 1.12, 1.12, 1.12, 1.12, 1.18, 1.22, 1.23, 1.19, 1.16, 1.10, 1.08),  // Sat
    )
}
