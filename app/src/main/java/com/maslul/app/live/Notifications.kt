package com.maslul.app.live

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.maslul.app.MainActivity
import com.maslul.app.R
import java.time.Instant

object Notifs {
    const val CH_TRIP = "live_trip"
    const val CH_ALERT = "trip_alerts"
    const val CH_REMIND = "reminders"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRIP, "Live directions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing step-by-step trip guidance"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Get-off & boarding alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when your ride is arriving or it's time to get off"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 150, 350)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Departure reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders to leave on time"
            },
        )
    }

    fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canPost(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
}

/** Schedules "time to leave" notifications with AlarmManager. */
object Reminders {
    fun schedule(ctx: Context, at: Instant, title: String, text: String): Boolean {
        if (at.isBefore(Instant.now())) return false
        val am = ctx.getSystemService(AlarmManager::class.java)
        val id = (at.epochSecond % Int.MAX_VALUE).toInt()
        val pi = PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, ReminderReceiver::class.java)
                .putExtra("title", title)
                .putExtra("text", text)
                .putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        return true
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Notifs.ensureChannels(ctx)
        if (!Notifs.canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, Notifs.CH_REMIND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(intent.getStringExtra("title"))
            .setContentText(intent.getStringExtra("text"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(intent.getStringExtra("text")))
            .setContentIntent(Notifs.openAppIntent(ctx))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(intent.getIntExtra("id", 7), n) }
    }
}
