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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.maslul.app.data.Freshness
import com.maslul.app.data.Combine
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveApproach
import com.maslul.app.data.Ranking
import com.maslul.app.data.ShuttleSchedule
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.TransitMode
import com.maslul.app.data.freshness
import com.maslul.app.live.ActiveTrip
import com.maslul.app.live.LiveTripService
import com.maslul.app.live.Reminders
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.LiveLocationCard
import com.maslul.app.ui.components.LiveSignal
import com.maslul.app.ui.components.freshnessColor
import com.maslul.app.ui.components.rememberNow
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
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.components.modeName
import com.maslul.app.ui.lines.TripModel
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.Numeric
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import com.maslul.app.i18n.S

class RouteDetailModel(nav: AppNav, private val original: Itinerary, val from: Place, val to: Place) : ScreenModel(nav) {
    /** Live state of every ride option, by [Leg.rideKey]. */
    val rideLive = mutableStateMapOf<String, LiveCall>()
    val approach = mutableStateMapOf<Int, LiveApproach>()
    /** Leg index whose live vehicle the map follows, after tapping its arrival. */
    var following by mutableStateOf<Int?>(null)

    /** The itinerary with the line picked on each combined leg (initially the best one). */
    var itinerary by mutableStateOf(original)
        private set
    /** Rides found after each transfer leg's (by leg index), for the wait if it's missed; null while looking. */
    private val after = mutableStateMapOf<Int, List<Leg>?>()

    init {
        val modes = store.data.value.settings.modes
        val first = original.legs.indexOfFirst { it.mode.isTransit }
        original.legs.forEachIndexed { i, l ->
            // The user's own shuttle: its timetable says what comes next.
            val own = l.shuttleId?.let { ShuttleSchedule.ridesAfter(store.data.value.shuttles, l) }
            if (i > first && own != null) after[i] = own
            else if (i > first && l.mode.isTransit && l.shuttleId == null) {
                after[i] = null
                scope.launch {
                    runCatching { repo.ridesAfter(l, modes) }.onSuccess { after[i] = it }.onFailure { after.remove(i) }
                }
            }
        }
    }

    /** Rides leaving after the one taken on transfer leg [i] (the wait if it's missed); null until looked up. */
    fun ridesAfter(i: Int): List<Leg>? = after[i]?.let { Combine.ridesAfter(itinerary.legs[i], it) }

    /** Whether the rides after transfer leg [i] are still being looked up. */
    fun lookingForRidesAfter(i: Int) = after.containsKey(i) && after[i] == null

    /** Live state of the ride currently chosen on leg [i]. */
    fun live(i: Int): LiveCall? = itinerary.legs.getOrNull(i)?.takeIf { it.mode.isTransit }?.let { rideLive[it.rideKey] }

    /** The trip as the rides are expected to run (late ones included): what its times show. */
    fun expected(): Itinerary = Combine.expected(itinerary) { rideLive[it.rideKey] }

    /** A tight connection onto [leg], if the previous vehicle (as expected to run) leaves little spare time. */
    fun tightTransferInto(leg: Leg) =
        Ranking.transfers(expected(), store.data.value.settings.walkSpeed.mps).firstOrNull { it.next.rideKey == leg.rideKey && it.tight }

    /** Legs whose ride the user picked by hand; the others follow whichever bus gets there first. */
    private val picked = HashSet<Int>()

    /** Rides [option] (another bus between the same stops) on leg [i] instead. */
    fun choose(i: Int, option: Leg) {
        picked += i
        switchTo(i, option)
    }

    private fun switchTo(i: Int, option: Leg) {
        itinerary = Combine.choose(itinerary, i, option)
        approach.remove(i)
        rideLive[option.rideKey]?.let { call -> scope.launch { updateApproach(i, itinerary.legs[i], call) } }
    }

    private suspend fun updateApproach(i: Int, leg: Leg, call: LiveCall) {
        val a = runCatching { repo.approach(leg, call) }.getOrNull()
        // The pick may have changed meanwhile.
        if (itinerary.legs.getOrNull(i)?.rideKey != leg.rideKey) return
        if (a != null) approach[i] = a else approach.remove(i)
    }

