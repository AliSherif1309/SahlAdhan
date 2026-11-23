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
                ALERT_IQAMA -> {
                    val key = Prefs(ctx).iqamaSound
                    if (!AdhanService.start(ctx, prayer, key)) shortAlert(ctx, prayer, key)
                }
                ALERT_PRE_ADHAN, ALERT_SHORT -> shortAlert(ctx, prayer)
            }
        }
        Scheduler.scheduleAll(ctx)
        Widgets.updateAll(ctx)
        Updater.dailyCheck(ctx)
        Official.syncIfNeeded(ctx)   // once a day: fetch the official Qatar Calendar times
        AutoLocation.checkIfNeeded(ctx, background = true)
    }

    companion object {
        fun title(ctx: Context, prayer: Int): String {
            val c = ctx.localized()
            return when {
                prayer == -3 -> c.getString(R.string.test_pre_adhan_title)
                prayer == -2 -> c.getString(R.string.test_iqama_title)
                prayer < 0 -> c.getString(R.string.test_title)
                prayer == 5 -> c.getString(R.string.sunrise_time)
                prayer == 6 -> c.getString(R.string.first_adhan)
                prayer in 20..24 -> {
                    val p = prayer - 20
                    c.getString(R.string.pre_adhan_time, Prefs(ctx).preAdhanMin(p),
                        Names.of(c, p, java.time.LocalDate.now(Prefs(ctx).zone)))
                }
                prayer in 10..14 -> c.getString(R.string.iqama_time, Names.of(c, prayer - 10, java.time.LocalDate.now(Prefs(ctx).zone)))
                else -> c.getString(R.string.time_for, Names.of(c, prayer, java.time.LocalDate.now(Prefs(ctx).zone)))
            }
        }

        fun shortAlert(ctx: Context, prayer: Int, soundKey: String? = null) {
            // Your own short sound and Iqama alerts play through AdhanService so Stop / volume down / flip all work
            val key = soundKey ?: when {
                prayer == -3 || prayer in 20..24 -> Prefs(ctx).preAdhanSound
                prayer == -2 || prayer in 10..14 -> Prefs(ctx).iqamaSound
                else -> Prefs(ctx).shortSound
            }
            if (soundKey == null && (prayer in 10..14 || Sounds.isCustom(key)) && AdhanService.start(ctx, prayer, key)) return
            Scheduler.ensureChannels(ctx, key)
            val c = ctx.localized()
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, Scheduler.shortChannelId(ctx, key))
                .setSmallIcon(R.drawable.ic_sahl_notif)
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
        Official.syncIfNeeded(ctx)
        AutoLocation.checkIfNeeded(ctx, force = intent.action == Intent.ACTION_TIMEZONE_CHANGED, background = true)
    }
}
