package com.sahl.adhan

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Scheduler.ACTION_ALERT) {
            val prayer = intent.getIntExtra(Scheduler.EXTRA_PRAYER, -1)
            val mode = intent.getIntExtra(Scheduler.EXTRA_MODE, ALERT_SHORT)
            if (mode != ALERT_OFF) notify(ctx, prayer, mode)
        }
        Scheduler.scheduleAll(ctx)
        Widgets.updateAll(ctx)
    }

    private fun notify(ctx: Context, prayer: Int, mode: Int) {
        Scheduler.ensureChannels(ctx)
        val channel = if (mode == ALERT_FULL) Scheduler.fullChannelId(ctx) else "adhan_short"
        val title = if (prayer < 0) ctx.getString(R.string.test_title)
                    else ctx.getString(R.string.time_for, Names.of(ctx, prayer))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(title)
            .setContentText(Prefs(ctx).city.let { if (Names.isArabic(ctx)) it.ar else it.en })
            .setCategory(if (mode == ALERT_FULL) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1000 + prayer + 1, n)
    }
}

/** Reboot, clock change, time-zone change, app update → rebuild schedule and widgets */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.scheduleAll(ctx)
        Widgets.updateAll(ctx)
    }
}
