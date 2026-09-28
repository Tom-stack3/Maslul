package com.maslul.app.ui.lines

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maslul.app.data.GeoMath
import com.maslul.app.data.Line
import com.maslul.app.data.NearbyLine
import com.maslul.app.data.LineRoute
import com.maslul.app.data.Place
import com.maslul.app.data.TransitMode
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.MessageBox
import com.maslul.app.ui.components.PlaceRow
import com.maslul.app.ui.components.SectionHeader
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.components.modeIcon
import com.maslul.app.ui.components.modeName
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.ModeColors
import com.maslul.app.ui.theme.Numeric
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LinesHomeModel(nav: AppNav) : ScreenModel(nav) {
    var query by mutableStateOf("")
    var mode by mutableStateOf<TransitMode?>(null)
    var results by mutableStateOf<List<Line>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var nearby by mutableStateOf<List<Place>>(emptyList())
    var nearbyLoading by mutableStateOf(false)
    var nearbyLines by mutableStateOf<List<NearbyLine>>(emptyList())
    var linesLoading by mutableStateOf(false)
    private var job: Job? = null

    val showsResults get() = query.isNotBlank() || mode == TransitMode.TRAIN || mode == TransitMode.LIGHT_RAIL

    fun onQuery(q: String) {
        query = q
        search(debounce = true)
    }

    fun onMode(m: TransitMode?) {
        mode = if (mode == m) null else m
        search(debounce = false)
    }

    private fun search(debounce: Boolean) {
        job?.cancel()
        if (!showsResults) { results = emptyList(); loading = false; error = null; return }
        job = scope.launch {
            if (debounce) delay(350)
            loading = true
            error = null
            runCatching { repo.searchLines(query, mode) }
                .onSuccess { results = it }
                .onFailure { error = "Couldn't load lines. Check your connection."; results = emptyList() }
            loading = false
        }
    }

    fun loadNearby() {
        if (nearbyLoading) return
        scope.launch {
            nearbyLoading = true
            val here = location.current() ?: location.last.value
            val stops = here?.let { runCatching { repo.nearbyStops(it) }.getOrNull() }
            if (stops != null) nearby = stops.take(12)
            nearbyLoading = false
            if (here != null && stops != null) {
                linesLoading = true
                runCatching { repo.nearbyLines(here, stops) }.onSuccess { nearbyLines = it }
                linesLoading = false
            }
        }
    }

    fun openLine(l: NearbyLine, onFail: () -> Unit) {
        scope.launch {
            runCatching { repo.lineRoute(l.departure.routeId) }.getOrNull()?.let(::open) ?: onFail()
        }
    }

    fun open(route: LineRoute) = nav.push(LineModel(nav, route))
    fun openStop(p: Place) = nav.push(StopModel(nav, p))
}

