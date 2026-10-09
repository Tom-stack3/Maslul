package com.maslul.app.ui.components

import com.maslul.app.data.IsraelZone
import com.maslul.app.i18n.S
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun time(i: Instant): String = hm.format(i.atZone(IsraelZone))

    /** "Now", "4 min", or "21:40" once more than an hour away. */
    fun relative(t: Instant, now: Instant): String {
        val sec = t.epochSecond - now.epochSecond
        return when {
            sec < 45 -> S.now
            sec < 60 * 60 -> S.min((sec + 30) / 60)
            else -> time(t)
        }
    }

    /** "Updated 30s ago", "Updated 2 min ago" — how old a live position is. */
    fun ago(t: Instant, now: Instant): String {
        val sec = (now.epochSecond - t.epochSecond).coerceAtLeast(0)
        return when {
            sec < 5 -> S.updatedJustNow
            sec < 60 -> S.updatedSecAgo(sec)
            sec < 60 * 60 -> S.updatedMinAgo(sec / 60)
            else -> S.updatedAt(time(t))
        }
    }

    fun duration(sec: Long): String {
        val m = ((sec + 30) / 60).coerceAtLeast(1)
        return if (m < 60) S.min(m) else S.hoursMin(m / 60, m % 60)
    }

    fun distance(m: Double): String = if (m < 1000) S.meters((m / 10).toInt() * 10) else S.km("%.1f".format(Locale.US, m / 1000))

    fun day(date: LocalDate, today: LocalDate = LocalDate.now(IsraelZone)): String = when (date) {
        today -> S.today
        today.plusDays(1) -> S.tomorrow
        else -> S.date(date)
    }
}
