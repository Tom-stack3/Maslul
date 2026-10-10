package com.maslul.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maslul.app.data.Place
import com.maslul.app.data.Shuttle
import com.maslul.app.data.ShuttleSchedule
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.PlaceRow
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.Numeric
import java.time.DayOfWeek
import java.util.UUID
import com.maslul.app.i18n.S

class ShuttlesModel(nav: AppNav) : ScreenModel(nav) {
    fun add() = nav.push(ShuttleEditModel(nav, null))
    fun edit(s: Shuttle) = nav.push(ShuttleEditModel(nav, s))
}

@Composable
fun ShuttlesScreen(model: ShuttlesModel) {
    val data by model.store.data.collectAsState()
    val x = LocalExtra.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, S.back) }
            Text(S.myShuttles, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text(
                S.shuttlesIntro,
                style = MaterialTheme.typography.bodyMedium, color = x.subtle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            data.shuttles.forEach { s ->
                PlaceRow(
                    s.name,
                    // Its timetable; the stops (often in Hebrew, which mixes badly with times on one line) are in the editor.
                    summary(s),
                    icon = Icons.Rounded.DirectionsBus,
                    iconTint = Color(ShuttleSchedule.COLOR),
                    onClick = { model.edit(s) },
                )
            }
            PlaceRow(S.addShuttle, null, icon = Icons.Rounded.Add, iconTint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("add_shuttle"), onClick = model::add)
        }
    }
}

/** "07:30, 08:00 +3 · Sun–Thu". */
private fun summary(s: Shuttle): String {
    val shown = s.times.take(2).joinToString(", ", transform = ShuttleSchedule::formatTime)
    val more = if (s.times.size > 2) " +${s.times.size - 2}" else ""
    return "$shown$more · ${ShuttleSchedule.formatDays(s.days)}"
}

class ShuttleEditModel(nav: AppNav, existing: Shuttle?) : ScreenModel(nav) {
    private val id = existing?.id ?: UUID.randomUUID().toString()
    val isNew = existing == null
    var name by mutableStateOf(existing?.name.orEmpty())
    var from by mutableStateOf(existing?.from)
    var to by mutableStateOf(existing?.to)
    var times by mutableStateOf(existing?.times?.let(ShuttleSchedule::formatTimes).orEmpty())
    /** Unset on a new shuttle: there's no sensible default, so it has to be chosen. */
    var rideMinutes by mutableStateOf(existing?.rideMinutes)
    var days by mutableStateOf(existing?.days ?: Shuttle.WORK_WEEK)
    /** Set once Save was tapped with something missing: from then on the missing fields are marked. */
    var showMissing by mutableStateOf(false)

    val parsedTimes get() = ShuttleSchedule.parseTimes(times)
    val missing get() = ShuttleSchedule.missing(name, from, to, times, rideMinutes, days)
    val canSave get() = missing.isEmpty()

    fun pick(isFrom: Boolean) {
        nav.push(SearchModel(nav, null, null, SearchField.TO, single = true,
            title = if (isFrom) S.shuttleFromTitle else S.shuttleToTitle) { p: Place, _ ->
            nav.pop()
            if (isFrom) from = p else to = p
        })
    }

    private fun shuttle() = Shuttle(id, name.trim(), from!!, to!!, parsedTimes!!, rideMinutes!!, days)

    /** False (and the missing fields marked) when something's still missing. */
    private fun check(): Boolean {
        if (!canSave) showMissing = true
        return canSave
    }

    fun save() {
        if (!check()) return
        store.saveShuttle(shuttle())
        nav.pop()
    }

    fun delete() {
        store.removeShuttle(id)
        nav.pop()
    }