    suspend fun liveLoop() {
        while (true) {
            val now = Instant.now()
            itinerary.legs.forEachIndexed { i, chosen ->
                if (!chosen.mode.isTransit) return@forEachIndexed
                // Every line of a combined leg, so their buses and arrivals can be compared.
                chosen.options.forEach { leg ->
                    scope.launch {
                        val call = runCatching { if (repo.worthLive(leg, now, graceSec = 300)) repo.legLive(leg, now) else null }
                            .getOrNull() ?: return@launch
                        rideLive[leg.rideKey] = call
                        if (leg.rideKey == itinerary.legs.getOrNull(i)?.rideKey) updateApproach(i, leg, call)
                        pickBest()
                    }
                }
            }
            delay(20_000)
        }
    }

    /**
     * Takes whichever catchable bus gets you there first on each leg the user hasn't picked —
     * e.g. an earlier 16 running late that comes before the planned one. Only for trips about
     * to happen, where live data says something.
     */
    private fun pickBest() {
        val now = Instant.now()
        if (itinerary.start.isAfter(now.plusSeconds(90 * 60))) return
        itinerary.legs.indices.forEach { i ->
            if (i in picked) return@forEach
            Combine.bestOption(itinerary, i, now) { rideLive[it.rideKey] }?.let { switchTo(i, it) }
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
        val call = live(itinerary.legs.indexOf(first))
        val walkBefore = first?.let { f -> itinerary.legs.takeWhile { it !== f }.sumOf { it.durationSec } } ?: 0
        val leaveAt = ((call?.takeIf { it.status == LiveStatus.LIVE }?.expected ?: first?.start ?: itinerary.start)
            .minusSeconds(walkBefore))
        val at = leaveAt.minusSeconds(minutes * 60L)
        val text = S.leaveAt(Fmt.time(leaveAt)) +
            (first?.let { " · " + S.rideFromAt(rideName(it), it.from.name, Fmt.time(it.start)) } ?: "")
        val ok = Reminders.schedule(ctx, at, S.timeToGoTo(to.name), text)
        Toast.makeText(
            ctx,
            if (ok) S.reminderSet(Fmt.time(at), minutes) else S.reminderTooSoon,
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun share(ctx: Context) {
        val text = buildString {
            val e = expected()
            appendLine(S.shareTitle(to.name, Fmt.time(e.start), Fmt.time(e.end), Fmt.duration(e.durationSec)))
            itinerary.legs.forEach { l ->
                if (l.mode == TransitMode.WALK) {
                    if (l.durationSec >= 60) appendLine("• " + S.shareWalk(Fmt.duration(l.durationSec), l.to.name.ifBlank { to.name }))
                } else {
                    appendLine("• " + S.rideFromAt(rideName(l), l.from.name, Fmt.time(l.start)) + " ${S.arrow} ${l.to.name} (${Fmt.time(l.end)})")
                }
            }
        }
        ctx.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), S.shareTrip),
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
        else Toast.makeText(ctx, S.liveNeedsLocation, Toast.LENGTH_SHORT).show()
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
                LazyColumn(Modifier.fillMaxWidth().testTag("route_detail")) {
                    item {
                        DetailHeader(
                            model.expected(), isActive,
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
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, S.back) }
                Column(
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { scope.launch { if (mapOnly) sheet.partialExpand() else sheet.hide() } },
                        modifier = Modifier.testTag("map_expand"),
                    ) { Icon(if (mapOnly) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (mapOnly) S.showDetails else S.expandMap) }
                    FilledTonalIconButton(
                        onClick = {
                            // Jump to the last fix right away, then to a fresh one.
                            model.location.last.value?.let { mapController.moveTo(it, 15.5) }
                            model.scope.launch { model.location.current()?.let { mapController.moveTo(it, 15.5) } }
                        },
                        modifier = Modifier.testTag("map_my_location"),
                    ) { Icon(Icons.Rounded.MyLocation, S.myLocation, tint = MaterialTheme.colorScheme.primary) }
                }
                val followed = model.following?.let { i -> model.live(i)?.takeIf { it.status == LiveStatus.LIVE }?.vehicle?.let { i to it } }
                if (followed != null) {
                    LiveLocationCard(
                        S.liveLocationOf(model.itinerary.legs[followed.first].lineLabel),
                        followed.second.recordedAt,
                        Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 64.dp, vertical = 12.dp),
                        onClose = { model.following = null },
                    )
                }
                if (mapOnly) {
                    ExtendedFloatingActionButton(
                        onClick = { scope.launch { sheet.partialExpand() } },
                        icon = { Icon(Icons.Rounded.ExpandLess, null) },
                        text = { Text(S.tripDetails) },
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
            when (l.mode) {
                TransitMode.WALK -> MapLine(pts, if (x.isDark) Color(0xFFB0B8C4) else Color(0xFF5B6472), 5f, dashed = true)
                TransitMode.CAR -> MapLine(pts, if (x.isDark) Color(0xFF9AA3B2) else Color(0xFF3F4652), 6f)
                else -> MapLine(pts, lineColor(l), 6f)
            }
        }
    }
    val markers = remember(itin) {
        buildList {
            itin.legs.filter { it.mode.isTransit }.forEach { l ->
                val c = lineColor(l)
                l.intermediateStops.forEach { add(MapMarker(it.point, c, MarkerKind.STOP_SMALL)) }
                add(MapMarker(l.from.point, c, MarkerKind.STOP))
                add(MapMarker(l.to.point, c, MarkerKind.STOP))
            }
            add(MapMarker(itin.legs.first().from.point, Color.Black, MarkerKind.ORIGIN))
            add(MapMarker(itin.legs.last().to.point, Color(0xFFE5484D), MarkerKind.DESTINATION))
        }
    }
    val now = rememberNow(10_000)
    val bg = MaterialTheme.colorScheme.background
    // Other lines of a combined leg: drawn faded underneath, with their buses, so all can be compared.
    val otherLines = remember(itin, x.isDark) {
        itin.legs.flatMap { l ->
            l.alternatives.map { o ->
                MapLine(o.geometry.ifEmpty { listOf(o.from.point, o.to.point) }, lerp(lineColor(o), bg, 0.5f), 4f)
            }
        }
    }
    val vehicles = itin.legs.flatMap { leg ->
        // The chosen ride's bus last, so it's drawn on top.
        (leg.alternatives + leg).mapNotNull { o ->
            val v = model.rideLive[o.rideKey]?.vehicle ?: return@mapNotNull null
            val c = lineColor(o)
            MapVehicle("v${o.rideKey}", v.point, v.bearing, if (o === leg) c else lerp(c, bg, 0.4f), o.lineLabel,
                faded = Freshness.of(v.recordedAt, now) == Freshness.STALE)
        }
    }
    val followId = model.following?.let { itin.legs.getOrNull(it)?.rideKey }?.let { "v$it" }?.takeIf { id -> vehicles.any { it.id == id } }
    // The live vehicle's way to the boarding stop, faded so it reads as "not your ride yet".
    val approach = model.approach.entries.map { (i, a) ->
        val leg = itin.legs[i]
        val c = lerp(lineColor(leg), bg, 0.45f)
        MapLine(a.path, c, 5f, dashed = true) to a.stops.map { MapMarker(it, c, MarkerKind.STOP_SMALL) }
    }
    val route = remember(itin) { itin.legs.flatMap { it.geometry.ifEmpty { listOf(it.from.point, it.to.point) } } }
    val hasApproach = model.approach.isNotEmpty()
    TransitMap(
        modifier = modifier,
        lines = otherLines + approach.map { it.first } + lines,
        markers = approach.flatMap { it.second } + markers,
        vehicles = vehicles,
        user = user,
        // Refit once the live vehicle shows up so it's in view too.
        fitPoints = route + model.approach.values.flatMap { it.path.take(1) },
        fitKey = "${itin.id}-$fitTag-$hasApproach",
        contentPadding = padding,
        controller = controller,
        focusVehicleId = followId,
        onUserPan = { model.following = null },
        compassModifier = Modifier.statusBarsPadding().padding(top = 124.dp, end = 16.dp),
    )
}

@Composable
private fun DetailHeader(itin: Itinerary, active: Boolean, onStart: () -> Unit, onRemind: () -> Unit, onShare: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${Fmt.time(itin.start)} ${S.arrow} ${Fmt.time(itin.end)}", style = MaterialTheme.typography.headlineSmall.merge(Numeric))
            Spacer(Modifier.width(10.dp))
            Text(Fmt.duration(itin.durationSec), style = MaterialTheme.typography.titleMedium, color = LocalExtra.current.subtle,
                modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(
            buildString {
                append(if (itin.transfers == 0) S.noTransfers else S.transfers(itin.transfers))
                if (itin.walkMeters > 0) append(" · " + S.walkingDistance(Fmt.distance(itin.walkMeters)))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = LocalExtra.current.subtle,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onStart, shape = CircleShape, modifier = Modifier.height(46.dp).testTag("start_button")) {
                Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.Navigation, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (active) S.end else S.start)
            }
            FilledTonalButton(onClick = onRemind, shape = CircleShape, modifier = Modifier.height(46.dp)) {
                Icon(Icons.Rounded.AlarmAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(S.remindMe)
            }
            OutlinedButton(onClick = onShare, shape = CircleShape, modifier = Modifier.height(46.dp)) {
                Icon(Icons.Rounded.Share, S.share, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun JourneyTimeline(model: RouteDetailModel, now: Instant) {
    val x = LocalExtra.current
    val legs = model.itinerary.legs
    // Leaving and arriving as the rides are expected to run (the stops show their own live times).
    val expected = model.expected()
    val walkRail = RailSpec(Rail.DOTTED, x.walk)
    val carRail = RailSpec(Rail.SOLID, x.subtle)
    fun railOf(l: Leg) = when (l.mode) {
        TransitMode.WALK -> walkRail
        TransitMode.CAR -> carRail
        else -> RailSpec(Rail.SOLID, lineColor(l))
    }
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column {
        // Origin
        TimelineRow(null, railOf(legs.first()), Node.ENDPOINT, onSurface, time = { TimeText(expected.start) }) {
            Text(model.from.name.takeIf { model.from.kind != PlaceKind.CURRENT_LOCATION } ?: S.yourLocation,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(10.dp))
        }
        legs.forEachIndexed { i, leg ->
            if (!leg.mode.isTransit) {
                if (leg.mode == TransitMode.CAR) CarSegment(leg, carRail) else WalkSegment(leg, walkRail)
                // A node where the walk (or the lift) ends, unless the next leg draws its own boarding node.
                val next = legs.getOrNull(i + 1)
                if (next != null && !next.mode.isTransit) {
                    val name = leg.to.name.ifBlank { if (leg.mode == TransitMode.CAR) S.getOutHere else "" }
                    TimelineRow(railOf(leg), railOf(next), Node.SMALL, if (leg.mode == TransitMode.CAR) x.subtle else x.walk,
                        time = if (leg.mode == TransitMode.CAR) ({ TimeText(leg.end) }) else null) {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                val c = lineColor(leg)
                val rail = RailSpec(Rail.SOLID, c)
                val prev = legs.getOrNull(i - 1)
                val live = model.live(i)
                // Boarding
                TimelineRow(
                    above = prev?.let(::railOf),
                    below = rail,
                    node = Node.STOP,
                    nodeColor = c,
                    time = { TimeText(leg.start, live?.takeIf { it.status == LiveStatus.LIVE }?.let { it.expected }, live.freshness(now)) },
                ) {
                    Text(leg.from.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    if (leg.tripId != null && leg.tripId == model.from.tripId) {
                        Text(S.youreOnThis(leg.mode), style = MaterialTheme.typography.bodySmall, color = x.subtle)
                    }
                    leg.from.platformLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.subtle) }
                    model.tightTransferInto(leg)?.let { TransferWarning(it, modifier = Modifier.padding(top = 4.dp)) }
                }
                TransitSegment(model, i, leg, rail, live, now)
                // Alighting
                val next = legs.getOrNull(i + 1)
                TimelineRow(
                    above = rail,
                    below = next?.let(::railOf),
                    node = Node.STOP,
                    nodeColor = c,
                    time = { TimeText(leg.end, live?.alightExpected, live.freshness(now)) },
                ) {
                    Text(leg.to.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                    Text(S.getOff, style = MaterialTheme.typography.bodySmall, color = x.subtle)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        // Destination
        TimelineRow(railOf(legs.last()), null, Node.TERMINUS, MaterialTheme.colorScheme.primary,
            time = { TimeText(expected.end) }) {
            Text(model.to.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            model.to.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.subtle) }
        }
    }
}

@Composable
private fun TimeText(scheduled: Instant, expected: Instant? = null, freshness: Freshness = Freshness.SCHEDULED) {
    val x = LocalExtra.current
    // Live predictions: green while the position is fresh, amber once it stops updating.
    val liveColor = freshnessColor(if (freshness == Freshness.SCHEDULED) Freshness.LIVE else freshness)
    Column {
        Text(Fmt.time(expected ?: scheduled), style = MaterialTheme.typography.labelLarge.merge(Numeric),
            color = if (expected != null) liveColor else MaterialTheme.colorScheme.onSurface)
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
                S.walkDuration(Fmt.duration(leg.durationSec)) + (leg.distanceM?.takeIf { it >= 1 }?.let { " · ${Fmt.distance(it)}" } ?: ""),
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

/** "Car · 18 min · 12 km": a lift for part of the way. */
@Composable
private fun CarSegment(leg: Leg, rail: RailSpec) {
    val x = LocalExtra.current
    TimelineRow(rail, rail, Node.NONE, Color.Transparent) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(com.maslul.app.ui.components.modeIcon(TransitMode.CAR), null, tint = x.subtle, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                S.rideDuration(Fmt.duration(leg.durationSec)) + (leg.distanceM?.takeIf { it >= 1 }?.let { " · ${Fmt.distance(it)}" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = x.subtle,
            )
        }
    }
}

@Composable
private fun TransitSegment(model: RouteDetailModel, index: Int, leg: Leg, rail: RailSpec, live: LiveCall?, now: Instant) {
    val x = LocalExtra.current
    var open by remember { mutableStateOf(false) }
    TimelineRow(rail, rail, Node.NONE, Color.Transparent) {
        Spacer(Modifier.height(6.dp))
        val liveOf = { o: Leg -> model.rideLive[o.rideKey] }
        val options = Combine.catchableOptions(model.itinerary, index, now, hurry = true, liveOf = liveOf)
            .let { o -> if (o.none { it.rideKey == leg.rideKey }) o + leg else o }
        if (options.size > 1) {
            LineOptions(leg, options, now, model.rideLive, hurry = { Combine.onlyByHurrying(model.itinerary, index, it, now, liveOf(it), liveOf) }) {
                model.choose(index, it)
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable { model.openTrip(leg) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LineBadge(leg.lineLabel, leg.mode, color = lineColor(leg))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(leg.headsign?.let { S.toHeadsign(it) } ?: modeName(leg.mode), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                leg.agencyName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.subtle) }
            }
        }
        Spacer(Modifier.height(6.dp))
        val legIndex = model.itinerary.legs.indexOf(leg)
        // On the bus already: "passed this stop, check the next one" isn't advice for you.
        if (leg.tripId == null || leg.tripId != model.from.tripId) {
            LiveStatusChip(live, leg, now, onClick = { model.following = legIndex })
        }
        if (model.lookingForRidesAfter(index)) {
            Text(S.missedChecking, style = MaterialTheme.typography.labelMedium,
                color = x.subtle, modifier = Modifier.padding(top = 8.dp).testTag("missed_rides_loading"))
        }
        model.ridesAfter(index)?.let { MissedRides(leg, it, Modifier.padding(top = 8.dp)) }
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
            Text(S.rideStops(n) + " · " + Fmt.duration(leg.durationSec), style = MaterialTheme.typography.bodySmall, color = x.subtle)
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

/** "If you miss it: [143] 14:32 · +12 min  [5] 14:40 · +20 min" for a transfer: the next two rides. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MissedRides(leg: Leg, rides: List<Leg>, modifier: Modifier = Modifier) {
    val x = LocalExtra.current
    Column(modifier.testTag("missed_rides")) {
        Text(
            if (rides.isEmpty()) S.missedNone else S.missedNext,
            style = MaterialTheme.typography.labelMedium, color = x.subtle,
        )
        if (rides.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                rides.forEach { o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(o.lineLabel, o.mode, color = lineColor(o), showIcon = false)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "${Fmt.time(o.start)} · +${Fmt.duration(o.start.epochSecond - leg.start.epochSecond)}",
                            style = MaterialTheme.typography.labelMedium.merge(Numeric), color = x.subtle,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Every bus that can still be caught for this ride (other lines, or the next ones of the same
 * line — including an earlier one running late), soonest first, each with its departure and arrival
 * (live when tracked). Tapping one rides it: the timeline and map follow the pick.
 */
@Composable
private fun LineOptions(
    leg: Leg,
    rides: List<Leg>,
    now: Instant,
    rideLive: Map<String, LiveCall>,
    /** Buses you'd only catch by hurrying to the stop. */
    hurry: (Leg) -> Boolean,
    onChoose: (Leg) -> Unit,
) {
    val x = LocalExtra.current
    val options = rides.map { it to rideLive[it.rideKey] }.sortedBy { (o, c) -> Combine.boards(o, c) }
    val lines = options.distinctBy { Combine.lineKey(it.first) }.size
    val mode = options.map { it.first.mode }.distinct().singleOrNull()
    Text(
        when {
            lines > 1 -> S.nGoThisWay(options.size, mode)
            // The user's own shuttle: "Next 2 shuttles", not "buses of Office shuttle".
            options.all { it.first.shuttleId != null } -> S.nextNShuttles(options.size)
            else -> S.nextNOf(options.size, mode, leg.lineLabel)
        } + " · " + S.tapToChoose,
        style = MaterialTheme.typography.labelMedium, color = x.subtle,
    )
    Spacer(Modifier.height(6.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("line_options"),
    ) {
        // The first few (and the chosen one); the rest behind "N more".
        var expanded by rememberSaveable(leg.from.stopId, leg.to.stopId) { mutableStateOf(false) }
        val shown = if (expanded) options else options.filterIndexed { i, (o, _) -> i < SHOWN_RIDES || o.rideKey == leg.rideKey }
        shown.forEach { (o, c) ->
            val chosen = o.rideKey == leg.rideKey
            val isLive = c?.status == LiveStatus.LIVE
            val dep = c?.takeIf { isLive }?.expected ?: o.start
            val arr = c?.takeIf { isLive }?.alightExpected ?: o.end
            val color = lineColor(o)
            Row(
                Modifier.fillMaxWidth().clickable { onChoose(o) }
                    .background(if (chosen) color.copy(alpha = 0.14f) else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen, onClick = { onChoose(o) }, modifier = Modifier.size(32.dp))
                LineBadge(o.lineLabel, o.mode, color = color, showIcon = false)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${Fmt.time(dep)} ${S.arrow} ${Fmt.time(arr)}",
                        style = MaterialTheme.typography.bodyMedium.merge(Numeric),
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        listOfNotNull(o.headsign?.let { S.toHeadsign(it) } ?: modeName(o.mode), o.from.platformLabel).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = x.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                when (c?.status) {
                    LiveStatus.LIVE -> Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val f = c.freshness(now)
                            LiveSignal(f)
                            Spacer(Modifier.width(4.dp))
                            Text(Fmt.relative(dep, now), style = MaterialTheme.typography.labelLarge.merge(Numeric), color = freshnessColor(f))
                        }
                        if (hurry(o)) Text(S.hurry, style = MaterialTheme.typography.labelMedium, color = x.late, fontWeight = FontWeight.SemiBold)
                    }
                    LiveStatus.PASSED -> Text(S.passed, style = MaterialTheme.typography.labelMedium, color = x.late)
                    else -> Text(Fmt.relative(dep, now), style = MaterialTheme.typography.labelLarge.merge(Numeric), color = x.subtle)
                }
                Spacer(Modifier.width(6.dp))
            }
        }
        val hidden = options.size - shown.size
        if (hidden > 0 || expanded && options.size > SHOWN_RIDES) {
            Text(
                if (expanded) S.showFewer else S.nMoreRides(hidden),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("more_rides"),
            )
        }
    }
}

@Composable
fun LiveStatusChip(live: LiveCall?, leg: Leg, now: Instant, onClick: (() -> Unit)? = null) {
    val x = LocalExtra.current
    val freshness = live.freshness(now)
    val (text, color, dot) = when (live?.status) {
        LiveStatus.LIVE -> {
            val eta = live.expected ?: leg.start
            val away = live.stopsAway?.takeIf { it in 1..40 }?.let { " · " + S.stopsAway(it) } ?: ""
            val soon = eta.epochSecond - now.epochSecond < 45
            Triple(S.arrives(if (soon) null else Fmt.relative(eta, now)) + away, freshnessColor(freshness), true)
        }
        LiveStatus.PASSED -> Triple(S.passedThisStop, x.late, false)
        LiveStatus.UNTRACKED -> Triple(S.noLiveScheduled(Fmt.time(leg.start)), x.subtle, false)
        LiveStatus.SCHEDULED -> Triple(S.scheduledNotDeparted(Fmt.time(leg.start)), x.subtle, false)
        null -> Triple(S.timetable(Fmt.time(leg.start)), x.subtle, false)
    }
    // A live vehicle can be shown on the map; timetable-only rows aren't tappable.
    val tappable = onClick != null && live?.status == LiveStatus.LIVE && live.vehicle != null
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.1f))
            .then(if (tappable) Modifier.clickable(onClickLabel = S.showOnMap) { onClick!!() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) { LiveSignal(freshness, size = 13.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        if (tappable) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.MyLocation, null, tint = color, modifier = Modifier.size(16.dp))
        }
    }
}
