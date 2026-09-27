package com.maslul.app.ui.lines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.Place
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.Hairline
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.LiveDot
import com.maslul.app.ui.components.LoadingBox
import com.maslul.app.ui.components.MapMarker
import com.maslul.app.ui.components.MarkerKind
import com.maslul.app.ui.components.MessageBox
import com.maslul.app.ui.components.TransitMap
import com.maslul.app.ui.components.delayColor
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.nav.CurrentLocation
import com.maslul.app.ui.nav.RoutesModel
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.ModeColors
import com.maslul.app.ui.theme.Numeric
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

class StopModel(nav: AppNav, val place: Place) : ScreenModel(nav) {
    var entries by mutableStateOf<List<BoardEntry>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    suspend fun refresh() {
        val id = place.stopId ?: run { error = "Unknown stop"; loading = false; return }
        runCatching { repo.stopBoard(id) }
            .onSuccess { entries = it; error = null }
            .onFailure { if (entries.isEmpty()) error = "Couldn't load departures" }
        loading = false
    }

    suspend fun liveLoop() {
        while (true) {
            refresh()
            delay(30_000)
        }
    }

    fun open(e: BoardEntry) = nav.push(TripModel(nav, e.departure.tripId, boardStopId = place.stopId))
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(model.place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        model.place.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle) }
                    }
                    IconButton(onClick = model::toggleFavorite) {
                        Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite stop",
                            tint = if (fav) Color(0xFFF5A524) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TransitMap(
                Modifier.fillMaxWidth().height(160.dp),
                markers = listOf(MapMarker(model.place.point, ModeColors.Bus, MarkerKind.STOP, model.place.stopId)),
                user = model.location.last.collectAsState().value,
                initialCenter = model.place.point,
                initialZoom = 16.0,
            )
            if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
            when {
                model.loading && model.entries.isEmpty() -> LoadingBox(text = "Loading departures…")
                model.error != null -> MessageBox("No departures", body = model.error, action = "Retry",
                    onAction = { model.scope.launch { model.refresh() } })
                groups.isEmpty() -> MessageBox("No upcoming departures", body = "Nothing scheduled from this stop in the next hours.")
                else -> LazyColumn(Modifier.fillMaxSize().testTag("stop_board"), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(groups, key = { it.key }) { g ->
                        BoardRow(g, now) { model.open(g.first) }
                        Hairline(start = 20.dp)
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = model::directions,
            icon = { Icon(Icons.Rounded.Directions, null) },
            text = { Text("Directions") },
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        )
    }
}

@Composable
private fun BoardRow(g: BoardGroup, now: Instant, onClick: () -> Unit) {
    val x = LocalExtra.current
    val d = g.first.departure
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = 64.dp)) { LineBadge(d.lineLabel, d.mode, color = lineColor(d.mode, d.routeColor)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.headsign, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val later = g.entries.drop(1).take(2).joinToString("  ") { Fmt.time(it.live?.best ?: it.departure.scheduled) }
            Text(listOfNotNull(d.agency, later.takeIf { it.isNotBlank() }?.let { "then $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = x.subtle, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        val live = g.first.live
        val t = live?.best ?: d.scheduled
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                if (live?.status == LiveStatus.LIVE) {
                    LiveDot(delayColor(live.delaySec))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    Fmt.relative(t, now),
                    style = MaterialTheme.typography.titleMedium.merge(Numeric),
                    color = if (live?.status == LiveStatus.LIVE) delayColor(live.delaySec) else MaterialTheme.colorScheme.onSurface,
                )
            }
            val sub = when (live?.status) {
                LiveStatus.LIVE -> if (Math.abs(live.delaySec) >= 60) "sched. ${Fmt.time(d.scheduled)}" else "live"
                LiveStatus.UNTRACKED -> "no live data"
                else -> if (t.epochSecond - now.epochSecond < 3600) Fmt.time(t) else "scheduled"
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = x.subtle)
        }
    }
}
