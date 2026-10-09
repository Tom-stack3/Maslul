package com.maslul.app.ui.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maslul.app.data.Combine
import com.maslul.app.data.IsraelZone
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.Place
import com.maslul.app.data.TransitMode
import com.maslul.app.ui.AppNav
import com.maslul.app.ui.ScreenModel
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.components.LineBadge
import com.maslul.app.ui.components.LiveDot
import com.maslul.app.ui.components.lineColor
import com.maslul.app.ui.components.modeIcon
import com.maslul.app.ui.nav.CurrentLocation
import com.maslul.app.ui.nav.RouteError
import com.maslul.app.ui.nav.RoutesModel
import com.maslul.app.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant

/** Longest walk offered as a way there on its own. */
private const val WALK_OPTION_SEC = 20 * 60L

/** Ways from here to [to], planned by the full app's [RoutesModel] and shown plainly. */
class SimpleRoutesModel(nav: AppNav, val to: Place, val title: String) : ScreenModel(nav) {
    val planner = RoutesModel(nav, CurrentLocation, to)
    var showAll by mutableStateOf(false)

    /** [itin] at the times its buses are expected, when they're tracked live. */
    fun expected(itin: Itinerary): Itinerary = Combine.expected(itin) { planner.rideLive[it.rideKey] }

    /** Whether [itin]'s first ride is tracked live. */
    fun isLive(itin: Itinerary): Boolean = itin.firstTransit?.let { planner.rideLive[it.rideKey] }?.status == LiveStatus.LIVE

    /** The options worth showing at [now]: still catchable, no car rides. */
    fun options(now: Instant): List<Itinerary> = planner.itineraries
        .filter { itin -> itin.legs.none { it.mode == TransitMode.CAR } }
        .filter { !expected(it).start.isBefore(now.minusSeconds(60)) }

    /** Walking there, when it's short enough to suggest (or nothing else was found). */
    fun walk(hasOptions: Boolean): Itinerary? = planner.walkOnly?.takeIf { !hasOptions || it.durationSec <= WALK_OPTION_SEC }

    fun open(itin: Itinerary) = nav.push(SimpleTripModel(nav, this, itin))

    override fun dispose() {
        planner.dispose()
        super.dispose()
    }
}

/** A clock that ticks every [periodMs], for "leave in N min". */
@Composable
private fun rememberNow(periodMs: Long = 15_000): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(periodMs)
            now = Instant.now()
        }
    }
    return now
}

