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
        val (i, t) = prefs.next()
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle(Names.of(c, i))
            .setContentText(Widgets.fmt2(t))
            .setSubText(c.getString(R.string.remaining, Names.of(c, i)))
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
