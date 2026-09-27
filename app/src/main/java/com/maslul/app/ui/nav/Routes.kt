package com.maslul.app.ui.nav

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.Accessible
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.IsraelZone
import com.maslul.app.data.Itinerary
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.Ranking
import com.maslul.app.data.SavedTrip
import com.maslul.app.data.TransitMode
import com.maslul.app.data.TransitousApi
import com.maslul.app.data.WalkSpeed
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.LiveDot
import com.maslul.app.ui.components.LoadingBox
import com.maslul.app.ui.components.MessageBox
import com.maslul.app.ui.components.Pill
import com.maslul.app.ui.components.delayColor
import com.maslul.app.ui.components.delayText
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.components.modeIcon
import com.maslul.app.ui.components.modeName
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.Numeric
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

enum class TimeMode { NOW, DEPART, ARRIVE }

class RoutesModel(nav: AppNav, from: Place, to: Place) : ScreenModel(nav) {
    var from by mutableStateOf(from)
    var to by mutableStateOf(to)
    var timeMode by mutableStateOf(TimeMode.NOW)
    var time by mutableStateOf<ZonedDateTime?>(null)

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var itineraries by mutableStateOf<List<Itinerary>>(emptyList())
    var walkOnly by mutableStateOf<Itinerary?>(null)
    var loadingMore by mutableStateOf(false)
    private var nextCursor: String? = null
    private var prevCursor: String? = null
    private var fromPoint: GeoPoint? = null
    private var toPoint: GeoPoint? = null
    val live = mutableStateMapOf<String, LiveCall>()
    /** When the current results were loaded, to refresh stale "leave now" searches. */
    private var searchedAt: Instant = Instant.EPOCH

    init { search() }

    private val walkMps get() = store.data.value.settings.walkSpeed.mps
    private fun ranked(list: List<Itinerary>) = Ranking.rank(list, timeMode == TimeMode.ARRIVE, walkMps)
    fun riskiest(itin: Itinerary) = Ranking.riskiest(itin, walkMps)

    private suspend fun resolve(p: Place): GeoPoint? =
        if (p.kind == PlaceKind.CURRENT_LOCATION) location.current() ?: location.last.value else p.point

    private fun request(cursor: String? = null): TransitousApi.PlanRequest? {
        val f = fromPoint ?: return null
        val t = toPoint ?: return null
        val s = store.data.value.settings
        return TransitousApi.PlanRequest(
            from = f,
            to = t,
            time = if (timeMode == TimeMode.NOW) null else time?.toInstant(),
            arriveBy = timeMode == TimeMode.ARRIVE,
            modes = s.modes,
            walkSpeedMps = s.walkSpeed.mps,
            maxWalkMinutes = s.maxWalkMinutes,
            wheelchair = s.wheelchair,
            pageCursor = cursor,
        )
    }

    fun search() {
        scope.launch {
            loading = true
            error = null
            itineraries = emptyList()
            walkOnly = null
            live.clear()
            searchedAt = Instant.now()
            fromPoint = resolve(from)
            toPoint = resolve(to)
            if (fromPoint == null || toPoint == null) {
                error = "Your location isn't available. Allow location access or choose a starting point."
                loading = false
                return@launch
            }
            runCatching { repo.plan(request()!!) }
                .onSuccess { r ->
                    itineraries = ranked(r.itineraries)
                    walkOnly = r.walkOnly?.takeIf { it.durationSec < 45 * 60 }
                    nextCursor = r.nextCursor
                    prevCursor = r.previousCursor
                    if (r.itineraries.isEmpty() && walkOnly == null) error = "No routes found for this time."
                }
                .onFailure { error = "Couldn't load routes. Check your connection." }
            loading = false
            refreshLive()
        }
    }

