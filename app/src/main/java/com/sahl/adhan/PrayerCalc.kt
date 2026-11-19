package com.sahl.adhan

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.*

/** Offline prayer-time calculation (standard astronomical formulas, ~1 min accuracy). */
object PrayerCalc {
    /** ishaMinutes > 0 means Isha = Maghrib + fixed minutes. */
    data class Method(val name: String, val nameAr: String, val fajr: Double, val isha: Double, val ishaMinutes: Int,
                      /** built-in minute offsets: Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha */
                      val off: IntArray = IntArray(6))

    val methods = listOf(
        Method("Qatar · Khatmah timetable", "قطر · مطابق لختمة", 18.0, 0.0, 90, intArrayOf(-2, -1, 0, 0, 2, 0)),
        Method("Qatar Calendar House (18°, Isha +90 min)", "دار التقويم القطري (١٨°، العشاء +٩٠ د)", 18.0, 0.0, 90, intArrayOf(-1, -1, 0, 1, 2, 0)),
        Method("Gulf (19.5°, Isha +90 min)", "الخليج (١٩٫٥°، العشاء +٩٠ د)", 19.5, 0.0, 90),
        Method("Umm al-Qura (18.5°, Isha +90 min)", "أم القرى (١٨٫٥°، العشاء +٩٠ د)", 18.5, 0.0, 90),
        Method("Muslim World League (18° / 17°)", "رابطة العالم الإسلامي (١٨° / ١٧°)", 18.0, 17.0, 0),
        Method("Egyptian (19.5° / 17.5°)", "الهيئة المصرية (١٩٫٥° / ١٧٫٥°)", 19.5, 17.5, 0),
        Method("Karachi (18° / 18°)", "كراتشي (١٨° / ١٨°)", 18.0, 18.0, 0),
        Method("ISNA (15° / 15°)", "أمريكا الشمالية (١٥° / ١٥°)", 15.0, 15.0, 0),
    )

    data class City(val en: String, val ar: String, val lat: Double, val lng: Double, val tz: String)

    val cities = listOf(
        City("Doha", "الدوحة", 25.2854, 51.5310, "Asia/Qatar"),
        City("Al Wakrah", "الوكرة", 25.1659, 51.6038, "Asia/Qatar"),
        City("Al Khor", "الخور", 25.6839, 51.5058, "Asia/Qatar"),
        City("Dukhan", "دخان", 25.4250, 50.7820, "Asia/Qatar"),
        City("Makkah", "مكة المكرمة", 21.4225, 39.8262, "Asia/Riyadh"),
        City("Madinah", "المدينة المنورة", 24.4672, 39.6111, "Asia/Riyadh"),
        City("Riyadh", "الرياض", 24.7136, 46.6753, "Asia/Riyadh"),
        City("Dubai", "دبي", 25.2048, 55.2708, "Asia/Dubai"),
        City("Abu Dhabi", "أبوظبي", 24.4539, 54.3773, "Asia/Dubai"),
        City("Kuwait City", "الكويت", 29.3759, 47.9774, "Asia/Kuwait"),
        City("Manama", "المنامة", 26.2285, 50.5860, "Asia/Bahrain"),
        City("Muscat", "مسقط", 23.5880, 58.3829, "Asia/Muscat"),
        City("Cairo", "القاهرة", 30.0444, 31.2357, "Africa/Cairo"),
    )

    /** Returns 6 times: Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha. adj = 5 values (no sunrise). */
    fun compute(date: LocalDate, city: City, m: Method, hanafi: Boolean, adj: IntArray): List<ZonedDateTime> {
        val jd = julian(date)
        val lat = city.lat
        val lngHours = city.lng / 15.0

        fun noonUtc(t: Double): Double {
            val eqt = sun(jd + t / 24.0).second
            return 12.0 - eqt - lngHours
        }
        // Time (UTC hours) when the sun is at altitude `alt` degrees; before noon if `morning`.
        fun atAltitude(alt: Double, guess: Double, morning: Boolean): Double {
            var t = guess
            repeat(3) {
                val decl = sun(jd + t / 24.0).first
                val cosH = (sin(rad(alt)) - sin(rad(lat)) * sin(rad(decl))) / (cos(rad(lat)) * cos(rad(decl)))
                val h = deg(acos(cosH.coerceIn(-1.0, 1.0))) / 15.0
                val noon = noonUtc(t)
                t = if (morning) noon - h else noon + h
            }
            return t
        }

        val dhuhr = noonUtc(12.0 - lngHours)
        val fajr = atAltitude(-m.fajr, 5.0 - lngHours, true)
        val sunrise = atAltitude(-0.833, 6.0 - lngHours, true)
        val asr = run {
            var t = 15.0 - lngHours
            repeat(3) {
                val decl = sun(jd + t / 24.0).first
                val factor = if (hanafi) 2.0 else 1.0
                val alt = deg(atan(1.0 / (factor + tan(rad(abs(lat - decl))))))
                t = atAltitude(alt, t, false)
            }
            t
        }
        val maghrib = atAltitude(-0.833, 18.0 - lngHours, false)
        val isha = if (m.ishaMinutes > 0) maghrib + m.ishaMinutes / 60.0
                   else atAltitude(-m.isha, 19.5 - lngHours, false)

        val zone = ZoneId.of(city.tz)
        val base = date.atStartOfDay(ZoneOffset.UTC)
        fun toZ(h: Double, adjMin: Int): ZonedDateTime {
            val secs = (h * 3600.0).roundToLong() + adjMin * 60L
            // round to nearest minute (round up seconds, common timetable practice)
            val z = base.plusSeconds(secs).withZoneSameInstant(zone)
            return (if (z.second > 0) z.plusMinutes(1) else z).withSecond(0).withNano(0)
        }
        val o = m.off
        // with a fixed Isha interval, Isha follows Maghrib's offset too
        val ishaOff = o[5] + (if (m.ishaMinutes > 0) o[4] else 0)
        return listOf(
            toZ(fajr, o[0] + adj[0]), toZ(sunrise, o[1]), toZ(dhuhr, o[2] + adj[1]),
            toZ(asr, o[3] + adj[2]), toZ(maghrib, o[4] + adj[3]), toZ(isha, ishaOff + adj[4])
        )
    }

    private fun julian(d: LocalDate): Double {
        var y = d.year; var mo = d.monthValue
        if (mo <= 2) { y -= 1; mo += 12 }
        val a = floor(y / 100.0); val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (mo + 1)) + d.dayOfMonth + b - 1524.5
    }

    /** (declination degrees, equation of time hours) */
    private fun sun(jd: Double): Pair<Double, Double> {
        val dd = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * dd, 360.0)
        val q = fix(280.459 + 0.98564736 * dd, 360.0)
        val l = fix(q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g)), 360.0)
        val e = 23.439 - 0.00000036 * dd
        val ra = fix(deg(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15.0, 24.0)
        var eqt = q / 15.0 - ra
        eqt -= 24.0 * round(eqt / 24.0)
        val decl = deg(asin(sin(rad(e)) * sin(rad(l))))
        return decl to eqt
    }

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI
    private fun fix(a: Double, b: Double): Double { val r = a - b * floor(a / b); return if (r < 0) r + b else r }
}
