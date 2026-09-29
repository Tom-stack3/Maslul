package com.maslul.app.ui.nav

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.LocationRepo
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.SavedTrip
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.MapController
import com.maslul.app.ui.components.MapMarker
import com.maslul.app.ui.components.MarkerKind
import com.maslul.app.ui.components.PlaceRow
import com.maslul.app.ui.components.SectionHeader
import com.maslul.app.ui.components.TransitMap
import com.maslul.app.ui.lines.StopModel
import com.maslul.app.ui.theme.LocalExtra
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val CurrentLocation = Place("Current location", 0.0, 0.0, kind = PlaceKind.CURRENT_LOCATION)

class NavHomeModel(nav: AppNav) : ScreenModel(nav) {
    var stops by mutableStateOf<List<Place>>(emptyList())
    private var stopsJob: Job? = null
    private var lastStopsCenter: GeoPoint? = null

    fun onCameraIdle(center: GeoPoint) {
        val last = lastStopsCenter
        if (last != null && com.maslul.app.data.GeoMath.distance(last, center) < 250) return
        stopsJob?.cancel()
        stopsJob = scope.launch {
            delay(300)
            runCatching { repo.nearbyStops(center) }.onSuccess {
                stops = it
                lastStopsCenter = center
            }
        }
    }

    fun planTo(to: Place, from: Place = CurrentLocation) {
        store.addRecent(to)
        nav.push(RoutesModel(nav, from, to))
    }

    fun openSearch() {
        nav.push(
            SearchModel(nav, from = CurrentLocation, to = null, editing = SearchField.TO) { from, to ->
                nav.replace(RoutesModel(nav, from, to!!))
            },
        )
    }

    fun pickHomeOrWork(isHome: Boolean) {
        nav.push(
            SearchModel(nav, from = null, to = null, editing = SearchField.TO, single = true,
                title = if (isHome) "Set home" else "Set work") { p, _ ->
                if (isHome) store.setHome(p) else store.setWork(p)
                nav.pop()
            },
        )
    }

    fun openStop(p: Place) = nav.push(StopModel(nav, p))

    /** Place whose favourite label is being edited. */
    var favoriteDraft by mutableStateOf<FavoriteDraft?>(null)

    fun editFavorite(p: Place) { favoriteDraft = FavoriteDraft.of(store, p) }

    fun addFavorite() {
        nav.push(
            SearchModel(nav, from = null, to = null, editing = SearchField.TO, single = true, title = "Add favorite") { p, _ ->
                nav.pop()
                editFavorite(p)
            },
        )
    }
}

@Composable
fun NavHomeScreen(model: NavHomeModel) {
    val data by model.store.data.collectAsState()
    val user by model.location.last.collectAsState()
    val controller = remember { MapController() }
    var askedPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.scope.launch { model.location.current()?.let { p -> controller.moveTo(p, 15.0) } }
    }
    LaunchedEffect(Unit) {
        if (model.location.hasPermission()) {
            model.location.current()?.let { if (user == null) controller.moveTo(it, 15.0) }
        } else if (!askedPermission) {
            askedPermission = true
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    Box(Modifier.fillMaxSize()) {
        TransitMap(
            modifier = Modifier.fillMaxSize(),
            markers = model.stops.map { MapMarker(it.point, Color(0xFF6B7280), MarkerKind.STOP_SMALL, it.stopId) },
            user = user,
            initialCenter = user ?: LocationRepo.DEFAULT,
            initialZoom = 15.0,
            controller = controller,
            onMarkerClick = { id -> model.stops.firstOrNull { it.stopId == id }?.let(model::openStop) },
            onLongPress = { p -> model.planTo(Place("Dropped pin", p.lat, p.lon, kind = PlaceKind.PIN)) },
            onCameraIdle = model::onCameraIdle,
            compassModifier = Modifier.statusBarsPadding().padding(top = 84.dp, end = 16.dp),
        )

        // Search card
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Surface(
                onClick = model::openSearch,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 18.dp, end = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Where to?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { model.nav.push(SettingsModel(model.nav)) }) {
                        Icon(Icons.Rounded.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Bottom panel
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            SmallFloatingActionButton(
                onClick = {
                    model.scope.launch {
                        (model.location.current() ?: user)?.let { controller.moveTo(it, 15.5) }
                            ?: permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.End).padding(end = 16.dp, bottom = 12.dp),
            ) { Icon(Icons.Rounded.MyLocation, "My location") }

            Surface(
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                shadowElevation = 12.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                    Box(
                        Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally).size(36.dp, 4.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    // Home and Work share the width; labelled favourites scroll beneath them.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        QuickPlace("Home", data.home?.name ?: "Set location", Icons.Rounded.Home,
                            modifier = Modifier.weight(1f),
                            onClick = { data.home?.let(model::planTo) ?: model.pickHomeOrWork(true) },
                            onEdit = { model.pickHomeOrWork(true) })
                        QuickPlace("Work", data.work?.name ?: "Set location", Icons.Rounded.Work,
                            modifier = Modifier.weight(1f),
                            onClick = { data.work?.let(model::planTo) ?: model.pickHomeOrWork(false) },
                            onEdit = { model.pickHomeOrWork(false) })
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        data.favoritePlaces.forEach { f ->
                            FavoriteChip(f.label, f.icon.vector(),
                                onClick = { model.planTo(f.place) },
                                onLongClick = { model.editFavorite(f.place) })
                        }
                        AddFavoriteChip(if (data.favoritePlaces.isEmpty()) "Add favorite place" else "Add", model::addFavorite)
                    }
                    if (data.savedTrips.isNotEmpty()) {
                        SectionHeader("Saved trips")
                        data.savedTrips.forEach { t -> SavedTripRow(t) { model.planTo(t.to, t.from) } }
                    }
                    SectionHeader("Recent")
                    if (data.recents.isEmpty()) {
                        Text(
                            "Search for a place, or long-press the map to get directions there.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalExtra.current.subtle,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    data.recents.take(6).forEach { p ->
                        PlaceRow(
                            title = p.name,
                            subtitle = p.subtitle,
                            icon = Icons.Rounded.History,
                            trailing = {
                                IconButton(onClick = { model.store.removeRecent(p) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Close, "Remove", tint = LocalExtra.current.subtle, modifier = Modifier.size(18.dp))
                                }
                            },
                            onLongClick = { model.editFavorite(p) },
                            onClick = { model.planTo(p) },
                        )
                    }
                    Spacer(Modifier.navigationBarsPadding().height(4.dp))
                }
            }
        }
    }
    FavoriteDialogHost(model.store, model.favoriteDraft) { model.favoriteDraft = null }
}

@Composable
private fun QuickPlace(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 12.dp, end = if (onEdit != null) 2.dp else 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Edit, "Edit $title", tint = LocalExtra.current.subtle, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteChip(label: String, icon: ImageVector, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).widthIn(max = 180.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onLongClick = onLongClick, onLongClickLabel = "Edit favorite", onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddFavoriteChip(text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(start = 10.dp, end = 14.dp).testTag("add_favorite"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

@Composable
private fun SavedTripRow(t: SavedTrip, onClick: () -> Unit) {
    PlaceRow(
        title = t.label ?: t.to.name,
        subtitle = "From ${t.from.name}",
        icon = Icons.Rounded.SwapCalls,
        iconTint = MaterialTheme.colorScheme.primary,
        onClick = onClick,
    )
}