    fun more(later: Boolean) {
        val cursor = (if (later) nextCursor else prevCursor) ?: return
        scope.launch {
            loadingMore = true
            runCatching { repo.plan(request(cursor)!!) }.onSuccess { r ->
                val known = itineraries.map { it.id }.toSet()
                val fresh = r.itineraries.filter { it.id !in known }
                itineraries = ranked(if (later) itineraries + fresh else fresh + itineraries)
                if (later) nextCursor = r.nextCursor else prevCursor = r.previousCursor
            }
            loadingMore = false
            refreshLive()
        }
    }

    suspend fun refreshLive() {
        val now = Instant.now()
        coroutineScope {
            itineraries
                .filter { it.firstTransit != null && it.firstTransit!!.start.isBefore(now.plusSeconds(90 * 60)) }
                .map { it to async { runCatching { repo.legLive(it.firstTransit!!, now) }.getOrNull() } }
                .forEach { (it, d) -> d.await()?.let { c -> live[it.id] = c } }
        }
    }

    suspend fun liveLoop() {
        while (true) {
            delay(25_000)
            refreshLive()
        }
    }

    /** Re-runs the search from now (the screen may have sat open for a while). */
    fun refreshNow() {
        timeMode = TimeMode.NOW
        time = null
        search()
    }

    fun refreshIfStale() {
        if (timeMode == TimeMode.NOW && !loading && Instant.now().isAfter(searchedAt.plusSeconds(150))) search()
    }

    fun edit(field: SearchField) {
        nav.push(SearchModel(nav, from, to, field) { f, t ->
            nav.pop()
            from = f
            to = t!!
            search()
        })
    }

    fun swap() {
        val f = from; from = to; to = f
        search()
    }

    fun setTime(mode: TimeMode, t: ZonedDateTime?) {
        timeMode = mode
        time = t
        search()
    }

    fun isSaved() = store.data.value.savedTrips.any { it.from.key == from.key && it.to.key == to.key }
    fun toggleSaved() = store.toggleSavedTrip(SavedTrip(from, to))

    fun open(it: Itinerary) = nav.push(RouteDetailModel(nav, it, from, to))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(model: RoutesModel) {
    val data by model.store.data.collectAsState()
    var showTime by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { model.liveLoop() }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = Instant.now() } }
    // Coming back to a "leave now" search after a while re-runs it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) model.refreshIfStale() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val fastest = model.itineraries.minByOrNull { it.durationSec }?.id
    val leastWalk = model.itineraries.takeIf { it.size > 2 }?.minByOrNull { it.walkSec }?.id

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Column(Modifier.statusBarsPadding().padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, end = 4.dp)) {
                    IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 4.dp),
                    ) {
                        EndpointRow(model.from.name, hollow = true) { model.edit(SearchField.FROM) }
                        EndpointRow(model.to.name, hollow = false) { model.edit(SearchField.TO) }
                    }
                    Column {
                        IconButton(onClick = model::swap) { Icon(Icons.Rounded.SwapVert, "Swap") }
                        val saved = data.savedTrips.any { it.from.key == model.from.key && it.to.key == model.to.key }
                        IconButton(onClick = model::toggleSaved) {
                            Icon(
                                if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                "Save trip",
                                tint = if (saved) Color(0xFFF5A524) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = model.timeMode != TimeMode.NOW,
                        onClick = { showTime = true },
                        label = { Text(timeLabel(model.timeMode, model.time)) },
                        leadingIcon = { Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("time_chip"),
                    )
                    val s = data.settings
                    val custom = s.modes.size < 6 || s.maxWalkMinutes != 15 || s.wheelchair || s.walkSpeed != WalkSpeed.NORMAL
                    FilterChip(
                        selected = custom,
                        onClick = { showOptions = true },
                        label = { Text("Options") },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
                    )
                    if (s.wheelchair) {
                        FilterChip(selected = true, onClick = { showOptions = true }, label = { Text("Accessible") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Accessible, null, Modifier.size(18.dp)) })
                    }
                    AssistChip(
                        onClick = model::refreshNow,
                        enabled = !model.loading,
                        label = { Text("Refresh") },
                        leadingIcon = { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)) },
                        modifier = Modifier.testTag("refresh_chip"),
                    )
                }
            }
        }

        when {
            model.loading -> LoadingBox(text = "Finding routes…")
            model.error != null && model.itineraries.isEmpty() && model.walkOnly == null ->
                MessageBox("No routes", body = model.error, action = "Try again", onAction = model::search)
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("routes_list"),
                contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
            ) {
                item {
                    TextButton(onClick = { model.more(false) }, enabled = !model.loadingMore,
                        modifier = Modifier.padding(start = 8.dp)) { Text("Earlier") }
                }
                model.walkOnly?.let { w ->
                    item(key = "walk") { WalkOnlyCard(w, now) { model.open(w) } }
                }
                items(model.itineraries, key = { it.id }) { itin ->
                    val tags = buildList {
                        // Ranked list: the first card is the recommended option.
                        if (itin === model.itineraries.first() && model.itineraries.size > 1) add("Best")
                        if (itin.id == fastest) add("Fastest")
                        if (itin.id == leastWalk && itin.id != fastest) add("Least walking")
                    }
                    ItineraryCard(itin, model.live[itin.id], now, tags, model.riskiest(itin)) { model.open(itin) }
                }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (model.loadingMore) LoadingBox()
                        else TextButton(onClick = { model.more(true) }) { Text("Later routes") }
                    }
                }
                item {
                    Text(
                        "Timetables: Ministry of Transport GTFS via Transitous · Live: MOT SIRI via Open Bus",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalExtra.current.subtle,
                        modifier = Modifier.padding(horizontal = 20.dp).navigationBarsPadding(),
                    )
                }
            }
        }
    }

    if (showTime) {
        TimeSheet(model.timeMode, model.time, onDismiss = { showTime = false }) { mode, t ->
            showTime = false
            model.setTime(mode, t)
        }
    }
    if (showOptions) {
        OptionsSheet(model, onDismiss = {
            showOptions = false
            model.search()
        })
    }
}

