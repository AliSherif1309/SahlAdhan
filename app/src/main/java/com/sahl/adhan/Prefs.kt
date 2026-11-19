package com.sahl.adhan

import android.content.Context
import android.content.res.Configuration
import android.media.RingtoneManager
import android.net.Uri
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Index into the 5 prayers: 0 Fajr, 1 Dhuhr, 2 Asr, 3 Maghrib, 4 Isha */
const val ALERT_FULL = 0
const val ALERT_SHORT = 1
const val ALERT_OFF = 2

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("sahl", Context.MODE_PRIVATE)

    var cityIndex: Int get() = p.getInt("city", 0); set(v) = p.edit().putInt("city", v).apply()
    var methodIndex: Int get() = p.getInt("method", 0); set(v) = p.edit().putInt("method", v).apply()
    var hanafi: Boolean get() = p.getBoolean("hanafi", false); set(v) = p.edit().putBoolean("hanafi", v).apply()
    /** "system", "ar" or "en" */
    var lang: String get() = p.getString("lang", "ar") ?: "ar"; set(v) = p.edit().putString("lang", v).apply()
    var fullSound: String get() = p.getString("fullSound", "adhan_beautiful") ?: "adhan_beautiful"; set(v) = p.edit().putString("fullSound", v).apply()
    var shortSound: String get() = p.getString("shortSound", "takbir") ?: "takbir"; set(v) = p.edit().putString("shortSound", v).apply()

    var showBanner: Boolean get() = p.getBoolean("banner", true); set(v) = p.edit().putBoolean("banner", v).apply()

    fun adj(i: Int) = p.getInt("adj$i", 0)
    fun setAdj(i: Int, v: Int) = p.edit().putInt("adj$i", v).apply()
    /** i = 0..4 prayers, 5 = sunrise, 6 = first Friday adhan (default: short alert) */
    fun alert(i: Int) = p.getInt("alert$i", if (i >= 5) ALERT_SHORT else ALERT_FULL)
    fun setAlert(i: Int, v: Int) = p.edit().putInt("alert$i", v).apply()

    val city get() = PrayerCalc.cities[cityIndex.coerceIn(0, PrayerCalc.cities.lastIndex)]
    val zone: ZoneId get() = ZoneId.of(city.tz)

    /** Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha for a date */
    fun times(date: LocalDate): List<ZonedDateTime> = PrayerCalc.compute(
        date, city, PrayerCalc.methods[methodIndex.coerceIn(0, PrayerCalc.methods.lastIndex)],
        hanafi, IntArray(5) { adj(it) }
    )

    fun fard(date: LocalDate) = times(date).let { listOf(it[0], it[2], it[3], it[4], it[5]) }

    /** Next prayer from now: (prayer index 0..4, time). After Isha → tomorrow's Fajr. */
    /** First Friday adhan (40 min before Dhuhr) of the next Friday on or after [from]'s date */
    fun firstAdhan(from: ZonedDateTime): ZonedDateTime {
        var d = from.toLocalDate()
        while (d.dayOfWeek != java.time.DayOfWeek.FRIDAY) d = d.plusDays(1)
        return fard(d)[1].minusMinutes(40)
    }

    fun next(now: ZonedDateTime = ZonedDateTime.now(zone)): Pair<Int, ZonedDateTime> {
        val today = fard(now.toLocalDate())
        today.forEachIndexed { i, t -> if (t.isAfter(now)) return i to t }
        return 0 to fard(now.toLocalDate().plusDays(1))[0]
    }
}

/** App language independent of the phone language */
fun Context.localized(): Context {
    val lang = Prefs(this).lang
    if (lang == "system") return this
    val cfg = Configuration(resources.configuration)
    cfg.setLocale(Locale(lang))
    return createConfigurationContext(cfg)
}

object Names {
    val ids = intArrayOf(R.string.fajr, R.string.dhuhr, R.string.asr, R.string.maghrib, R.string.isha)
    fun of(ctx: Context, i: Int) = ctx.getString(ids[i])
    /** Same as [of] but Dhuhr becomes الجمعة on Fridays */
    fun of(ctx: Context, i: Int, day: java.time.LocalDate) =
        if (i == 1 && day.dayOfWeek == java.time.DayOfWeek.FRIDAY) ctx.getString(R.string.jumuah) else of(ctx, i)
    fun isArabic(ctx: Context) = ctx.resources.configuration.locales[0].language == "ar"
}

/** Sound catalogue. Raw files are only listed if they exist in the build. */
object Sounds {
    data class Sound(val key: String, val label: Int)
    val full = listOf(
        Sound("adhan_beautiful", R.string.s_adhan_beautiful),
        Sound("adhan_classic", R.string.s_adhan_classic),
        Sound("alarm", R.string.s_alarm),
    )
    val short = listOf(
        Sound("takbir", R.string.s_takbir),
        Sound("chime_soft", R.string.s_chime_soft),
        Sound("chime_double", R.string.s_chime_double),
        Sound("system", R.string.s_system),
    )
    private fun rawId(ctx: Context, key: String) = ctx.resources.getIdentifier(key, "raw", ctx.packageName)
    fun available(ctx: Context, list: List<Sound>) =
        list.filter { it.key == "alarm" || it.key == "system" || rawId(ctx, it.key) != 0 }

    fun uri(ctx: Context, key: String): Uri {
        val id = rawId(ctx, key)
        return when {
            id != 0 -> Uri.parse("android.resource://${ctx.packageName}/$id")
            key == "system" || key == "takbir" || key.startsWith("chime") ->
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
    }
}
