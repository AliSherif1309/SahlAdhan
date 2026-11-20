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
            when (mode) {
                ALERT_FULL -> if (!AdhanService.start(ctx, prayer)) shortAlert(ctx, prayer)
                ALERT_SHORT -> shortAlert(ctx, prayer)
            }
        }
        Scheduler.scheduleAll(ctx)
        Widgets.updateAll(ctx)
        Updater.dailyCheck(ctx)
        Official.syncIfNeeded(ctx)   // once a day: fetch the official Qatar Calendar times
    }

    companion object {
        fun title(ctx: Context, prayer: Int): String {
            val c = ctx.localized()
            return when {
                prayer < 0 -> c.getString(R.string.test_title)
                prayer == 5 -> c.getString(R.string.sunrise_time)
                prayer == 6 -> c.getString(R.string.first_adhan)
                prayer in 10..14 -> c.getString(R.string.iqama_time, Names.of(c, prayer - 10, java.time.LocalDate.now(Prefs(ctx).zone)))
                else -> c.getString(R.string.time_for, Names.of(c, prayer, java.time.LocalDate.now(Prefs(ctx).zone)))
            }
        }

        fun shortAlert(ctx: Context, prayer: Int) {
            // Your own short sound plays through the player (Android channels can't use private files)
            // Iqama alerts (10..14) have their own sound
            val key = if (prayer in 10..14) Prefs(ctx).iqamaSound else Prefs(ctx).shortSound
            if (Sounds.isCustom(key) && AdhanService.start(ctx, prayer, key)) return
            Scheduler.ensureChannels(ctx, key)
            val c = ctx.localized()
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, Scheduler.shortChannelId(ctx, key))
                .setSmallIcon(R.drawable.ic_mosque)
                .setContentTitle(title(ctx, prayer))
                .setContentText(Prefs(ctx).city.let { if (Names.isArabic(c)) it.ar else it.en })
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(1000 + prayer + 1, n)
        }
    }
}

/** Reboot, clock change, time-zone change, app update → rebuild schedule and widgets */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.scheduleAll(ctx)
        Widgets.updateAll(ctx)
    }
}
