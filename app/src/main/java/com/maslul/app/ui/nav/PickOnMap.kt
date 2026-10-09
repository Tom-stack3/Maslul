package com.maslul.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.LocationRepo
import com.maslul.app.data.Place
import com.maslul.app.data.PlaceKind
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.MapController
import com.maslul.app.ui.components.TransitMap
import com.maslul.app.ui.theme.LocalExtra
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.maslul.app.i18n.S

class PickOnMapModel(nav: AppNav, val onPicked: (Place) -> Unit) : ScreenModel(nav) {
    var center by mutableStateOf<GeoPoint?>(null)
    var label by mutableStateOf<Place?>(null)
    var resolving by mutableStateOf(false)
    private var job: Job? = null

    fun onIdle(p: GeoPoint) {
        center = p
        job?.cancel()
        job = scope.launch {
            resolving = true
            delay(250)
            label = repo.reverseGeocode(p)
            resolving = false
        }
    }

    fun confirm() {
        val c = center ?: return
        val l = label
        onPicked(
            Place(
                name = l?.name ?: S.pinnedLocation,
                lat = c.lat,
                lon = c.lon,
                subtitle = l?.subtitle,
                kind = PlaceKind.PIN,
            ),
        )
    }
}

@Composable
fun PickOnMapScreen(model: PickOnMapModel) {
    val user by model.location.last.collectAsState()
    val controller = remember { MapController() }
    Box(Modifier.fillMaxSize()) {
        TransitMap(
            Modifier.fillMaxSize(),
            user = user,
            initialCenter = user ?: LocationRepo.DEFAULT,
            initialZoom = 16.0,
            controller = controller,
            onCameraIdle = model::onIdle,
            compassModifier = Modifier.statusBarsPadding().padding(16.dp),
        )
        Icon(
            Icons.Rounded.Place,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).size(44.dp).offset(y = (-20).dp),
        )
        FilledTonalIconButton(
            onClick = { model.nav.pop() },
            modifier = Modifier.statusBarsPadding().padding(12.dp),
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, S.back) }

        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 10.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(Modifier.navigationBarsPadding().padding(20.dp)) {
                Text(S.moveMapToChoose, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.subtle)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (model.resolving) S.findingAddress else model.label?.name ?: S.pinnedLocation,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                model.label?.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle)
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = model::confirm,
                    enabled = model.center != null,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = CircleShape,
                ) { Text(S.chooseThisLocation) }
            }
        }
    }
}
