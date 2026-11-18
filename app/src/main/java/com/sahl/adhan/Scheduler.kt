package com.sahl.adhan

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import java.time.ZonedDateTime

/**
 * Schedules: one exact alarm per prayer (next occurrence), plus one refresh alarm at the
 * next boundary (next prayer or midnight) so widgets switch without polling.
 */
object Scheduler {
    const val EXTRA_PRAYER = "prayer"
    const val EXTRA_MODE = "mode"
    const val ACTION_ALERT = "com.sahl.adhan.ALERT"
    const val ACTION_REFRESH = "com.sahl.adhan.REFRESH"
    private const val REQ_REFRESH = 100
    private const val REQ_TEST = 200

    fun canExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }

    private fun setAt(ctx: Context, millis: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
    }

    private fun pi(ctx: Context, req: Int, intent: Intent) = PendingIntent.getBroadcast(
        ctx, req, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun scheduleAll(ctx: Context) {
        val prefs = Prefs(ctx)
        val now = ZonedDateTime.now(prefs.zone)
        val today = prefs.fard(now.toLocalDate())
        val tomorrow = prefs.fard(now.toLocalDate().plusDays(1))
        val am = ctx.getSystemService(AlarmManager::class.java)

        for (i in 0..4) {
            val intent = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALERT)
                .putExtra(EXTRA_PRAYER, i).putExtra(EXTRA_MODE, prefs.alert(i))
            val p = pi(ctx, i, intent)
            if (prefs.alert(i) == ALERT_OFF) { am.cancel(p); continue }
            val t = if (today[i].isAfter(now)) today[i] else tomorrow[i]
            setAt(ctx, t.toInstant().toEpochMilli(), p)
        }

        // Refresh at the next prayer time or at local midnight, whichever is first
        val nextPrayer = prefs.next(now).second
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(prefs.zone)
        val boundary = if (nextPrayer.isBefore(midnight)) nextPrayer else midnight
        setAt(ctx, boundary.toInstant().toEpochMilli() + 1000,
            pi(ctx, REQ_REFRESH, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_REFRESH)))
    }

    fun scheduleTest(ctx: Context, mode: Int) {
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALERT)
            .putExtra(EXTRA_PRAYER, -1).putExtra(EXTRA_MODE, mode)
        setAt(ctx, System.currentTimeMillis() + 60_000, pi(ctx, REQ_TEST + mode, intent))
    }

    /** If you add app/src/main/res/raw/adhan.mp3, it becomes the full adhan sound. */
    fun fullChannelId(ctx: Context): String {
        val raw = ctx.resources.getIdentifier("adhan", "raw", ctx.packageName)
        return if (raw != 0) "adhan_full_file" else "adhan_full_default"
    }

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val raw = ctx.resources.getIdentifier("adhan", "raw", ctx.packageName)
        val fullSound: Uri = if (raw != 0) Uri.parse("android.resource://${ctx.packageName}/$raw")
                             else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarmAttrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val full = NotificationChannel(fullChannelId(ctx), ctx.getString(R.string.alert_full), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(fullSound, alarmAttrs); enableVibration(true); setBypassDnd(true)
        }
        val short = NotificationChannel("adhan_short", ctx.getString(R.string.alert_short), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(true)
        }
        nm.createNotificationChannel(full)
        nm.createNotificationChannel(short)
    }
}
