package com.maslul.app.live

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.maslul.app.MainActivity
import com.maslul.app.MaslulApp
import com.maslul.app.R
import com.maslul.app.data.AppJson
import com.maslul.app.data.GeoPoint
import com.maslul.app.data.PlaceKind
import com.maslul.app.data.SavedTrip
import com.maslul.app.data.TransitousApi
import com.maslul.app.data.TripAlerts
import com.maslul.app.i18n.S
import com.maslul.app.ui.components.Fmt
import com.maslul.app.ui.nav.rideName
import java.time.Instant

/**
 * Watches saved trips that have an alert (see [TripAlerts]): an alarm per trip wakes [TripCheckWorker]
 * at the start of its watch and every few minutes through it, then waits for the next day it's taken.
 */
object TripWatcher {
    private const val PREFS = "trip_alerts"
    const val EXTRA_TRIP = "trip_key"

    /**
     * Sets (or clears) the next alarm of every saved trip: after a change, a reboot or an update. A trip
     * already inside its watch is checked right away rather than in a few minutes.
     */
    fun scheduleAll(ctx: Context, now: Instant = Instant.now()) {
        val trips = MaslulApp.instance.store.data.value.savedTrips
        trips.forEach { schedule(ctx, it, now, soon = true) }
    }

    /** The next check of [trip] (with [soon], at once if it's being watched now), or none when it isn't watched. */
    fun schedule(ctx: Context, trip: SavedTrip, now: Instant = Instant.now(), soon: Boolean = false) {
        val pi = alarmIntent(ctx, trip.key)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val departure = trip.alert?.let { TripAlerts.nextDeparture(it, now) }
        val watching = departure != null && !now.isBefore(departure.minusSeconds(TripAlerts.WATCH_BEFORE_SEC))
        val at = if (soon && watching) now.plusSeconds(5) else departure?.let { TripAlerts.nextCheck(it, now) }
        if (at == null) { am.cancel(pi); return }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
    }

    /** Whether alarms can come right on time (else Android may hold them back a few minutes). */
    fun exactAlarms(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun alarmIntent(ctx: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, requestCode(key),
        Intent(ctx, TripAlertReceiver::class.java).setAction(ACTION_CHECK).putExtra(EXTRA_TRIP, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun requestCode(key: String) = 7_000 + (key.hashCode() and 0x3FFF)

    /** Opens the trip's routes from a notification. */
    fun openIntent(ctx: Context, key: String): PendingIntent = PendingIntent.getActivity(
        ctx, requestCode(key),
        Intent(ctx, MainActivity::class.java).putExtra(EXTRA_TRIP, key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(ctx: Context, key: String): TripAlerts.Progress? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?.let { runCatching { AppJson.decodeFromString<TripAlerts.Progress>(it) }.getOrNull() }

    fun saveProgress(ctx: Context, key: String, p: TripAlerts.Progress) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, AppJson.encodeToString(p)).apply()
    }

    const val ACTION_CHECK = "com.maslul.app.TRIP_CHECK"
}

/** Wakes up for a trip's check (handing the work to [TripCheckWorker]), and re-arms everything after a reboot or update. */
class TripAlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            TripWatcher.ACTION_CHECK -> {
                val key = intent.getStringExtra(TripWatcher.EXTRA_TRIP) ?: return
                val work = OneTimeWorkRequestBuilder<TripCheckWorker>()
                    .setInputData(workDataOf(TripWatcher.EXTRA_TRIP to key))
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(ctx).enqueueUniqueWork("trip-$key", ExistingWorkPolicy.REPLACE, work)
            }
            else -> TripWatcher.scheduleAll(ctx)
        }
    }
}

