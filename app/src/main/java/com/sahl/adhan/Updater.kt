package com.sahl.adhan

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK and installs it. */
object Updater {
    private const val API = "https://api.github.com/repos/AliSherif1309/SahlAdhan/releases/latest"
    data class Release(val code: Int, val name: String, val apkUrl: String, val notes: String)

    /** Blocking — call off the main thread. Returns a release only if it is newer. */
    fun fetchNewer(): Release? = try {
        val con = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val j = JSONObject(con.inputStream.bufferedReader().readText())
        val tag = j.getString("tag_name")                       // e.g. v1.7
        val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
        val assets = j.getJSONArray("assets")
        var url = ""
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
        }
        if (code > BuildConfig.VERSION_CODE && url.isNotEmpty())
            Release(code, tag, url, j.optString("body")) else null
    } catch (e: Exception) { null }

    /** Once a day from the background: post a notification if an update exists. */
    fun dailyCheck(ctx: Context) {
        val p = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastUpdateCheck", 0) < 20 * 3600_000L) return
        p.edit().putLong("lastUpdateCheck", now).apply()
        Thread {
            val r = fetchNewer() ?: return@Thread
            val c = ctx.localized()
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("updates", c.getString(R.string.check_updates), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(500, Notification.Builder(ctx, "updates").setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(c.getString(R.string.update_available, r.name))
                .setContentText(c.getString(R.string.install_update))
                .setContentIntent(open).setAutoCancel(true).build())
        }.start()
    }

    /** Download the APK, then open Android's installer. */
    fun downloadAndInstall(ctx: Context, r: Release) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
            return
        }
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val file = "SahlAdhan-${r.name}.apk"
        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(file)?.delete()
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(r.apkUrl))
            .setTitle("Sahl Adhan ${r.name}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, file))
        val app = ctx.applicationContext
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                val uri = dm.getUriForDownloadedFile(id) ?: return
                app.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(rx, f)
    }
}