@Composable
fun SimpleRoutesScreen(model: SimpleRoutesModel) = SimpleFrame {
    val s = LocalSimpleStrings.current
    val planner = model.planner
    val now = rememberNow()
    LaunchedEffect(Unit) { planner.liveLoop() }

    val options = model.options(now)
    val walk = model.walk(options.isNotEmpty())
    // Every option has left while the screen sat open: look again from now.
    LaunchedEffect(options.isEmpty(), planner.loading) {
        if (!planner.loading && options.isEmpty() && planner.itineraries.isNotEmpty()) planner.refreshNow()
    }

    Column(Modifier.fillMaxSize()) {
        SimpleTopBar(s.toPlace(model.title), onBack = { model.nav.pop() })
        when {
            // Still looking (or about to look again): only a search that failed says so.
            options.isEmpty() && walk == null && planner.errorKind == null -> Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(Modifier.size(48.dp), strokeWidth = 4.dp)
                Spacer(Modifier.height(16.dp))
                Text(s.finding, fontSize = 22.sp, textAlign = TextAlign.Center)
            }
            options.isEmpty() && walk == null -> Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    when (planner.errorKind) {
                        RouteError.LOCATION -> s.errLocation
                        RouteError.NETWORK -> s.errNetwork
                        RouteError.NO_ROUTES, null -> s.errNoRoutes
                    },
                    fontSize = 22.sp, lineHeight = 28.sp,
                )
                Spacer(Modifier.height(20.dp))
                SimpleWideButton(s.tryAgain, onClick = planner::refreshNow, icon = Icons.Rounded.Refresh)
            }
            else -> {
                val shown = if (model.showAll) options else options.take(3)
                LazyColumn(
                    Modifier.fillMaxSize().testTag("simple_routes"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // A short walk beats waiting for a bus: offer it first.
                    if (walk != null && (options.isEmpty() || walk.end <= model.expected(options.first()).end)) {
                        item { WalkCard(walk) { model.open(walk) } }
                    }
                    itemsIndexed(shown, key = { _, it -> it.id }) { i, itin ->
                        OptionCard(model.expected(itin), best = i == 0, live = model.isLive(itin), now = now) { model.open(itin) }
                    }
                    if (walk != null && options.isNotEmpty() && walk.end > model.expected(options.first()).end) {
                        item { WalkCard(walk) { model.open(walk) } }
                    }
                    if (!model.showAll && options.size > shown.size) {
                        item {
                            SimpleWideButton(s.moreOptions, onClick = { model.showAll = true }, icon = Icons.Rounded.ExpandMore,
                                color = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
        }
    }
}

/** Which day a time is on, seen from today in Israel. */
private sealed interface Day
private data object Today : Day
/** Another day: [weekday] null means tomorrow. */
private data class OtherDay(val weekday: DayOfWeek?) : Day

private fun dayOf(t: Instant, now: Instant): Day {
    val d = t.atZone(IsraelZone).toLocalDate()
    val today = now.atZone(IsraelZone).toLocalDate()
    return when (d) {
        today -> Today
        today.plusDays(1) -> OtherDay(null)
        else -> OtherDay(d.dayOfWeek)
    }
}

/**
 * "Leave now", "Leave in 6 min", "Leave at 14:05", or "Leave tomorrow at 17:59": on Friday
 * evening the next bus is often after Shabbat, and that has to be plain.
 */
private fun leaveText(s: SimpleStrings, start: Instant, now: Instant): String {
    val sec = start.epochSecond - now.epochSecond
    return when {
        sec < 45 -> s.leaveNow
        sec < 60 * 60 -> s.leaveIn((sec + 30) / 60)
        else -> when (val d = dayOf(start, now)) {
            Today -> s.leaveAt(Fmt.time(start))
            is OtherDay -> s.leaveOn(d.weekday, Fmt.time(start))
        }
    }
}

/** A clock time, with the day added when it isn't today: "17:59 tomorrow". */
private fun timeText(s: SimpleStrings, t: Instant, now: Instant = Instant.now()): String = when (val d = dayOf(t, now)) {
    Today -> Fmt.time(t)
    is OtherDay -> Fmt.time(t) + " " + s.onDay(d.weekday)
}

@Composable
private fun OptionCard(itin: Itinerary, best: Boolean, live: Boolean, now: Instant, onClick: () -> Unit) {
    val s = LocalSimpleStrings.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().then(
            if (best) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp)) else Modifier,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (best) {
                Text(s.best, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(leaveText(s, itin.start, now), fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false))
                if (live) {
                    Spacer(Modifier.width(10.dp))
                    LiveDot(size = 10.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(s.live, fontSize = 15.sp, color = LocalExtra.current.live, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Rides(itin.transitLegs)
            Spacer(Modifier.height(12.dp))
            Text(s.arriveAt(timeText(s, itin.end, now)), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(s.changes(itin.transitLegs.size - 1), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The rides in order, as big line badges: [🚌 18] › [🚆]. */
@Composable
private fun Rides(legs: List<Leg>) {
    val s = LocalSimpleStrings.current
    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        legs.forEachIndexed { i, l ->
            if (i > 0) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(28.dp).align(Alignment.CenterVertically),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LineBadge(simpleLine(l).ifEmpty { s.mode(l.mode) }, l.mode, color = lineColor(l), large = true)
        }
    }
}

@Composable
private fun WalkCard(itin: Itinerary, onClick: () -> Unit) {
    val s = LocalSimpleStrings.current
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(s.walkThere(simpleMinutes(itin.durationSec)), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(s.arriveAt(Fmt.time(Instant.now().plusSeconds(itin.durationSec))), fontSize = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One way there, as numbered plain steps that follow the buses live. */
class SimpleTripModel(nav: AppNav, val routes: SimpleRoutesModel, private val initial: Itinerary) : ScreenModel(nav) {
    /** The option as it stands now: the planner may have moved it to a bus that's coming sooner. */
    val itinerary: Itinerary
        get() = routes.planner.itineraries.firstOrNull { it.id == initial.id } ?: initial
}

@Composable
fun SimpleTripScreen(model: SimpleTripModel) = SimpleFrame {
    val s = LocalSimpleStrings.current
    // The options screen isn't showing, so keep its live times coming from here.
    LaunchedEffect(Unit) { model.routes.planner.liveLoop() }
    val itin = model.routes.expected(model.itinerary)
    val steps = simpleSteps(itin)

    Column(Modifier.fillMaxSize()) {
        SimpleTopBar(s.howToGetThere, onBack = { model.nav.pop() })
        LazyColumn(Modifier.fillMaxSize().testTag("simple_steps"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(steps) { i, step ->
                when (step) {
                    is SimpleStep.WalkToStop -> StepCard(i + 1, Icons.AutoMirrored.Rounded.DirectionsWalk, LocalExtra.current.walk,
                        s.walkToStop(step.stop), listOf(s.aboutMin(step.minutes)))
                    is SimpleStep.WalkToEnd -> StepCard(i + 1, Icons.AutoMirrored.Rounded.DirectionsWalk, LocalExtra.current.walk,
                        s.walkToDestination, listOf(s.aboutMin(step.minutes)))
                    is SimpleStep.Ride -> RideStep(i + 1, step, model.routes.planner.rideLive[step.leg.rideKey]?.status == LiveStatus.LIVE)
                    is SimpleStep.Arrive -> StepCard(i + 1, Icons.Rounded.Flag, MaterialTheme.colorScheme.primary,
                        s.arrived, listOf(s.arriveAt(timeText(s, step.at))))
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
}

@Composable
private fun RideStep(n: Int, step: SimpleStep.Ride, live: Boolean) {
    val s = LocalSimpleStrings.current
    val color = lineColor(step.leg)
    StepCard(
        n, modeIcon(step.leg.mode), color,
        s.take(step.leg.mode, step.line),
        buildList {
            step.leg.headsign?.takeIf { it.isNotBlank() }?.let { add(s.towards(it)) }
            add(s.leavesAt(timeText(s, step.departs)))
            step.platform?.let { add(s.platform(it)) }
            if (step.others.isNotEmpty()) add(s.orLines(step.others.joinToString(", ")))
        },
        live = live,
    )
    Spacer(Modifier.height(12.dp))
    StepCard(n, Icons.Rounded.Flag, color, s.getOffAt(step.getOff), listOf(s.rideStops(step.stops)), number = false)
}

@Composable
private fun StepCard(
    n: Int,
    icon: ImageVector,
    color: Color,
    title: String,
    lines: List<String>,
    live: Boolean = false,
    number: Boolean = true,
) {
    val s = LocalSimpleStrings.current
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp)) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(30.dp), tint = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (number) {
                    Text("$n", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(title, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
                lines.forEach { Text(it, fontSize = 19.sp, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (live) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        LiveDot(size = 10.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(s.live, fontSize = 16.sp, color = LocalExtra.current.live, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
