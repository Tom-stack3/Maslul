package com.maslul.app.ui.nav

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maslul.app.data.Combine
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveCall
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.Ranking
import com.maslul.app.data.Reroute
import com.maslul.app.data.TransitousApi
import com.maslul.app.i18n.S
import com.maslul.app.live.ActiveTrip
import com.maslul.app.live.LiveTripService
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.theme.LocalExtra
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.time.Instant

/** One other way from where the user is: as its rides are expected to run, the ride to board next and its live state. */
data class RerouteOption(val itinerary: Itinerary, val expected: Itinerary, val next: Leg?, val nextLive: LiveCall?, val stayOn: Leg?)

/** Other ways to finish [detail]'s trip from where the user is now, looked up again every minute while shown. */
class RerouteState(private val detail: RouteDetailModel) {
    var options by mutableStateOf<List<RerouteOption>?>(null)
        private set
    /** When the trip as it's going gets there, live. */
    var currentArrival by mutableStateOf<Instant?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    suspend fun loop() {
        while (true) {
            refresh()
            delay(REFRESH_MS)
        }
    }

    /** The trip being ridden now, when the live directions say the user is on a bus or train. */
    private fun riding(now: Instant): String? {
        val s = ActiveTrip.session.value?.takeIf { it.itinerary.id == detail.itinerary.id } ?: return detail.riding
        val leg = s.state?.legIndex?.let { s.itinerary.legs.getOrNull(it) } ?: return null
        return leg.tripId?.takeIf { leg.mode.isTransit && now.isBefore(leg.end.plusSeconds(15 * 60)) }
    }

    suspend fun refresh() {
        val now = Instant.now()
        val s = detail.store.data.value.settings
        val current = detail.itinerary
        val here = runCatching { detail.location.current() }.getOrNull() ?: detail.location.last.value
        val tripId = riding(now)
        val score = { i: Itinerary -> Ranking.score(i, false, s.walkSpeed.mps, s.railPreference) }
        val base = TransitousApi.PlanRequest(
            from = here ?: current.legs.first().from.point, to = current.legs.last().to.point,
            modes = s.modes, walkSpeedMps = s.walkSpeed.mps, maxWalkMinutes = s.maxWalkMinutes, wheelchair = s.wheelchair,
        )
        val found = runCatching {
            // On a bus: staying on, or getting off later to change, as the on-board planner sees it.
            val start = tripId?.let { detail.repo.onBoardStart(it, here, now) }
            if (start != null) {
                val req = base.copy(from = start.next.point, fromStopId = start.next.stopId,
                    time = (start.next.scheduledTime ?: now).minusSeconds(60))
                detail.repo.planOnBoard(req, start, score)
            } else {
                detail.repo.plan(base).itineraries
            }
        }.getOrElse {
            failed = options == null
            return
        }
        failed = false
        val candidates = Ranking.rank(Combine.merge(found.sortedBy(score)), false, s.walkSpeed.mps, s.railPreference).take(CANDIDATES)
        // Live times for the rides about to happen, the current way's too.
        val legs = (candidates + current).flatMap { it.transitLegs }
            .filter { it.end.isAfter(now) && it.start.isBefore(now.plusSeconds(3600)) }
            .distinctBy { it.rideKey }
        val live = coroutineScope {
            legs.map { l -> async { runCatching { detail.repo.legLive(l, now) }.getOrNull()?.let { l.rideKey to it } } }.awaitAll()
        }.filterNotNull().toMap()
        val liveOf = { l: Leg -> live[l.rideKey] }
        currentArrival = Combine.expected(current, tripId, liveOf).end
        options = Reroute.choose(current, candidates, now, tripId, liveOf, score).map { itin ->
            val transit = itin.transitLegs
            val stayOn = transit.firstOrNull()?.takeIf { tripId != null && it.tripId == tripId }
            val next = transit.firstOrNull { it.tripId == null || it.tripId != tripId }
            RerouteOption(itin, Combine.expected(itin, tripId, liveOf), next, next?.let(liveOf), stayOn)
        }
    }

    /** Rides [option] from now on: the live directions follow it, and the screen shows it. */
    fun take(ctx: Context, option: RerouteOption) {
        val itin = option.itinerary
        if (ActiveTrip.session.value != null) LiveTripService.start(ctx, itin, detail.to.name)
        val start = itin.legs.first().from.let { Place(it.name.ifBlank { S.start }, it.lat, it.lon, kind = PlaceKind.PIN) }
        detail.nav.replace(RouteDetailModel(detail.nav, itin, start, detail.to))
    }

    private companion object {
        const val REFRESH_MS = 60_000L
        const val CANDIDATES = 8
    }
}

/** The other ways from here, at most two, with the current way's arrival to compare. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RerouteSheet(state: RerouteState, now: Instant, onClose: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(state) { state.loop() }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .navigationBarsPadding().padding(bottom = 16.dp).testTag("reroute_sheet"),
        ) {
            Text(S.otherWaysFromHere, style = MaterialTheme.typography.titleLarge)
            Text(S.updatedEveryMinute, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle)
            state.currentArrival?.let {
                Spacer(Modifier.height(8.dp))
                Text(S.yourWayArrives(Fmt.time(it)), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            val options = state.options
            when {
                options == null && !state.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(S.lookingForWays, style = MaterialTheme.typography.bodyMedium)
                }
                options == null -> Text(S.errLoadRoutes, style = MaterialTheme.typography.bodyMedium)
                options.isEmpty() -> Text(S.noBetterWay, style = MaterialTheme.typography.bodyMedium)
                else -> {
                    // Every other way gets there later: say so, so nobody switches for nothing.
                    val current = state.currentArrival
                    if (current != null && options.all { it.expected.end.isAfter(current.plusSeconds(60)) }) {
                        Text(S.noBetterWay, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.live)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            if (!options.isNullOrEmpty()) {
                options.forEach { o ->
                    OptionCard(o, state.currentArrival, now) { state.take(ctx, o); onClose() }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionCard(o: RerouteOption, currentArrival: Instant?, now: Instant, onTake: () -> Unit) {
    val x = LocalExtra.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(S.arrivesAt(Fmt.time(o.expected.end)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                // How it compares with the way as it's going.
                val gain = currentArrival?.let { (it.epochSecond - o.expected.end.epochSecond) / 60 }
                if (gain != null && gain != 0L) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (gain > 0) "−${S.min(gain)}" else "+${S.min(-gain)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (gain > 0) x.live else x.late,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                o.itinerary.transitLegs.forEachIndexed { i, l ->
                    if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp).align(Alignment.CenterVertically), tint = x.subtle)
                    LineBadge(l.lineLabel, l.mode, color = lineColor(l))
                }
            }
            o.stayOn?.let {
                Spacer(Modifier.height(8.dp))
                Text(S.stayOnUntil(it.to.name), style = MaterialTheme.typography.bodyMedium)
            }
            o.next?.let { next ->
                Spacer(Modifier.height(8.dp))
                LiveLine(next.lineLabel, next.mode, next.from.name, o.nextLive, next.start, now)
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onTake, modifier = Modifier.fillMaxWidth().testTag("reroute_take")) { Text(S.takeThisWay) }
        }
    }
}
