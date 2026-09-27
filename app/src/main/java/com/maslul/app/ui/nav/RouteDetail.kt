package com.maslul.app.ui.nav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveApproach
import com.maslul.app.data.Ranking
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.TransitMode
import com.maslul.app.live.ActiveTrip
import com.maslul.app.live.LiveTripService
import com.maslul.app.live.Reminders
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.LiveDot
import com.maslul.app.ui.components.MapController
import com.maslul.app.ui.components.MapLine
import com.maslul.app.ui.components.MapMarker
import com.maslul.app.ui.components.MapVehicle
import com.maslul.app.ui.components.MarkerKind
import com.maslul.app.ui.components.Node
import com.maslul.app.ui.components.Pill
import com.maslul.app.ui.components.Rail
import com.maslul.app.ui.components.RailSpec
import com.maslul.app.ui.components.TimelineRow
import com.maslul.app.ui.components.TransitMap
import com.maslul.app.ui.components.delayColor
import com.maslul.app.ui.components.delayText
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.components.modeName
import com.maslul.app.ui.lines.TripModel
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.Numeric
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

class RouteDetailModel(nav: AppNav, val itinerary: Itinerary, val from: Place, val to: Place) : ScreenModel(nav) {
    val live = mutableStateMapOf<Int, LiveCall>()
    val approach = mutableStateMapOf<Int, LiveApproach>()
    private val transfers = Ranking.transfers(itinerary, store.data.value.settings.walkSpeed.mps)

    /** A tight connection onto [leg], if the previous vehicle leaves little spare time. */
    fun tightTransferInto(leg: Leg) = transfers.firstOrNull { it.next === leg && it.tight }

    suspend fun liveLoop() {
        while (true) {
            val now = Instant.now()
            itinerary.legs.forEachIndexed { i, leg ->
                if (leg.mode.isTransit && leg.end.isAfter(now.minusSeconds(300))) {
                    scope.launch {
                        val call = runCatching { repo.legLive(leg, now) }.getOrNull() ?: return@launch
                        live[i] = call
                        val a = runCatching { repo.approach(leg, call) }.getOrNull()
                        if (a != null) approach[i] = a else approach.remove(i)
                    }
                }
            }
            delay(20_000)
        }
    }

    fun openTrip(leg: Leg) {
        val id = leg.tripId ?: return
        nav.push(TripModel(nav, id, boardStopId = leg.from.stopId, alightStopId = leg.to.stopId))
    }

    fun start(ctx: Context) = LiveTripService.start(ctx, itinerary, to.name)

