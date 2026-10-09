package com.maslul.app.ui.simple

import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.TransitMode
import java.time.Instant

/** One plain instruction of a Simple Maslul trip. */
sealed interface SimpleStep {
    /** A walk to the stop of the next ride. */
    data class WalkToStop(val stop: String, val minutes: Long) : SimpleStep

    /** The walk at the end, to the destination. */
    data class WalkToEnd(val minutes: Long) : SimpleStep

    /**
     * A ride: [line] is blank when the line has no number (most trains); [others] are other lines
     * that ride the same stretch and do just as well.
     */
    data class Ride(
        val leg: Leg,
        val line: String,
        val others: List<String>,
        val departs: Instant,
        val platform: String?,
        val stops: Int,
        val getOff: String,
    ) : SimpleStep

    data class Arrive(val at: Instant) : SimpleStep
}

/** The line's number as riders know it, or blank (a train). */
fun simpleLine(leg: Leg): String = leg.routeShortName?.takeIf { it.isNotBlank() && it != "NaN" }.orEmpty()

/** Walks this short (say, crossing to the platform) aren't worth a step. */
private const val TRIVIAL_WALK_SEC = 60L

/** Minutes for a step, never shown as 0. */
fun simpleMinutes(sec: Long): Long = ((sec + 30) / 60).coerceAtLeast(1)

/** [itin] as plain steps: walk here, take this, get off there, you're there. */
fun simpleSteps(itin: Itinerary): List<SimpleStep> {
    val steps = mutableListOf<SimpleStep>()
    val legs = itin.legs
    legs.forEachIndexed { i, leg ->
        when {
            leg.mode.isTransit -> {
                val line = simpleLine(leg)
                steps += SimpleStep.Ride(
                    leg = leg,
                    line = line,
                    others = leg.options.map { simpleLine(it) }.filter { it.isNotBlank() && it != line }.distinct().take(3),
                    departs = leg.start,
                    platform = leg.from.track,
                    stops = leg.intermediateStops.size + 1,
                    getOff = leg.to.name,
                )
            }
            leg.mode == TransitMode.WALK -> {
                if (leg.durationSec < TRIVIAL_WALK_SEC) return@forEachIndexed
                val next = legs.drop(i + 1).firstOrNull { it.mode.isTransit }
                steps += if (next != null) SimpleStep.WalkToStop(next.from.name, simpleMinutes(leg.durationSec))
                else SimpleStep.WalkToEnd(simpleMinutes(leg.durationSec))
            }
        }
    }
    steps += SimpleStep.Arrive(itin.end)
    return steps
}
