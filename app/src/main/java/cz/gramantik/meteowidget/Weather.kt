package cz.gramantik.meteowidget

import java.util.Locale
import kotlin.math.pow

/** Měřítko a jednotka jedné hodnoty podle Tuya specifikace zařízení. */
data class Spec(val scale: Int, val unit: String)

/**
 * Převod surových hodnot Sencor SWS 16600 (Tuya) na lidské jednotky.
 * Měřítka se berou ze specifikace stažené z Tuya; když chybí, použijí se výchozí
 * (ověřené podle displeje stanice: teploty /10, tlak /10, déšť /1000).
 * Nepřipojené čidlo hlásí teplotu 601 a vlhkost 101.
 */
class Weather(private val d: Map<String, Any>, private val spec: Map<String, Spec>) {

    private fun raw(code: String): Double? = when (val v = d[code]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun scaled(code: String, defScale: Int): Double? {
        val r = raw(code) ?: return null
        val s = spec[code]?.scale ?: defScale
        return r / 10.0.pow(s)
    }

    fun unit(code: String, def: String) = spec[code]?.unit?.takeIf { it.isNotBlank() } ?: def

    fun temp(code: String): Double? {
        val t = scaled(code, 1) ?: return null
        return if (t >= 60 || t <= -40) null else t
    }

    fun hum(code: String): Int? {
        val r = scaled(code, 0)?.toInt() ?: return null
        return if (r in 0..100) r else null
    }

    val tempIn get() = temp("temp_current")
    val humIn get() = hum("humidity_value")
    val tempOut get() = temp("temp_current_external")
    val humOut get() = hum("humidity_outdoor")
    fun tempSensor(i: Int) = temp("temp_current_external_$i")
    fun humSensor(i: Int) = hum("humidity_outdoor_$i")

    val pressure: Double? get() = scaled("atmospheric_pressture", 1)?.takeIf { it > 0 }
    val feelsLike get() = temp("feellike_temp")
    val dewPoint get() = temp("dew_point_temp")
    val windChill get() = temp("windchill_index")
    val uv: Double? get() = scaled("uv_index", 0)

    /** Rychlost větru vždy v km/h. */
    private fun wind(code: String): Double? {
        val v = scaled(code, 1) ?: return null
        val u = unit(code, "km/h").lowercase(Locale.ROOT)
        return when {
            u.contains("m/s") -> v * 3.6
            u.contains("mph") -> v * 1.609
            u.contains("kn") -> v * 1.852
            else -> v
        }
    }
    val windAvg get() = wind("windspeed_avg")
    val windGust get() = wind("windspeed_gust")

    /** Směr větru, pokud ho stanice posílá (jako text nebo ve stupních). */
    val windDir: String?
        get() {
            val code = d.keys.firstOrNull { it.startsWith("wind_dir") } ?: return null
            return when (val v = d[code]) {
                is Number -> compass(v.toDouble())
                is String -> v.uppercase(Locale.ROOT).replace("_", "")
                else -> null
            }
        }

    val rainDay get() = scaled("rain_24h", 3)
    val rainRate get() = scaled("rain_rate", 3)

    /** Osvětlení v klux. */
    val lightKlux: Double?
        get() {
            val v = scaled("bright_value", 0) ?: return null
            return if (unit("bright_value", "lux").lowercase(Locale.ROOT).startsWith("k")) v else v / 1000.0
        }

    companion object {
        private val CZ = Locale("cs", "CZ")
        fun t(v: Double?) = if (v == null) "--°" else String.format(CZ, "%.1f°", v)
        fun h(v: Int?) = if (v == null) "-- %" else "$v %"
        fun n1(v: Double) = String.format(CZ, "%.1f", v)
        fun hpa(v: Double) = String.format(CZ, "%.0f hPa", v)

        private val DIRS = listOf(
            "S", "SSV", "SV", "VSV", "V", "VJV", "JV", "JJV",
            "J", "JJZ", "JZ", "ZJZ", "Z", "ZSZ", "SZ", "SSZ"
        )
        fun compass(deg: Double): String = DIRS[(((deg % 360) + 360) % 360 / 22.5 + 0.5).toInt() % 16]
    }
}
