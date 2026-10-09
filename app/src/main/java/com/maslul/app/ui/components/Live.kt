package com.maslul.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maslul.app.data.Freshness
import com.maslul.app.ui.theme.LocalExtra
import com.maslul.app.ui.theme.Numeric
import kotlinx.coroutines.delay
import java.time.Instant
import com.maslul.app.i18n.S

/** Arrival-time colour by data quality: green live, amber stale, plain timetable. */
@Composable
fun freshnessColor(f: Freshness): Color = when (f) {
    Freshness.LIVE -> LocalExtra.current.live
    Freshness.STALE -> LocalExtra.current.stale
    Freshness.SCHEDULED -> MaterialTheme.colorScheme.onSurface
}

/** The current time, re-read every [periodMs] so relative labels ("30s ago") keep ticking. */
@Composable
fun rememberNow(periodMs: Long = 1_000): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            now = Instant.now()
        }
    }
    return now
}

/**
 * Realtime "signal" mark (a dot with two arcs radiating from it), shown next to live times.
 * Live: the arcs light up in turn, continuously, to show the position keeps updating.
 * Stale: drawn still. Timetable-only: nothing.
 */
@Composable
fun LiveSignal(freshness: Freshness, modifier: Modifier = Modifier, size: Dp = 12.dp, color: Color = freshnessColor(freshness)) {
    if (freshness == Freshness.SCHEDULED) return
    val phase = if (freshness == Freshness.LIVE) {
        val t = rememberInfiniteTransition(label = "signal")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "phase").value
    } else {
        null
    }
    Canvas(modifier.size(size).semantics { contentDescription = if (phase != null) S.live else S.liveDelayed }) {
        val w = this.size.minDimension
        val stroke = w * 0.15f
        val c = Offset(w * 0.2f, w * 0.8f)
        // Each part glows as the wave passes it: dot, then inner arc, then outer arc.
        fun alpha(i: Int): Float {
            if (phase == null) return if (i == 0) 1f else 0.55f
            val d = ((phase - i * 0.22f) + 1f) % 1f
            return 0.3f + 0.7f * (if (d < 0.35f) 1f - d / 0.35f else 0f)
        }
        drawCircle(color.copy(alpha = alpha(0).coerceAtLeast(0.7f)), radius = w * 0.13f, center = c)
        listOf(0.42f, 0.72f).forEachIndexed { i, r ->
            val rad = w * r
            drawArc(
                color = color.copy(alpha = alpha(i + 1)),
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(c.x - rad, c.y - rad),
                size = Size(rad * 2, rad * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * An arrival time ("4 min", "21:40") coloured by [freshness], with the live signal beside it.
 * When [onClick] is set the time is tappable (e.g. to show the vehicle on the map).
 */
@Composable
fun ArrivalTime(
    text: String,
    freshness: Freshness,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    onClick: (() -> Unit)? = null,
) {
    val color = freshnessColor(freshness)
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = S.showOnMap, onClick = onClick) else Modifier)
            .padding(horizontal = if (onClick != null) 4.dp else 0.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(text, style = style.merge(Numeric), color = color, maxLines = 1)
        if (freshness != Freshness.SCHEDULED) {
            Spacer(Modifier.width(2.dp))
            LiveSignal(freshness, Modifier.padding(top = 1.dp), size = 11.dp, color = color)
        }
    }
}

/**
 * Floating card over a map that follows one vehicle: "Updated 30s ago" ticking every second,
 * the live signal, and a note that the position refreshes by itself.
 */
@Composable
fun LiveLocationCard(
    title: String,
    recordedAt: Instant,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val now = rememberNow()
    val f = Freshness.of(recordedAt, now)
    val x = LocalExtra.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 12.dp, end = if (onClose != null) 0.dp else 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LiveSignal(f, size = 16.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Fmt.ago(recordedAt, now) + " · " + if (f == Freshness.LIVE) S.refreshesAutomatically else S.notReporting,
                    style = MaterialTheme.typography.labelSmall.merge(Numeric),
                    color = if (f == Freshness.LIVE) x.subtle else x.stale,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, S.stopFollowing, tint = x.subtle, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
