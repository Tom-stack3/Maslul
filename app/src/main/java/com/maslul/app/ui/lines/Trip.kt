package com.maslul.app.ui.lines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maslul.app.data.Freshness
import com.maslul.app.data.Leg
import com.maslul.app.data.TripTimeline
import com.maslul.app.data.freshness
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.LiveLocationCard
import com.maslul.app.ui.components.LiveSignal
import com.maslul.app.ui.components.LoadingBox
import com.maslul.app.ui.components.MapLine
import com.maslul.app.ui.components.MapMarker
import com.maslul.app.ui.components.MapVehicle
import com.maslul.app.ui.components.MarkerKind
import com.maslul.app.ui.components.MessageBox
import com.maslul.app.ui.components.Node
import com.maslul.app.ui.components.Pill
import com.maslul.app.ui.components.Rail
import com.maslul.app.ui.components.RailSpec
import com.maslul.app.ui.components.TimelineRow
import com.maslul.app.ui.components.TransitMap
import com.maslul.app.ui.components.freshnessColor
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.components.readableAccent
import com.maslul.app.ui.components.rememberNow
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.Numeric
import kotlinx.coroutines.delay
import java.time.Instant
import com.maslul.app.i18n.S

/** Map id of the trip's live vehicle. */
private const val VEHICLE_ID = "veh"

/** A single trip: every stop with scheduled and live-predicted times. */
class TripModel(
    nav: AppNav,
    val tripId: String,
    val boardStopId: String? = null,
    val alightStopId: String? = null,
    /** Opened by tapping a live arrival time: the map zooms to the vehicle and follows it. */
    focusVehicle: Boolean = false,
) : ScreenModel(nav) {
    var following by mutableStateOf(focusVehicle)
    var trip by mutableStateOf<Leg?>(null)
    var progress by mutableStateOf<TripTimeline.Progress?>(null)
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)

    suspend fun liveLoop() {
        while (true) {
            val r = runCatching { repo.tripLive(tripId) }.getOrNull()
            if (r != null) {
                trip = r.first
                progress = r.second
            } else if (trip == null) {
                failed = true
            }
            loading = false
            delay(20_000)
        }
    }
}

@Composable
fun TripScreen(model: TripModel) {
    LaunchedEffect(Unit) { model.liveLoop() }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = Instant.now() } }
    val trip = model.trip
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, S.back) }
                if (trip != null) {
                    LineBadge(trip.lineLabel, trip.mode, color = lineColor(trip), large = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(trip.headsign?.let { S.toHeadsign(it) } ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        trip.agencyName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle) }
                    }
                }
            }
        }
        when {
            trip == null && model.loading -> LoadingBox(text = S.loadingTrip)
            trip == null -> MessageBox(S.tripUnavailable, body = S.tripUnavailableHint)
            else -> TripBody(model, trip, now)
        }
    }
}

