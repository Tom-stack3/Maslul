package com.maslul.app.ui.nav

import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.Canvas
import com.maslul.app.R
import com.maslul.app.BuildConfig
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Code
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material3.Switch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.maslul.app.data.ThemeMode
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.Tab
import com.maslul.app.ui.components.PlaceRow
import com.maslul.app.ui.components.SectionHeader
import com.maslul.app.ui.theme.LocalExtra

private const val SOURCE_URL = "https://github.com/Tom-stack3/Maslul"

class SettingsModel(nav: AppNav) : ScreenModel(nav) {
    var favoriteDraft by mutableStateOf<FavoriteDraft?>(null)

    fun addFavorite() {
        nav.push(
            SearchModel(nav, null, null, SearchField.TO, single = true, title = "Add favorite") { p, _ ->
                nav.pop()
                favoriteDraft = FavoriteDraft.of(store, p)
            },
        )
    }

    fun pick(isHome: Boolean) {
        nav.push(
            SearchModel(nav, null, null, SearchField.TO, single = true, title = if (isHome) "Set home" else "Set work") { p, _ ->
                if (isHome) store.setHome(p) else store.setWork(p)
                nav.pop()
            },
        )
    }
}

@Composable
fun SettingsScreen(model: SettingsModel) {
    val data by model.store.data.collectAsState()
    val s = data.settings
    var taps by remember { mutableIntStateOf(0) }
    var drives by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SectionHeader("Places")
            PlaceRow("Home", data.home?.name ?: "Not set", icon = Icons.Rounded.Home,
                trailing = { if (data.home != null) IconButton(onClick = { model.store.setHome(null) }) { Icon(Icons.Rounded.Close, "Clear") } },
                onClick = { model.pick(true) })
            PlaceRow("Work", data.work?.name ?: "Not set", icon = Icons.Rounded.Work,
                trailing = { if (data.work != null) IconButton(onClick = { model.store.setWork(null) }) { Icon(Icons.Rounded.Close, "Clear") } },
                onClick = { model.pick(false) })

            SectionHeader("Favorite places")
            data.favoritePlaces.forEachIndexed { i, f ->
                PlaceRow(f.label, f.place.name, icon = f.icon.vector(), iconTint = MaterialTheme.colorScheme.primary,
                    trailing = {
                        Row {
                            IconButton(onClick = { model.store.moveFavorite(f.key, -1) }, enabled = i > 0) {
                                Icon(Icons.Rounded.KeyboardArrowUp, "Move up")
                            }
                            IconButton(onClick = { model.store.moveFavorite(f.key, 1) }, enabled = i < data.favoritePlaces.lastIndex) {
                                Icon(Icons.Rounded.KeyboardArrowDown, "Move down")
                            }
                        }
                    },
                    onClick = { model.favoriteDraft = FavoriteDraft(f.place, f) })
            }
            PlaceRow("Add favorite place", "Tap a favorite above to edit or remove it",
                icon = Icons.Rounded.Add, iconTint = MaterialTheme.colorScheme.primary, onClick = model::addFavorite)

            SectionHeader("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                ThemeMode.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = s.theme == t,
                        onClick = { model.store.updateSettings { it.copy(theme = t) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(t.name.lowercase().replaceFirstChar(Char::uppercase)) }
                }
            }

            SectionHeader("Departure reminders")
            Text("Notify me this long before I need to leave",
                style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.subtle,
                modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 5, 10, 15).forEach { m ->
                    FilterChip(selected = s.reminderMinutes == m, onClick = { model.store.updateSettings { it.copy(reminderMinutes = m) } },
                        label = { Text("$m min") })
                }
            }

            SectionHeader("Simple Maslul")
            SettingSwitch(
                "Simple Maslul",
                "Big buttons for a few places and plain step-by-step directions, in English, Hebrew or Russian. " +
                    "Good for grandparents.",
                s.simpleMode, Modifier.testTag("simple_switch"),
            ) { c ->
                model.store.updateSettings { it.copy(simpleMode = c) }
                if (c) model.nav.selectTab(Tab.SIMPLE)
            }

            SectionHeader("Advanced")
            SettingSwitch(
                "Getting a ride (טרמפ)",
                "Find the best place to be dropped off and go on by bus or train",
                s.rides, Modifier.testTag("rides_switch"),
            ) { c -> model.store.updateSettings { it.copy(rides = c) } }
            SettingSwitch(
                "Already on a bus",
                "Plan the rest of the trip from the bus or train you're on",
                s.onBoard, Modifier.testTag("on_board_switch"),
            ) { c -> model.store.updateSettings { it.copy(onBoard = c) } }
            PlaceRow(
                "My shuttles",
                when (val n = data.shuttles.size) {
                    0 -> "Company or private shuttles with a fixed timetable"
                    1 -> data.shuttles.first().name
                    else -> "$n shuttles"
                },
                icon = Icons.Rounded.DirectionsBus,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { model.nav.push(ShuttlesModel(model.nav)) },
            )

            SectionHeader("About")
            Row(
                Modifier.fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        taps++
                        if (taps % 7 == 0) drives++
                    }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1F6FEB)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_launcher_foreground), null, tint = Color.Unspecified, modifier = Modifier.size(56.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Maslul", style = MaterialTheme.typography.titleMedium)
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle)
                }
            }
            Text(
                "Maslul is a free, open-source transit app for Israel, licensed under the GNU AGPL v3.\n\n" +
                    "• Routes & timetables: Israel Ministry of Transport GTFS, routed by Transitous (transitous.org).\n" +
                    "• Live vehicles: Ministry of Transport SIRI feed, published by Open Bus / The Public Knowledge Workshop (Hasadna).\n" +
                    "• Line search & daily schedules: Open Bus Stride API.\n" +
                    "• Search: Transitous, Photon (komoot) and Nominatim, OpenStreetMap data.\n" +
                    "• Maps: OpenFreeMap, © OpenStreetMap contributors.\n\n" +
                    "Live arrival times are estimated from each vehicle's reported position and are typically 30–90 s behind real time.",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalExtra.current.subtle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            val uri = LocalUriHandler.current
            TextButton(onClick = { uri.openUri(SOURCE_URL) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Rounded.Code, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Source code on GitHub")
            }
            Text(
                "Made with ♥ by Tommy and contributors",
                style = MaterialTheme.typography.bodySmall,
                color = LocalExtra.current.subtle,
                modifier = Modifier
                    .clickable { uri.openUri("$SOURCE_URL/graphs/contributors") }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
    if (drives > 0) DrivingBus(drives, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(bottom = 12.dp))
    }
    FavoriteDialogHost(model.store, model.favoriteDraft) { model.favoriteDraft = null }
}

/** A setting row that toggles when tapped anywhere. */
@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DrivingBus(run: Int, modifier: Modifier) {
    BoxWithConstraints(Modifier.fillMaxWidth().then(modifier)) {
        val w = 104.dp
        val h = 52.dp
        val span = maxWidth + w * 2
        val progress = remember(run) { Animatable(0f) }
        LaunchedEffect(run) { progress.animateTo(1f, tween(2800, easing = LinearEasing)) }
        if (progress.value < 1f) {
            val t = rememberInfiniteTransition(label = "ride")
            val bob by t.animateFloat(0f, -2f, infiniteRepeatable(tween(160), RepeatMode.Reverse), label = "bob")
            val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(500, easing = LinearEasing)), label = "spin")
            val body = Color(0xFF1F6FEB)
            val glass = Color(0xFFD6E6FF)
            val tyre = Color(0xFF263238)
            Canvas(Modifier.size(w, h).offset(x = span * progress.value - w, y = bob.dp)) {
                val u = size.width / 26f
                // Body, with a slanted windscreen at the front (right).
                drawRoundRect(body, Offset(0f, 0f), Size(24 * u, 10 * u), CornerRadius(2.5f * u))
                drawPath(Path().apply {
                    moveTo(22 * u, 0f); lineTo(24 * u, 0f); lineTo(26 * u, 6 * u); lineTo(26 * u, 10 * u); lineTo(22 * u, 10 * u); close()
                }, body)
                // Side windows and windscreen.
                for (i in 0 until 5) drawRoundRect(glass, Offset((1.5f + i * 4f) * u, 1.5f * u), Size(3f * u, 3.5f * u), CornerRadius(0.6f * u))
                drawPath(Path().apply {
                    moveTo(22.3f * u, 1.5f * u); lineTo(23.6f * u, 1.5f * u); lineTo(25.2f * u, 5f * u); lineTo(22.3f * u, 5f * u); close()
                }, glass)
                drawRect(Color.White.copy(alpha = 0.5f), Offset(0f, 6.5f * u), Size(26f * u, 0.6f * u))
                // Wheels with a spoke so they visibly turn.
                for (cx in listOf(6f * u, 20f * u)) {
                    val c = Offset(cx, 10.3f * u)
                    drawCircle(tyre, 2.6f * u, c)
                    drawCircle(Color(0xFFB0BEC5), 1.1f * u, c)
                    rotate(spin, c) { drawLine(tyre, c, Offset(c.x, c.y - 1.1f * u), strokeWidth = 0.5f * u) }
                }
            }
        }
    }
}
