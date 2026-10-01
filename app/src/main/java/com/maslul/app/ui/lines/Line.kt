package com.maslul.app.ui.lines

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maslul.app.data.Freshness
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.freshness
import com.maslul.app.data.TransitMode
import com.maslul.app.data.IsraelZone
import com.maslul.app.data.LineDetail
import com.maslul.app.data.LineRoute
import com.maslul.app.data.LineStop
import com.maslul.app.data.LineUnavailableException
import com.maslul.app.data.LineVehicle
import com.maslul.app.data.Ride
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.ArrivalTime
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LiveLocationCard
import com.maslul.app.ui.components.LiveSignal
import com.maslul.app.ui.components.freshnessColor
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.components.readableAccent
import com.maslul.app.ui.components.LiveDot
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
import com.maslul.app.ui.components.modeIcon
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.Numeric
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

class LineModel(nav: AppNav, route: LineRoute) : ScreenModel(nav) {
    var route by mutableStateOf(route)
    var variants by mutableStateOf(listOf(route))
    var detail by mutableStateOf<LineDetail?>(null)
    var vehicles by mutableStateOf<List<LineVehicle>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var tab by mutableStateOf(0)
    var expanded by mutableStateOf<Int?>(null)
    /** Map id of the live vehicle the map is following, after tapping its arrival time. */
    var following by mutableStateOf<String?>(null)

    init { load() }

    /** Follows the live vehicle running [journeyRef] on the map, if it's tracked. */
    fun follow(journeyRef: String?) {
        val v = vehicles.firstOrNull { journeyRef != null && it.vehicle.journeyRef == journeyRef } ?: return
        following = v.mapId
    }

    private fun load() {
        scope.launch {
            loading = true
            error = null
            detail = null
            vehicles = emptyList()
            val current = runCatching { repo.currentRoute(route) }.getOrDefault(route)
            route = current
            val v = async { runCatching { repo.lineVariants(current) }.getOrNull() }
            runCatching { repo.lineDetail(current) }
                .onSuccess { detail = it; refreshLive() }
                .onFailure {
                    error = (it as? LineUnavailableException)?.message
                        ?: "Couldn't load this line. Check your connection and try again."
                }
            v.await()?.let { variants = it }
            loading = false
        }
    }

    fun retry() {
        if (!loading) load()
    }

    suspend fun refreshLive() {
        val d = detail ?: return
        runCatching { repo.lineVehicles(d) }.onSuccess { vehicles = it }
    }

    suspend fun liveLoop() {
        while (true) {
            delay(20_000)
            refreshLive()
        }
    }

    fun select(r: LineRoute) {
        if (r.gtfsRouteId == route.gtfsRouteId) return
        route = r
        expanded = null
        following = null
        load()
    }

    /** Toggles to the opposite direction (first variant with a different direction). */
    fun reverse() {
        val other = variants.firstOrNull { it.direction != route.direction && it.alternative == route.alternative }
            ?: variants.firstOrNull { it.direction != route.direction }
            ?: variants.firstOrNull { it.gtfsRouteId != route.gtfsRouteId }
        other?.let(::select)
    }

    fun openStop(s: LineStop, onFail: () -> Unit) {
        scope.launch {
            val match = repo.transitStop(s)
            if (match != null) nav.push(StopModel(nav, match.copy(name = s.name))) else onFail()
        }
    }

    fun openRide(r: Ride) {
        val d = detail ?: return
        scope.launch { nav.push(TripModel(nav, repo.rideTripId(d, r))) }
    }

    fun toggleFavorite() = store.toggleFavoriteLine(route)
}

@Composable
fun LineScreen(model: LineModel) {
    val data by model.store.data.collectAsState()
    val fav = data.favoriteLines.any { it.operatorRef == model.route.operatorRef && it.mkt == model.route.mkt }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { model.liveLoop() }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = Instant.now() } }
    val r = model.route
    val color = lineColor(r)
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    LineBadge(r.label, r.mode, color = color, large = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("to ${r.destination.ifBlank { r.longName }}", style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${r.agency} · from ${r.origin}", style = MaterialTheme.typography.bodySmall,
                            color = LocalExtra.current.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = model::toggleFavorite) {
                        Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite",
                            tint = if (fav) Color(0xFFF5A524) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton(
                            onClick = { if (model.variants.size > 2) menu = true else model.reverse() },
                            enabled = model.variants.size > 1,
                        ) { Icon(Icons.Rounded.SwapHoriz, "Change direction") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            model.variants.forEach { v ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("to ${v.destination}", style = MaterialTheme.typography.bodyMedium)
                                            Text("from ${v.origin}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle)
                                        }
                                    },
                                    onClick = { menu = false; model.select(v) },
                                )
                            }
                        }
                    }
                }
            }
        }

        val d = model.detail
        when {
            d == null && model.loading -> LoadingBox(text = "Loading line…")
            d == null -> MessageBox("Line unavailable", body = model.error, action = "Retry", onAction = model::retry)
            else -> LineBody(model, d, color, now)
        }
    }
}

