package com.maslul.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maslul.app.data.BoardCandidate
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.TransitMode
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.LiveSignal
import com.maslul.app.ui.components.LoadingBox
import com.maslul.app.ui.components.MessageBox
import com.maslul.app.ui.components.freshnessColor
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.components.modeIcon
import com.maslul.app.ui.components.modeName
import com.maslul.app.ui.components.rememberNow
import com.maslul.app.ui.lines.SearchBox
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.data.Freshness
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import com.maslul.app.i18n.S

/**
 * "Which ride are you on?": the buses and trains around the user, live ones nearest first,
 * to start a trip from. [onPicked] gets an [PlaceKind.ON_BOARD] place for the chosen ride.
 */
class OnBoardModel(nav: AppNav, private val onPicked: (Place) -> Unit) : ScreenModel(nav) {
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var candidates by mutableStateOf<List<BoardCandidate>>(emptyList())
    var query by mutableStateOf("")
    var mode by mutableStateOf<TransitMode?>(null)
    /** The candidate whose timetable is being looked up after a tap. */
    var picking by mutableStateOf<String?>(null)
    private var here: GeoPoint? = null
    /** Trips of live vehicles not on the timetable around you, by [BoardCandidate.key]; null when it has none. */
    private val resolved = HashMap<String, String?>()
    private var resolving: Job? = null

    init { refresh() }

    fun refresh(quiet: Boolean = false) {
        scope.launch {
            if (!quiet) loading = true
            error = null
            val p = runCatching { location.current() }.getOrNull() ?: location.last.value
            if (p == null) {
                error = S.errOnBoardLocation
                loading = false
                return@launch
            }
            here = p
            runCatching { repo.boardCandidates(p) }
                .onSuccess { candidates = tidy(it); resolveLoose() }
                .onFailure { if (candidates.isEmpty()) error = S.errOnBoardNetwork }
            loading = false
        }
    }

    /**
     * Live vehicles the timetable around you didn't account for, with what's known of their trips:
     * found ones get it, ones without one (often a bus that just ended its trip here) are left out,
     * and one that turns out to be a ride already listed from the timetable replaces that row.
     */
    private fun tidy(list: List<BoardCandidate>): List<BoardCandidate> {
        val withTrips = list.mapNotNull { c ->
            if (c.tripId != null || c.key !in resolved) c else resolved[c.key]?.let { c.copy(tripId = it) }
        }
        val live = withTrips.filter { it.vehicle != null && it.tripId != null }.map { it.tripId }.toSet()
        return withTrips.filter { it.vehicle != null || it.tripId !in live }.distinctBy { it.tripId ?: it.key }
    }

    /** Looks up the trips of live-only rows in the background, nearest first, so every row can be picked. */
    private fun resolveLoose() {
        resolving?.cancel()
        resolving = scope.launch {
            for (c in candidates.filter { it.tripId == null && it.key !in resolved }.take(12)) {
                resolved[c.key] = runCatching { repo.boardTripId(c) }.getOrNull()
                candidates = tidy(candidates)
            }
        }
    }

    /** Vehicles move along with you: keep the list current. */
    suspend fun loop() {
        while (true) {
            delay(30_000)
            if (picking == null) refresh(quiet = true)
        }
    }

    val modes: List<TransitMode> get() = candidates.map { it.mode }.distinct()

    val shown: List<BoardCandidate>
        get() {
            val q = query.trim()
            return candidates
                .filter { mode == null || it.mode == mode }
                .filter { q.isEmpty() || it.lineLabel.contains(q, ignoreCase = true) || it.headsign.contains(q, ignoreCase = true) }
        }

    fun pick(c: BoardCandidate) {
        if (picking != null) return
        scope.launch {
            picking = c.key
            val tripId = c.tripId ?: resolved[c.key] ?: runCatching { repo.boardTripId(c) }.getOrNull()
            picking = null
            val p = here
            if (tripId == null || p == null) {
                error = S.errOnBoardTimetable(rideName(c.mode, c.lineLabel))
                return@launch
            }
            onPicked(Place(boardName(c), p.lat, p.lon, kind = PlaceKind.ON_BOARD, tripId = tripId))
        }
    }

