package com.sahl.adhan

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/** Plays the adhan or iqama to the end with Stop, volume-down, and flip-face-down controls, even when locked. */
class AdhanService : Service(), SensorEventListener {
    private var player: MediaPlayer? = null
    private var silentTrack: AudioTrack? = null
    private var session: MediaSession? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null
    private var volumePoll: Runnable? = null
    private var sensors: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler by lazy { Handler(mainLooper) }

    private var bumpedMusic = false
    private var stopping = false
    private var faceDownSince = 0L
    private var faceUpSeen = false

    private fun startSilentMusicTrack(): AudioTrack? = try {
        val rate = 44100
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)
        val bufBytes = ((minBuf + 1) / 2) * 2
        val frames = bufBytes / 2
        val silence = ByteArray(bufBytes)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bufBytes)
            .build()
            .apply {
                write(silence, 0, silence.size)
                setLoopPoints(0, frames, -1)
                play()
            }
    } catch (_: Exception) {
        null
    }

    private fun ringerRank(mode: Int) = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> 2
        AudioManager.RINGER_MODE_VIBRATE -> 1
        else -> 0
    }

    /**
     * Volume down → stop.
     *
     * On Android 12+, remote MediaSession VolumeProvider no longer receives hardware volume keys
     * in the background, and when the screen is off Android drops volume keys unless STREAM_MUSIC
     * is active (musicOnly = true in PhoneWindowManager/MediaSessionService). Keeping a silent
     * zero-PCM AudioTrack on USAGE_MEDIA ensures screen-off volume presses reach AudioService,
     * where VOLUME_CHANGED_ACTION + polling + ContentObserver detect any drop immediately and
     * restore the lowered step.
     */
    private fun listenVolume() {
        if (!Prefs(this).opt("stop_volume")) return
        val am = getSystemService(AudioManager::class.java)

        // If media volume is 0 and no other app is playing media, temporarily lift it to 1 (invisibly)
        // so pressing Volume Down can actually produce a volume drop while our silent track is active.
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 && !am.isMusicActive) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) bumpedMusic = true
            } catch (_: Exception) {}
        }

        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            silentTrack = startSilentMusicTrack()
        }

        session = MediaSession(this, "SahlAdhan").apply {
            setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setCallback(object : MediaSession.Callback() {
                override fun onStop() { stopSelf() }
                override fun onPause() { stopSelf() }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            isActive = true
        }

        val streams = intArrayOf(
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
        )
        val lastVol = IntArray(streams.size) { am.getStreamVolume(streams[it]) }
        var lastRinger = am.ringerMode

        fun stopByVolume(stream: Int = -1, restoreTo: Int = -1) {
            if (stopping) return
            stopping = true
            stopListeners()
            if (stream == AudioManager.STREAM_MUSIC && bumpedMusic) {
                bumpedMusic = false
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) } catch (_: Exception) {}
            } else if (stream >= 0 && restoreTo >= 0) {
                try { am.setStreamVolume(stream, restoreTo, 0) } catch (_: Exception) {}
            }
            stopSelf()
        }

        fun checkVolumeDrop() {
            if (stopping) return
            for (i in streams.indices) {
                val s = streams[i]
                val cur = am.getStreamVolume(s)
                if (cur < lastVol[i]) {
                    stopByVolume(s, lastVol[i])
                    return
                }
                if (cur > lastVol[i]) lastVol[i] = cur
            }
            val ringer = am.ringerMode
            if (ringerRank(ringer) < ringerRank(lastRinger)) {
                stopByVolume()
                return
            }
            if (ringerRank(ringer) > ringerRank(lastRinger)) lastRinger = ringer
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_VOLUME_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(ACTION_STREAM_MUTE_CHANGED)
        }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (stopping) return
                when (intent.action) {
                    ACTION_VOLUME_CHANGED -> {
                        val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                        val cur = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                        val prev = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)
                        if (stream in streams && cur >= 0 && prev >= 0 && cur < prev) {
                            stopByVolume(stream, prev)
                            return
                        }
                        checkVolumeDrop()
                    }
                    ACTION_STREAM_MUTE_CHANGED -> {
                        val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                        val muted = intent.getBooleanExtra(EXTRA_STREAM_VOLUME_MUTED, false)
                        if (stream in streams && muted) {
                            stopByVolume()
                            return
                        }
                        checkVolumeDrop()
                    }
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> checkVolumeDrop()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, filter, RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(rx, filter)
        volumeReceiver = rx

        // Polling fallback for OEM ROMs that restrict hidden volume broadcasts
        val poll = object : Runnable {
            override fun run() {
                if (stopping) return
                checkVolumeDrop()
                if (!stopping) handler.postDelayed(this, 150L)
            }
        }
        volumePoll = poll
        handler.postDelayed(poll, 150L)

        // Extra backup via Settings.System observer
        volumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { checkVolumeDrop() }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver!!)
    }

    /** Turn the phone face down (screen on the table) for about a second → stop */
    private fun listenFaceDown() {
        if (!Prefs(this).opt("stop_flip")) return
        faceUpSeen = false
        faceDownSince = 0L
        val sm = getSystemService(SensorManager::class.java)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors = sm
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val z = e.values[2]
        if (z > 0f) { faceUpSeen = true; faceDownSince = 0L; return }   // must not already be lying face down
        if (faceUpSeen && z < -8.5f) {
            if (faceDownSince == 0L) faceDownSince = SystemClock.elapsedRealtime()
            else if (SystemClock.elapsedRealtime() - faceDownSince > 800) stopSelf()
        } else faceDownSince = 0L
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun stopListeners() {
        volumePoll?.let { handler.removeCallbacks(it) }; volumePoll = null
        volumeReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }; volumeReceiver = null
        volumeObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }; volumeObserver = null
        sensors?.unregisterListener(this); sensors = null
        session?.let { try { it.isActive = false; it.release() } catch (_: Exception) {} }; session = null
        silentTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        silentTrack = null
    }

    private fun restoreBumpedMusicIfNeeded() {
        if (!bumpedMusic) return
        bumpedMusic = false
        try {
            val am = getSystemService(AudioManager::class.java)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 1) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_STOP = "com.sahl.adhan.STOP"
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private const val EXTRA_STREAM_VOLUME_MUTED = "android.media.EXTRA_STREAM_VOLUME_MUTED"

        @Volatile
        private var active: AdhanService? = null

        fun handleVolumeDown(ctx: Context): Boolean {
            val svc = active ?: return false
            if (!Prefs(ctx).opt("stop_volume")) return false
            svc.stopSelf()
            return true
        }

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

        stopListeners()
        restoreBumpedMusicIfNeeded()
        stopping = false
        if (wakeLock == null) {
            wakeLock = try {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SahlAdhan:play")
                    .apply { setReferenceCounted(false); acquire(5 * 60_000L) }
            } catch (_: Exception) { null }
        } else {
            try { wakeLock?.acquire(5 * 60_000L) } catch (_: Exception) {}
        }

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
        if (player != null) {
            active = this
            listenVolume()
            listenFaceDown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        if (active === this) active = null
        player?.release(); player = null
        stopListeners()
        restoreBumpedMusicIfNeeded()
        wakeLock?.let { if (it.isHeld) try { it.release() } catch (_: Exception) {} }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
