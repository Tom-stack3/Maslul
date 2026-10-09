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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.Accessible
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.maslul.app.data.Combine
import com.maslul.app.data.DEFAULT_MAX_WALK_MINUTES
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.IsraelZone
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Freshness
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveCall
import com.maslul.app.data.freshness
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.RailPreference
import com.maslul.app.data.NoDriveException
import com.maslul.app.data.PlanResult
import com.maslul.app.data.RideOffer
import com.maslul.app.data.RidePlanner
import com.maslul.app.data.ShuttleSchedule
import com.maslul.app.data.OnBoardStart
import com.maslul.app.data.Ranking
import com.maslul.app.data.Settings
import com.maslul.app.data.SavedTrip
import com.maslul.app.data.TransitMode
import com.maslul.app.data.TransitRepository
import com.maslul.app.data.TransitousApi
import com.maslul.app.data.UserData
import com.maslul.app.data.WalkSpeed
import com.maslul.app.data.favoriteFor
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.LiveSignal
import com.maslul.app.ui.components.freshnessColor
import com.maslul.app.ui.lines.TripModel
import com.maslul.app.ui.components.LoadingBox
import com.maslul.app.ui.components.MessageBox
import com.maslul.app.ui.components.Pill
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.components.modeIcon
import com.maslul.app.ui.components.modeName
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.Numeric
import kotlinx.coroutines.Deferred
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

/** Why a search came back empty, for screens that word [RoutesModel.error] their own way. */
enum class RouteError { LOCATION, NO_ROUTES, NETWORK }

class RoutesModel(nav: AppNav, from: Place, to: Place) : ScreenModel(nav) {
    var from by mutableStateOf(from)
    var to by mutableStateOf(to)
    var timeMode by mutableStateOf(TimeMode.NOW)
    var time by mutableStateOf<ZonedDateTime?>(null)

    var loading by mutableStateOf(false)
    /** Options are showing but more are still being looked for (a lift's drop-offs, stops to get off at). */
    var refining by mutableStateOf(false)
        private set
    private var searchJob: kotlinx.coroutines.Job? = null
    var error by mutableStateOf<String?>(null)
    var errorKind by mutableStateOf<RouteError?>(null)
        private set
    var itineraries by mutableStateOf<List<Itinerary>>(emptyList())
    var walkOnly by mutableStateOf<Itinerary?>(null)
    var loadingMore by mutableStateOf(false)
    private var nextCursor: String? = null
    private var prevCursor: String? = null
    private var fromPoint: GeoPoint? = null
    private var toPoint: GeoPoint? = null
    val live = mutableStateMapOf<String, LiveCall>()
    /** Live state of every line option on each option's first ride, by [Leg.rideKey]. */
    val rideLive = mutableStateMapOf<String, LiveCall>()
    /** Rides leaving after a tightly-connected leg, by its [Leg.rideKey]: the wait if it's missed. */
    val after = mutableStateMapOf<String, List<Leg>>()
    /** Itineraries as returned (before combining), and extra rides found per stop pair. */
    private var raw: List<Itinerary> = emptyList()
    private val rides = HashMap<String, List<Leg>>()
    /** When the current results were loaded, to refresh stale "leave now" searches. */
    private var searchedAt: Instant = Instant.EPOCH
    /** Kept here, not in the screen, so coming back from a route's details keeps the scroll position. */
    val listState = LazyListState()
    /** The endpoints of the current results, to tell a refresh from a new search. */
    private var searchedFrom: String? = null
    private var searchedTo: String? = null
    /** A lift for the first part of the trip, if someone's giving one. */
    var ride by mutableStateOf<RideOffer?>(null)
        private set
    /** On board: the trip being ridden and its next stop, where planning starts. */
    private var boardStart: OnBoardStart? = null
    /** Whether there are earlier / later results to page to. */
    var canPage by mutableStateOf(false)
        private set

    /** The trip the user is on, when planning from a bus or train they're riding. */
    val onBoardTripId: String? get() = from.takeIf { it.kind == PlaceKind.ON_BOARD }?.tripId

    init { search() }

    private val walkMps get() = store.data.value.settings.walkSpeed.mps
    private val rail get() = store.data.value.settings.railPreference
    /** Folds options that differ only by line into one, then ranks and adds more lines found per ride. */
    private fun ranked(list: List<Itinerary>): List<Itinerary> {
        val arriveBy = timeMode == TimeMode.ARRIVE
        val best = list.sortedBy { Ranking.score(it, arriveBy, walkMps, rail) }
        val now = Instant.now()
        return Ranking.rank(Combine.merge(best), arriveBy, walkMps, rail).map { itin ->
            itin.legs.indices.fold(itin) { acc, i ->
                val found = rides[TransitRepository.rideBucket(acc.legs[i])]
                // Already on board: there's no other bus to take for this part.
                val ridden = acc.legs[i].tripId != null && acc.legs[i].tripId == onBoardTripId
                if (found == null || !acc.legs[i].mode.isTransit || ridden) acc else Combine.addAlternatives(acc, i, found, now)
            }
        }
    }

