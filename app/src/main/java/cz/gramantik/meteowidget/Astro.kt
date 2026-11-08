package cz.gramantik.meteowidget

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Měsíc: fáze, osvětlení, východ a západ.
 * Výpočet podle zjednodušených vzorců (jako knihovna SunCalc), přesnost v řádu minut.
 */
object Astro {
    private const val RAD = PI / 180
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val SYNODIC = 29.530588853
    private const val E = RAD * 23.4397

    private fun toDays(ms: Long) = ms / DAY_MS - 0.5 + J1970 - J2000

    private fun ra(l: Double, b: Double) = atan2(sin(l) * cos(E) - tan(b) * sin(E), cos(l))
    private fun dec(l: Double, b: Double) = asin(sin(b) * cos(E) + cos(b) * sin(E) * sin(l))

    private fun moonAltitude(ms: Long, lat: Double, lon: Double): Double {
        val d = toDays(ms)
        val lw = RAD * -lon
        val phi = RAD * lat
        val l0 = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.229350 * d)
        val l = l0 + RAD * 6.289 * sin(m)
        val b = RAD * 5.128 * sin(f)
        val h = RAD * (280.16 + 360.9856235 * d) - lw - ra(l, b)
        val dc = dec(l, b)
        return asin(sin(phi) * sin(dc) + cos(phi) * cos(dc) * cos(h))
    }

    /** Východ a západ Měsíce v daný den (ms nebo null, když ten den nenastane). */
    fun moonTimes(date: LocalDate, lat: Double, lon: Double): Pair<Long?, Long?> {
        val t0 = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hour = 3_600_000L
        val hc = 0.133 * RAD
        var h0 = moonAltitude(t0, lat, lon) - hc
        var rise: Double? = null
        var set: Double? = null
        var i = 1
        while (i <= 24) {
            val h1 = moonAltitude(t0 + i * hour, lat, lon) - hc
            val h2 = moonAltitude(t0 + (i + 1) * hour, lat, lon) - hc
            val a = (h0 + h2) / 2 - h1
            val b = (h2 - h0) / 2
            val xe = -b / (2 * a)
            val ye = (a * xe + b) * xe + h1
            val d = b * b - 4 * a * h1
            var roots = 0
            var x1 = 0.0
            var x2 = 0.0
            if (d >= 0) {
                val dx = sqrt(d) / (abs(a) * 2)
                x1 = xe - dx
                x2 = xe + dx
                if (abs(x1) <= 1) roots++
                if (abs(x2) <= 1) roots++
                if (x1 < -1) x1 = x2
            }
            if (roots == 1) {
                if (h0 < 0) rise = i + x1 else set = i + x1
            } else if (roots == 2) {
                rise = i + if (ye < 0) x2 else x1
                set = i + if (ye < 0) x1 else x2
            }
            if (rise != null && set != null) break
            h0 = h2
            i += 2
        }
        return rise?.let { t0 + (it * hour).toLong() } to set?.let { t0 + (it * hour).toLong() }
    }

    /** Stáří Měsíce ve dnech od novu (0–29,5). */
    fun moonAge(ms: Long): Double {
        val ref = 947_182_440_000L // nov 6. 1. 2000 18:14 UTC
        val days = (ms - ref) / DAY_MS
        return ((days % SYNODIC) + SYNODIC) % SYNODIC
    }

    /** Osvětlená část Měsíce 0–1. */
    fun illumination(ms: Long): Double = (1 - cos(2 * PI * moonAge(ms) / SYNODIC)) / 2

    fun phaseName(ms: Long): String {
        val a = moonAge(ms)
        return when {
            a < 1.0 || a > 28.5 -> "Nov"
            a < 6.4 -> "Dorůstající srpek"
            a < 8.4 -> "První čtvrť"
            a < 13.8 -> "Dorůstající Měsíc"
            a < 15.8 -> "Úplněk"
            a < 21.1 -> "Couvající Měsíc"
            a < 23.1 -> "Poslední čtvrť"
            else -> "Couvající srpek"
        }
    }

    fun phaseEmoji(ms: Long): String {
        val a = moonAge(ms) / SYNODIC
        val i = ((a * 8) + 0.5).toInt() % 8
        return listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")[i]
    }

    /** Absolutní vlhkost vzduchu v g/m³. */
    fun absHumidity(t: Double, rh: Double): Double =
        6.112 * Math.exp(17.67 * t / (t + 243.5)) * rh * 2.1674 / (273.15 + t)
}
