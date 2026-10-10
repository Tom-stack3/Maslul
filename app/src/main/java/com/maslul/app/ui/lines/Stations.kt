package com.maslul.app.ui.lines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maslul.app.data.GeoMath
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Place
import com.maslul.app.data.TransitMode
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LoadingBox
import com.maslul.app.ui.components.MessageBox
import com.maslul.app.ui.components.PlaceRow
import com.maslul.app.ui.components.SectionHeader
import com.maslul.app.ui.components.modeIcon
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.ModeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.maslul.app.i18n.S

/** The Stations tab: find a stop or station, and your favourite and nearby ones. */
class StationsHomeModel(nav: AppNav) : ScreenModel(nav) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Place>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var nearby by mutableStateOf<List<Place>>(emptyList())
    var nearbyLoading by mutableStateOf(false)
    private var job: Job? = null

    fun onQuery(q: String) {
        query = q
        job?.cancel()
        if (q.isBlank()) { results = emptyList(); loading = false; error = null; return }
        job = scope.launch {
            delay(350)
            loading = true
            error = null
            runCatching { repo.searchStops(q.trim(), location.last.value) }
                .onSuccess { results = it }
                .onFailure {
                    if (it is CancellationException) throw it
                    error = S.errSearchStations
                    results = emptyList()
                }
            loading = false
        }
    }

    fun loadNearby() {
        if (nearbyLoading) return
        scope.launch {
            nearbyLoading = true
            val here = location.current() ?: location.last.value
            here?.let { runCatching { repo.nearbyStops(it) }.getOrNull() }?.let { nearby = it.take(15) }
            nearbyLoading = false
        }
    }

    fun openStop(p: Place) = nav.push(StopModel(nav, p))
}

@Composable
fun StationsHomeScreen(model: StationsHomeModel) {
    val data by model.store.data.collectAsState()
    val here by model.location.last.collectAsState()
    LaunchedEffect(Unit) { if (model.nearby.isEmpty()) model.loadNearby() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(bottom = 10.dp)) {
            Text(S.tabStations, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 10.dp))
            SearchBox(model.query, model::onQuery, S.stationSearchHint, Modifier.testTag("station_search"))
        }
        if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (model.query.isNotBlank()) {
                if (model.error != null) item { MessageBox(S.somethingWrong, body = model.error) }
                else if (!model.loading && model.results.isEmpty()) {
                    item { MessageBox(S.noStationsFound, body = S.noStationsFoundHint) }
                }
                items(model.results, key = { "sr-${it.stopId}" }) { p -> StationRow(p, here) { model.openStop(p) } }
            } else {
                if (data.favoriteStops.isNotEmpty()) {
                    item { SectionHeader(S.favoriteStops) }
                    items(data.favoriteStops, key = { "fs-${it.stopId}" }) { p ->
                        PlaceRow(p.name, p.subtitle, icon = Icons.Rounded.Star, iconTint = ModeColors.Bus) { model.openStop(p) }
                    }
                }
                item { SectionHeader(S.nearbyStations, action = S.refresh, onAction = model::loadNearby) }
                if (model.nearby.isEmpty()) {
                    item {
                        if (model.nearbyLoading) LoadingBox()
                        else MessageBox(S.noStopsNearby, body = S.noStopsNearbyHint, icon = Icons.Rounded.NearMe)
                    }
                }
                items(model.nearby, key = { "nb-${it.stopId}" }) { p -> StationRow(p, here) { model.openStop(p) } }
            }
        }
    }
}

@Composable
private fun StationRow(p: Place, here: GeoPoint?, onClick: () -> Unit) {
    PlaceRow(
        p.name, p.subtitle, icon = modeIcon(TransitMode.BUS), iconTint = ModeColors.Bus,
        trailing = {
            here?.let { h ->
                Text(Fmt.distance(GeoMath.distance(h, p.point)), style = MaterialTheme.typography.labelMedium,
                    color = LocalExtra.current.subtle)
            }
        },
        onClick = onClick,
    )
}