@Composable
private fun EndpointRow(text: String, hollow: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (hollow) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
        Box(Modifier.size(10.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
            if (hollow) Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun timeLabel(mode: TimeMode, t: ZonedDateTime?): String {
    if (mode == TimeMode.NOW || t == null) return "Leave now"
    val day = t.toLocalDate().let { d -> if (d == LocalDate.now(IsraelZone)) "" else " · " + Fmt.day(d) }
    val hm = Fmt.time(t.toInstant())
    return (if (mode == TimeMode.DEPART) "Depart $hm" else "Arrive by $hm") + day
}

// ---- Cards ----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItineraryCard(
    itin: Itinerary,
    live: LiveCall?,
    now: Instant,
    tags: List<String>,
    risk: Ranking.Transfer? = null,
    onClick: () -> Unit,
) {
    val x = LocalExtra.current
    val first = itin.firstTransit
    // Walking time before boarding, so "leave in" can follow the live vehicle.
    val walkBefore = first?.let { f -> itin.legs.takeWhile { it !== f }.sumOf { it.durationSec } } ?: 0
    val boardAt = when (live?.status) {
        LiveStatus.LIVE -> live.expected ?: first?.start
        else -> first?.start
    } ?: itin.start
    val leaveAt = boardAt.minusSeconds(walkBefore)
    val endAt = live?.alightExpected?.let { a ->
        val after = first?.let { f -> itin.legs.dropWhile { it !== f }.drop(1) }.orEmpty()
        if (after.none { it.mode.isTransit }) a.plusSeconds(after.sumOf { it.durationSec }) else null
    } ?: itin.end

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (tags.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                            tags.forEach { Pill(it, MaterialTheme.colorScheme.primary) }
                        }
                    }
                    Text(
                        "${Fmt.time(leaveAt)} – ${Fmt.time(endAt)}",
                        style = MaterialTheme.typography.titleLarge.merge(Numeric),
                    )
                    Text(
                        buildString {
                            append(Fmt.duration(endAt.epochSecond - leaveAt.epochSecond))
                            append(" · ")
                            append(if (itin.transfers == 0) "Direct" else "${itin.transfers} transfer${if (itin.transfers > 1) "s" else ""}")
                            if (itin.walkSec >= 60) append(" · ${Fmt.duration(itin.walkSec)} walk")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = x.subtle,
                    )
                }
                LeaveIn(leaveAt, now, live?.status == LiveStatus.LIVE)
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = itin.legs.filter { it.mode.isTransit || it.durationSec >= 60 }
                shown.forEachIndexed { i, leg ->
                    if (leg.mode == TransitMode.WALK) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = x.subtle, modifier = Modifier.size(18.dp))
                            Text("${(leg.durationSec + 30) / 60}", style = MaterialTheme.typography.labelMedium, color = x.subtle)
                        }
                    } else {
                        LineBadge(leg.lineLabel, leg.mode, color = lineColor(leg.mode, leg.routeColor))
                    }
                    if (i < shown.lastIndex) {
                        Icon(Icons.Rounded.ChevronRight, null, tint = x.subtle.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (first != null) {
                Spacer(Modifier.height(12.dp))
                LiveLine(first.lineLabel, first.mode, first.from.name, live, first.start, now)
            }
            if (risk != null) {
                Spacer(Modifier.height(8.dp))
                TransferWarning(risk)
            }
            if (itin.legs.any { it.alerts.isNotEmpty() }) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFF5A524), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Service alert on this route", style = MaterialTheme.typography.bodySmall, color = x.subtle)
                }
            }
        }
    }
}