    companion object {
        /** "On bus 480 to Haifa", "On the train to Nahariya". */
        fun boardName(c: BoardCandidate): String {
            val line = c.lineLabel.takeUnless { it.equals(modeName(c.mode), ignoreCase = true) || it == "—" }
            return S.onBoardName(c.mode, line, c.headsign.takeIf { it.isNotBlank() })
        }
    }
}

@Composable
fun OnBoardScreen(model: OnBoardModel) {
    val x = LocalExtra.current
    val now = rememberNow(15_000)
    LaunchedEffect(Unit) { model.loop() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Column(Modifier.statusBarsPadding().padding(bottom = 10.dp)) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, S.back) }
                    Column(Modifier.weight(1f)) {
                        Text(S.whichRide, style = MaterialTheme.typography.titleLarge)
                        Text(S.whichRideHint, style = MaterialTheme.typography.bodySmall, color = x.subtle)
                    }
                    IconButton(onClick = { model.refresh() }, enabled = !model.loading) { Icon(Icons.Rounded.Refresh, S.refresh) }
                }
                SearchBox(model.query, { model.query = it }, S.lineOrDestination, Modifier.testTag("onboard_filter"))
                if (model.modes.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        model.modes.forEach { m ->
                            FilterChip(
                                selected = model.mode == m,
                                onClick = { model.mode = if (model.mode == m) null else m },
                                label = { Text(modeName(m)) },
                                leadingIcon = { Icon(modeIcon(m), null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }
        }
        val shown = model.shown
        when {
            model.loading -> LoadingBox(text = S.lookingForRides)
            model.candidates.isEmpty() && model.error != null ->
                MessageBox(S.cantTellRide, body = model.error, action = S.tryAgain, onAction = { model.refresh() })
            model.candidates.isEmpty() -> MessageBox(
                S.noRidesAround,
                body = S.noRidesAroundHint,
                icon = Icons.Rounded.DirectionsBus, action = S.refresh, onAction = { model.refresh() },
            )
            else -> LazyColumn(Modifier.fillMaxSize().testTag("onboard_list")) {
                model.error?.let { e ->
                    item { Text(e, style = MaterialTheme.typography.bodySmall, color = x.late, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                }
                if (shown.isEmpty()) item {
                    Text(S.nothingMatchesRides, style = MaterialTheme.typography.bodyMedium,
                        color = x.subtle, modifier = Modifier.padding(20.dp))
                }
                items(shown, key = { it.key }) { c -> CandidateRow(c, now, model.picking == c.key) { model.pick(c) } }
                item {
                    Text(
                        S.onBoardFooter,
                        style = MaterialTheme.typography.labelSmall, color = x.subtle,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(c: BoardCandidate, now: Instant, busy: Boolean, onClick: () -> Unit) {
    val x = LocalExtra.current
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LineBadge(c.lineLabel, c.mode, color = lineColor(c.mode, c.routeColor, c.agencyId, c.agencyName))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.headsign.takeIf { it.isNotBlank() }?.let { S.toHeadsign(it) } ?: modeName(c.mode),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val v = c.vehicle
                if (v != null) {
                    val f = Freshness.of(v.recordedAt, now)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveSignal(f)
                        Spacer(Modifier.width(4.dp))
                        Text(S.liveFromYou(Fmt.distance(c.distanceM)), style = MaterialTheme.typography.bodySmall, color = freshnessColor(f))
                    }
                } else {
                    Text(
                        listOfNotNull(c.stopName?.let { S.dueAt(it) }, c.stopTime?.let(Fmt::time)).joinToString(" · ")
                            .ifEmpty { S.timetableWord } + " · " + S.noLiveData,
                        style = MaterialTheme.typography.bodySmall, color = x.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}
