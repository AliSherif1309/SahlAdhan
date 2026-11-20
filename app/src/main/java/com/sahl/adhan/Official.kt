package com.sahl.adhan

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/**
 * Exact times from the official Qatar Calendar House site (qatarch.com/cal).
 * The page carries today's times for 13 cities; we fetch it at most a few times a day,
 * store today's times, and fall back to our own calculation whenever they're missing.
 */
object Official {
    private const val URL_CAL = "https://www.qatarch.com/cal"
    val QATAR: ZoneId = ZoneId.of("Asia/Qatar")

    fun key(qch: Int, date: LocalDate) = "qch_${qch}_$date"

    /** Site shows 12-hour times without am/pm: convert to minutes after midnight */
    private fun toMin(s: String, row: Int): Int {
        val (h0, m) = s.trim().split(":").map { it.toInt() }
        var h = h0
        when (row) {
            0, 1 -> if (h == 12) h = 0                 // Fajr, Sunrise: morning
            2 -> if (h < 10) h += 12                   // Dhuhr: 11:xx / 12:xx / 1:xx
            else -> if (h < 12) h += 12                // Asr, Maghrib, Isha: afternoon/evening
        }
        return h * 60 + m
    }

    /** Blocking. Returns true if today's official times were saved. */
    fun fetch(ctx: Context): Boolean = try {
        val con = (URL(URL_CAL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) SahlAdhan")
        }
        val html = con.inputStream.bufferedReader().readText()
        val json = Regex("prayData\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL).find(html)!!.groupValues[1]
        val arr = JSONArray(json)
        val today = LocalDate.now(QATAR)
        val ed = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE).edit()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mins = listOf("fajr", "shrouq", "thahr", "aser", "moghreb", "ishaa")
                .mapIndexed { r, k -> toMin(o.getString(k), r) }
            ed.putString(key(o.getInt("cityId"), today), mins.joinToString(","))
        }
        ed.putString("qch_last", today.toString()).apply()
        true
    } catch (e: Exception) { false }

    /** Fetch in the background if today's times are missing, then refresh everything. */
    fun syncIfNeeded(ctx: Context, force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val prefs = Prefs(ctx)
        val p = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE)
        if (!prefs.officialSync) { done?.invoke(false); return }
        if (!force && p.getString("qch_last", "") == LocalDate.now(QATAR).toString()) { done?.invoke(true); return }
        val app = ctx.applicationContext
        Thread {
            val ok = fetch(app)
            if (ok) { Scheduler.scheduleAll(app); Widgets.updateAll(app) }
            done?.invoke(ok)
        }.start()
    }
}