    /** Looks up other lines riding the same hops as the top options, then re-combines. */
    private suspend fun findAlternatives() {
        val modes = store.data.value.settings.modes
        val legs = itineraries.take(8).flatMap { it.transitLegs }
            .filter { it.from.stopId != null && it.to.stopId != null && (it.tripId == null || it.tripId != onBoardTripId) }
            .distinctBy { TransitRepository.rideBucket(it) }
            .filter { TransitRepository.rideBucket(it) !in rides }
        if (legs.isEmpty()) return
        val found = coroutineScope {
            legs.map { l -> async { runCatching { TransitRepository.rideBucket(l) to repo.rideAlternatives(l, modes) }.getOrNull() } }
                .awaitAll().filterNotNull()
        }
        if (found.isEmpty()) return
        found.forEach { (k, v) -> rides[k] = v }
        itineraries = ranked(raw)
    }

    /** For each top option's tight transfer, looks up the rides after it: the wait if it's missed. */
    private suspend fun findRidesAfter() {
        val modes = store.data.value.settings.modes
        val (own, legs) = itineraries.take(8).mapNotNull { riskiest(it)?.next }
            .distinctBy { it.rideKey }
            .filter { it.rideKey !in after }
            .partition { it.shuttleId != null }
        // The user's shuttles: their own timetable says what comes next.
        own.forEach { l -> ShuttleSchedule.ridesAfter(store.data.value.shuttles, l)?.let { after[l.rideKey] = it } }
        coroutineScope {
            legs.map { l -> async { runCatching { l.rideKey to repo.ridesAfter(l, modes) }.getOrNull() } }
                .awaitAll().filterNotNull().forEach { (k, v) -> after[k] = v }
        }
    }

    fun riskiest(itin: Itinerary) = Ranking.riskiest(itin, walkMps)

    /** The next ride if [t]'s connection is missed, if one is known. */
    fun nextIfMissed(t: Ranking.Transfer): Leg? = Combine.ridesAfter(t.next, after[t.next.rideKey].orEmpty(), 1).firstOrNull()

    private suspend fun resolve(p: Place): GeoPoint? =
        if (p.kind == PlaceKind.CURRENT_LOCATION) location.current() ?: location.last.value else p.point

    private fun request(cursor: String? = null): TransitousApi.PlanRequest? {
        val f = fromPoint ?: return null
        val t = toPoint ?: return null
        val s = store.data.value.settings
        val board = boardStart?.next.takeIf { onBoardTripId != null }
        val within = ride as? RideOffer.Within
        return TransitousApi.PlanRequest(
            from = f,
            to = t,
            time = when {
                // On board: from the next stop, when the timetable has the bus there.
                board != null -> (board.scheduledTime ?: Instant.now()).minusSeconds(60)
                timeMode == TimeMode.NOW -> null
                else -> time?.toInstant()
            },
            // A driver heading somewhere leaves at the time given, whichever way it was set.
            arriveBy = board == null && ride !is RideOffer.ToPlace && timeMode == TimeMode.ARRIVE,
            modes = s.modes,
            walkSpeedMps = s.walkSpeed.mps,
            maxWalkMinutes = s.maxWalkMinutes,
            wheelchair = s.wheelchair,
            pageCursor = cursor,
            fromStopId = board?.stopId,
            preTransitModes = within?.let { "CAR_DROPOFF" },
            maxPreTransitMinutes = within?.minutes,
            directModes = within?.let { "WALK,CAR" },
            maxDirectMinutes = within?.minutes,
        )
    }

    /** A refreshed "leave now" search keeps options whose live first bus can still be caught, if only by hurrying. */
    private fun stillCatchable(now: Instant): List<Itinerary> {
        if (timeMode != TimeMode.NOW || ride != null || onBoardTripId != null) return emptyList()
        return itineraries.filter { itin ->
            val first = itin.firstTransit ?: return@filter false
            val i = itin.legs.indexOf(first)
            Combine.onlyByHurrying(itin, i, first, now, rideLive[first.rideKey])
        }
    }

