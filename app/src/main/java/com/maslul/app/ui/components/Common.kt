package com.maslul.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsBoat
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsRailway
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Tram
import androidx.compose.material.icons.rounded.Commute
import androidx.compose.material.icons.rounded.AirlineSeatReclineNormal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maslul.app.data.LiveCall
import com.maslul.app.data.LiveStatus
import com.maslul.app.data.TransitMode
import com.maslul.app.data.freshness
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.ModeColors
import com.maslul.app.ui.theme.Numeric

fun modeIcon(mode: TransitMode): ImageVector = when (mode) {
    TransitMode.WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
    TransitMode.BUS -> Icons.Rounded.DirectionsBus
    TransitMode.TRAIN -> Icons.Rounded.DirectionsRailway
    TransitMode.LIGHT_RAIL -> Icons.Rounded.Tram
    TransitMode.METRO -> Icons.Rounded.DirectionsSubway
    TransitMode.CABLE_CAR -> Icons.Rounded.AirlineSeatReclineNormal
    TransitMode.FERRY -> Icons.Rounded.DirectionsBoat
    TransitMode.OTHER -> Icons.Rounded.Commute
}

fun modeName(mode: TransitMode): String = when (mode) {
    TransitMode.WALK -> "Walk"
    TransitMode.BUS -> "Bus"
    TransitMode.TRAIN -> "Train"
    TransitMode.LIGHT_RAIL -> "Light rail"
    TransitMode.METRO -> "Carmelit"
    TransitMode.CABLE_CAR -> "Cable car"
    TransitMode.FERRY -> "Ferry"
    TransitMode.OTHER -> "Transit"
}

fun lineColor(mode: TransitMode, routeColor: Int?): Color =
    routeColor?.let { Color(it) }?.takeIf { it != Color.White && it != Color.Black } ?: ModeColors.of(mode)

/** Rounded line-number pill with the mode icon, e.g. [🚌 480]. */
@Composable
fun LineBadge(
    label: String,
    mode: TransitMode,
    modifier: Modifier = Modifier,
    color: Color = ModeColors.of(mode),
    showIcon: Boolean = true,
    large: Boolean = false,
) {
    val fg = if (color.luminance() > 0.6f) Color.Black else Color.White
    Row(
        modifier
            .clip(RoundedCornerShape(if (large) 10.dp else 8.dp))
            .background(color)
            .padding(horizontal = if (large) 10.dp else 7.dp, vertical = if (large) 5.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) {
            Icon(modeIcon(mode), null, tint = fg, modifier = Modifier.size(if (large) 18.dp else 15.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(
            label,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = if (large) 17.sp else 14.sp,
            maxLines = 1,
            style = Numeric,
        )
    }
}

@Composable
fun ModeDot(mode: TransitMode, size: Dp = 32.dp, color: Color = ModeColors.of(mode)) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(modeIcon(mode), null, tint = color, modifier = Modifier.size(size * 0.58f))
    }
}

/** Small pulsing dot used to mark realtime information. */
@Composable
fun LiveDot(color: Color = LocalExtra.current.live, size: Dp = 7.dp) {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(color))
}

@Composable
fun delayColor(delaySec: Long): Color {
    val x = LocalExtra.current
    return when {
        delaySec >= 120 -> x.late
        delaySec <= -90 -> x.early
        else -> x.live
    }
}

fun delayText(delaySec: Long): String {
    val m = Math.round(delaySec / 60.0)
    return when {
        m >= 1 -> "$m min late"
        m <= -1 -> "${-m} min early"
        else -> "On time"
    }
}

/** "3 min", "Now", or a clock time for far-off departures. */
@Composable
fun MinutesText(call: LiveCall?, fallback: java.time.Instant, now: java.time.Instant, modifier: Modifier = Modifier) {
    val t = call?.best ?: fallback
    val live = call?.status == LiveStatus.LIVE
    Column(modifier, horizontalAlignment = Alignment.End) {
        ArrivalTime(Fmt.relative(t, now), call.freshness(now))
        if (live && Math.abs(call!!.delaySec) >= 60) {
            Text(
                "${Fmt.time(call.scheduled)} sched.",
                style = MaterialTheme.typography.labelSmall,
                color = LocalExtra.current.subtle,
            )
        } else if (call?.status == LiveStatus.UNTRACKED) {
            Text("no live data", style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.subtle)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = LocalExtra.current.subtle,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(action) }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, text: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
        if (text != null) {
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.subtle)
        }
    }
}

@Composable
fun MessageBox(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = Icons.Rounded.CloudOff,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = LocalExtra.current.subtle, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.subtle, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** A tappable list row with a leading icon bubble. */
@Composable
fun PlaceRow(
    title: String,
    subtitle: String?,
    icon: ImageVector = Icons.Rounded.Place,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalExtra.current.subtle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.widthIn(max = 140.dp)) { trailing() }
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = if (filled) Color.White else color,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier, start: Dp = 0.dp) {
    Box(
        modifier.fillMaxWidth().padding(start = start).height(1.dp).background(LocalExtra.current.divider),
    )
}

val SpacedBy4 = Arrangement.spacedBy(4.dp)
val SpacedBy8 = Arrangement.spacedBy(8.dp)