/** "⚠ Tight transfer · 2 min to 143" — the connection may be missed if a bus runs off schedule. */
@Composable
fun TransferWarning(t: Ranking.Transfer, modifier: Modifier = Modifier) {
    val min = t.slackSec.coerceAtLeast(0) / 60
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFF5A524), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Tight transfer · ${if (min < 1) "under 1 min" else "$min min"} to catch ${t.next.lineLabel}" +
                (if (t.walking) " after a ${Fmt.distance(t.walkM)} walk" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = LocalExtra.current.subtle,
        )
    }
}

@Composable
private fun LeaveIn(leaveAt: Instant, now: Instant, live: Boolean) {
    val min = (leaveAt.epochSecond - now.epochSecond + 30) / 60
    Column(horizontalAlignment = Alignment.End) {
        if (min <= 0 && min > -2) {
            Text("Go now", style = MaterialTheme.typography.titleMedium, color = LocalExtra.current.live)
        } else if (min in 1..90) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$min",
                    style = MaterialTheme.typography.headlineSmall.merge(Numeric),
                    color = if (live) LocalExtra.current.live else MaterialTheme.colorScheme.onSurface,
                )
                Text(" min", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 3.dp))
            }
            Text("leave in", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.subtle)
        } else if (min < 0) {
            Text("Missed", style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.subtle)
        }
    }
}

