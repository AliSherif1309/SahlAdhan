package com.sahl.adhan

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatically keeps the prayer location up to date when the user travels,
 * and posts a notification whenever the location changes.
 */
object AutoLocation {
    private const val PREF_LAST = "lastAutoLoc"
    private const val CHANNEL_ID = "location"
    private const val NOTIF_ID = 501
    private const val MIN_DISTANCE_KM = 15.0
    private const val FG_INTERVAL_MS = 30 * 60_000L
    private const val BG_INTERVAL_MS = 3 * 3600_000L
    private const val FIX_TIMEOUT_MS = 10_000L

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks the current location if auto-location is enabled and throttled interval has elapsed.
     * Updates the saved city, reschedules alerts, refreshes widgets, and notifies the user if moved.
     */
    fun checkIfNeeded(
        ctx: Context,
        force: Boolean = false,
        background: Boolean = false,
        onUpdated: (() -> Unit)? = null,
    ) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (!prefs.opt("auto_location")) return
        if (!hasPermission(app)) return
        if (background && !hasBackgroundPermission(app)) return

        val p = app.getSharedPreferences("sahl", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val interval = if (background) BG_INTERVAL_MS else FG_INTERVAL_MS
        if (!force && now - p.getLong(PREF_LAST, 0L) < interval) return
        p.edit().putLong(PREF_LAST, now).apply()

        val lm = app.getSystemService(LocationManager::class.java) ?: return
        val cached = bestLastKnown(lm)
        val maxCacheAge = if (force) 5 * 60_000L else 30 * 60_000L
        if (cached != null && now - cached.time in 0..maxCacheAge) {
            applyIfMoved(app, cached, onUpdated)
            return
        }

        requestSingleFix(app, lm, cached) { loc ->
            if (loc != null) applyIfMoved(app, loc, onUpdated)
        }
    }

    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        var best: Location? = null
        for (prov in providers) {
            val loc = try { lm.getLastKnownLocation(prov) } catch (_: SecurityException) { null } catch (_: Exception) { null } ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }

    private fun requestSingleFix(
        app: Context,
        lm: LocationManager,
        fallback: Location?,
        onResult: (Location?) -> Unit,
    ) {
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> {
                onResult(fallback)
                return
            }
        }

        val done = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())
        fun finish(loc: Location?) {
            if (done.compareAndSet(false, true)) onResult(loc ?: fallback)
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val cancel = CancellationSignal()
                val timeout = Runnable {
                    cancel.cancel()
                    finish(null)
                }
                mainHandler.postDelayed(timeout, FIX_TIMEOUT_MS)
                lm.getCurrentLocation(provider, cancel, app.mainExecutor) { loc ->
                    mainHandler.removeCallbacks(timeout)
                    finish(loc)
                }
            } else {
                var listener: LocationListener? = null
                val timeout = Runnable {
                    listener?.let { try { lm.removeUpdates(it) } catch (_: Exception) {} }
                    finish(null)
                }
                listener = LocationListener { loc ->
                    mainHandler.removeCallbacks(timeout)
                    finish(loc)
                }
                mainHandler.postDelayed(timeout, FIX_TIMEOUT_MS)
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            finish(null)
        } catch (_: Exception) {
            finish(null)
        }
    }

    private fun applyIfMoved(app: Context, loc: Location, onUpdated: (() -> Unit)?) {
        Thread {
            val prefs = Prefs(app)
            val cur = prefs.city
            val newTz = ZoneId.systemDefault().id
            val distKm = PrayerCalc.km(cur.lat, cur.lng, loc.latitude, loc.longitude)
            if (distKm < MIN_DISTANCE_KM && cur.tz == newTz) return@Thread

            val c = app.localized()
            val label = try {
                @Suppress("DEPRECATION")
                Geocoder(c, c.resources.configuration.locales[0]).getFromLocation(loc.latitude, loc.longitude, 1)
                    ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            } catch (_: Exception) { null } ?: String.format(Locale.ENGLISH, "%.3f, %.3f", loc.latitude, loc.longitude)

            prefs.setCustom("📍 $label", loc.latitude, loc.longitude, newTz)
            Official.syncIfNeeded(app, force = true) { onUpdated?.invoke() }
            Scheduler.scheduleAll(app)
            Widgets.updateAll(app)
            notifyUpdated(app, label)
            onUpdated?.invoke()
        }.start()
    }

    private fun notifyUpdated(app: Context, label: String) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (!nm.areNotificationsEnabled()) return
            val c = app.localized()
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, c.getString(R.string.loc_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                app, 6, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sahl_notif)
                .setContentTitle(c.getString(R.string.loc_updated_title, label))
                .setContentText(c.getString(R.string.loc_updated_body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, n)
        } catch (_: Exception) {}
    }
}