    /** Saves this one and starts the way back: same shuttle, ends swapped, its own times. */
    fun addReturn() {
        if (!check()) return
        val s = shuttle()
        store.saveShuttle(s)
        nav.replace(ShuttleEditModel(nav, null).also {
            it.name = s.name
            it.from = s.to
            it.to = s.from
            it.rideMinutes = s.rideMinutes
            it.days = s.days
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShuttleEditScreen(model: ShuttleEditModel) {
    val x = LocalExtra.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { model.nav.pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, S.back) }
            Text(if (model.isNew) S.newShuttle else S.editShuttle, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            OutlinedTextField(
                value = model.name,
                onValueChange = { model.name = it.take(24) },
                label = { Text(S.name) },
                isError = model.showMissing && model.name.isBlank(),
                placeholder = { Text(S.shuttleNameExample) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("shuttle_name"),
            )
            Spacer(Modifier.height(6.dp))
            val err = MaterialTheme.colorScheme.error
            PlaceRow(S.from, model.from?.name ?: S.chooseShuttleFrom, icon = Icons.Rounded.TripOrigin,
                iconTint = MaterialTheme.colorScheme.primary,
                subtitleColor = if (model.showMissing && model.from == null) err else x.subtle) { model.pick(true) }
            // Both ends at one stop: the second one picked is the one to change.
            val sameStop = model.from != null && model.to != null && ShuttleSchedule.isNear(model.from!!.point, model.to!!.point)
            PlaceRow(S.to, model.to?.name ?: S.chooseShuttleTo, icon = Icons.Rounded.Flag,
                iconTint = MaterialTheme.colorScheme.primary,
                subtitleColor = if (model.showMissing && (model.to == null || sameStop)) err else x.subtle) { model.pick(false) }
            Spacer(Modifier.height(8.dp))
            val parsed = model.parsedTimes
            val bad = parsed == null && (!ShuttleSchedule.isEmptyTimes(model.times) || model.showMissing)
            OutlinedTextField(
                value = model.times,
                onValueChange = { model.times = it },
                label = { Text(S.departureTimes) },
                placeholder = { Text("07:30, 08:15, 17:00") },
                isError = bad,
                supportingText = {
                    Text(
                        when {
                            bad && ShuttleSchedule.isEmptyTimes(model.times) -> S.addDepartureTime
                            bad -> S.timesFormatHint
                            parsed != null -> S.departuresADay(parsed.size)
                            else -> S.departureTimesHint
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("shuttle_times"),
            )
            Spacer(Modifier.height(8.dp))
            val ride = model.rideMinutes
            val rideMissing = ride == null && model.showMissing
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(S.rideTakes, style = MaterialTheme.typography.titleSmall,
                    color = if (rideMissing) MaterialTheme.colorScheme.error else Color.Unspecified, modifier = Modifier.weight(1f))
                Text(
                    if (ride != null) S.min(ride.toLong()) else S.notSet,
                    style = MaterialTheme.typography.bodyMedium.merge(Numeric),
                    color = when {
                        rideMissing -> MaterialTheme.colorScheme.error
                        ride == null -> x.subtle
                        else -> Color.Unspecified
                    },
                    modifier = Modifier.testTag("shuttle_ride"),
                )
            }
            Slider(
                // Unset: the thumb waits faded at the start until it's moved.
                value = (ride ?: 5).toFloat(),
                colors = if (ride == null) SliderDefaults.colors(
                    thumbColor = x.subtle,
                    activeTrackColor = MaterialTheme.colorScheme.outlineVariant,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ) else SliderDefaults.colors(),
                onValueChange = { model.rideMinutes = (Math.round(it / 5) * 5).coerceIn(5, 120) },
                // Snaps to 5 minutes without drawing a tick for each.
                valueRange = 5f..120f,
                // Its start is near the screen's edge: dragging from there mustn't count as the back gesture.
                modifier = Modifier.padding(horizontal = 20.dp).systemGestureExclusion().testTag("shuttle_ride_slider"),
            )
            if (ride == null) {
                Text(S.rideTakesHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rideMissing) MaterialTheme.colorScheme.error else x.subtle,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp))
            }
            Text(S.runsOn, style = MaterialTheme.typography.titleSmall,
                color = if (model.showMissing && model.days.isEmpty()) MaterialTheme.colorScheme.error else Color.Unspecified, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Sunday first, as the week goes in Israel.
                listOf(7, 1, 2, 3, 4, 5, 6).forEach { d ->
                    val on = d in model.days
                    FilterChip(
                        selected = on,
                        onClick = { model.days = if (on) model.days - d else model.days + d },
                        label = { Text(S.dayShort(DayOfWeek.of(d))) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            // Save can always be tapped: with something missing it says what, rather than doing nothing.
            ShuttleSchedule.missingText(model.missing)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (model.showMissing) MaterialTheme.colorScheme.error else x.subtle,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp).testTag("shuttle_missing"))
            }
            Button(
                onClick = model::save,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(50.dp).testTag("shuttle_save"),
            ) { Text(S.save) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                TextButton(onClick = model::addReturn) { Text(S.saveAndAddReturn) }
                Spacer(Modifier.weight(1f))
                if (!model.isNew) {
                    TextButton(onClick = model::delete) { Text(S.delete, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
