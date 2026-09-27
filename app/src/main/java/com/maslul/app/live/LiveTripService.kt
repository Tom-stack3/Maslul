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
import com.maslul.app.MaslulApp
import com.maslul.app.R
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.Itinerary
import com.maslul.app.data.LiveCall
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

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
            this, NOTIF_ID, ongoing("Starting live directions…", session.destination, 0f),
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
            .addAction(0, "End trip", stopPi)
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

        fun start(ctx: Context, itinerary: Itinerary, destination: String) {
            ActiveTrip._session.value = ActiveTrip.Session(itinerary, destination)
            ContextCompat.startForegroundService(ctx, Intent(ctx, LiveTripService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LiveTripService::class.java).setAction(ACTION_STOP))
        }
    }
}
