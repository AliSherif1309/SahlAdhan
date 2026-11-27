package com.sahl.adhan

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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
    const val ACTION_LOC_CHECK = "com.sahl.adhan.LOC_CHECK"
    private const val REQ_REFRESH = 100
    private const val REQ_LOC = 101
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

        val sunToday = prefs.times(now.toLocalDate())[1]
        val sunTomorrow = prefs.times(now.toLocalDate().plusDays(1))[1]
        val first = prefs.firstAdhan(now).let { if (it.isAfter(now)) it else prefs.firstAdhan(now.plusDays(1)) }
        for (i in 0..6) {
            val intent = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALERT)
                .putExtra(EXTRA_PRAYER, i).putExtra(EXTRA_MODE, prefs.alert(i))
            val p = pi(ctx, i, intent)
            if (prefs.alert(i) == ALERT_OFF) { am.cancel(p); continue }
            val t = if (i == 6) first else if (i == 5) (if (sunToday.isAfter(now)) sunToday else sunTomorrow)
                    else if (today[i].isAfter(now)) today[i] else tomorrow[i]
            setAt(ctx, t.toInstant().toEpochMilli(), p)
        }

        // Iqama alerts (optional): adhan time + minutes, played via AdhanService so stop methods work
        for (i in 0..4) {
            val p = pi(ctx, 20 + i, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALERT)
                .putExtra(EXTRA_PRAYER, 10 + i).putExtra(EXTRA_MODE, ALERT_IQAMA))
            if (!prefs.opt("iqama") || !prefs.iqamaEnabled(i)) { am.cancel(p); continue }
            val m = prefs.iqamaMin(i).toLong()
            val t = today[i].plusMinutes(m).let { if (it.isAfter(now)) it else tomorrow[i].plusMinutes(m) }
            setAt(ctx, t.toInstant().toEpochMilli(), p)
        }

        // Pre-adhan alerts (optional): adhan time - minutes
        for (i in 0..4) {
            val p = pi(ctx, 30 + i, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALERT)
                .putExtra(EXTRA_PRAYER, 20 + i).putExtra(EXTRA_MODE, ALERT_PRE_ADHAN))
            if (!prefs.opt("pre_adhan") || !prefs.preAdhanEnabled(i)) { am.cancel(p); continue }
            val m = prefs.preAdhanMin(i).toLong()
            val t = today[i].minusMinutes(m).let { if (it.isAfter(now)) it else tomorrow[i].minusMinutes(m) }
            setAt(ctx, t.toInstant().toEpochMilli(), p)
        }

        // Refresh at the next change: next time (incl. Sunrise), end of a 30-min "since" period, or midnight
        val shown = prefs.show(now)
        val candidates = mutableListOf(prefs.nextRow(now).second, now.toLocalDate().plusDays(1).atStartOfDay(prefs.zone))
        if (shown.since) candidates += shown.time.plusMinutes(30)
        val boundary = candidates.filter { it.isAfter(now) }.minOrNull()!!
        setAt(ctx, boundary.toInstant().toEpochMilli() + 1000,
            pi(ctx, REQ_REFRESH, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_REFRESH)))

        scheduleLocationCheck(ctx)
    }

    fun scheduleLocationCheck(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, REQ_LOC, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_LOC_CHECK))
        if (!Prefs(ctx).opt("auto_location") || !AutoLocation.hasPermission(ctx) || !AutoLocation.hasBackgroundPermission(ctx)) {
            am.cancel(p)
            return
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 30 * 60_000L, p)
    }

    fun scheduleTest(ctx: Context, mode: Int) {
        val prayer = when (mode) {
            ALERT_IQAMA -> -2
            ALERT_PRE_ADHAN -> -3
            else -> -1
        }
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALERT)
            .putExtra(EXTRA_PRAYER, prayer).putExtra(EXTRA_MODE, mode)
        setAt(ctx, System.currentTimeMillis() + 60_000, pi(ctx, REQ_TEST + mode, intent))
    }

    /** Short alerts: one notification channel per sound (Android fixes a channel's sound). */
    fun shortChannelId(ctx: Context, key: String = Prefs(ctx).shortSound) = "short_$key"

    fun ensureChannels(ctx: Context, shortKey: String? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val c = ctx.localized()
        val playing = NotificationChannel("adhan_playing", c.getString(R.string.playing), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true); setBypassDnd(true)
        }
        nm.createNotificationChannel(playing)
        val silent = NotificationChannel("adhan_silent", c.getString(R.string.alerts), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true)
        }
        nm.createNotificationChannel(silent)
        val key = shortKey ?: Prefs(ctx).shortSound
        val label = Sounds.short.firstOrNull { it.key == key }?.label ?: R.string.alert_short
        val short = NotificationChannel("short_$key", c.getString(R.string.alert_short) + " · " + c.getString(label),
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Sounds.uri(ctx, key), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        }
        nm.createNotificationChannel(short)
    }
}
