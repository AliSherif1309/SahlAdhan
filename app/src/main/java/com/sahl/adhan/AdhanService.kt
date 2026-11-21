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
class AdhanService : Service(), android.hardware.SensorEventListener {
    private var player: MediaPlayer? = null
    private var session: android.media.session.MediaSession? = null
    private var volumeObserver: android.database.ContentObserver? = null
    private var sensors: android.hardware.SensorManager? = null
    private var faceDownSince = 0L
    private var faceUpSeen = false

    /** Volume down → stop. A media session receives the volume keys even with the screen locked. */
    private fun listenVolume() {
        if (!Prefs(this).opt("stop_volume")) return
        session = android.media.session.MediaSession(this, "SahlAdhan").apply {
            setPlaybackToRemote(object : android.media.VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) { if (direction < 0) stopSelf() }
            })
            setPlaybackState(android.media.session.PlaybackState.Builder()
                .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true
        }
        // Fallback for phones that send the keys elsewhere: any drop in a volume level stops the adhan
        val am = getSystemService(android.media.AudioManager::class.java)
        val streams = intArrayOf(android.media.AudioManager.STREAM_ALARM, android.media.AudioManager.STREAM_RING,
            android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.STREAM_NOTIFICATION)
        val start = streams.map { am.getStreamVolume(it) }
        volumeObserver = object : android.database.ContentObserver(android.os.Handler(mainLooper)) {
            override fun onChange(selfChange: Boolean) {
                if (streams.indices.any { am.getStreamVolume(streams[it]) < start[it] }) stopSelf()
            }
        }
        contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, volumeObserver!!)
    }

    /** Turn the phone face down (screen on the table) for about a second → stop */
    private fun listenFaceDown() {
        if (!Prefs(this).opt("stop_flip")) return
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        val acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        sensors = sm
        sm.registerListener(this, acc, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(e: android.hardware.SensorEvent) {
        val z = e.values[2]
        if (z > 3f) { faceUpSeen = true; faceDownSince = 0L; return }   // must start face up (not already lying face down)
        if (faceUpSeen && z < -8.5f) {
            if (faceDownSince == 0L) faceDownSince = android.os.SystemClock.elapsedRealtime()
            else if (android.os.SystemClock.elapsedRealtime() - faceDownSince > 800) stopSelf()
        } else faceDownSince = 0L
    }
    override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}

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
        if (player != null && session == null) { listenVolume(); listenFaceDown() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        player?.release(); player = null
        session?.release(); session = null
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }; volumeObserver = null
        sensors?.unregisterListener(this); sensors = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
