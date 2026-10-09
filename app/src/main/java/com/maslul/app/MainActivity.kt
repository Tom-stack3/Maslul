package com.maslul.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.live.ActiveTrip
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.Tab
import com.maslul.app.ui.lines.LineModel
import com.maslul.app.ui.lines.LineScreen
import com.maslul.app.ui.lines.LinesHomeModel
import com.maslul.app.ui.lines.LinesHomeScreen
import com.maslul.app.ui.lines.StationsHomeModel
import com.maslul.app.ui.lines.StationsHomeScreen
import com.maslul.app.ui.lines.StopModel
import com.maslul.app.ui.lines.StopScreen
import com.maslul.app.ui.lines.TripModel
import com.maslul.app.ui.lines.TripScreen
import com.maslul.app.ui.nav.NavHomeModel
import com.maslul.app.ui.nav.NavHomeScreen
import com.maslul.app.ui.nav.OnBoardModel
import com.maslul.app.ui.nav.OnBoardScreen
import com.maslul.app.ui.nav.ShuttleEditModel
import com.maslul.app.ui.nav.ShuttleEditScreen
import com.maslul.app.ui.nav.ShuttlesModel
import com.maslul.app.ui.nav.ShuttlesScreen
import com.maslul.app.ui.nav.PickOnMapModel
import com.maslul.app.ui.nav.PickOnMapScreen
import com.maslul.app.ui.nav.RouteDetailModel
import com.maslul.app.ui.nav.RouteDetailScreen
import com.maslul.app.ui.nav.RoutesModel
import com.maslul.app.ui.nav.RoutesScreen
import com.maslul.app.ui.nav.SearchModel
import com.maslul.app.ui.nav.SearchScreen
import com.maslul.app.ui.nav.SettingsModel
import com.maslul.app.ui.nav.SettingsScreen
import com.maslul.app.ui.simple.SimpleHomeModel
import com.maslul.app.ui.simple.SimpleHomeScreen
import com.maslul.app.ui.simple.SimpleRoutesModel
import com.maslul.app.ui.simple.SimpleRoutesScreen
import com.maslul.app.ui.simple.SimpleSearchModel
import com.maslul.app.ui.simple.SimpleSearchScreen
import com.maslul.app.ui.simple.SimpleSettingsModel
import com.maslul.app.ui.simple.SimpleSettingsScreen
import com.maslul.app.ui.simple.SimpleTripModel
import com.maslul.app.ui.simple.SimpleTripScreen
import com.maslul.app.ui.simple.simpleLanguageOrDefault
import com.maslul.app.ui.theme.MaslulTheme

class MainActivity : ComponentActivity() {
    private val nav: AppNav by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val data by MaslulApp.instance.store.data.collectAsState()
            val simple = data.settings.simpleMode
            val simpleLanguage = data.settings.simpleLanguageOrDefault()
            LaunchedEffect(simple, simpleLanguage) {
                // Simple Maslul asks for stop and line names in its language; the full app keeps the feed's.
                MaslulApp.instance.repo.language = if (simple) simpleLanguage.code else null
                if (simple != (nav.tab == Tab.SIMPLE)) nav.selectTab(if (simple) Tab.SIMPLE else Tab.NAVIGATE)
            }
            MaslulTheme(data.settings.theme) {
                AppShell(nav)
            }
        }
    }
}

@Composable
fun AppShell(nav: AppNav) {
    BackHandler(enabled = !nav.atRoot || (nav.tab != Tab.NAVIGATE && nav.tab != Tab.SIMPLE)) {
        if (!nav.pop()) nav.selectTab(Tab.NAVIGATE)
    }
    val session by ActiveTrip.session.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = nav.current,
                transitionSpec = {
                    val dir = nav.depthChange
                    if (dir == 0) fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                    else (slideInHorizontally(tween(260)) { it * dir / 3 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it * dir / 4 } + fadeOut(tween(160)))
                },
                label = "screens",
            ) { screen ->
                // Gives every screen the theme's content colour (plain Text would default to black).
                Surface(color = MaterialTheme.colorScheme.background) { ScreenHost(screen) }
            }

            // Ongoing live trip, reachable from anywhere.
            val s = session
            androidx.compose.animation.AnimatedVisibility(
                visible = s != null && nav.current !is RouteDetailModel,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) {
                if (s != null) {
                    Surface(
                        onClick = {
                            val legs = s.itinerary.legs
                            val from = legs.first().from.let { Place(it.name.ifBlank { "Start" }, it.lat, it.lon, kind = PlaceKind.PIN) }
                            val to = legs.last().to.let { Place(s.destination, it.lat, it.lon, kind = PlaceKind.PIN) }
                            nav.push(RouteDetailModel(nav, s.itinerary, from, to))
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Navigation, null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.state?.title ?: "Live directions", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.state?.text ?: s.destination, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (nav.atRoot && nav.tab != Tab.SIMPLE) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = nav.tab == Tab.NAVIGATE,
                    onClick = { nav.selectTab(Tab.NAVIGATE) },
                    icon = { Icon(Icons.Rounded.Directions, null) },
                    label = { Text("Navigate") },
                    modifier = Modifier.testTag("tab_navigate"),
                )
                NavigationBarItem(
                    selected = nav.tab == Tab.LINES,
                    onClick = { nav.selectTab(Tab.LINES) },
                    icon = { Icon(Icons.Rounded.Timeline, null) },
                    label = { Text("Lines") },
                    modifier = Modifier.testTag("tab_lines"),
                )
                NavigationBarItem(
                    selected = nav.tab == Tab.STATIONS,
                    onClick = { nav.selectTab(Tab.STATIONS) },
                    icon = { Icon(Icons.Rounded.Place, null) },
                    label = { Text("Stations") },
                    modifier = Modifier.testTag("tab_stations"),
                )
            }
        }
    }
}

@Composable
private fun ScreenHost(screen: ScreenModel) {
    when (screen) {
        is NavHomeModel -> NavHomeScreen(screen)
        is SearchModel -> SearchScreen(screen)
        is PickOnMapModel -> PickOnMapScreen(screen)
        is RoutesModel -> RoutesScreen(screen)
        is RouteDetailModel -> RouteDetailScreen(screen)
        is SettingsModel -> SettingsScreen(screen)
        is OnBoardModel -> OnBoardScreen(screen)
        is ShuttlesModel -> ShuttlesScreen(screen)
        is ShuttleEditModel -> ShuttleEditScreen(screen)
        is LinesHomeModel -> LinesHomeScreen(screen)
        is StationsHomeModel -> StationsHomeScreen(screen)
        is LineModel -> LineScreen(screen)
        is StopModel -> StopScreen(screen)
        is TripModel -> TripScreen(screen)
        is SimpleHomeModel -> SimpleHomeScreen(screen)
        is SimpleSearchModel -> SimpleSearchScreen(screen)
        is SimpleRoutesModel -> SimpleRoutesScreen(screen)
        is SimpleTripModel -> SimpleTripScreen(screen)
        is SimpleSettingsModel -> SimpleSettingsScreen(screen)
        else -> Text("Unknown screen")
    }
}
