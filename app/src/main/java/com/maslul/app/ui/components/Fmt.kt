package com.maslul.app.ui.components

import com.maslul.app.data.IsraelZone
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.US)

    fun time(i: Instant): String = hm.format(i.atZone(IsraelZone))

    /** "Now", "4 min", or "21:40" once more than an hour away. */
    fun relative(t: Instant, now: Instant): String {
        val sec = t.epochSecond - now.epochSecond
        return when {
            sec < 45 -> "Now"
            sec < 60 * 60 -> "${(sec + 30) / 60} min"
            else -> time(t)
        }
    }

    /** "Updated 30s ago", "Updated 2 min ago" — how old a live position is. */
    fun ago(t: Instant, now: Instant): String {
        val sec = (now.epochSecond - t.epochSecond).coerceAtLeast(0)
        return when {
            sec < 5 -> "Updated just now"
            sec < 60 -> "Updated ${sec}s ago"
            sec < 60 * 60 -> "Updated ${sec / 60} min ago"
            else -> "Updated at ${time(t)}"
        }
    }

    fun duration(sec: Long): String {
        val m = ((sec + 30) / 60).coerceAtLeast(1)
        return if (m < 60) "$m min" else "${m / 60} h ${(m % 60).toString().padStart(2, '0')} min".replace(" 00 min", "")
    }

    fun distance(m: Double): String = if (m < 1000) "${(m / 10).toInt() * 10} m" else "%.1f km".format(Locale.US, m / 1000)

    fun day(date: LocalDate, today: LocalDate = LocalDate.now(IsraelZone)): String = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> dayFmt.format(date)
    }
}
