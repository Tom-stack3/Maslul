package com.maslul.app.ui.nav

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
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maslul.app.data.ThemeMode
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.PlaceRow
import com.maslul.app.ui.components.SectionHeader
import com.maslul.app.ui.theme.LocalExtra

class SettingsModel(nav: AppNav) : ScreenModel(nav) {
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

            SectionHeader("About")
            Text(
                "Maslul is a personal, free transit app for Israel.\n\n" +
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
            Spacer(Modifier.height(24.dp))
        }
    }
}
