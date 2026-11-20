package com.sahl.adhan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Persistent "next prayer" notification, like Qatar Calendar House's.
 * Battery: no service and no timers. Android draws the countdown itself (chronometer);
 * the app only refreshes it at prayer times and midnight, via the existing alarm.
 */
object Banner {
    private const val ID = 600
    private const val CHANNEL = "banner"

    fun update(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val prefs = Prefs(ctx)
        if (!prefs.showBanner || !nm.areNotificationsEnabled()) { nm.cancel(ID); return }
        val c = ctx.localized()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.banner), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        val (row, t) = prefs.nextRow()
        val day = t.toLocalDate()
        val name = Names.row(c, row, day)
        val city = prefs.city.let { if (Names.isArabic(c)) it.ar else it.en }
        // Collapsed line: time · city · hijri
        val line = buildList {
            add(Widgets.fmt2(t)); add(city)
            if (prefs.opt("b_hijri")) add(Widgets.hijri(c, java.time.LocalDate.now(prefs.zone)))
        }.joinToString(" · ")
        // Expanded: the prayer after next, then the whole day's timetable with the next one marked
        val big = StringBuilder(line)
        if (prefs.opt("b_after")) {
            val all6 = prefs.times(day)
            val after = if (row < 5) Names.row(c, row + 1, day) + " " + Widgets.fmt2(all6[row + 1])
                        else c.getString(R.string.fajr) + " " + Widgets.fmt2(prefs.times(day.plusDays(1))[0])
            big.append("\n").append(c.getString(R.string.then)).append(": ").append(after)
        }
        if (prefs.opt("b_times")) {
            val all = prefs.times(day)
            val labels = intArrayOf(R.string.fajr, R.string.sunrise, R.string.dhuhr, R.string.asr, R.string.maghrib, R.string.isha)
            big.append("\n")
            for (r in 0..5) {
                val n = if (r == 2) Names.of(c, 1, day) else c.getString(labels[r])
                big.append("\n").append(if (r == row) "▸ " else "   ").append(n).append("  ").append(Widgets.fmt2(all[r]))
            }
        }
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(name)
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(big.toString()))
            .setSubText(c.getString(R.string.remaining, name))
            .setWhen(t.toInstant().toEpochMilli())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF12463A.toInt())
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .build()
        nm.notify(ID, n)
    }
}