    fun search() {
        // Same trip, leaving now: a bus you could still run for shouldn't vanish on refresh.
        val keep = if (from.key == searchedFrom && to.key == searchedTo) stillCatchable(Instant.now()) else emptyList()
        val keptLive = keep.mapNotNull { it.firstTransit?.rideKey }.associateWith { rideLive[it] }
        searchedFrom = from.key
        searchedTo = to.key
        // A newer search replaces one still running (a lift's or on-board search can take a while).
        searchJob?.cancel()
        refining = false
        searchJob = scope.launch {
            // New results start at the top. On its own: scrolling waits for the list's first layout,
            // and a screen that doesn't show this list (Simple Maslul) would hold up the search.
            launch { listState.scrollToItem(0) }
            loading = true
            error = null
            errorKind = null
            itineraries = emptyList()
            walkOnly = null
            raw = emptyList()
            rides.clear()
            live.clear()
            rideLive.clear()
            searchedAt = Instant.now()
            canPage = false
            boardStart = null
            val boardTrip = onBoardTripId
            fromPoint = if (boardTrip != null) boardFrom(boardTrip) else resolve(from)
            toPoint = resolve(to)
            if (fromPoint == null || toPoint == null) {
                error = if (boardTrip != null) "Couldn't follow the ride you're on. Pick it again, or choose a starting point."
                    else "Your location isn't available. Allow location access or choose a starting point."
                errorKind = RouteError.LOCATION
                loading = false
                return@launch
            }
            keptLive.forEach { (k, c) -> if (c != null) rideLive[k] = c }
            // The user's own shuttles, looked up alongside (for plain trips).
            val shuttles = store.data.value.shuttles
            val shuttleReq = request()
            val shuttleOptions: Deferred<List<Itinerary>>? = if (shuttles.isNotEmpty() && ride == null && boardTrip == null && shuttleReq != null) {
                async { runCatching { repo.shuttleOptions(shuttleReq, shuttles) }.getOrDefault(emptyList()) }
            } else null
            runCatching { planWithRail() }
                .onSuccess { r ->
                    val found = r.itineraries + listOfNotNull(r.carOnly)
                    val ids = found.map { it.id }.toSet()
                    raw = found + keep.filter { it.id !in ids }
                    itineraries = ranked(raw)
                    walkOnly = r.walkOnly?.takeIf { it.durationSec < 45 * 60 && ride == null }
                    nextCursor = r.nextCursor
                    prevCursor = r.previousCursor
                    canPage = nextCursor != null || prevCursor != null
                    if (found.isEmpty() && walkOnly == null) {
                        error = "No routes found for this time."
                        errorKind = RouteError.NO_ROUTES
                    }
                }
                .onFailure { e ->
                    error = if (e is NoDriveException) e.message else "Couldn't load routes. Check your connection."
                    errorKind = if (e is NoDriveException) RouteError.NO_ROUTES else RouteError.NETWORK
                }
            loading = false
            shuttleOptions?.await()?.takeIf { it.isNotEmpty() }?.let { extra ->
                val ids = raw.map { it.id }.toSet()
                raw = raw + extra.filter { it.id !in ids }
                itineraries = ranked(raw)
            }
            refreshLive()
            findAlternatives()
            refreshLive()
            findRidesAfter()
        }
    }

    /**
     * Plans the trip; when trains are preferred, also asks for rail-only options, which the
     * planner may otherwise leave out for being slower than a bus.
     */
    private suspend fun planWithRail(): PlanResult = coroutineScope {
        val req = request()!!
        (ride as? RideOffer.ToPlace)?.let { r ->
            val options = refined { show -> repo.rideAlong(req, r.dest.point, { Ranking.score(it, false, walkMps, rail) }, show) }
            return@coroutineScope PlanResult(options, null, null, null)
        }
        boardStart?.takeIf { onBoardTripId != null }?.let { start ->
            val options = refined { show -> repo.planOnBoard(req, start, { Ranking.score(it, false, walkMps, rail) }, show) }
            return@coroutineScope PlanResult(options, null, null, null)
        }
        val railModes = req.modes - TransitMode.BUS
        val railOnly = if (rail != RailPreference.NONE && TransitMode.BUS in req.modes && railModes.isNotEmpty()) {
            async { runCatching { repo.plan(req.copy(modes = railModes)) }.getOrNull() }
        } else null
        val main = repo.plan(req)
        val extra = railOnly?.await()?.itineraries.orEmpty()
        val known = main.itineraries.map { it.id }.toSet()
        main.copy(itineraries = main.itineraries + extra.filter { it.id !in known })
    }

    fun more(later: Boolean) {
        val cursor = (if (later) nextCursor else prevCursor) ?: return
        scope.launch {
            loadingMore = true
            runCatching { repo.plan(request(cursor)!!) }.onSuccess { r ->
                val known = raw.map { it.id }.toSet()
                val fresh = r.itineraries.filter { it.id !in known }
                raw = if (later) raw + fresh else fresh + raw
                itineraries = ranked(raw)
                if (later) nextCursor = r.nextCursor else prevCursor = r.previousCursor
            }
            loadingMore = false
            refreshLive()
            findAlternatives()
            refreshLive()
            findRidesAfter()
        }
    }