/** The map, with the stops and timetable in a sheet over it that can be dragged down (or hidden) to enlarge the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineBody(model: LineModel, d: LineDetail, color: Color, now: Instant) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val peek = maxHeight * 0.62f
        val scaffold = rememberBottomSheetScaffoldState(
            // Dragging the sheet all the way down shows the map on its own.
            bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false),
        )
        val sheet = scaffold.bottomSheetState
        val mapOnly = sheet.currentValue == SheetValue.Hidden && sheet.targetValue == SheetValue.Hidden
        val scope = rememberCoroutineScope()
        // Following a vehicle needs the map in view.
        LaunchedEffect(model.following) {
            if (model.following != null && sheet.currentValue == SheetValue.Expanded) sheet.partialExpand()
        }
        BottomSheetScaffold(
            scaffoldState = scaffold,
            sheetPeekHeight = peek,
            sheetShadowElevation = 12.dp,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContent = {
                Column(Modifier.fillMaxSize()) {
                    PrimaryTabRow(selectedTabIndex = model.tab, containerColor = MaterialTheme.colorScheme.surface) {
                        val off = MaterialTheme.colorScheme.onSurfaceVariant
                        Tab(model.tab == 0, onClick = { model.tab = 0 }, text = { Text("Stops") }, unselectedContentColor = off)
                        Tab(model.tab == 1, onClick = { model.tab = 1 }, text = { Text("Timetable") }, unselectedContentColor = off)
                    }
                    if (model.tab == 0) StopsList(model, d, color, now) else Timetable(model, d, now)
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                LineMap(model, d, color, now, bottomPadding = if (mapOnly) 72.dp else peek, fitTag = mapOnly)
                FilledTonalIconButton(
                    onClick = { scope.launch { if (mapOnly) sheet.partialExpand() else sheet.hide() } },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("line_map_expand"),
                ) { Icon(if (mapOnly) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (mapOnly) "Show stops" else "Expand map") }
                if (mapOnly) {
                    ExtendedFloatingActionButton(
                        onClick = { scope.launch { sheet.partialExpand() } },
                        icon = { Icon(Icons.Rounded.ExpandLess, null) },
                        text = { Text(if (model.tab == 0) "Stops" else "Timetable") },
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LineMap(model: LineModel, d: LineDetail, color: Color, now: Instant, bottomPadding: Dp, fitTag: Boolean) {
    val stops = remember(d) { d.stops.map { GeoPoint(it.lat, it.lon) } }
    // Keep following only while that vehicle is still reported.
    val followed = model.vehicles.firstOrNull { it.mapId == model.following }
    Box(Modifier.fillMaxSize()) {
        TransitMap(
            modifier = Modifier.fillMaxSize(),
            lines = listOf(MapLine(d.timeline.line, color, 5f)),
            markers = stops.mapIndexed { i, p ->
                MapMarker(p, color, if (i == 0 || i == stops.lastIndex) MarkerKind.STOP else MarkerKind.STOP_SMALL)
            },
            vehicles = model.vehicles.map {
                MapVehicle(it.mapId, it.vehicle.point, it.vehicle.bearing, color, d.route.label,
                    faded = it.progress.freshness(now) == Freshness.STALE)
            },
            fitPoints = stops,
            fitKey = "${d.route.gtfsRouteId}-$fitTag",
            contentPadding = PaddingValues(top = if (followed != null) 56.dp else 0.dp, bottom = bottomPadding),
            focusVehicleId = followed?.mapId,
            onUserPan = { model.following = null },
            onMarkerClick = { id -> if (model.vehicles.any { it.mapId == id }) model.following = id },
            compassModifier = Modifier.padding(top = 64.dp, end = 12.dp),
        )
        if (followed != null) {
            LiveLocationCard(
                "${d.route.label} live location" + (followed.ride?.let { " · left ${Fmt.time(it.departure)}" } ?: ""),
                followed.vehicle.recordedAt,
                Modifier.padding(horizontal = 60.dp, vertical = 8.dp).align(Alignment.TopCenter),
                onClose = { model.following = null },
            )
        }
    }
}

/** Stable map id for a line's live vehicle. */
private val LineVehicle.mapId: String get() = vehicle.vehicleRef ?: vehicle.journeyRef ?: "${vehicle.lineRef}-${vehicle.originDeparture}"