    fun remind(ctx: Context) {
        val minutes = store.data.value.settings.reminderMinutes
        val first = itinerary.firstTransit
        val call = itinerary.legs.indexOf(first).let { live[it] }
        val walkBefore = first?.let { f -> itinerary.legs.takeWhile { it !== f }.sumOf { it.durationSec } } ?: 0
        val leaveAt = ((call?.takeIf { it.status == LiveStatus.LIVE }?.expected ?: first?.start ?: itinerary.start)
            .minusSeconds(walkBefore))
        val at = leaveAt.minusSeconds(minutes * 60L)
        val text = first?.let { "Leave at ${Fmt.time(leaveAt)} · ${modeName(it.mode)} ${it.lineLabel} from ${it.from.name} at ${Fmt.time(it.start)}" }
            ?: "Leave at ${Fmt.time(leaveAt)}"
        val ok = Reminders.schedule(ctx, at, "Time to go to ${to.name}", text)
        Toast.makeText(
            ctx,
            if (ok) "Reminder set for ${Fmt.time(at)} ($minutes min before leaving)" else "That departure is too soon for a reminder",
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun share(ctx: Context) {
        val text = buildString {
            appendLine("My trip to ${to.name}: ${Fmt.time(itinerary.start)} → ${Fmt.time(itinerary.end)} (${Fmt.duration(itinerary.durationSec)})")
            itinerary.legs.forEach { l ->
                if (l.mode == TransitMode.WALK) {
                    if (l.durationSec >= 60) appendLine("• Walk ${Fmt.duration(l.durationSec)} to ${l.to.name.ifBlank { to.name }}")
                } else {
                    appendLine("• ${modeName(l.mode)} ${l.lineLabel} from ${l.from.name} at ${Fmt.time(l.start)} → ${l.to.name} (${Fmt.time(l.end)})")
                }
            }
        }
        ctx.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share trip"),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(model: RouteDetailModel) {
    val ctx = LocalContext.current
    val active by ActiveTrip.session.collectAsState()
    val isActive = active?.itinerary?.id == model.itinerary.id
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { model.liveLoop() }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = Instant.now() } }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true || model.location.hasPermission()) model.start(ctx)
        else Toast.makeText(ctx, "Live directions need location access", Toast.LENGTH_SHORT).show()
    }
    val startLive = {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.remind(ctx) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val peek = maxHeight * 0.52f
        val scaffold = rememberBottomSheetScaffoldState(
            // Dragging the sheet all the way down shows the map full screen.
            bottomSheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded, skipHiddenState = false),
        )
        val sheet = scaffold.bottomSheetState
        val mapOnly = sheet.currentValue == SheetValue.Hidden && sheet.targetValue == SheetValue.Hidden
        val scope = rememberCoroutineScope()
        val mapController = remember { MapController() }
        BottomSheetScaffold(
            scaffoldState = scaffold,
            sheetPeekHeight = peek,
            sheetShadowElevation = 12.dp,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetContent = {
                LazyColumn(Modifier.fillMaxWidth().testTag("route_detail")) {
                    item {
                        DetailHeader(
                            model.itinerary, isActive,
                            onStart = { if (isActive) LiveTripService.stop(ctx) else startLive() },
                            onRemind = {
                                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else model.remind(ctx)
                            },
                            onShare = { model.share(ctx) },
                        )
                    }
                    if (isActive) active?.state?.let { st ->
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(st.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(st.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item { JourneyTimeline(model, now) }
                    item { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                RouteMap(model, Modifier.fillMaxSize(), PaddingValues(top = 60.dp, bottom = if (mapOnly) 72.dp else peek),
                    mapController, fitTag = mapOnly)
                FilledTonalIconButton(
                    onClick = { model.nav.pop() },
                    modifier = Modifier.statusBarsPadding().padding(12.dp),
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { scope.launch { if (mapOnly) sheet.partialExpand() else sheet.hide() } },
                        modifier = Modifier.testTag("map_expand"),
                    ) { Icon(if (mapOnly) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (mapOnly) "Show details" else "Expand map") }
                    FilledTonalIconButton(
                        onClick = {
                            // Jump to the last fix right away, then to a fresh one.
                            model.location.last.value?.let { mapController.moveTo(it, 15.5) }
                            model.scope.launch { model.location.current()?.let { mapController.moveTo(it, 15.5) } }
                        },
                        modifier = Modifier.testTag("map_my_location"),
                    ) { Icon(Icons.Rounded.MyLocation, "My location", tint = MaterialTheme.colorScheme.primary) }
                }
                if (mapOnly) {
                    ExtendedFloatingActionButton(
                        onClick = { scope.launch { sheet.partialExpand() } },
                        icon = { Icon(Icons.Rounded.ExpandLess, null) },
                        text = { Text("Trip details") },
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteMap(model: RouteDetailModel, modifier: Modifier, padding: PaddingValues, controller: MapController, fitTag: Boolean) {
    val x = LocalExtra.current
    val user by model.location.last.collectAsState()
    val itin = model.itinerary
    val lines = remember(itin, x.isDark) {
        itin.legs.map { l ->
            val pts = l.geometry.ifEmpty { listOf(l.from.point, l.to.point) }
            if (l.mode == TransitMode.WALK) MapLine(pts, if (x.isDark) Color(0xFFB0B8C4) else Color(0xFF5B6472), 5f, dashed = true)
            else MapLine(pts, lineColor(l.mode, l.routeColor), 6f)
        }
    }
    val markers = remember(itin) {
        buildList {
            itin.legs.filter { it.mode.isTransit }.forEach { l ->
                val c = lineColor(l.mode, l.routeColor)
                l.intermediateStops.forEach { add(MapMarker(it.point, c, MarkerKind.STOP_SMALL)) }
                add(MapMarker(l.from.point, c, MarkerKind.STOP))
                add(MapMarker(l.to.point, c, MarkerKind.STOP))
            }
            add(MapMarker(itin.legs.first().from.point, Color.Black, MarkerKind.ORIGIN))
            add(MapMarker(itin.legs.last().to.point, Color(0xFFE5484D), MarkerKind.DESTINATION))
        }
    }
    val vehicles = model.live.entries.mapNotNull { (i, c) ->
        val v = c.vehicle ?: return@mapNotNull null
        val leg = itin.legs[i]
        MapVehicle("v$i", v.point, v.bearing, lineColor(leg.mode, leg.routeColor), leg.lineLabel)
    }
    // The live vehicle's way to the boarding stop, faded so it reads as "not your ride yet".
    val bg = MaterialTheme.colorScheme.background
    val approach = model.approach.entries.map { (i, a) ->
        val leg = itin.legs[i]
        val c = lerp(lineColor(leg.mode, leg.routeColor), bg, 0.45f)
        MapLine(a.path, c, 5f, dashed = true) to a.stops.map { MapMarker(it, c, MarkerKind.STOP_SMALL) }
    }
    val route = remember(itin) { itin.legs.flatMap { it.geometry.ifEmpty { listOf(it.from.point, it.to.point) } } }
    val hasApproach = model.approach.isNotEmpty()
    TransitMap(
        modifier = modifier,
        lines = approach.map { it.first } + lines,
        markers = approach.flatMap { it.second } + markers,
        vehicles = vehicles,
        user = user,
        // Refit once the live vehicle shows up so it's in view too.
        fitPoints = route + model.approach.values.flatMap { it.path.take(1) },
        fitKey = "${itin.id}-$fitTag-$hasApproach",
        contentPadding = padding,
        controller = controller,
    )
}

@Composable
private fun DetailHeader(itin: Itinerary, active: Boolean, onStart: () -> Unit, onRemind: () -> Unit, onShare: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${Fmt.time(itin.start)} → ${Fmt.time(itin.end)}", style = MaterialTheme.typography.headlineSmall.merge(Numeric))
            Spacer(Modifier.width(10.dp))
            Text(Fmt.duration(itin.durationSec), style = MaterialTheme.typography.titleMedium, color = LocalExtra.current.subtle,
                modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(
            buildString {
                append(if (itin.transfers == 0) "No transfers" else "${itin.transfers} transfer${if (itin.transfers > 1) "s" else ""}")
                if (itin.walkMeters > 0) append(" · ${Fmt.distance(itin.walkMeters)} walking")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = LocalExtra.current.subtle,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onStart, shape = CircleShape, modifier = Modifier.height(46.dp).testTag("start_button")) {
                Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.Navigation, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (active) "End" else "Start")
            }
            FilledTonalButton(onClick = onRemind, shape = CircleShape, modifier = Modifier.height(46.dp)) {
                Icon(Icons.Rounded.AlarmAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Remind me")
            }
            OutlinedButton(onClick = onShare, shape = CircleShape, modifier = Modifier.height(46.dp)) {
                Icon(Icons.Rounded.Share, "Share", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun JourneyTimeline(model: RouteDetailModel, now: Instant) {
    val x = LocalExtra.current
    val legs = model.itinerary.legs
    val walkRail = RailSpec(Rail.DOTTED, x.walk)
    fun railOf(l: Leg) = if (l.mode == TransitMode.WALK) walkRail else RailSpec(Rail.SOLID, lineColor(l.mode, l.routeColor))
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column {
        // Origin
        TimelineRow(null, railOf(legs.first()), Node.ENDPOINT, onSurface, time = { TimeText(legs.first().start) }) {
            Text(model.from.name.takeIf { model.from.kind != PlaceKind.CURRENT_LOCATION } ?: "Your location",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(10.dp))
        }
        legs.forEachIndexed { i, leg ->
            if (leg.mode == TransitMode.WALK) {
                WalkSegment(leg, walkRail)
                // A node where the walk ends, unless the next leg draws its own boarding node.
                val next = legs.getOrNull(i + 1)
                if (next != null && next.mode == TransitMode.WALK) {
                    TimelineRow(walkRail, walkRail, Node.SMALL, x.walk) { Text(leg.to.name, style = MaterialTheme.typography.bodyMedium) }
                }
            } else {
                val c = lineColor(leg.mode, leg.routeColor)
                val rail = RailSpec(Rail.SOLID, c)
                val prev = legs.getOrNull(i - 1)
                val live = model.live[i]
                // Boarding
                TimelineRow(
                    above = prev?.let(::railOf),
                    below = rail,
                    node = Node.STOP,
                    nodeColor = c,
                    time = { TimeText(leg.start, live?.takeIf { it.status == LiveStatus.LIVE }?.let { it.expected }) },
                ) {
                    Text(leg.from.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    leg.from.track?.let { Text("Platform $it", style = MaterialTheme.typography.bodySmall, color = x.subtle) }
                    model.tightTransferInto(leg)?.let { TransferWarning(it, Modifier.padding(top = 4.dp)) }
                }
                TransitSegment(model, leg, rail, live, now)
                // Alighting
                val next = legs.getOrNull(i + 1)
                TimelineRow(
                    above = rail,
                    below = next?.let(::railOf),
                    node = Node.STOP,
                    nodeColor = c,
                    time = { TimeText(leg.end, live?.alightExpected) },
                ) {
                    Text(leg.to.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Text("Get off", style = MaterialTheme.typography.bodySmall, color = x.subtle)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        // Destination
        TimelineRow(railOf(legs.last()), null, Node.TERMINUS, MaterialTheme.colorScheme.primary,
            time = { TimeText(legs.last().end) }) {
            Text(model.to.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            model.to.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.subtle) }
        }
    }
}

@Composable
private fun TimeText(scheduled: Instant, expected: Instant? = null) {
    val x = LocalExtra.current
    Column {
        if (expected != null && Math.abs(expected.epochSecond - scheduled.epochSecond) >= 60) {
            Text(Fmt.time(expected), style = MaterialTheme.typography.labelLarge.merge(Numeric),
                color = delayColor(expected.epochSecond - scheduled.epochSecond))
            Text(Fmt.time(scheduled), style = MaterialTheme.typography.labelSmall.merge(Numeric), color = x.subtle,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
        } else {
            Text(Fmt.time(scheduled), style = MaterialTheme.typography.labelLarge.merge(Numeric),
                color = if (expected != null) x.live else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun WalkSegment(leg: Leg, rail: RailSpec) {
    val x = LocalExtra.current
    var open by remember { mutableStateOf(false) }
    TimelineRow(rail, rail, Node.NONE, Color.Transparent) {
        Row(
            Modifier.padding(vertical = 10.dp).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = leg.steps.isNotEmpty()) { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(com.maslul.app.ui.components.modeIcon(TransitMode.WALK), null, tint = x.subtle, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Walk ${Fmt.duration(leg.durationSec)}" + (leg.distanceM?.let { " · ${Fmt.distance(it)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = x.subtle,
            )
            if (leg.steps.isNotEmpty()) Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = x.subtle)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(bottom = 8.dp)) {
                leg.steps.forEach { s ->
                    Text("${s.instruction} · ${Fmt.distance(s.distanceM)}", style = MaterialTheme.typography.bodySmall,
                        color = x.subtle, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun TransitSegment(model: RouteDetailModel, leg: Leg, rail: RailSpec, live: LiveCall?, now: Instant) {
    val x = LocalExtra.current
    var open by remember { mutableStateOf(false) }
    TimelineRow(rail, rail, Node.NONE, Color.Transparent) {
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { model.openTrip(leg) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineBadge(leg.lineLabel, leg.mode, color = lineColor(leg.mode, leg.routeColor))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(leg.headsign?.let { "to $it" } ?: modeName(leg.mode), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                leg.agencyName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.subtle) }
            }
        }
        Spacer(Modifier.height(6.dp))
        LiveStatusChip(live, leg, now)
        leg.alerts.forEach { a ->
            Row(Modifier.padding(top = 6.dp)) {
                Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFF5A524), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(a, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        val n = leg.intermediateStops.size + 1
        Row(
            Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = leg.intermediateStops.isNotEmpty()) { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ride $n stop${if (n > 1) "s" else ""} · ${Fmt.duration(leg.durationSec)}", style = MaterialTheme.typography.bodySmall, color = x.subtle)
            if (leg.intermediateStops.isNotEmpty()) {
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = x.subtle, modifier = Modifier.size(18.dp))
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(bottom = 6.dp)) {
                leg.intermediateStops.forEach { s ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(s.scheduledTime?.let(Fmt::time) ?: "", style = MaterialTheme.typography.bodySmall.merge(Numeric),
                            color = x.subtle, modifier = Modifier.width(44.dp))
                        Text(s.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun LiveStatusChip(live: LiveCall?, leg: Leg, now: Instant) {
    val x = LocalExtra.current
    val (text, color, dot) = when (live?.status) {
        LiveStatus.LIVE -> {
            val eta = live.expected ?: leg.start
            val away = live.stopsAway?.takeIf { it in 1..40 }?.let { " · $it stop${if (it > 1) "s" else ""} away" } ?: ""
            Triple("Arrives ${Fmt.relative(eta, now).let { if (it == "Now") "now" else "in $it" }}$away", delayColor(live.delaySec), true)
        }
        LiveStatus.PASSED -> Triple("Already passed this stop — check the next one", x.late, false)
        LiveStatus.UNTRACKED -> Triple("No live data · scheduled ${Fmt.time(leg.start)}", x.subtle, false)
        LiveStatus.SCHEDULED -> Triple("Scheduled ${Fmt.time(leg.start)} · not departed yet", x.subtle, false)
        null -> Triple("Timetable ${Fmt.time(leg.start)}", x.subtle, false)
    }
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) { LiveDot(color); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        if (live?.status == LiveStatus.LIVE) {
            Spacer(Modifier.width(8.dp))
            Pill(delayText(live.delaySec), color, filled = true)
        }
    }
}