    suspend fun refreshLive() {
        val now = Instant.now()
        coroutineScope {
            // Every bus of every ride (other lines, later ones, an earlier one running late),
            // so each card can show — and take — whichever comes first.
            itineraries.flatMap { it.transitLegs }
                .flatMap { it.options }
                .filter { it.start.isBefore(now.plusSeconds(90 * 60)) }
                .distinctBy { it.rideKey }
                .map { it to async { runCatching { if (repo.worthLive(it, now)) repo.legLive(it, now) else null }.getOrNull() } }
                .forEach { (l, d) -> d.await()?.let { c -> rideLive[l.rideKey] = c } }
        }
        // Leaving now: ride the bus that gets there first rather than the timetable's pick, and rank
        // by when the options are expected to get there (a late bus can turn the best into the worst).
        if (timeMode == TimeMode.NOW) {
            val liveOf = { l: Leg -> rideLive[l.rideKey] }
            val board = onBoardTripId
            itineraries = Combine.distinctRides(itineraries.map { itin ->
                // The bus you're on is the one you're on, whatever else comes.
                val keep = itin.legs.indices.filter { board != null && itin.legs[it].tripId == board }.toSet()
                Combine.pickBest(itin, now, liveOf, keep)
            })
                .sortedBy { Ranking.score(Combine.expected(it, liveOf), false, walkMps, rail) }
        }
        itineraries.forEach { itin -> itin.firstTransit?.let { rideLive[it.rideKey] }?.let { live[itin.id] = it } }
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

    /** Runs a search that reports options as it finds them, showing each batch while the rest load. */
    private suspend fun refined(run: suspend (show: (List<Itinerary>) -> Unit) -> List<Itinerary>): List<Itinerary> {
        refining = true
        try {
            return run { partial ->
                if (partial.isNotEmpty()) {
                    // Better options come in above the ones showing: stay at the top unless the user scrolled down.
                    val atTop = loading || listState.firstVisibleItemIndex <= 1
                    raw = partial
                    itineraries = ranked(partial)
                    loading = false
                    // After the new list is laid out, which would otherwise keep the old first card in view.
                    if (atTop) scope.launch { delay(150); listState.scrollToItem(0) }
                }
            }
        } finally {
            refining = false
        }
    }

    /** Where the trip you're on goes next, as the starting point (and named on the From field). */
    private suspend fun boardFrom(tripId: String): GeoPoint? {
        val here = runCatching { location.current() }.getOrNull() ?: location.last.value
        val start = runCatching { repo.onBoardStart(tripId, here) }.getOrNull() ?: return null
        boardStart = start
        from = from.copy(subtitle = "Next stop: ${start.next.name}")
        return start.next.point
    }

    fun useRide(r: RideOffer?) {
        ride = r
        search()
    }

    /** Asks where the driver is heading, then plans the lift along their way. */
    fun pickDriverDestination() {
        nav.push(SearchModel(nav, null, null, SearchField.TO, single = true, title = "Where's the driver going?") { p, _ ->
            nav.pop()
            useRide(RideOffer.ToPlace(p))
        })
    }

    /** Searches again only if the options sheet changed something: closing it untouched keeps the routes. */
    fun optionsClosed(before: Settings) {
        if (store.data.value.settings != before) search()
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
        if (onBoardTripId != null) return
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

    /** Shows the first vehicle's live location: its trip, with the map following it. */
    fun openLive(it: Itinerary) {
        val leg = it.firstTransit ?: return
        val id = leg.tripId ?: return
        nav.push(TripModel(nav, id, boardStopId = leg.from.stopId, alightStopId = leg.to.stopId, focusVehicle = true))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesScreen(model: RoutesModel) {
    val data by model.store.data.collectAsState()
    var showTime by remember { mutableStateOf(false) }
    var showRide by remember { mutableStateOf(false) }
    /** The options as they were when the sheet opened (null while it's closed). */
    var optionsBefore by remember { mutableStateOf<Settings?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { model.liveLoop() }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = Instant.now() } }
    // Coming back to a "leave now" search after a while re-runs it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Adding the observer replays ON_RESUME at once (e.g. coming back from a route's details);
        // only a real resume, such as returning to the app, should refresh.
        var replayed = false
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { if (replayed) model.refreshIfStale() else replayed = true }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val expectedOf = { itin: Itinerary -> Combine.expected(itin) { model.rideLive[it.rideKey] } }
    // Leaving now: options whose connection a late bus has made impossible aren't options (unless nothing else is left).
    val itineraries = if (model.timeMode != TimeMode.NOW) model.itineraries
        else model.itineraries.filter { Combine.connects(it, now) { l -> model.rideLive[l.rideKey] } }.ifEmpty { model.itineraries }
    val fastest = itineraries.minByOrNull { expectedOf(it).durationSec }?.id
    val leastWalk = itineraries.takeIf { it.size > 2 }?.minByOrNull { it.walkSec }?.id

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
                        IconButton(onClick = model::swap, enabled = model.onBoardTripId == null) { Icon(Icons.Rounded.SwapVert, "Swap") }
                        SaveMenu(model, data)
                    }
                }
                // Chips scroll (with end padding inside the scroll so the last one is never clipped);
                // Refresh is a fixed icon button so it always stays reachable.
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // On board, the trip starts when the bus gets to its next stop.
                        if (model.onBoardTripId == null) {
                            FilterChip(
                                selected = model.timeMode != TimeMode.NOW,
                                onClick = { showTime = true },
                                label = { Text(timeLabel(model.timeMode, model.time)) },
                                leadingIcon = { Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp)) },
                                modifier = Modifier.testTag("time_chip"),
                            )
                        }
                        // Getting a ride (טרמפ): an advanced tool, so only offered once it's switched on.
                        if (model.ride != null || (data.settings.rides && model.onBoardTripId == null)) {
                            FilterChip(
                                selected = model.ride != null,
                                onClick = { showRide = true },
                                label = { Text(rideLabel(model.ride), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Rounded.DirectionsCar, null, Modifier.size(18.dp)) },
                                modifier = Modifier.widthIn(max = 200.dp).testTag("ride_chip"),
                            )
                        }
                        val s = data.settings
                        val custom = s.modes.size < 6 || s.maxWalkMinutes != DEFAULT_MAX_WALK_MINUTES || s.wheelchair ||
                            s.walkSpeed != WalkSpeed.NORMAL || s.railPreference != RailPreference.NONE
                        FilterChip(
                            selected = custom,
                            onClick = { optionsBefore = data.settings },
                            label = { Text("Options") },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
                        )
                        if (s.wheelchair) {
                            FilterChip(selected = true, onClick = { optionsBefore = data.settings }, label = { Text("Accessible") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Accessible, null, Modifier.size(18.dp)) })
                        }
                    }
                    IconButton(onClick = model::refreshNow, enabled = !model.loading, modifier = Modifier.testTag("refresh_chip")) {
                        Icon(Icons.Rounded.Refresh, "Refresh")
                    }
                }
            }
        }

        if (model.refining && !model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("refining"))
        when {
            model.loading -> LoadingBox(text = "Finding routes…")
            model.error != null && model.itineraries.isEmpty() && model.walkOnly == null ->
                MessageBox("No routes", body = model.error, action = "Try again", onAction = model::search)
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("routes_list"),
                state = model.listState,
                contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
            ) {
                if (model.canPage) item {
                    TextButton(onClick = { model.more(false) }, enabled = !model.loadingMore,
                        modifier = Modifier.padding(start = 8.dp)) { Text("Earlier") }
                }
                model.walkOnly?.let { w ->
                    item(key = "walk") { WalkOnlyCard(w, now) { model.open(w) } }
                }
                items(itineraries, key = { it.id }) { itin ->
                    val tags = buildList {
                        // Ranked list: the first card is the recommended option.
                        if (itin === itineraries.first() && itineraries.size > 1) add("Best")
                        if (itin.id == fastest) add("Fastest")
                        if (itin.id == leastWalk && itin.id != fastest) add("Least walking")
                    }
                    // Transfer slack as the rides are expected to run, late ones included.
                    val risk = model.riskiest(expectedOf(itin))
                    ItineraryCard(itin, model.live[itin.id], now, tags, risk, risk?.let(model::nextIfMissed),
                        model.rideLive, onLiveClick = { model.openLive(itin) }, onBoardTripId = model.onBoardTripId) { model.open(itin) }
                }
                if (model.canPage) item {
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
    if (showRide) {
        RideSheet(
            model.ride,
            onDismiss = { showRide = false },
            onPickDestination = { showRide = false; model.pickDriverDestination() },
            onApply = { showRide = false; model.useRide(it) },
        )
    }
    optionsBefore?.let { before ->
        OptionsSheet(model, onDismiss = {
            optionsBefore = null
            model.optionsClosed(before)
        })
    }
}

/** Star menu: save the whole trip, and/or save the destination as a labelled favourite place. */
@Composable
private fun SaveMenu(model: RoutesModel, data: UserData) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<FavoriteDraft?>(null) }
    val tripSaved = data.savedTrips.any { it.from.key == model.from.key && it.to.key == model.to.key }
    // A trip from the bus you're on now isn't one to come back to.
    val canSaveTrip = model.from.kind != PlaceKind.ON_BOARD
    val canFavorite = model.to.kind != PlaceKind.CURRENT_LOCATION
    val favorite = data.favoriteFor(model.to)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("save_menu")) {
            Icon(
                if (tripSaved || favorite != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                "Save",
                tint = if (tripSaved || favorite != null) FavoriteGold else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canSaveTrip) {
                DropdownMenuItem(
                    text = { Text(if (tripSaved) "Remove saved trip" else "Save this trip") },
                    leadingIcon = { Icon(Icons.Rounded.SwapCalls, null) },
                    onClick = { open = false; model.toggleSaved() },
                )
            }
            if (canFavorite) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (favorite != null) "Edit favorite “${favorite.label}”" else "Save destination as favorite",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { Icon(favorite?.icon?.vector() ?: Icons.Rounded.StarBorder, null) },
                    onClick = { open = false; draft = FavoriteDraft.of(model.store, model.to) },
                )
            }
        }
    }
    FavoriteDialogHost(model.store, draft) { draft = null }
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
    /** The ride after [risk]'s, if that connection is missed. */
    missed: Leg? = null,
    rideLive: Map<String, LiveCall> = emptyMap(),
    /** Tapping the live arrival line shows the vehicle on a map. */
    onLiveClick: (() -> Unit)? = null,
    /** The trip the user is on, when planning from it: its ride reads "stay on". */
    onBoardTripId: String? = null,
    onClick: () -> Unit,
) {
    val x = LocalExtra.current
    val first = itin.firstTransit
    val liveOf: (Leg) -> LiveCall? = { o -> if (o.rideKey == first?.rideKey) live else rideLive[o.rideKey] }
    // Times follow the live vehicles: "leave in" the first one, arrival with the last one, so a late
    // bus in the plan doesn't leave the times on its timetable slot.
    val expected = Combine.expected(itin, liveOf)
    val leaveAt = expected.start
    val endAt = expected.end

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
                // The planned bus is live and close: you'd only make it by hurrying, so say so instead of "Missed".
                val hurry = first != null && Combine.onlyByHurrying(itin, itin.legs.indexOf(first), first, now, live)
                // Already on board there's nothing to leave for; nor once a driver's set off (they leave when you're in).
                val liftLeft = itin.legs.firstOrNull()?.fixed == true && leaveAt.isBefore(now.minusSeconds(120))
                if (onBoardTripId == null && !liftLeft) LeaveIn(leaveAt, now, live.freshness(now), hurry, first?.mode ?: TransitMode.BUS)
            }
            Spacer(Modifier.height(12.dp))
            // Buses that can still be caught on each ride (other lines, the next ones, a late earlier one),
            // including a live one that's close enough to run for.
            val choices = itin.legs.withIndex().filter { it.value.mode.isTransit }
                .associate { (i, l) -> l.rideKey to Combine.catchableOptions(itin, i, now, hurry = true, liveOf = liveOf).ifEmpty { listOf(l) } }
            val lineCount = { l: Leg -> choices[l.rideKey].orEmpty().distinctBy { Combine.lineKey(it) }.size }
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                val shown = itin.legs.filter { it.mode.isTransit || it.durationSec >= 60 }
                shown.forEachIndexed { i, leg ->
                    if (!leg.mode.isTransit) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(modeIcon(leg.mode), null, tint = x.subtle, modifier = Modifier.size(18.dp))
                            Text("${(leg.durationSec + 30) / 60}", style = MaterialTheme.typography.labelMedium, color = x.subtle)
                        }
                    } else if (lineCount(leg) > 1) {
                        CombinedBadges(choices[leg.rideKey].orEmpty())
                    } else {
                        LineBadge(leg.lineLabel, leg.mode, color = lineColor(leg))
                    }
                    if (i < shown.lastIndex) {
                        Icon(Icons.Rounded.ChevronRight, null, tint = x.subtle.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }
            // Where a lift drops you off: the answer this option is about.
            itin.legs.firstOrNull { it.mode == TransitMode.CAR }?.let { car ->
                Spacer(Modifier.height(12.dp))
                val place = RidePlanner.dropOffName(itin)
                HintLine(Icons.Rounded.DirectionsCar,
                    if (first == null && place == null) "Ride all the way · ${Fmt.duration(car.durationSec)}"
                    else "Get dropped off ${place?.let { "near $it" } ?: "on the way"}",
                    "${Fmt.time(car.end)} · ${Fmt.duration(car.durationSec)} by car")
            }
            val onBoard = onBoardTripId != null
            val stayOn = first?.tripId != null && first.tripId == onBoardTripId
            if (onBoard && first != null) {
                Spacer(Modifier.height(12.dp))
                if (stayOn) {
                    val then = itin.transitLegs.getOrNull(1)
                    HintLine(modeIcon(first.mode), "Stay on until ${first.to.name}",
                        "Get off ${Fmt.time(Combine.arrives(first, live))}" +
                            (then?.let { " · then ${rideName(it)}" } ?: first.to.platformLabel?.let { " · $it" } ?: ""))
                } else {
                    // Where the next ride boards (it may be a stop next to the one you get off at), at its expected time.
                    val boardAt = expected.legs.getOrNull(itin.legs.indexOf(first))?.start ?: first.start
                    HintLine(Icons.Rounded.Place, "Get off at the next stop",
                        "Then ${rideName(first)} from ${first.from.name} at ${Fmt.time(boardAt)}")
                }
            }
            // The first ride lists its next buses when there's a choice; later rides only when several lines go.
            val strips = itin.transitLegs.filter { l ->
                if (l === first) !onBoard && choices[l.rideKey].orEmpty().size > 1 else lineCount(l) > 1
            }
            if (first != null && first !in strips && !onBoard) {
                Spacer(Modifier.height(12.dp))
                LiveLine(first.lineLabel, first.mode, listOfNotNull(first.from.name, first.from.platformLabel).joinToString(" · "), live, first.start, now, onClick = onLiveClick)
            }
            strips.forEach { leg ->
                Spacer(Modifier.height(if (leg === first) 12.dp else 8.dp))
                val i = itin.legs.indexOf(leg)
                RideOptions(leg, choices[leg.rideKey].orEmpty(), now, relative = leg === first, liveOf) { o ->
                    Combine.onlyByHurrying(itin, i, o, now, liveOf(o), liveOf)
                }
            }
            if (risk != null) {
                Spacer(Modifier.height(8.dp))
                TransferWarning(risk, missed)
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

/** A boxed one-liner on a route card: "Get dropped off near X · 07:42". */
@Composable
private fun HintLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?) {
    val x = LocalExtra.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = x.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** "Bus 480", or just "Train" for a ride whose line is named after its mode. */
fun rideName(l: Leg): String = modeName(l.mode).let { m -> if (l.lineLabel.equals(m, ignoreCase = true)) m else "$m ${l.lineLabel}" }

fun rideLabel(r: RideOffer?): String = when (r) {
    null -> "Ride"
    is RideOffer.ToPlace -> "Ride to ${r.dest.name}"
    is RideOffer.Within -> "${r.minutes} min ride"
}

/**
 * Getting a lift for the first part of the trip: either the driver is heading somewhere (get out
 * wherever on their way is best), or they'll take you anywhere within a few minutes' drive.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RideSheet(current: RideOffer?, onDismiss: () -> Unit, onPickDestination: () -> Unit, onApply: (RideOffer?) -> Unit) {
    val x = LocalExtra.current
    var minutes by remember { mutableIntStateOf((current as? RideOffer.Within)?.minutes ?: 10) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("Getting a ride (טרמפ)?", style = MaterialTheme.typography.titleLarge)
            Text("Someone's driving you part of the way. Find the best place to get out and go on by bus or train.",
                style = MaterialTheme.typography.bodySmall, color = x.subtle)
            Spacer(Modifier.height(16.dp))
            Surface(
                onClick = onPickDestination,
                shape = RoundedCornerShape(16.dp),
                color = if (current is RideOffer.ToPlace) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag("ride_to_place"),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("The driver is going somewhere", style = MaterialTheme.typography.titleSmall)
                        Text(
                            (current as? RideOffer.ToPlace)?.let { "To ${it.dest.name} · tap to change" }
                                ?: "Choose where they're heading; get out wherever on the way is best",
                            style = MaterialTheme.typography.bodySmall, color = x.subtle,
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = x.subtle)
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (current is RideOffer.Within) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("The driver can take me up to…", style = MaterialTheme.typography.titleSmall)
                            Text("Get dropped off wherever is best within that drive", style = MaterialTheme.typography.bodySmall, color = x.subtle)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RideOffer.MINUTES.forEach { m ->
                            FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { onApply(RideOffer.Within(minutes)) }, shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().height(46.dp).testTag("ride_within")) {
                        Text("Find where to get out")
                    }
                }
            }
            if (current != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onApply(null) }, modifier = Modifier.fillMaxWidth()) { Text("No ride, plan as usual") }
            }
        }
    }
}

/** Departures listed per ride before the rest fold into "N more". */
const val SHOWN_RIDES = 3

/** "16 / 92 / 5": every line that makes a combined ride, soonest first. */
@Composable
fun CombinedBadges(rides: List<Leg>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        rides.distinctBy { it.lineLabel }.forEachIndexed { i, o ->
            if (i > 0) Text(" / ", style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.subtle)
            LineBadge(o.lineLabel, o.mode, color = lineColor(o), showIcon = i == 0)
        }
    }
}

/**
 * Compact next departures of every line in a combined ride:
 * "From Stop · [16] 4 min  [92] 7 min  [5] 12 min", live ones marked, and those you'd only
 * catch by hurrying marked "Hurry".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideOptions(
    leg: Leg,
    rides: List<Leg>,
    now: Instant,
    relative: Boolean,
    liveOf: (Leg) -> LiveCall?,
    hurry: (Leg) -> Boolean = { false },
) {
    val x = LocalExtra.current
    val perLine = HashMap<String, Int>()
    val all = rides.map { it to liveOf(it) }.sortedBy { (o, c) -> Combine.boards(o, c) }
        .filter { (o, _) -> perLine.merge(Combine.lineKey(o), 1, Int::plus)!! <= 2 }
    val lines = all.distinctBy { Combine.lineKey(it.first) }.size
    val options = all.take(SHOWN_RIDES)
    val more = all.size - options.size
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            // The user's own shuttle reads as one, not as a bus.
            (if (lines > 1) "$lines lines" else if (options.all { it.first.shuttleId != null }) "Next shuttles"
                else "Next ${TransitMode.vehiclesOf(options.map { it.first })}") + " from ${leg.from.name}",
            style = MaterialTheme.typography.bodySmall, color = x.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (o, c) ->
                val isLive = c?.status == LiveStatus.LIVE
                val t = if (isLive) c!!.best else o.start
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(o.lineLabel, o.mode, color = lineColor(o), showIcon = false)
                    Spacer(Modifier.width(5.dp))
                    val f = c.freshness(now)
                    if (isLive) { LiveSignal(f); Spacer(Modifier.width(4.dp)) }
                    Text(
                        if (relative) Fmt.relative(t, now) else Fmt.time(t),
                        style = MaterialTheme.typography.labelLarge.merge(Numeric),
                        color = if (isLive) freshnessColor(f) else MaterialTheme.colorScheme.onSurface,
                    )
                    if (hurry(o)) {
                        Spacer(Modifier.width(4.dp))
                        Text("Hurry", style = MaterialTheme.typography.labelMedium, color = x.late, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (more > 0) {
                Text("+$more more", style = MaterialTheme.typography.labelLarge, color = x.subtle,
                    modifier = Modifier.align(Alignment.CenterVertically).testTag("more_rides"))
            }
        }
    }
}

/**
 * "⚠ Tight transfer · 2 min to 143" — the connection may be missed if a bus runs off schedule —
 * and, when known, the ride after it: "If missed, next 143 at 14:32 (+12 min)".
 */
@Composable
fun TransferWarning(t: Ranking.Transfer, missed: Leg? = null, modifier: Modifier = Modifier) {
    val min = t.slackSec.coerceAtLeast(0) / 60
    Row(modifier, verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFF5A524), modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                "Tight transfer · ${if (min < 1) "under 1 min" else "$min min"} to catch ${t.next.lineLabel}" +
                    (if (t.walking) " after a ${Fmt.distance(t.walkM)} walk" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = LocalExtra.current.subtle,
            )
            if (missed != null) {
                Text(
                    "If missed, next ${missed.lineLabel} at ${Fmt.time(missed.start)} (+${Fmt.duration(missed.start.epochSecond - t.next.start.epochSecond)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalExtra.current.subtle,
                )
            }
        }
    }
}

