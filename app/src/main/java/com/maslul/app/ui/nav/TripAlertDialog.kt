package com.maslul.app.ui.nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import com.maslul.app.ui.theme.Numeric
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maslul.app.data.SavedTrip
import com.maslul.app.data.TripAlert
import com.maslul.app.data.UserStore
import com.maslul.app.data.languageOrDefault
import com.maslul.app.i18n.S
import com.maslul.app.i18n.shownName
import com.maslul.app.ui.components.Fmt
import com.maslul.app.live.TripWatcher
import com.maslul.app.ui.theme.LocalExtra
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Israel's week, Sunday first. */
private val WEEK = listOf(
    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
)

/**
 * When [trip] is usually taken, to be alerted around it (see [com.maslul.app.data.TripAlerts]).
 * [suggested] is the time to offer for a trip that has none yet. Saving also saves the trip.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TripAlertDialog(store: UserStore, trip: SavedTrip, suggested: LocalTime, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val existing = store.data.value.savedTrips.firstOrNull { it.key == trip.key }?.alert
    val start = existing?.minuteOfDay?.let { LocalTime.of(it / 60, it % 60) } ?: suggested
    var time by remember { mutableStateOf(start) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        val dial = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            text = { TimePicker(dial) },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(dial.hour, dial.minute); picking = false }) { Text(S.ok) } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(S.cancel) } },
        )
    }
    var days by remember { mutableStateOf(existing?.days ?: TripAlert.WORKDAYS) }
    var on by remember { mutableStateOf(existing?.enabled ?: true) }
    val locale = Locale(store.data.value.settings.languageOrDefault().code)
    // Alerts are notifications: ask for them (Android 13+) when saving, then close either way.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onClose() }

    fun save() {
        store.setTripAlert(trip, TripAlert(time.hour * 60 + time.minute, days, on && days.isNotEmpty()))
        val ask = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(S.tripAlerts) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(Fmt.fromTo(trip.from.shownName, trip.label ?: trip.to.name), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(S.tripAlertsHint, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(S.tripAlertsOn, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { on = it }, modifier = Modifier.testTag("trip_alert_on"))
                }
                Spacer(Modifier.height(12.dp))
                Text(S.usualDeparture, style = MaterialTheme.typography.labelLarge)
                // A clock dial: easy to set with a finger.
                OutlinedButton(
                    onClick = { picking = true },
                    modifier = Modifier.padding(vertical = 6.dp).testTag("trip_alert_time"),
                ) {
                    Text("%02d:%02d".format(time.hour, time.minute), style = MaterialTheme.typography.headlineMedium.merge(Numeric))
                }
                Spacer(Modifier.height(6.dp))
                Text(S.onDays, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEK.forEach { d ->
                        FilterChip(
                            selected = d in days,
                            onClick = { days = if (d in days) days - d else days + d },
                            label = { Text(d.getDisplayName(TextStyle.SHORT, locale)) },
                        )
                    }
                }
                if (!TripWatcher.exactAlarms(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(S.exactAlarmsHint, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.subtle,
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                            }
                        }) { Text(S.allow) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::save, modifier = Modifier.testTag("trip_alert_save")) { Text(S.save) } },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { store.setTripAlert(trip, null); onClose() }) {
                        Text(S.remove, color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onClose) { Text(S.cancel) }
            }
        },
    )
}

/** "Alerts at 08:30" for a watched trip, else null. */
fun SavedTrip.alertLabel(): String? = alert?.takeIf { it.enabled }?.let { a ->
    S.alertsAt("%02d:%02d".format(a.minuteOfDay / 60, a.minuteOfDay % 60))
}
