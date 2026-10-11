package com.maslul.app.ui.lines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import com.maslul.app.ui.components.freshnessColor
import com.maslul.app.ui.theme.Numeric
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maslul.app.data.BoardEntry
import com.maslul.app.data.Freshness
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.Place
import com.maslul.app.data.freshness
import com.maslul.app.data.recordedAt
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.ArrivalTime
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.Hairline
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.LoadingBox
import com.maslul.app.ui.components.MapMarker
import com.maslul.app.ui.components.MarkerKind
import com.maslul.app.ui.components.MessageBox
import com.maslul.app.ui.components.TransitMap
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.nav.CurrentLocation
import com.maslul.app.ui.nav.RoutesModel
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.ModeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import com.maslul.app.i18n.S

class StopModel(nav: AppNav, val place: Place) : ScreenModel(nav) {
    var entries by mutableStateOf<List<BoardEntry>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    suspend fun refresh() {
        val id = place.stopId ?: run { error = S.unknownStop; loading = false; return }
        runCatching { repo.stopBoard(id) }
            .onSuccess { entries = it; error = null }
            .onFailure { if (entries.isEmpty()) error = S.errLoadDepartures }
        loading = false
    }

    suspend fun liveLoop() {
        while (true) {
            refresh()
            delay(30_000)
        }
    }

    /** Opens the trip; with [focusVehicle] its map zooms to the live vehicle and follows it. */
    fun open(e: BoardEntry, focusVehicle: Boolean = false) =
        nav.push(TripModel(nav, e.departure.tripId, boardStopId = place.stopId, focusVehicle = focusVehicle))
    fun toggleFavorite() = store.toggleFavoriteStop(place)
    fun directions() = nav.push(RoutesModel(nav, CurrentLocation, place))
}

/** Departures grouped per line+direction, next few times each. */
private data class BoardGroup(val key: String, val entries: List<BoardEntry>) {
    val first get() = entries.first()
}

@Composable
fun StopScreen(model: StopModel) {
    val data by model.store.data.collectAsState()
    val fav = data.favoriteStops.any { it.stopId == model.place.stopId }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { model.liveLoop() }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = Instant.now() } }
    val groups = remember(model.entries) {
        model.entries.groupBy { "${it.departure.routeId}|${it.departure.headsign}" }
            .map { (k, v) -> BoardGroup(k, v) }
            .sortedBy { it.first.live?.best ?: it.first.departure.scheduled }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, S.back) }
                Column(Modifier.weight(1f)) {
                    Text(model.place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    model.place.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle) }
                }
                IconButton(onClick = model::toggleFavorite) {
                    Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, S.favoriteStop,
                        tint = if (fav) Color(0xFFF5A524) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        StopBody(model, groups, now)
    }
}

/** The map, with the departures in a sheet over it that can be dragged down (or hidden) to enlarge the map, as on a line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopBody(model: StopModel, groups: List<BoardGroup>, now: Instant) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val peek = maxHeight * 0.7f
        val scaffold = rememberBottomSheetScaffoldState(
            // Dragging the sheet all the way down shows the map on its own.
            bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false),
        )
        val sheet = scaffold.bottomSheetState
        val mapOnly = sheet.currentValue == SheetValue.Hidden && sheet.targetValue == SheetValue.Hidden
        val scope = rememberCoroutineScope()
        BottomSheetScaffold(
            scaffoldState = scaffold,
            sheetPeekHeight = peek,
            sheetShadowElevation = 12.dp,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContent = { Departures(model, groups, now) },
        ) {
            Box(Modifier.fillMaxSize()) {
                TransitMap(
                    Modifier.fillMaxSize(),
                    markers = listOf(MapMarker(model.place.point, ModeColors.Bus, MarkerKind.STOP, model.place.stopId)),
                    user = model.location.last.collectAsState().value,
                    initialCenter = model.place.point,
                    initialZoom = 16.0,
                    // The stop stays in the middle of what's in view: above the sheet, or the whole map.
                    fitPoints = listOf(model.place.point),
                    fitKey = "${model.place.stopId}-$mapOnly",
                    contentPadding = PaddingValues(bottom = if (mapOnly) 72.dp else peek),
                    compassModifier = Modifier.padding(top = 64.dp, end = 12.dp),
                )
                FilledTonalIconButton(
                    onClick = { scope.launch { if (mapOnly) sheet.partialExpand() else sheet.hide() } },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("stop_map_expand"),
                ) { Icon(if (mapOnly) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (mapOnly) S.showDepartures else S.expandMap) }
                if (mapOnly) {
                    ExtendedFloatingActionButton(
                        onClick = { scope.launch { sheet.partialExpand() } },
                        icon = { Icon(Icons.Rounded.ExpandLess, null) },
                        text = { Text(S.showDepartures) },
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp),
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = model::directions,
            icon = { Icon(Icons.Rounded.Directions, null) },
            text = { Text(S.directions) },
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }
}

@Composable
private fun Departures(model: StopModel, groups: List<BoardGroup>, now: Instant) {
    Column(Modifier.fillMaxWidth()) {
        if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
        when {
            model.loading && model.entries.isEmpty() -> LoadingBox(text = S.loadingDepartures)
            model.error != null -> MessageBox(S.noDepartures, body = model.error, action = S.retry,
                onAction = { model.scope.launch { model.refresh() } })
            groups.isEmpty() -> MessageBox(S.noUpcomingDepartures, body = S.noUpcomingDeparturesHint)
            else -> LazyColumn(Modifier.fillMaxWidth().testTag("stop_board"), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(groups, key = { it.key }) { g ->
                    BoardRow(g, now, onTimeClick = { model.open(g.first, focusVehicle = true) }) { model.open(g.first) }
                    Hairline(start = 20.dp)
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun BoardRow(g: BoardGroup, now: Instant, onTimeClick: () -> Unit, onClick: () -> Unit) {
    val x = LocalExtra.current
    val d = g.first.departure
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = 64.dp)) { LineBadge(d.lineLabel, d.mode, color = lineColor(d)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.headsign, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOfNotNull(d.stop.platformLabel, d.agency).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = x.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        val live = g.first.live
        val t = live?.best ?: d.scheduled
        val freshness = live.freshness(now)
        Column(horizontalAlignment = Alignment.End) {
            // Tapping a live time shows that vehicle on the trip map; timetable times just open the trip.
            ArrivalTime(
                Fmt.relative(t, now),
                freshness,
                onClick = onTimeClick.takeIf { live?.vehicle != null && live.status == LiveStatus.LIVE },
            )
            val sub = when (live?.status) {
                LiveStatus.LIVE -> when {
                    freshness == Freshness.STALE -> live.recordedAt?.let { Fmt.ago(it, now).lowercase() } ?: S.liveLower
                    else -> S.liveLower
                }
                LiveStatus.UNTRACKED -> S.noLiveData
                else -> if (t.epochSecond - now.epochSecond < 3600) Fmt.time(t) else S.scheduledLower
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = x.subtle)
            // And the one after it, live when tracked.
            g.entries.getOrNull(1)?.let { e ->
                val next = e.live?.takeIf { it.status == LiveStatus.LIVE }
                Text(
                    S.thenTime(Fmt.relative(next?.best ?: e.departure.scheduled, now)),
                    style = MaterialTheme.typography.labelMedium.merge(Numeric),
                    color = if (next != null) freshnessColor(next.freshness(now)) else x.subtle,
                    modifier = Modifier.padding(top = 2.dp).testTag("next_arrival"),
                )
            }
        }
    }
}
