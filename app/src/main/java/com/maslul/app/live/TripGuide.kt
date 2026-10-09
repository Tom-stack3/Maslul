package com.maslul.app.live

import com.maslul.app.data.GeoMath
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Itinerary
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.TransitMode
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.modeName
import java.time.Instant
import com.maslul.app.i18n.S

enum class GuideAlert { BOARD_SOON, GET_OFF_NEXT, GET_OFF_NOW, ARRIVED }

data class GuideState(
    val legIndex: Int,
    val title: String,
    val text: String,
    val alert: GuideAlert?,
    /** Alerts fire once per (leg, kind); this is the dedupe key. */
    val alertKey: String?,
    val progress: Float,
    val finished: Boolean,
)

/**
 * Follows the user along an itinerary. Positions are projected onto the concatenated leg
 * geometry, which tells us the current leg and — on a transit leg — how many stops remain.
 */
class TripGuide(private val itinerary: Itinerary, private val destination: String) {
    private val path = ArrayList<GeoPoint>()
    private val legStart = DoubleArray(itinerary.legs.size + 1)
    private val cum: DoubleArray
    /** Distance along the path of each stop on each transit leg (index 0 = boarding stop). */
    private val stopAlong: Map<Int, DoubleArray>
    private var lastAlong = 0.0

    init {
        itinerary.legs.forEachIndexed { i, leg ->
            val pts = leg.geometry.ifEmpty { listOf(leg.from.point, leg.to.point) }
            val startLen = if (path.isEmpty()) 0.0 else GeoMath.cumulative(path).last()
            legStart[i] = startLen
            if (path.isNotEmpty() && pts.isNotEmpty() && path.last() == pts.first()) path.addAll(pts.drop(1)) else path.addAll(pts)
        }
        cum = GeoMath.cumulative(path)
        legStart[itinerary.legs.size] = cum.lastOrNull() ?: 0.0
        stopAlong = itinerary.legs.withIndex().filter { it.value.mode.isTransit }.associate { (i, leg) ->
            val stops = listOf(leg.from) + leg.intermediateStops + leg.to
            var prev = legStart[i]
            i to DoubleArray(stops.size) { k ->
                val p = GeoMath.project(path, cum, stops[k].point, hintAlongM = prev, snapM = 80.0)
                val a = (p?.alongM ?: prev).coerceIn(prev, legStart[i + 1].coerceAtLeast(prev))
                prev = a
                a
            }
        }
    }

    val totalM get() = legStart.last()

    fun update(pos: GeoPoint?, now: Instant, live: Map<Int, LiveCall>): GuideState {
        val along = pos?.let { p ->
            val proj = GeoMath.project(path, cum, p, hintAlongM = lastAlong, snapM = 120.0)
            // Ignore big backwards jumps (GPS noise) and positions far from the route.
            if (proj != null && proj.offsetM < 400 && proj.alongM > lastAlong - 150) proj.alongM else null
        }
        if (along != null) lastAlong = maxOf(lastAlong, along)
        val legIndex = if (pos != null) legAt(lastAlong) else legByTime(now)
        val leg = itinerary.legs[legIndex]
        val progress = if (totalM > 0) (lastAlong / totalM).toFloat().coerceIn(0f, 1f) else 0f

        val dest = itinerary.legs.last().to.point
        val arrived = pos != null && (GeoMath.distance(pos, dest) < 60 || lastAlong >= totalM - 40)
        if (arrived) {
            return GuideState(legIndex, S.youveArrived, destination, GuideAlert.ARRIVED, "arrived", 1f, true)
        }

        if (!leg.mode.isTransit) {
            val nextIdx = (legIndex + 1 until itinerary.legs.size).firstOrNull { itinerary.legs[it].mode.isTransit }
            if (nextIdx == null) {
                val remaining = (totalM - lastAlong).coerceAtLeast(0.0)
                val title = if (leg.mode == TransitMode.CAR) S.rideTo(destination) else S.walkTo(destination)
                return GuideState(legIndex, title, S.toGo(Fmt.distance(remaining)), null, null, progress, false)
            }
            val next = itinerary.legs[nextIdx]
            val call = live[nextIdx]
            val eta = if (call?.status == LiveStatus.LIVE) call.expected else next.start
            val mins = eta?.let { (it.epochSecond - now.epochSecond + 30) / 60 }
            val liveTag = if (call?.status == LiveStatus.LIVE) " (${S.liveLower})" else ""
            val text = "${modeName(next.mode)} ${next.lineLabel} " +
                (mins?.let { if (it <= 0) S.isArriving + liveTag else S.inTime(S.min(it)) + liveTag } ?: S.atTime(Fmt.time(next.start)))
            val soon = mins != null && mins in 0..3
            return GuideState(
                legIndex, if (leg.mode == TransitMode.CAR) S.droppedOff(next.from.name) else S.walkTo(next.from.name), text,
                if (soon) GuideAlert.BOARD_SOON else null, if (soon) "board-$nextIdx" else null, progress, false,
            )
        }

        // On board: count stops still ahead of us.
        val stops = stopAlong[legIndex]
        val all = listOf(leg.from) + leg.intermediateStops + leg.to
        val remaining = if (stops != null && pos != null) stops.drop(1).count { it > lastAlong + 40 } else {
            // Without location, estimate by schedule.
            all.drop(1).count { (it.scheduledTime ?: leg.end).isAfter(now) }
        }
        val arriveAt = live[legIndex]?.alightExpected ?: leg.end
        val title = "${modeName(leg.mode)} ${leg.lineLabel} ${S.arrow} ${leg.to.name}"
        return when {
            remaining <= 0 -> GuideState(legIndex, S.getOffNow, leg.to.name, GuideAlert.GET_OFF_NOW, "off-$legIndex", progress, false)
            remaining == 1 -> GuideState(
                legIndex, S.getOffNextStop, leg.to.name, GuideAlert.GET_OFF_NEXT, "next-$legIndex", progress, false,
            )
            else -> GuideState(
                legIndex, title, S.stopsGetOff(remaining, Fmt.time(arriveAt)), null, null, progress, false,
            )
        }
    }

    private fun legAt(along: Double): Int {
        for (i in itinerary.legs.indices.reversed()) if (along >= legStart[i] - 1) return i
        return 0
    }

    private fun legByTime(now: Instant): Int =
        itinerary.legs.indexOfFirst { now.isBefore(it.end) }.takeIf { it >= 0 } ?: itinerary.legs.lastIndex
}
