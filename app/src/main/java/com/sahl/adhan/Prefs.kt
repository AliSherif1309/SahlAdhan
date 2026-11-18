package com.sahl.adhan

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Index into the 5 prayers: 0 Fajr, 1 Dhuhr, 2 Asr, 3 Maghrib, 4 Isha */
const val ALERT_FULL = 0
const val ALERT_SHORT = 1
const val ALERT_OFF = 2

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE)

    var cityIndex: Int get() = p.getInt("city", 0); set(v) = p.edit().putInt("city", v).apply()
    var methodIndex: Int get() = p.getInt("method", 0); set(v) = p.edit().putInt("method", v).apply()
    var hanafi: Boolean get() = p.getBoolean("hanafi", false); set(v) = p.edit().putBoolean("hanafi", v).apply()

    fun adj(i: Int) = p.getInt("adj$i", 0)
    fun setAdj(i: Int, v: Int) = p.edit().putInt("adj$i", v).apply()
    fun alert(i: Int) = p.getInt("alert$i", ALERT_FULL)
    fun setAlert(i: Int, v: Int) = p.edit().putInt("alert$i", v).apply()

    val city get() = PrayerCalc.cities[cityIndex.coerceIn(0, PrayerCalc.cities.lastIndex)]
    val zone: ZoneId get() = ZoneId.of(city.tz)

    /** Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha for a date */
    fun times(date: LocalDate): List<ZonedDateTime> = PrayerCalc.compute(
        date, city, PrayerCalc.methods[methodIndex.coerceIn(0, PrayerCalc.methods.lastIndex)],
        hanafi, IntArray(5) { adj(it) }
    )

    /** The 5 fard prayers (sunrise removed) */
    fun fard(date: LocalDate) = times(date).let { listOf(it[0], it[2], it[3], it[4], it[5]) }

    /** Next prayer from now: (prayer index 0..4, time). After Isha → tomorrow's Fajr. */
    fun next(now: ZonedDateTime = ZonedDateTime.now(zone)): Pair<Int, ZonedDateTime> {
        val today = fard(now.toLocalDate())
        today.forEachIndexed { i, t -> if (t.isAfter(now)) return i to t }
        return 0 to fard(now.toLocalDate().plusDays(1))[0]
    }
}

object Names {
    val ids = intArrayOf(R.string.fajr, R.string.dhuhr, R.string.asr, R.string.maghrib, R.string.isha)
    fun of(ctx: Context, i: Int) = ctx.getString(ids[i])
    fun isArabic(ctx: Context) = ctx.resources.configuration.locales[0].language == "ar"
}
