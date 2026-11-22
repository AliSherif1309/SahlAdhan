package com.sahl.adhan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.ZonedDateTime

/**
 * Persistent "next prayer" banner: an emerald-and-gold card.
 * Battery: no service, no timers. Android draws the live timer; the app redraws the card
 * only at prayer times, 30 min after a prayer, and at midnight (the existing refresh alarm).
 */
object Banner {
    private const val ID = 600
    private const val CHANNEL = "banner_v2"
    private const val OLD_CHANNEL = "banner"
    private const val GOLD = 0xFFF5C84C.toInt()
    private const val DARK = 0xFF0A231D.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    private fun ensureChannel(nm: NotificationManager, c: Context) {
        try { nm.deleteNotificationChannel(OLD_CHANNEL) } catch (_: Exception) {}
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.banner), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun update(ctx: Context) {
        try { updateFancy(ctx) } catch (e: Exception) { updatePlain(ctx) }
    }

    /** Fallback: simple banner (used if the custom card ever fails) */
    private fun updatePlain(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val prefs = Prefs(ctx)
        if (!prefs.showBanner || !nm.areNotificationsEnabled()) { nm.cancel(ID); return }
        val c = ctx.localized()
        ensureChannel(nm, c)
        val s = prefs.show()
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(ID, Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_mosque)
            .setContentTitle(Names.row(c, s.row, s.time.toLocalDate()) + "  " + Widgets.fmt(s.time))
            .setWhen(s.time.toInstant().toEpochMilli()).setShowWhen(true).setUsesChronometer(true)
            .setChronometerCountDown(!s.since).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open).build())
    }

    private fun updateFancy(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val prefs = Prefs(ctx)
        if (!prefs.showBanner || !nm.areNotificationsEnabled()) { nm.cancel(ID); return }
        val c = ctx.localized()
        ensureChannel(nm, c)
        val now = ZonedDateTime.now(prefs.zone)
        val today = now.toLocalDate()
        val s = prefs.show(now)
        val shownDate = s.time.toLocalDate()
        val ar = Names.isArabic(c)
        val name = Names.row(c, s.row, shownDate)

        // Expanded strip always shows today's times, matching inside the app
        val todayTimes = prefs.times(today)
        val shownTimes = if (shownDate == today) todayTimes else prefs.times(shownDate)
        val subShort = buildList {
            add(prefs.city.let { if (ar) it.ar else it.en })
            if (prefs.opt("b_hijri")) add(Widgets.hijri(c, today))
        }.joinToString("  ·  ")
        val sub = buildList {
            add(prefs.city.let { if (ar) it.ar else it.en })
            if (prefs.opt("b_hijri")) add(Widgets.hijri(c, today))
            if (prefs.opt("b_after")) {
                val nr = if (s.since) {
                    prefs.nextRow(now)
                } else {
                    var nextR = s.row + 1
                    if (nextR == 1 && !prefs.opt("sunrise")) nextR = 2
                    if (nextR <= 5) nextR to shownTimes[nextR] else 0 to prefs.times(shownDate.plusDays(1))[0]
                }
                add(c.getString(R.string.then) + " " + Names.row(c, nr.first, nr.second.toLocalDate()) + " " + Widgets.fmt(nr.second))
            }
        }.joinToString("  ·  ")

        fun fill(v: RemoteViews, big: Boolean) {
            // Name + time on the reading side, label on the other
            // Arabic reads: الفجر · باقي 7:19:00 · 04:33   (English: Fajr · in 7:19:00 · 4:33)
            val label = c.getString(if (s.since) R.string.since_short else R.string.remaining_short)
            val time = Widgets.fmt(s.time)
            v.setTextViewText(R.id.b_right, if (ar) name else time)
            v.setTextViewText(R.id.b_left, if (ar) time else name)
            v.setTextColor(if (ar) R.id.b_left else R.id.b_right, 0xCCFFFFFF.toInt())
            v.setTextViewText(R.id.b_lab_r, if (ar) label else "")
            v.setTextViewText(R.id.b_lab_l, if (ar) "" else label)
            v.setViewVisibility(R.id.b_lab_r, if (ar) android.view.View.VISIBLE else android.view.View.GONE)
            v.setViewVisibility(R.id.b_lab_l, if (ar) android.view.View.GONE else android.view.View.VISIBLE)
            Widgets.countdown(v, R.id.b_timer, s.time, s.since)
            v.setTextViewText(R.id.b_sub, if (big) sub else subShort)
            if (!big) return
            val showStrip = prefs.opt("b_times")
            val vis = if (showStrip) android.view.View.VISIBLE else android.view.View.GONE
            v.setViewVisibility(R.id.b_strip, vis); v.setViewVisibility(R.id.b_div, vis)
            if (!showStrip) return
            val labels = intArrayOf(R.string.fajr, R.string.sunrise, R.string.dhuhr, R.string.asr, R.string.maghrib, R.string.isha)
            val cells = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4, R.id.c5)
            val ns = intArrayOf(R.id.cn0, R.id.cn1, R.id.cn2, R.id.cn3, R.id.cn4, R.id.cn5)
            val ts = intArrayOf(R.id.ct0, R.id.ct1, R.id.ct2, R.id.ct3, R.id.ct4, R.id.ct5)
            for (slot in 0..5) {
                val r = if (ar) 5 - slot else slot   // Arabic: Fajr on the right
                val hi = r == s.row && shownDate == today
                v.setTextViewText(ns[slot], if (r == 2) Names.of(c, 1, today) else c.getString(labels[r]))
                v.setTextViewText(ts[slot], Widgets.fmt(todayTimes[r]))
                v.setInt(cells[slot], "setBackgroundResource", if (hi) R.drawable.pill_gold else R.drawable.pill_none)
                v.setTextColor(ns[slot], if (hi) DARK else 0xD9FFFFFF.toInt())
                v.setTextColor(ts[slot], if (hi) DARK else WHITE)
            }
        }
        val small = RemoteViews(ctx.packageName, R.layout.notif_small).also { fill(it, false) }
        val big = RemoteViews(ctx.packageName, R.layout.notif_big).also { fill(it, true) }

        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val qibla = PendingIntent.getActivity(ctx, 4, Qibla.intent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mosque)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setContentTitle(name)
            .setContentText(subShort)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(GOLD)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, c.getString(R.string.times_btn), open).build())
            .addAction(Notification.Action.Builder(null, c.getString(R.string.qibla), qibla).build())
            .build()
        nm.notify(ID, n)
    }
}