@Composable
private fun TripBody(model: TripModel, trip: Leg, now: Instant) {
    val x = LocalExtra.current
    val color = lineColor(trip)
    val calls = remember(trip) { listOf(trip.from) + trip.intermediateStops + trip.to }
    val p = model.progress
    val board = calls.indexOfFirst { it.stopId != null && it.stopId == model.boardStopId }
    val alight = calls.indexOfFirst { it.stopId != null && it.stopId == model.alightStopId }

    val following = model.following && p != null
    Box(Modifier.fillMaxWidth().height(230.dp)) {
        TransitMap(
            Modifier.fillMaxSize(),
            lines = listOf(MapLine(trip.geometry.ifEmpty { calls.map { it.point } }, color, 5f)),
            markers = calls.mapIndexed { i, c ->
                MapMarker(c.point, color, if (i == board || i == alight || i == 0 || i == calls.lastIndex) MarkerKind.STOP else MarkerKind.STOP_SMALL)
            },
            vehicles = listOfNotNull(p?.let {
                MapVehicle(VEHICLE_ID, it.vehicle.point, it.vehicle.bearing, color, trip.lineLabel, faded = it.freshness(now) == Freshness.STALE)
            }),
            fitPoints = remember(trip) { calls.map { it.point } },
            fitKey = trip.tripId,
            // Leave room for the live card at the top.
            contentPadding = PaddingValues(top = if (following) 56.dp else 0.dp),
            focusVehicleId = VEHICLE_ID.takeIf { following },
            onUserPan = { model.following = false },
            compassAlignment = Alignment.BottomEnd,
            compassModifier = Modifier.padding(8.dp),
        )
        if (following) {
            LiveLocationCard(
                S.liveLocationOf(trip.lineLabel),
                p!!.vehicle.recordedAt,
                Modifier.padding(8.dp).align(Alignment.TopCenter),
                onClose = { model.following = false },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = p != null, onClickLabel = S.showOnMap) { model.following = true }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p != null) {
            val tick = rememberNow()
            val f = p.freshness(tick)
            LiveSignal(f, size = 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                (if (f == Freshness.LIVE) S.live else S.notUpdating) + " · " + Fmt.ago(p.vehicle.recordedAt, tick).replaceFirstChar { it.lowercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = if (f == Freshness.LIVE) MaterialTheme.colorScheme.onSurface else x.stale,
                modifier = Modifier.weight(1f),
            )
            if (!model.following) {
                Icon(Icons.Rounded.MyLocation, S.showOnMap, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
        } else {
            val start = trip.from.scheduledTime
            Text(
                when {
                    start != null && start.isAfter(now) -> S.departsNotStarted(Fmt.time(start))
                    trip.end.isBefore(now) -> S.tripEnded
                    else -> S.noLivePosition
                },
                style = MaterialTheme.typography.bodyMedium,
                color = x.subtle,
            )
        }
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = ((if (board >= 0) board else (p?.lastPassed ?: 0)) - 2).coerceAtLeast(0))
    val rail = RailSpec(Rail.SOLID, color)
    val passedRail = RailSpec(Rail.FADED, color)
    // Predicted times are green while the position is fresh, amber once it stops updating.
    val liveColor = freshnessColor(p?.freshness(now) ?: Freshness.SCHEDULED)
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        itemsIndexed(calls) { i, c ->
            val passed = p != null && i <= p.lastPassed
            val predicted = p?.predicted?.getOrNull(i)
            val sched = c.scheduledTime
            val highlight = i == board || i == alight
            Column {
                TimelineRow(
                    above = if (i == 0) null else if (passed) passedRail else rail,
                    below = if (i == calls.lastIndex) null else if (p != null && i < p.lastPassed) passedRail else rail,
                    node = if (highlight || i == 0 || i == calls.lastIndex) Node.TERMINUS else Node.STOP,
                    nodeColor = if (passed) color.copy(alpha = 0.35f) else color,
                    modifier = Modifier.alpha(if (passed) 0.5f else 1f),
                    time = {
                        Column {
                            // Only when it'll actually be there; how late that is vs the timetable isn't useful.
                            Text((predicted ?: sched)?.let(Fmt::time) ?: "", style = MaterialTheme.typography.labelLarge.merge(Numeric),
                                color = if (predicted != null) liveColor else MaterialTheme.colorScheme.onSurface)
                        }
                    },
                ) {
                    Text(
                        c.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(top = 11.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            when (i) { board -> S.boardHere; alight -> S.getOffHere; else -> null },
                            c.code?.let { "#$it" },
                            c.platformLabel,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (highlight) MaterialTheme.colorScheme.primary else x.subtle,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (p != null && i == p.lastPassed && i < calls.lastIndex) {
                    TimelineRow(rail, rail, Node.VEHICLE, color, nodeY = 16.dp) {
                        Text(S.lineIsHere(trip.lineLabel), style = MaterialTheme.typography.labelMedium, color = readableAccent(color),
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}