@Composable
private fun LeaveIn(leaveAt: Instant, now: Instant, freshness: Freshness, hurry: Boolean = false, mode: TransitMode = TransitMode.BUS) {
    val min = (leaveAt.epochSecond - now.epochSecond + 30) / 60
    Column(horizontalAlignment = Alignment.End) {
        if (hurry && min <= 0) {
            Text("Hurry", style = MaterialTheme.typography.titleMedium, color = LocalExtra.current.late)
            Text("${mode.vehicle} is close", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.subtle)
        } else if (min <= 0 && min > -2) {
            Text("Go now", style = MaterialTheme.typography.titleMedium, color = LocalExtra.current.live)
        } else if (min in 1..90) {
            Text("leave in", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.subtle)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$min",
                    style = MaterialTheme.typography.headlineSmall.merge(Numeric),
                    color = freshnessColor(freshness),
                )
                Text(" min", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 3.dp))
            }
        } else if (min < 0) {
            Text("Missed", style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.subtle)
        }
    }
}

/** "● 116 in 4 min · 3 stops away, at X" style summary for a boarding. */
@Composable
fun LiveLine(
    label: String,
    mode: TransitMode,
    stop: String,
    live: LiveCall?,
    scheduled: Instant,
    now: Instant,
    /** Tapping a live arrival shows the vehicle on a map; ignored without a live vehicle. */
    onClick: (() -> Unit)? = null,
) {
    val x = LocalExtra.current
    val tappable = onClick != null && live?.status == LiveStatus.LIVE && live.vehicle != null
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (tappable) Modifier.clickable(onClickLabel = "Show on map") { onClick!!() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (live?.status) {
            LiveStatus.LIVE -> {
                val f = live.freshness(now)
                LiveSignal(f, size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    val eta = if (live.best.epochSecond - now.epochSecond < 45) "is arriving" else "in " + Fmt.relative(live.best, now)
                    val fc = freshnessColor(f)
                    Text(
                        buildAnnotatedString {
                            append("${modeName(mode)} $label ")
                            withStyle(SpanStyle(color = fc, fontWeight = FontWeight.SemiBold)) { append(eta) }
                            append(live.stopsAway?.takeIf { it in 1..30 }?.let { " · $it stop${if (it > 1) "s" else ""} away" } ?: "")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("at $stop", style = MaterialTheme.typography.bodySmall, color = x.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
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
                // Dragging from its start, by the screen's edge, mustn't count as the back gesture.
                modifier = Modifier.systemGestureExclusion(),
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
            Text("Prefer trains & light rail", style = MaterialTheme.typography.titleSmall)
            Text("Favor rail over buses, e.g. if buses make you feel sick", style = MaterialTheme.typography.bodySmall,
                color = LocalExtra.current.subtle)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("rail_preference")) {
                RailPreference.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = s.railPreference == r,
                        onClick = { model.store.updateSettings { it.copy(railPreference = r) } },
                        shape = SegmentedButtonDefaults.itemShape(i, RailPreference.entries.size),
                    ) { Text(r.label, maxLines = 1) }
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
