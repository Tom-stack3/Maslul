package com.maslul.app.live

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.maslul.app.MainActivity
import com.maslul.app.MaslulApp
import com.maslul.app.R
import com.maslul.app.data.Combine
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Itinerary
import com.maslul.app.data.Leg
import com.maslul.app.data.LiveCall
import com.maslul.app.ui.nav.rideName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import com.maslul.app.i18n.S

/** The trip being guided, shared with the UI (same process). */
object ActiveTrip {
    data class Session(val itinerary: Itinerary, val destination: String, val state: GuideState? = null)

    internal val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
}

/**
 * Foreground service for live directions: follows location, keeps an ongoing notification
 * with the current step, and alerts when the ride is arriving and before your stop.
 */
class LiveTripService : LifecycleService() {
    private var loop: Job? = null
    private var pos: GeoPoint? = null
    private val fired = HashSet<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        val session = ActiveTrip.session.value ?: run { stopSelf(); return START_NOT_STICKY }
        Notifs.ensureChannels(this)
        ServiceCompat.startForeground(
            this, NOTIF_ID, ongoing(S.startingLiveDirections, session.destination, 0f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        fired.clear()
        loop?.cancel()
        loop = lifecycleScope.launch { run(session) }
        lifecycleScope.launch {
            MaslulApp.instance.location.updates(4000).collect { pos = it }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(session: ActiveTrip.Session) {
        val guide = TripGuide(session.itinerary, session.destination)
        val repo = MaslulApp.instance.repo
        val live = HashMap<Int, LiveCall>()
        var lastLive = Instant.EPOCH
        while (true) {
            val now = Instant.now()
            if (now.isAfter(lastLive.plusSeconds(30))) {
                lastLive = now
                session.itinerary.legs.forEachIndexed { i, leg ->
                    if (leg.mode.isTransit && leg.end.isAfter(now)) {
                        runCatching { repo.legLive(leg, now) }.getOrNull()?.let { live[i] = it }
                    }
                }
            }
            val state = guide.update(pos, now, live)
            ActiveTrip._session.value = session.copy(state = state)
            NotificationManagerCompat.from(this).runCatching {
                notify(NOTIF_ID, ongoing(state.title, state.text, state.progress))
            }
            if (state.alert != null && state.alertKey != null && fired.add(state.alertKey)) alert(state)
            // A ride running late enough to miss the next one: say so, and offer the other ways from here.
            missedConnection(session.itinerary, now) { l -> session.itinerary.legs.indexOfFirst { it === l }.takeIf { it >= 0 }?.let(live::get) }
                ?.let { i -> if (fired.add("miss-$i")) alertMiss(session.itinerary.legs[i]) }
            if (state.finished) {
                delay(60_000)
                stop()
                return
            }
            // Give up an hour after the planned arrival.
            if (now.isAfter(session.itinerary.end.plusSeconds(3600))) { stop(); return }
            delay(5000)
        }
    }

    private fun ongoing(title: String, text: String, progress: Float): Notification {
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, LiveTripService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifs.CH_TRIP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, (progress * 100).toInt(), false)
            .setContentIntent(Notifs.openAppIntent(this))
            .addAction(0, S.otherWays, rerouteIntent(this))
            .addAction(0, S.endTrip, stopPi)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .build()
    }

    private fun alert(s: GuideState) {
        if (!Notifs.canPost(this)) return
        val n = NotificationCompat.Builder(this, Notifs.CH_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title)
            .setContentText(s.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(Notifs.openAppIntent(this))
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(ALERT_ID, n) }
    }

    private fun alertMiss(leg: Leg) {
        if (!Notifs.canPost(this)) return
        val n = NotificationCompat.Builder(this, Notifs.CH_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S.mayMiss(rideName(leg)))
            .setContentText(S.tapForOtherWays)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(rerouteIntent(this))
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(ALERT_ID, n) }
    }

    private fun stop() {
        loop?.cancel()
        ActiveTrip._session.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val NOTIF_ID = 41
        private const val ALERT_ID = 42
        private const val ACTION_STOP = "stop"
        /** Opens the live trip on the other ways from here. */
        const val EXTRA_REROUTE = "reroute"

        fun rerouteIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_REROUTE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * The leg of [itin] whose ride can't be caught any more as the rides before it are expected
         * to run ([liveOf]), if any. Only rides after the first: before boarding, walking faster helps.
         */
        fun missedConnection(itin: Itinerary, now: Instant, liveOf: (Leg) -> LiveCall?): Int? {
            val transit = itin.legs.indices.filter { itin.legs[it].mode.isTransit }
            return transit.drop(1).firstOrNull { i ->
                val leg = itin.legs[i]
                leg.start.isAfter(now.minusSeconds(60)) &&
                    !Combine.catchable(leg, liveOf(leg), Combine.reachBy(itin, i, now, liveOf = liveOf))
            }
        }

        fun start(ctx: Context, itinerary: Itinerary, destination: String) {
            ActiveTrip._session.value = ActiveTrip.Session(itinerary, destination)
            ContextCompat.startForegroundService(ctx, Intent(ctx, LiveTripService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LiveTripService::class.java).setAction(ACTION_STOP))
        }
    }
}
