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
    init { Widgets.h24 = p.getBoolean("h24", false) }

    /** 24-hour clock */
    var h24: Boolean get() = p.getBoolean("h24", false); set(v) { p.edit().putBoolean("h24", v).apply(); Widgets.h24 = v }

    /** Iqama: minutes after the adhan, per prayer (0 Fajr … 4 Isha). Qatar's usual waits by default. */
    fun iqamaMin(i: Int) = p.getInt("iq$i", intArrayOf(25, 20, 20, 10, 20)[i])
    fun setIqamaMin(i: Int, v: Int) = p.edit().putInt("iq$i", v).apply()

    /** What the widgets/banner show now: the next time, or "since" the last prayer for 30 minutes */
    data class Show(val row: Int, val time: ZonedDateTime, val since: Boolean)
    fun show(now: ZonedDateTime = ZonedDateTime.now(zone)): Show {
        if (opt("since")) {
            val all = times(now.toLocalDate())
            for (r in listOf(5, 4, 3, 2, 0)) {   // latest first; Sunrise isn't a prayer
                if (!all[r].isAfter(now) && now.isBefore(all[r].plusMinutes(30))) return Show(r, all[r], true)
            }
        }
        val (r, t) = nextRow(now)
        return Show(r, t, false)
    }

    var cityIndex: Int get() = p.getInt("city", 0); set(v) = p.edit().putInt("city", v).apply()
    var methodIndex: Int get() = p.getInt("method", 0); set(v) = p.edit().putInt("method", v).apply()
    var hanafi: Boolean get() = p.getBoolean("hanafi", false); set(v) = p.edit().putBoolean("hanafi", v).apply()
    /** "system", "ar" or "en" */
    var lang: String get() = p.getString("lang", "ar") ?: "ar"; set(v) = p.edit().putString("lang", v).apply()
    var fullSound: String get() = p.getString("fullSound", "adhan_beautiful") ?: "adhan_beautiful"; set(v) = p.edit().putString("fullSound", v).apply()
    var shortSound: String get() = p.getString("shortSound", "takbir") ?: "takbir"; set(v) = p.edit().putString("shortSound", v).apply()

    var showBanner: Boolean get() = p.getBoolean("banner", true); set(v) = p.edit().putBoolean("banner", v).apply()

    /** Widget / banner display options (key → default) */
    fun opt(key: String) = p.getBoolean("opt_$key", Options.defaults[key] ?: true)
    fun setOpt(key: String, v: Boolean) = p.edit().putBoolean("opt_$key", v).apply()

    fun adj(i: Int) = p.getInt("adj$i", 0)
    fun setAdj(i: Int, v: Int) = p.edit().putInt("adj$i", v).apply()
    /** i = 0..4 prayers, 5 = sunrise, 6 = first Friday adhan (default: short alert) */
    fun alert(i: Int) = p.getInt("alert$i", if (i >= 5) ALERT_SHORT else ALERT_FULL)
    fun setAlert(i: Int, v: Int) = p.edit().putInt("alert$i", v).apply()

    /** Use the official Qatar Calendar House times when available (needs internet once a day) */
    var officialSync: Boolean get() = p.getBoolean("officialSync", true); set(v) = p.edit().putBoolean("officialSync", v).apply()

    /** My location (GPS or search). Selected when cityIndex == cities.size */
    fun setCustom(name: String, lat: Double, lng: Double, tz: String) {
        // If it's within 40 km of an official Qatar Calendar city, use that city's official times
        val near = PrayerCalc.cities.filter { it.qch > 0 }
            .minByOrNull { PrayerCalc.km(lat, lng, it.lat, it.lng) }
            ?.takeIf { PrayerCalc.km(lat, lng, it.lat, it.lng) <= 40.0 }
        p.edit().putString("c_name", name).putFloat("c_lat", lat.toFloat()).putFloat("c_lng", lng.toFloat())
            .putString("c_tz", tz).putInt("c_qch", near?.qch ?: 0)
            .putInt("city", PrayerCalc.cities.size).apply()
    }
    val custom: PrayerCalc.City? get() = p.getString("c_name", null)?.let {
        PrayerCalc.City(it, it, p.getFloat("c_lat", 0f).toDouble(), p.getFloat("c_lng", 0f).toDouble(),
            p.getString("c_tz", "Asia/Qatar")!!, p.getInt("c_qch", 0))
    }

    val city: PrayerCalc.City get() =
        if (cityIndex == PrayerCalc.cities.size) custom ?: PrayerCalc.cities[0]
        else PrayerCalc.cities[cityIndex.coerceIn(0, PrayerCalc.cities.lastIndex)]

    /** True when today's times come from the official calendar */
    fun isOfficial(date: LocalDate) = officialSync && city.qch > 0 && p.getString(Official.key(city.qch, date), null) != null
    val zone: ZoneId get() = ZoneId.of(city.tz)

    /** Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha for a date */
    fun times(date: LocalDate): List<ZonedDateTime> {
        val c = city
        if (officialSync && c.qch > 0) {
            p.getString(Official.key(c.qch, date), null)?.let { s ->
                val mins = s.split(",").map { it.toInt() }
                if (mins.size == 6) {
                    val adjFor = intArrayOf(adj(0), 0, adj(1), adj(2), adj(3), adj(4))
                    // Official times are Qatar local time (Gulf cities on the site are given in their own local time)
                    return mins.mapIndexed { r, m -> date.atStartOfDay(zone).plusMinutes((m + adjFor[r]).toLong()) }
                }
            }
        }
        return PrayerCalc.compute(date, c, PrayerCalc.methods[methodIndex.coerceIn(0, PrayerCalc.methods.lastIndex)],
            hanafi, IntArray(5) { adj(it) })
    }

    fun fard(date: LocalDate) = times(date).let { listOf(it[0], it[2], it[3], it[4], it[5]) }

    /** Next prayer from now: (prayer index 0..4, time). After Isha → tomorrow's Fajr. */
    /** First Friday adhan (40 min before Dhuhr) of the next Friday on or after [from]'s date */
    fun firstAdhan(from: ZonedDateTime): ZonedDateTime {
        var d = from.toLocalDate()
        while (d.dayOfWeek != java.time.DayOfWeek.FRIDAY) d = d.plusDays(1)
        return fard(d)[1].minusMinutes(40)
    }

    /**
     * Next row of the six-line timetable (0 Fajr, 1 Sunrise, 2 Dhuhr, 3 Asr, 4 Maghrib, 5 Isha).
     * Includes Sunrise when the Sunrise option is on. Used for countdowns and highlights.
     */
    fun nextRow(now: ZonedDateTime = ZonedDateTime.now(zone)): Pair<Int, ZonedDateTime> {
        val all = times(now.toLocalDate())
        for (r in 0..5) {
            if (r == 1 && !opt("sunrise")) continue
            if (all[r].isAfter(now)) return r to all[r]
        }
        return 0 to times(now.toLocalDate().plusDays(1))[0]
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
    /** Name of a six-line row (Sunrise included; Dhuhr → Jumuah on Fridays) */
    fun row(ctx: Context, r: Int, day: java.time.LocalDate): String = when (r) {
        0 -> of(ctx, 0); 1 -> ctx.getString(R.string.sunrise); else -> of(ctx, r - 1, day)
    }
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
    fun isCustom(key: String) = key.startsWith("custom_")
    fun customFile(ctx: Context, key: String) = java.io.File(ctx.filesDir, key)
    fun available(ctx: Context, list: List<Sound>, customKey: String) =
        list.filter { it.key == "alarm" || it.key == "system" || rawId(ctx, it.key) != 0 } +
            (if (customFile(ctx, customKey).exists()) listOf(Sound(customKey, R.string.s_custom)) else emptyList())

    fun uri(ctx: Context, key: String): Uri {
        if (isCustom(key) && customFile(ctx, key).exists()) return Uri.fromFile(customFile(ctx, key))
        val id = rawId(ctx, key)
        return when {
            id != 0 -> Uri.parse("android.resource://${ctx.packageName}/$id")
            key == "system" || key == "takbir" || key.startsWith("chime") ->
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
    }
}

/** What the widgets and banner can show. Order = order on the settings screen. */
object Options {
    val widget = listOf(
        "hijri" to R.string.o_hijri, "date" to R.string.o_date, "city" to R.string.o_city, "since" to R.string.o_since,
        "countdown" to R.string.o_countdown, "sunrise" to R.string.o_sunrise, "qibla" to R.string.o_qibla,
    )
    val banner = listOf(
        "b_times" to R.string.o_b_times, "b_hijri" to R.string.o_hijri, "b_after" to R.string.o_b_after,
    )
    val defaults = mapOf("since" to true, "iqama" to false, "hijri" to true, "date" to false, "city" to false, "countdown" to true,
        "sunrise" to true, "qibla" to true, "b_times" to true, "b_hijri" to true, "b_after" to true)
}