@Composable
fun LinesHomeScreen(model: LinesHomeModel) {
    val data by model.store.data.collectAsState()
    val here by model.location.last.collectAsState()
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { model.loadNearby() }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = Instant.now() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
            Text("Lines", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 10.dp))
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp).height(50.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = LocalExtra.current.subtle)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (model.query.isEmpty()) {
                        Text("Line number or name, e.g. 480", color = LocalExtra.current.subtle, style = MaterialTheme.typography.bodyLarge)
                    }
                    BasicTextField(
                        value = model.query,
                        onValueChange = model::onQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth().testTag("line_search"),
                    )
                }
                if (model.query.isNotEmpty()) {
                    IconButton(onClick = { model.onQuery("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Close, "Clear", Modifier.size(18.dp))
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(TransitMode.BUS, TransitMode.TRAIN, TransitMode.LIGHT_RAIL, TransitMode.CABLE_CAR).forEach { m ->
                    FilterChip(
                        selected = model.mode == m,
                        onClick = { model.onMode(m) },
                        label = { Text(modeName(m)) },
                        leadingIcon = { Icon(modeIcon(m), null, Modifier.size(18.dp), tint = ModeColors.of(m)) },
                    )
                }
            }
        }
        if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (model.showsResults) {
                if (model.error != null) item { MessageBox("Something went wrong", body = model.error) }
                else if (!model.loading && model.results.isEmpty()) {
                    item { MessageBox("No lines found", body = "Search by line number (e.g. 1, 480, 5א) or by a city or station name.") }
                }
                items(model.results, key = { "${it.main.operatorRef}-${it.main.mkt}-${it.main.gtfsRouteId}" }) { line ->
                    LineRow(line.main, variants = line.routes.size) { model.open(line.main) }
                }
            } else {
                if (data.favoriteLines.isNotEmpty()) {
                    item { SectionHeader("Favorite lines") }
                    items(data.favoriteLines, key = { "fav-${it.operatorRef}-${it.mkt}" }) { r -> LineRow(r, favorite = true) { model.open(r) } }
                }
                if (data.favoriteStops.isNotEmpty()) {
                    item { SectionHeader("Favorite stops") }
                    items(data.favoriteStops, key = { "fs-${it.stopId}" }) { p ->
                        PlaceRow(p.name, p.subtitle, icon = Icons.Rounded.Star, iconTint = ModeColors.Bus) { model.openStop(p) }
                    }
                }
                val lines = model.nearbyLines.filter { model.mode == null || it.departure.mode == model.mode }
                if (lines.isNotEmpty() || model.linesLoading) {
                    item { SectionHeader("Nearby lines") }
                    if (lines.isEmpty()) item { com.maslul.app.ui.components.LoadingBox() }
                    items(lines.take(10), key = { "nl-${it.departure.routeId}-${it.departure.headsign}" }) { l ->
                        NearbyLineRow(l, now) {
                            model.openLine(l) { Toast.makeText(ctx, "Line details unavailable", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
                item { SectionHeader("Nearby stations", action = "Refresh", onAction = model::loadNearby) }
                if (model.nearby.isEmpty()) {
                    item {
                        if (model.nearbyLoading) com.maslul.app.ui.components.LoadingBox()
                        else MessageBox("No stops nearby", body = "Allow location access to see stations around you.", icon = Icons.Rounded.NearMe)
                    }
                }
                items(model.nearby, key = { "nb-${it.stopId}" }) { p ->
                    PlaceRow(
                        p.name, p.subtitle, icon = modeIcon(TransitMode.BUS), iconTint = ModeColors.Bus,
                        trailing = {
                            here?.let { h ->
                                Text(Fmt.distance(GeoMath.distance(h, p.point)), style = MaterialTheme.typography.labelMedium,
                                    color = LocalExtra.current.subtle)
                            }
                        },
                    ) { model.openStop(p) }
                }
            }
        }
    }
}

@Composable
private fun NearbyLineRow(l: NearbyLine, now: Instant, onClick: () -> Unit) {
    val d = l.departure
    val x = LocalExtra.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = 64.dp)) { LineBadge(d.lineLabel, d.mode, color = lineColor(d)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("to ${d.headsign}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Fmt.distance(l.distanceM)} · ${l.stop.name}", style = MaterialTheme.typography.bodySmall, color = x.subtle,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.relative(l.next.first(), now), style = MaterialTheme.typography.titleSmall.merge(Numeric),
                color = MaterialTheme.colorScheme.onSurface)
            l.next.getOrNull(1)?.let {
                Text("then ${Fmt.time(it)}", style = MaterialTheme.typography.labelSmall.merge(Numeric), color = x.subtle)
            }
        }
    }
}

@Composable
fun LineRow(r: LineRoute, variants: Int = 1, favorite: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = 64.dp)) { LineBadge(r.label, r.mode, color = lineColor(r)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (r.destination.isNotBlank()) {
                Text(r.destination, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.origin, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(r.longName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.agency, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.subtle)
                if (variants > 2) {
                    Text("  ·  $variants variants", style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.subtle)
                }
            }
        }
        if (favorite) Icon(Icons.Rounded.Star, null, tint = androidx.compose.ui.graphics.Color(0xFFF5A524), modifier = Modifier.size(18.dp))
    }
}
