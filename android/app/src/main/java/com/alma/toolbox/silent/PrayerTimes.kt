package com.alma.toolbox.silent

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*

@Serializable
data class PrayerSettings(
    val city: String = "صنعاء",
    val lat: Double = 15.3694,
    val lon: Double = 44.1910,
    val method: String = "UmmAlQura",
    val asrHanafi: Boolean = false,
    /** minute offsets for fajr, sunrise, dhuhr, asr, maghrib, isha */
    val adjust: List<Int> = listOf(0, 0, 0, 0, 0, 0),
)

data class City(val name: String, val lat: Double, val lon: Double)

val Cities = listOf(
    City("صنعاء", 15.3694, 44.1910), City("عدن", 12.7855, 45.0187), City("تعز", 13.5795, 44.0209),
    City("الحديدة", 14.7978, 42.9545), City("إب", 13.9667, 44.1833), City("ذمار", 14.5427, 44.4051),
    City("المكلا", 14.5425, 49.1242), City("سيئون", 15.9431, 48.7879), City("صعدة", 16.9402, 43.7639),
    City("حجة", 15.6943, 43.6058), City("عمران", 15.6594, 43.9439), City("مأرب", 15.4591, 45.3253),
    City("البيضاء", 13.9850, 45.5727), City("مكة المكرمة", 21.4225, 39.8262), City("المدينة المنورة", 24.4672, 39.6024),
    City("الرياض", 24.7136, 46.6753), City("جدة", 21.4858, 39.1925), City("القاهرة", 30.0444, 31.2357),
)

object PrayerCalc {
    data class Method(val title: String, val fajr: Double, val isha: Double, val ishaMinutes: Int = 0, val maghribAngle: Double = 0.0)

    val methods = linkedMapOf(
        "UmmAlQura" to Method("أم القرى (مكة)", 18.5, 0.0, ishaMinutes = 90),
        "MWL" to Method("رابطة العالم الإسلامي", 18.0, 17.0),
        "Egypt" to Method("الهيئة المصرية العامة للمساحة", 19.5, 17.5),
        "Karachi" to Method("جامعة العلوم الإسلامية بكراتشي", 18.0, 18.0),
        "ISNA" to Method("أمريكا الشمالية ISNA", 15.0, 15.0),
        "Jafari" to Method("الجعفري (مؤسسة ليفا – قم)", 16.0, 14.0, maghribAngle = 4.0),
        "Tehran" to Method("معهد الجيوفيزياء – طهران", 17.7, 14.0, maghribAngle = 4.5),
    )

    val names = listOf("الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء")

    private fun dsin(d: Double) = sin(Math.toRadians(d))
    private fun dcos(d: Double) = cos(Math.toRadians(d))
    private fun dtan(d: Double) = tan(Math.toRadians(d))
    private fun darcsin(x: Double) = Math.toDegrees(asin(x))
    private fun darccos(x: Double) = Math.toDegrees(acos(x.coerceIn(-1.0, 1.0)))
    private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    private fun darccot(x: Double) = Math.toDegrees(atan(1 / x))
    private fun fix(a: Double, b: Double): Double { val r = a - b * floor(a / b); return if (r < 0) r + b else r }

    private fun julian(y0: Int, m0: Int, d: Int): Double {
        var y = y0; var m = m0
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0); val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    /** returns [declination, equation of time] */
    private fun sunPos(jd: Double): DoubleArray {
        val dd = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * dd, 360.0)
        val q = fix(280.459 + 0.98564736 * dd, 360.0)
        val l = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * dd
        val ra = fix(darctan2(dcos(e) * dsin(l), dcos(l)) / 15, 24.0)
        val eqt = q / 15 - ra
        val decl = darcsin(dsin(e) * dsin(l))
        return doubleArrayOf(decl, eqt)
    }

    /** Times in local hours (e.g. 12.5 = 12:30) for: fajr, sunrise, dhuhr, asr, maghrib, isha */
    fun times(date: LocalDate, s: PrayerSettings, zone: ZoneId = ZoneId.systemDefault()): DoubleArray {
        val m = methods[s.method] ?: methods["UmmAlQura"]!!
        val lat = s.lat; val lng = s.lon
        val tz = zone.rules.getOffset(date.atTime(12, 0)).totalSeconds / 3600.0
        val jd = julian(date.year, date.monthValue, date.dayOfMonth) - lng / (15 * 24.0)

        fun midDay(t: Double) = fix(12 - sunPos(jd + t)[1], 24.0)
        fun angleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sunPos(jd + t)[0]
            val noon = midDay(t)
            val tt = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15
            return noon + if (ccw) -tt else tt
        }
        fun asrTime(factor: Double, t: Double): Double {
            val decl = sunPos(jd + t)[0]
            val angle = -darccot(factor + dtan(abs(lat - decl)))
            return angleTime(angle, t, false)
        }

        var t = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0, 18.0) // fajr sunrise dhuhr asr sunset maghrib isha
        repeat(2) {
            val p = t.map { it / 24 }
            t = doubleArrayOf(
                angleTime(m.fajr, p[0], true),
                angleTime(0.833, p[1], true),
                midDay(p[2]),
                asrTime(if (s.asrHanafi) 2.0 else 1.0, p[3]),
                angleTime(0.833, p[4], false),
                if (m.maghribAngle > 0) angleTime(m.maghribAngle, p[5], false) else angleTime(0.833, p[5], false),
                if (m.ishaMinutes > 0) 0.0 else angleTime(m.isha, p[6], false),
            )
        }
        val shift = tz - lng / 15
        val out = doubleArrayOf(t[0], t[1], t[2], t[3], t[5], t[6]).map { it + shift }.toDoubleArray()
        if (m.ishaMinutes > 0) {
            val ramadan = isRamadan(date)
            out[5] = out[4] + (if (ramadan) m.ishaMinutes + 30 else m.ishaMinutes) / 60.0
        }
        for (i in 0..5) out[i] += (s.adjust.getOrElse(i) { 0 }) / 60.0
        return out
    }

    private fun isRamadan(date: LocalDate): Boolean = try {
        val cal = android.icu.util.IslamicCalendar()
        cal.calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        cal.timeInMillis = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        cal.get(android.icu.util.Calendar.MONTH) == 8
    } catch (e: Throwable) { false }
}