/** "● 116 arrives in 4 min at X · 2 min late" style summary for a boarding. */
@Composable
fun LiveLine(label: String, mode: TransitMode, stop: String, live: LiveCall?, scheduled: Instant, now: Instant) {
    val x = LocalExtra.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (live?.status) {
            LiveStatus.LIVE -> {
                val c = delayColor(live.delaySec)
                LiveDot(c)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${modeName(mode)} $label ${if (live.best.epochSecond - now.epochSecond < 45) "is arriving" else "in " + Fmt.relative(live.best, now)}" +
                            (live.stopsAway?.takeIf { it in 1..30 }?.let { " · $it stop${if (it > 1) "s" else ""} away" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("at $stop", style = MaterialTheme.typography.bodySmall, color = x.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Pill(delayText(live.delaySec), c)
            }
            LiveStatus.PASSED -> {
                Icon(Icons.Rounded.WarningAmber, null, tint = x.late, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("$label has already passed $stop", style = MaterialTheme.typography.bodyMedium, color = x.late,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            else -> {
                Icon(modeIcon(mode), null, tint = x.subtle, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("$label from $stop", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Scheduled ${Fmt.time(scheduled)}" + if (live?.status == LiveStatus.UNTRACKED) " · no live data" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = x.subtle,
                    )
                }
            }
        }
    }
}

@Composable
private fun WalkOnlyCard(itin: Itinerary, now: Instant, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Walk ${Fmt.duration(itin.durationSec)}", style = MaterialTheme.typography.titleMedium)
                Text(Fmt.distance(itin.walkMeters), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle)
            }
            Text("Arrive ${Fmt.time(now.plusSeconds(itin.durationSec))}", style = MaterialTheme.typography.bodyMedium.merge(Numeric))
        }
    }
}

// ---- Sheets ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSheet(mode: TimeMode, time: ZonedDateTime?, onDismiss: () -> Unit, onApply: (TimeMode, ZonedDateTime?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = time ?: ZonedDateTime.now(IsraelZone).plusMinutes(15)
    var sel by remember { mutableStateOf(if (mode == TimeMode.NOW) TimeMode.DEPART else mode) }
    var date by remember { mutableStateOf(initial.toLocalDate()) }
    val tp = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    val today = LocalDate.now(IsraelZone)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("When?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(TimeMode.DEPART to "Depart at", TimeMode.ARRIVE to "Arrive by").forEachIndexed { i, (m, l) ->
                    SegmentedButton(
                        selected = sel == m,
                        onClick = { sel = m },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(l) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (0L..6L).map { today.plusDays(it) }.forEach { d ->
                    FilterChip(
                        selected = d == date,
                        onClick = { date = d },
                        label = { Text(Fmt.day(d, today)) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimeInput(state = tp) }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { onApply(TimeMode.NOW, null) }, modifier = Modifier.weight(1f).height(50.dp)) {
                    Text("Leave now")
                }
                Button(
                    onClick = { onApply(sel, date.atTime(LocalTime.of(tp.hour, tp.minute)).atZone(IsraelZone)) },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = CircleShape,
                ) { Text("Set time") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OptionsSheet(model: ScreenModel, onDismiss: () -> Unit) {
    val data by model.store.data.collectAsState()
    val s = data.settings
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Route options", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text("Transport", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TransitMode.BUS, TransitMode.TRAIN, TransitMode.LIGHT_RAIL, TransitMode.METRO, TransitMode.CABLE_CAR, TransitMode.FERRY)
                    .forEach { m ->
                        val on = m in s.modes
                        FilterChip(
                            selected = on,
                            onClick = {
                                model.store.updateSettings { st ->
                                    val next = if (on) st.modes - m else st.modes + m
                                    st.copy(modes = next.ifEmpty { st.modes })
                                }
                            },
                            label = { Text(modeName(m)) },
                            leadingIcon = { Icon(modeIcon(m), null, Modifier.size(18.dp)) },
                        )
                    }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Max walk to/from stops", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${s.maxWalkMinutes} min", style = MaterialTheme.typography.bodyMedium.merge(Numeric))
            }
            Slider(
                value = s.maxWalkMinutes.toFloat(),
                onValueChange = { v -> model.store.updateSettings { it.copy(maxWalkMinutes = (v / 5).toInt().coerceAtLeast(1) * 5) } },
                valueRange = 5f..30f,
                steps = 4,
            )
            Spacer(Modifier.height(8.dp))
            Text("Walking speed", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                WalkSpeed.entries.forEachIndexed { i, w ->
                    SegmentedButton(
                        selected = s.walkSpeed == w,
                        onClick = { model.store.updateSettings { it.copy(walkSpeed = w) } },
                        shape = SegmentedButtonDefaults.itemShape(i, WalkSpeed.entries.size),
                    ) { Text(w.label) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wheelchair accessible", style = MaterialTheme.typography.titleSmall)
                    Text("Avoid stairs; prefer step-free routes", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle)
                }
                Switch(checked = s.wheelchair, onCheckedChange = { c -> model.store.updateSettings { it.copy(wheelchair = c) } })
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(50.dp), shape = CircleShape) { Text("Show routes") }
        }
    }
}