@Composable
private fun StopsList(model: LineModel, d: LineDetail, color: Color, now: Instant) {
    val x = LocalExtra.current
    val ctx = LocalContext.current
    val rail = RailSpec(Rail.SOLID, color)
    val state = rememberLazyListState()
    val byGap = model.vehicles.groupBy { it.progress.lastPassed }
    LazyColumn(Modifier.fillMaxSize().testTag("line_stops"), state = state, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        itemsIndexed(d.stops, key = { i, s -> "$i-${s.gtfsStopId}" }) { i, s ->
            val arrivals = remember(model.vehicles, now, i) { model.repo.arrivalsAt(d, model.vehicles, i, now) }
            val next = arrivals.firstOrNull()
            Column {
                // Vehicles approaching this stop (between stop i-1 and i).
                byGap[i - 1]?.forEach { v ->
                    VehicleRow(v, rail, color, d.route.label, d.route.mode, now) { model.following = v.mapId }
                }
                TimelineRow(
                    above = if (i == 0) null else rail,
                    below = if (i == d.stops.lastIndex) null else rail,
                    node = if (i == 0 || i == d.stops.lastIndex) Node.TERMINUS else Node.STOP,
                    nodeColor = color,
                    timeWidth = 12.dp,
                    modifier = Modifier.clickable { model.expanded = if (model.expanded == i) null else i },
                ) {
                    Row(Modifier.padding(top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(s.city, "#${s.code}").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = x.subtle)
                        }
                        if (next != null) {
                            Column(horizontalAlignment = Alignment.End) {
                                // Tapping a live time follows that vehicle on the map above.
                                ArrivalTime(
                                    Fmt.relative(next.time, now),
                                    next.freshness(now),
                                    style = MaterialTheme.typography.titleSmall,
                                    onClick = if (next.live) ({ model.follow(next.journeyRef) }) else null,
                                )
                                if (!next.live) Text("scheduled", style = MaterialTheme.typography.labelSmall, color = x.subtle)
                            }
                        }
                    }
                    AnimatedVisibility(model.expanded == i) {
                        Column(Modifier.padding(bottom = 12.dp)) {
                            FlowRow2 {
                                arrivals.forEach { a ->
                                    val f = a.freshness(now)
                                    Pill(
                                        (if (a.live) "● " else "") + Fmt.time(a.time),
                                        if (a.live) freshnessColor(f) else x.subtle,
                                        modifier = if (a.live) {
                                            Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = "Show on map") { model.follow(a.journeyRef) }
                                        } else {
                                            Modifier
                                        },
                                    )
                                }
                                if (arrivals.isEmpty()) Text("No more trips today", style = MaterialTheme.typography.bodySmall, color = x.subtle)
                            }
                            TextButton(onClick = {
                                model.openStop(s) { Toast.makeText(ctx, "Stop board unavailable", Toast.LENGTH_SHORT).show() }
                            }, contentPadding = PaddingValues(0.dp)) { Text("All departures from this stop") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow2(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun VehicleRow(v: LineVehicle, rail: RailSpec, color: Color, label: String, mode: TransitMode, now: Instant, onClick: () -> Unit) {
    val x = LocalExtra.current
    TimelineRow(rail, rail, Node.VEHICLE, color, timeWidth = 12.dp, nodeY = 18.dp,
        modifier = Modifier.clickable(onClickLabel = "Show on map", onClick = onClick)) {
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val accent = readableAccent(color)
                Icon(modeIcon(mode), null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            val f = v.progress.freshness(now)
            Spacer(Modifier.width(6.dp))
            LiveSignal(f, size = 12.dp)
            if (f == Freshness.STALE) {
                Spacer(Modifier.width(4.dp))
                Text("last seen ${Fmt.time(v.vehicle.recordedAt)}", style = MaterialTheme.typography.labelSmall, color = x.stale)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Timetable(model: LineModel, d: LineDetail, now: Instant) {
    val x = LocalExtra.current
    val liveRefs = model.vehicles.mapNotNull { it.ride?.journeyRef }.toSet()
    val byHour = d.rides.groupBy { it.departure.atZone(IsraelZone).hour }.toSortedMap()
    val date = LocalDate.parse(d.route.date)
    // Open at the current hour (item 0 is the header).
    val currentHour = now.atZone(IsraelZone).hour
    val start = byHour.keys.indexOfFirst { it >= currentHour }.let { if (it < 0) 0 else it + 1 }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp)) {
        item {
            Text(
                "Departures from ${d.stops.first().name} · ${Fmt.day(date)}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (d.rides.isEmpty()) MessageBox("No departures listed for today")
        }
        byHour.forEach { (hour, rides) ->
            item(key = "h$hour") {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Text(hour.toString().padStart(2, '0'), style = MaterialTheme.typography.titleMedium.merge(Numeric),
                        color = x.subtle, modifier = Modifier.width(40.dp).padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rides.forEach { ride ->
                            val past = ride.departure.isBefore(now) && ride.journeyRef !in liveRefs
                            val live = ride.journeyRef in liveRefs
                            Surface(
                                onClick = { model.openRide(ride) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (live) x.live.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.alpha(if (past) 0.4f else 1f),
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (live) { LiveDot(); Spacer(Modifier.width(5.dp)) }
                                    Text(Fmt.time(ride.departure), style = MaterialTheme.typography.bodyMedium.merge(Numeric))
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}
