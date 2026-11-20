package com.sahl.adhan

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder

/** Plays the full adhan to the end with a Stop button, even when the phone is locked. */
class AdhanService : Service() {
    private var player: MediaPlayer? = null

    companion object {
        const val ACTION_STOP = "com.sahl.adhan.STOP"
        fun start(ctx: Context, prayer: Int, sound: String? = null): Boolean = try {
            ctx.startForegroundService(Intent(ctx, AdhanService::class.java)
                .putExtra(Scheduler.EXTRA_PRAYER, prayer).putExtra("sound", sound))
            true
        } catch (e: Exception) { false }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        Scheduler.ensureChannels(this)
        val prayer = intent?.getIntExtra(Scheduler.EXTRA_PRAYER, -1) ?: -1
        val c = localized()
        val stop = PendingIntent.getService(this, 9, Intent(this, AdhanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "adhan_playing")
            .setSmallIcon(R.drawable.ic_mosque)
            .setContentTitle(AlarmReceiver.title(this, prayer))
            .setContentText(Prefs(this).city.let { if (Names.isArabic(c)) it.ar else it.en })
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, c.getString(R.string.stop_adhan), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(77, n)

        player?.release()
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@AdhanService, Sounds.uri(this@AdhanService, intent?.getStringExtra("sound") ?: Prefs(this@AdhanService).fullSound))
                setOnCompletionListener { stopSelf() }
                prepare(); start()
            }
        } catch (e: Exception) { stopSelf(); null }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        player?.release(); player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
