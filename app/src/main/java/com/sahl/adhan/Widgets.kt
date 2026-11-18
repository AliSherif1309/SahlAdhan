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
    fun fmt(ctx: Context, t: ZonedDateTime): String =
        t.format(DateTimeFormatter.ofPattern("h:mm", Locale.getDefault()))

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun compact(ctx: Context): RemoteViews {
        val prefs = Prefs(ctx)
        val (i, t) = prefs.next()
        val v = RemoteViews(ctx.packageName, R.layout.widget_compact)
        v.setTextViewText(R.id.name, Names.of(ctx, i))
        v.setTextViewText(R.id.time, fmt(ctx, t))
        val ms = t.toInstant().toEpochMilli() - System.currentTimeMillis()
        v.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + ms, null, true)
        v.setChronometerCountDown(R.id.countdown, true)
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
        return v
    }

    fun wide(ctx: Context): RemoteViews {
        val prefs = Prefs(ctx)
        val now = ZonedDateTime.now(prefs.zone)
        val (next, nextT) = prefs.next(now)
        // after Isha show tomorrow's timetable so the highlighted Fajr is correct
        val day = if (nextT.toLocalDate().isAfter(now.toLocalDate())) now.toLocalDate().plusDays(1) else now.toLocalDate()
        val times = prefs.fard(day)
        val v = RemoteViews(ctx.packageName, R.layout.widget_wide)
        val cells = intArrayOf(R.id.cell0, R.id.cell1, R.id.cell2, R.id.cell3, R.id.cell4)
        val ns = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4)
        val ts = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4)
        for (k in 0..4) {
            v.setTextViewText(ns[k], Names.of(ctx, k))
            v.setTextViewText(ts[k], fmt(ctx, times[k]))
            v.setInt(cells[k], "setBackgroundResource", if (k == next) R.drawable.cell_hi else R.drawable.cell_none)
            v.setTextColor(ts[k], if (k == next) 0xFFE2C25B.toInt() else 0xFFFFFFFF.toInt())
        }
        v.setOnClickPendingIntent(R.id.root, openApp(ctx))
        return v
    }

    fun updateAll(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        m.getAppWidgetIds(ComponentName(ctx, CompactWidget::class.java)).forEach { m.updateAppWidget(it, compact(ctx)) }
        m.getAppWidgetIds(ComponentName(ctx, WideWidget::class.java)).forEach { m.updateAppWidget(it, wide(ctx)) }
    }
}

class CompactWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, Widgets.compact(ctx)) }
        Scheduler.scheduleAll(ctx)
    }
}

class WideWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        ids.forEach { m.updateAppWidget(it, Widgets.wide(ctx)) }
        Scheduler.scheduleAll(ctx)
    }
}
