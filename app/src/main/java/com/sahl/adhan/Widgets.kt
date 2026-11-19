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

    fun fmt(t: ZonedDateTime): String = t.format(DateTimeFormatter.ofPattern("h:mm", Locale.ENGLISH))
    fun fmt2(t: ZonedDateTime): String = t.format(DateTimeFormatter.ofPattern("hh:mm", Locale.ENGLISH))

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun countdown(v: RemoteViews, id: Int, t: ZonedDateTime) {
        val ms = t.toInstant().toEpochMilli() - System.currentTimeMillis()
        v.setChronometer(id, SystemClock.elapsedRealtime() + ms, null, true)
        v.setChronometerCountDown(id, true)
    }

    fun compact(ctx: Context): RemoteViews {
        val c = ctx.localized()
        val (i, t) = Prefs(ctx).next()
        val v = RemoteViews(ctx.packageName, R.layout.widget_compact)
        v.setTextViewText(R.id.label, c.getString(R.string.next))
        v.setTextViewText(R.id.name, Names.of(c, i))
        v.setTextViewText(R.id.time, fmt(t))
        countdown(v, R.id.countdown, t)
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
        return v
    }

    /** Day shown on widgets: after Isha, tomorrow's timetable so the highlight is right */
    private fun shownDay(prefs: Prefs, now: ZonedDateTime, nextT: ZonedDateTime) =
        if (nextT.toLocalDate().isAfter(now.toLocalDate())) now.toLocalDate().plusDays(1) else now.toLocalDate()

    fun wide(ctx: Context): RemoteViews {
        val c = ctx.localized()
        val prefs = Prefs(ctx)
        val now = ZonedDateTime.now(prefs.zone)
        val (next, nextT) = prefs.next(now)
        val times = prefs.fard(shownDay(prefs, now, nextT))
        val ar = Names.isArabic(c)
        val v = RemoteViews(ctx.packageName, R.layout.widget_wide)
        val cells = intArrayOf(R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4)
        val ns = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4)
        val ts = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4)
        for (slot in 0..4) {
            val k = if (ar) 4 - slot else slot   // Arabic reads right to left: Fajr on the right
            v.setTextViewText(ns[slot], Names.of(c, k))
            v.setTextViewText(ts[slot], fmt(times[k]))
            v.setInt(cells[slot], "setBackgroundResource", if (k == next) R.drawable.cell_hi else R.drawable.cell_none)
            v.setTextColor(ts[slot], if (k == next) GOLD else WHITE)
        }
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
        return v
    }

    /** Khatmah style: six times on one side, "until <prayer>" + live countdown on the other */
    fun khatmah(ctx: Context): RemoteViews {
        val c = ctx.localized()
        val prefs = Prefs(ctx)
        val now = ZonedDateTime.now(prefs.zone)
        val (next, nextT) = prefs.next(now)
        val all = prefs.times(shownDay(prefs, now, nextT))
        val ar = Names.isArabic(c)
        val labels = intArrayOf(R.string.fajr, R.string.sunrise, R.string.dhuhr, R.string.asr, R.string.maghrib, R.string.isha)
        val nextRow = intArrayOf(0, 2, 3, 4, 5)[next]
        val ls = intArrayOf(R.id.l0, R.id.l1, R.id.l2, R.id.l3, R.id.l4, R.id.l5)
        val rs = intArrayOf(R.id.r0, R.id.r1, R.id.r2, R.id.r3, R.id.r4, R.id.r5)
        val v = RemoteViews(ctx.packageName, R.layout.widget_khatmah)
        for (row in 0..5) {
            val name = c.getString(labels[row]); val time = fmt2(all[row])
            // Arabic: time on the left, name on the right (like Khatmah). English: the reverse.
            v.setTextViewText(ls[row], if (ar) time else name)
            v.setTextViewText(rs[row], if (ar) name else time)
            val col = if (row == nextRow) GOLD else WHITE
            v.setTextColor(ls[row], col); v.setTextColor(rs[row], col)
        }
        v.setTextViewText(R.id.remain, c.getString(R.string.remaining, Names.of(c, next)))
        countdown(v, R.id.countdown, nextT)
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
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
        ids.forEach { m.updateAppWidget(it, Widgets.compact(ctx)) }; Scheduler.scheduleAll(ctx)
    }
}

class WideWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, Widgets.wide(ctx)) }; Scheduler.scheduleAll(ctx)
    }
}

class KhatmahWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, Widgets.khatmah(ctx)) }; Scheduler.scheduleAll(ctx)
    }
}
