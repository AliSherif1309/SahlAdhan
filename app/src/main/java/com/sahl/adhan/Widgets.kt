package com.sahl.adhan

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Widgets {
    private const val GOLD = 0xFFF5C84C.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    @Volatile var h24 = false
    fun fmt(t: ZonedDateTime): String = t.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm", Locale.ENGLISH))
    fun fmt2(t: ZonedDateTime): String = t.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "hh:mm", Locale.ENGLISH))

    /** Hijri (Umm al-Qura) date in the app language, e.g. ٩ جمادى الآخرة ١٤٤٨ */
    fun hijri(c: Context, day: java.time.LocalDate): String {
        val loc = c.resources.configuration.locales[0]
        return java.time.chrono.HijrahDate.from(day).format(DateTimeFormatter.ofPattern("d MMMM yyyy", loc))
    }

    fun gregorian(c: Context, day: java.time.LocalDate): String =
        day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", c.resources.configuration.locales[0]))

    /** Extra line from the options: Gregorian date and/or city */
    fun extraLine(c: Context, prefs: Prefs, day: java.time.LocalDate): String {
        val parts = mutableListOf<String>()
        if (prefs.opt("date")) parts += gregorian(c, day)
        if (prefs.opt("city")) parts += prefs.city.let { if (Names.isArabic(c)) it.ar else it.en }
        return parts.joinToString(" · ")
    }

    private fun vis(on: Boolean) = if (on) android.view.View.VISIBLE else android.view.View.GONE

    private fun openQibla(ctx: Context) = PendingIntent.getActivity(
        ctx, 3, Qibla.intent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** Live timer drawn by Android: counts down to t, or up from t when [since] */
    fun countdown(v: RemoteViews, id: Int, t: ZonedDateTime, since: Boolean = false) {
        val ms = t.toInstant().toEpochMilli() - System.currentTimeMillis()
        v.setChronometer(id, SystemClock.elapsedRealtime() + ms, null, true)
        v.setChronometerCountDown(id, !since)
    }

    /** "باق على الفجر" or "مضى على الفجر" */
    fun label(c: Context, s: Prefs.Show) =
        c.getString(if (s.since) R.string.since else R.string.remaining, Names.row(c, s.row, s.time.toLocalDate()))

    fun compact(ctx: Context): RemoteViews {
        val c = ctx.localized()
        val pr = Prefs(ctx)
        val now = ZonedDateTime.now(pr.zone)
        val s = pr.show(now); val r = s.row; val t = s.time
        val today = now.toLocalDate()
        val v = RemoteViews(ctx.packageName, R.layout.widget_compact)
        v.setTextViewText(R.id.label, if (s.since) label(c, s) else c.getString(R.string.next))
        v.setTextViewText(R.id.hijri, hijri(c, today)); v.setViewVisibility(R.id.hijri, vis(pr.opt("hijri")))
        val ex = extraLine(c, pr, today)
        v.setTextViewText(R.id.extra, ex); v.setViewVisibility(R.id.extra, vis(ex.isNotEmpty()))
        v.setViewVisibility(R.id.countdown, vis(pr.opt("countdown")))
        v.setTextViewText(R.id.name, Names.row(c, r, t.toLocalDate()))
        v.setTextViewText(R.id.time, fmt(t))
        countdown(v, R.id.countdown, t, s.since)
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
        return v
    }

    fun wide(ctx: Context): RemoteViews {
        val c = ctx.localized()
        val prefs = Prefs(ctx)
        val now = ZonedDateTime.now(prefs.zone)
        val today = now.toLocalDate()
        val (next, nextT) = prefs.next(now)
        val times = prefs.fard(today)
        val ar = Names.isArabic(c)
        val v = RemoteViews(ctx.packageName, R.layout.widget_wide)
        val cells = intArrayOf(R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4)
        val ns = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4)
        val ts = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4)
        for (slot in 0..4) {
            val k = if (ar) 4 - slot else slot   // Arabic reads right to left: Fajr on the right
            val hi = k == next && nextT.toLocalDate() == today
            v.setTextViewText(ns[slot], Names.of(c, k, today))
            v.setTextViewText(ts[slot], fmt(times[k]))
            v.setInt(cells[slot], "setBackgroundResource", if (hi) R.drawable.cell_hi else R.drawable.cell_none)
            v.setTextColor(ts[slot], if (hi) GOLD else WHITE)
        }
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
        return v
    }

    /** Khatmah style: six times on one side, "until <prayer>" + live countdown on the other */
    fun khatmah(ctx: Context): RemoteViews {
        val c = ctx.localized()
        val prefs = Prefs(ctx)
        val now = ZonedDateTime.now(prefs.zone)
        val today = now.toLocalDate()
        val s = prefs.show(now); val nextRow = s.row; val nextT = s.time
        val all = prefs.times(today)
        val ar = Names.isArabic(c)
        val labels = intArrayOf(R.string.fajr, R.string.sunrise, R.string.dhuhr, R.string.asr, R.string.maghrib, R.string.isha)
        val ls = intArrayOf(R.id.l0, R.id.l1, R.id.l2, R.id.l3, R.id.l4, R.id.l5)
        val rs = intArrayOf(R.id.r0, R.id.r1, R.id.r2, R.id.r3, R.id.r4, R.id.r5)
        val v = RemoteViews(ctx.packageName, R.layout.widget_khatmah)
        for (row in 0..5) {
            val name = if (row == 2) Names.of(c, 1, today) else c.getString(labels[row]); val time = fmt2(all[row])
            // Arabic: time on the left, name on the right (like Khatmah). English: the reverse.
            v.setTextViewText(ls[row], if (ar) time else name)
            v.setTextViewText(rs[row], if (ar) name else time)
            val col = if (row == nextRow && nextT.toLocalDate() == today) GOLD else WHITE
            v.setTextColor(ls[row], col); v.setTextColor(rs[row], col)
        }
        v.setTextViewText(R.id.remain, label(c, s))
        countdown(v, R.id.countdown, nextT, s.since)
        v.setTextViewText(R.id.hijri, hijri(c, today))
        v.setViewVisibility(R.id.hijri, vis(prefs.opt("hijri")))
        val ex = extraLine(c, prefs, today)
        v.setTextViewText(R.id.extra, ex); v.setViewVisibility(R.id.extra, vis(ex.isNotEmpty()))
        v.setViewVisibility(R.id.countdown, vis(prefs.opt("countdown")))
        v.setViewVisibility(R.id.row1, vis(prefs.opt("sunrise")))
        v.setViewVisibility(R.id.qibla, vis(prefs.opt("qibla")))
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
        v.setOnClickPendingIntent(R.id.qibla, openQibla(ctx))
        return v
    }

    fun updateAll(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        m.getAppWidgetIds(ComponentName(ctx, CompactWidget::class.java)).forEach { m.updateAppWidget(it, compact(ctx)) }
        m.getAppWidgetIds(ComponentName(ctx, WideWidget::class.java)).forEach { m.updateAppWidget(it, wide(ctx)) }
        m.getAppWidgetIds(ComponentName(ctx, KhatmahWidget::class.java)).forEach { m.updateAppWidget(it, khatmah(ctx)) }
        Banner.update(ctx)
    }
}

class CompactWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, Widgets.compact(ctx)) }; Banner.update(ctx); Scheduler.scheduleAll(ctx)
    }
}

class WideWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, Widgets.wide(ctx)) }; Banner.update(ctx); Scheduler.scheduleAll(ctx)
    }
}

class KhatmahWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, Widgets.khatmah(ctx)) }; Banner.update(ctx); Scheduler.scheduleAll(ctx)
    }
}