/** One check of a watched trip: plans it with live times, sends what's due, and sets the next check. */
class TripCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val key = inputData.getString(TripWatcher.EXTRA_TRIP) ?: return Result.success()
        val app = MaslulApp.instance
        val trip = app.store.data.value.savedTrips.firstOrNull { it.key == key } ?: return Result.success()
        val alert = trip.alert ?: return Result.success()
        val now = Instant.now()
        try {
            val departure = TripAlerts.nextDeparture(alert, now) ?: return Result.success()
            // Too early (an alarm that came ahead of time): just wait for the watch.
            if (now.isBefore(departure.minusSeconds(TripAlerts.WATCH_BEFORE_SEC))) return Result.success()
            val from = point(trip.from.kind, trip.from.point) ?: return Result.success()
            val s = app.store.data.value.settings
            val req = TransitousApi.PlanRequest(
                from = from, to = trip.to.point, time = now,
                modes = s.modes, walkSpeedMps = s.walkSpeed.mps, maxWalkMinutes = s.maxWalkMinutes, wheelchair = s.wheelchair,
                // From now until well past the usual time: earlier ways, the usual one, and what follows it.
                searchWindowSec = ((departure.epochSecond - now.epochSecond).coerceAtLeast(0) + TripAlerts.NO_SERVICE_SEC).toInt(),
                numItineraries = 10,
            )
            val options = app.repo.tripOptions(req, s.railPreference, now)
            val before = TripWatcher.progress(ctx, key)?.takeIf { it.departure == departure.epochSecond }
                ?: TripAlerts.Progress(departure.epochSecond)
            val (alerts, progress) = TripAlerts.decide(before, departure, options, now, s.reminderMinutes * 60L)
            // An alert that couldn't be shown (notifications not allowed yet) isn't sent: the next check tries again.
            val shown = alerts.isEmpty() || notify(ctx, trip, alerts)
            TripWatcher.saveProgress(ctx, key, if (shown) progress else progress.copy(sent = before.sent))
        } catch (e: Exception) {
            // A failed check (no network) is skipped; the next one comes as planned.
        } finally {
            TripWatcher.schedule(ctx, trip, Instant.now())
        }
        return Result.success()
    }

    private suspend fun point(kind: PlaceKind, saved: GeoPoint): GeoPoint? =
        if (kind != PlaceKind.CURRENT_LOCATION) saved
        else runCatching { MaslulApp.instance.location.current() }.getOrNull() ?: MaslulApp.instance.location.last.value

    /** Shows [alerts] as one notification; false when it couldn't be shown. */
    private fun notify(ctx: Context, trip: SavedTrip, alerts: List<TripAlerts.Alert>): Boolean {
        Notifs.ensureChannels(ctx)
        if (!Notifs.canPost(ctx)) return false
        val place = trip.label ?: trip.to.name
        val go = alerts.filterIsInstance<TripAlerts.Alert.GoNow>().firstOrNull()
        val earlier = alerts.filterIsInstance<TripAlerts.Alert.LeaveEarlier>().firstOrNull()
        val none = alerts.filterIsInstance<TripAlerts.Alert.NoService>().firstOrNull()
        val (title, text) = when {
            none != null -> S.noServiceTo(place) to (none.next?.let { S.nextRideAt(rideName(it.mode, it.line), Fmt.time(it.boardAt)) } ?: S.nothingSoon)
            // Leaving earlier and it's time already: one notification that says both.
            earlier != null -> S.leaveEarlierTo(earlier.earlierMin, place) to listOfNotNull(
                earlier.usual.lateMinutes.takeIf { it > 0 }?.let { S.runningLate(rideName(earlier.usual.mode, earlier.usual.line), it) },
                describe(earlier.option),
            ).joinToString(". ")
            go != null -> S.timeToGoTo(place) to describe(go.option)
            else -> return true
        }
        val n = NotificationCompat.Builder(ctx, Notifs.CH_REMIND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(TripWatcher.openIntent(ctx, trip.key))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(45 * 60_000L)
            .build()
        return runCatching { NotificationManagerCompat.from(ctx).notify(trip.key.hashCode(), n) }.isSuccess
    }

    /** "Leave at 08:24 · Bus 18 from Dizengoff at 08:31 (live) · arrive 09:02". */
    private fun describe(o: TripAlerts.Option): String = listOf(
        S.leaveAt(Fmt.time(o.leaveAt)),
        S.rideFromAt(rideName(o.mode, o.line), o.stop, Fmt.time(o.boardAt)) + if (o.live) " (${S.liveLower})" else "",
        S.alertArrive(Fmt.time(o.arriveAt)),
    ).joinToString(" · ")

    /** Expedited work before Android 12 runs as a foreground job, which needs a (quiet) notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notifs.ensureChannels(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Notifs.CH_TRIP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(S.checkingTrip)
            .setSilent(true)
            .build()
        // Only ever asked for before Android 12, where no service type is needed.
        return ForegroundInfo(CHECK_NOTIF_ID, n)
    }

    private companion object {
        const val CHECK_NOTIF_ID = 43
    }
}
