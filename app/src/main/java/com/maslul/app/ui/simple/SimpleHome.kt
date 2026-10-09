package com.maslul.app.ui.simple

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maslul.app.data.Place
import com.maslul.app.data.simpleSlots
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel

/** Simple Maslul's home: five place buttons and one for any other address. */
class SimpleHomeModel(nav: AppNav) : ScreenModel(nav) {
    /** Goes to button [index]'s place, or asks for its address first if it has none. */
    fun open(index: Int, title: String, s: SimpleStrings) {
        val place = store.data.value.simpleSlots().getOrNull(index)?.place
        if (place != null) go(place, title) else setUp(index, s.whereIs(title))
    }

    private fun setUp(index: Int, title: String) {
        nav.push(SimpleSearchModel(nav, title) { p ->
            store.data.value.simpleButtons.getOrNull(index)?.let { store.setSimpleButton(index, it.copy(place = p)) }
            nav.pop()
        })
    }

    private fun go(p: Place, title: String) {
        store.addRecent(p)
        nav.push(SimpleRoutesModel(nav, p, title))
    }

    fun search() {
        nav.push(SimpleSearchModel(nav, null) { p ->
            store.addRecent(p)
            nav.replace(SimpleRoutesModel(nav, p, p.name))
        })
    }

    fun openSettings() = nav.push(SimpleSettingsModel(nav))
}

@Composable
fun SimpleHomeScreen(model: SimpleHomeModel) = SimpleFrame {
    val s = LocalSimpleStrings.current
    val data by model.store.data.collectAsState()
    val slots = data.simpleSlots()

    // Directions start from where you are, so ask for location straight away.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        if (!model.location.hasPermission()) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        SimpleTopBar(s.whereTo, onBack = null, trailing = null)
        val cells = slots.mapIndexed { i, b ->
            val title = b.title(s)
            Cell(title, if (b.place == null) s.tapToSet else null, b.icon.vector(), slotColor(i), "simple_slot_$i") {
                model.open(i, title, s)
            }
        } + Cell(s.otherAddress, null, Icons.Rounded.Search, SearchButtonColor, "simple_search") { model.search() }

        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cells.chunked(2).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { c -> BigButton(c, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        // Settings is there, but kept small and out of the way.
        Surface(
            onClick = model::openSettings,
            color = Color.Transparent,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp).testTag("simple_settings"),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Settings, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Text(s.settings, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private class Cell(
    val title: String,
    val hint: String?,
    val icon: ImageVector,
    val color: Color,
    val tag: String,
    val onClick: () -> Unit,
)

@Composable
private fun BigButton(c: Cell, modifier: Modifier) {
    Surface(
        onClick = c.onClick,
        shape = RoundedCornerShape(24.dp),
        color = c.color,
        contentColor = Color.White,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxSize().testTag(c.tag),
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(c.icon, null, Modifier.size(52.dp))
            Spacer(Modifier.height(8.dp))
            Text(c.title, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (c.hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(c.hint, fontSize = 15.sp, lineHeight = 18.sp, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.9f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
