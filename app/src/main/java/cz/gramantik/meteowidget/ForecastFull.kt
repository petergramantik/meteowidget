package cz.gramantik.meteowidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

data class HourF(
    val time: LocalDateTime,
    val temp: Double, val feels: Double, val hum: Double, val dew: Double,
    val precip: Double, val prob: Int, val snow: Double,
    val code: Int, val cloud: Double, val pressure: Double,
    val wind: Double, val gust: Double, val dir: Double,
    val uv: Double, val visibility: Double, val isDay: Boolean,
    val cape: Double,
) {
    val icon get() = ForecastFull.hourIcon(code, precip, isDay)
    /** 0 = žádné, 1 = možné, 2 = vysoké riziko bouřek */
    val storm get() = ForecastFull.stormLevel(code, cape)
}

data class DayF(
    val date: LocalDate, val code: Int,
    val tMax: Double, val tMin: Double, val feelsMax: Double, val feelsMin: Double,
    val precip: Double, val prob: Int, val precipHours: Double, val snow: Double,
    val windMax: Double, val gustMax: Double, val dir: Double, val uvMax: Double,
    val sunrise: LocalDateTime?, val sunset: LocalDateTime?,
    val sunshineH: Double, val daylightH: Double,
) {
    val icon get() = ForecastFull.dayIcon(code, precip)
}

data class FullForecast(val hours: List<HourF>, val days: List<DayF>, val fetched: Long)

/** Podrobná předpověď z Open-Meteo (hodinová + denní, 16 dní) pro obrazovku Předpověď. */
object ForecastFull {
    private const val FILE = "forecast_full.json"

    fun fetchedAt(ctx: Context): Long = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)
        .getLong("fullFcTime", 0)

    fun fetch(ctx: Context) {
        val p = Prefs(ctx)
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${String.format(Locale.US, "%.4f", p.lat)}&longitude=${String.format(Locale.US, "%.4f", p.lon)}" +
            "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,precipitation," +
            "precipitation_probability,snowfall,weather_code,cloud_cover,pressure_msl,wind_speed_10m," +
            "wind_gusts_10m,wind_direction_10m,uv_index,visibility,is_day,cape" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max," +
            "apparent_temperature_min,precipitation_sum,precipitation_probability_max,precipitation_hours," +
            "snowfall_sum,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,uv_index_max," +
            "sunrise,sunset,sunshine_duration,daylight_duration" +
            "&timezone=Europe%2FPrague&forecast_days=16"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "meteowidget-android")
        val body = try {
            if (conn.responseCode !in 200..299) throw Exception("Open-Meteo HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        JSONObject(body) // ověřit, že je to platný JSON
        File(ctx.filesDir, FILE).writeText(body)
        ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE).edit()
            .putLong("fullFcTime", System.currentTimeMillis()).apply()
        load(ctx)?.let { f ->
            logTomorrow(ctx, f.days.map {
                ForecastDay(it.date.toString(), it.icon, it.tMax, it.tMin, it.precip, it.prob)
            }, Prefs.SOURCE_OPEN_METEO)
        }
    }

    /**
     * Uloží předpověď na zítřek (poslední verze z předchozího dne) pro porovnání se skutečností.
     */
    fun logTomorrow(ctx: Context, days: List<ForecastDay>, source: String) {
        val tomorrow = LocalDate.now().plusDays(1)
        val d = days.firstOrNull { it.date == tomorrow.toString() } ?: return
        if (d.tMax.isNaN() || d.tMin.isNaN()) return
        MeteoDb.get(ctx).logForecast(ForecastLogRow(tomorrow, source, d.tMax, d.tMin, d.precipMm))
    }

    fun load(ctx: Context): FullForecast? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        return try {
            parse(JSONObject(f.readText()), fetchedAt(ctx))
        } catch (e: Exception) {
            null
        }
    }

    private fun JSONObject.dArr(name: String): DoubleArray {
        val a = optJSONArray(name) ?: return DoubleArray(0)
        return DoubleArray(a.length()) { if (a.isNull(it)) Double.NaN else a.optDouble(it, Double.NaN) }
    }

    private fun JSONObject.sArr(name: String): List<String?> {
        val a = optJSONArray(name) ?: JSONArray()
        return (0 until a.length()).map { if (a.isNull(it)) null else a.optString(it) }
    }

    private fun DoubleArray.at(i: Int) = if (i < size) this[i] else Double.NaN

    private fun parse(o: JSONObject, fetched: Long): FullForecast {
        val h = o.getJSONObject("hourly")
        val ht = h.sArr("time")
        val temp = h.dArr("temperature_2m"); val feels = h.dArr("apparent_temperature")
        val hum = h.dArr("relative_humidity_2m"); val dew = h.dArr("dew_point_2m")
        val pr = h.dArr("precipitation"); val prob = h.dArr("precipitation_probability")
        val snow = h.dArr("snowfall"); val code = h.dArr("weather_code")
        val cloud = h.dArr("cloud_cover"); val pres = h.dArr("pressure_msl")
        val wind = h.dArr("wind_speed_10m"); val gust = h.dArr("wind_gusts_10m")
        val dir = h.dArr("wind_direction_10m"); val uv = h.dArr("uv_index")
        val vis = h.dArr("visibility"); val isDay = h.dArr("is_day"); val cape = h.dArr("cape")
        val hours = ht.mapIndexedNotNull { i, s ->
            val t = s?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return@mapIndexedNotNull null
            HourF(
                t, temp.at(i), feels.at(i), hum.at(i), dew.at(i), pr.at(i).nz(), prob.at(i).toIntOr(-1),
                snow.at(i).nz(), code.at(i).toIntOr(-1), cloud.at(i), pres.at(i), wind.at(i), gust.at(i),
                dir.at(i), uv.at(i), vis.at(i), isDay.at(i) != 0.0, cape.at(i),
            )
        }

        val d = o.getJSONObject("daily")
        val dt = d.sArr("time")
        val dCode = d.dArr("weather_code"); val tMax = d.dArr("temperature_2m_max"); val tMin = d.dArr("temperature_2m_min")
        val fMax = d.dArr("apparent_temperature_max"); val fMin = d.dArr("apparent_temperature_min")
        val dPr = d.dArr("precipitation_sum"); val dProb = d.dArr("precipitation_probability_max")
        val dPh = d.dArr("precipitation_hours"); val dSnow = d.dArr("snowfall_sum")
        val dWind = d.dArr("wind_speed_10m_max"); val dGust = d.dArr("wind_gusts_10m_max")
        val dDir = d.dArr("wind_direction_10m_dominant"); val dUv = d.dArr("uv_index_max")
        val rise = d.sArr("sunrise"); val set = d.sArr("sunset")
        val sun = d.dArr("sunshine_duration"); val light = d.dArr("daylight_duration")
        val days = dt.mapIndexedNotNull { i, s ->
            val date = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapIndexedNotNull null
            DayF(
                date, dCode.at(i).toIntOr(-1), tMax.at(i), tMin.at(i), fMax.at(i), fMin.at(i),
                dPr.at(i).nz(), dProb.at(i).toIntOr(-1), dPh.at(i).nz(), dSnow.at(i).nz(),
                dWind.at(i), dGust.at(i), dDir.at(i), dUv.at(i),
                rise.getOrNull(i)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                set.getOrNull(i)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                sun.at(i).nz() / 3600.0, light.at(i).nz() / 3600.0,
            )
        }
        return FullForecast(hours, days, fetched)
    }

    private fun Double.nz() = if (isNaN()) 0.0 else this
    private fun Double.toIntOr(def: Int) = if (isNaN()) def else toInt()

    // ---------- ikony a popisy ----------
    fun dayIcon(code: Int, mm: Double): String = when (code) {
        0 -> "clear-day"
        1, 2 -> "partly-cloudy-day"
        3 -> "overcast"
        45, 48 -> "fog-day"
        51, 53, 55, 61, 63, 65 -> when { mm < 1 -> "rain-1"; mm < 5 -> "rain-2"; mm < 15 -> "rain-3"; else -> "rain-4" }
        56, 57, 66, 67 -> "sleet-2"
        71, 73, 75, 77 -> when { mm < 1 -> "snow-1"; mm < 5 -> "snow-2"; else -> "snow-3" }
        80, 81, 82 -> if (mm < 5) "showers-1" else "showers-2"
        85, 86 -> "snow-showers"
        95 -> "thunderstorms-day-rain"
        96, 99 -> "thunderstorms-rain"
        else -> "not-available"
    }

    /** Hodinová ikona – srážky za hodinu, v noci noční varianty. */
    fun hourIcon(code: Int, mm: Double, day: Boolean): String {
        val i = when (code) {
            51, 53, 55, 61, 63, 65 -> when { mm < 0.5 -> "rain-1"; mm < 2 -> "rain-2"; mm < 5 -> "rain-3"; else -> "rain-4" }
            71, 73, 75, 77 -> when { mm < 0.3 -> "snow-1"; mm < 1 -> "snow-2"; else -> "snow-3" }
            80, 81, 82 -> if (mm < 2) "showers-1" else "showers-2"
            else -> dayIcon(code, mm)
        }
        if (day) return i
        return when (i) {
            "clear-day" -> "clear-night"
            "partly-cloudy-day" -> "partly-cloudy-night"
            "fog-day" -> "fog-night"
            "showers-1" -> "rain-1"
            "showers-2" -> "rain-2"
            "snow-showers" -> "snow-2"
            "thunderstorms-day-rain" -> "thunderstorms-night-rain"
            else -> i
        }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Jasno"
        1 -> "Skoro jasno"
        2 -> "Polojasno"
        3 -> "Zataženo"
        45 -> "Mlha"
        48 -> "Mlha s námrazou"
        51 -> "Slabé mrholení"
        53 -> "Mrholení"
        55 -> "Silné mrholení"
        56, 57 -> "Mrznoucí mrholení"
        61 -> "Slabý déšť"
        63 -> "Déšť"
        65 -> "Silný déšť"
        66, 67 -> "Mrznoucí déšť"
        71 -> "Slabé sněžení"
        73 -> "Sněžení"
        75 -> "Silné sněžení"
        77 -> "Sněhová zrna"
        80 -> "Slabé přeháňky"
        81 -> "Přeháňky"
        82 -> "Silné přeháňky"
        85 -> "Sněhové přeháňky"
        86 -> "Silné sněhové přeháňky"
        95 -> "Bouřka"
        96, 99 -> "Bouřka s kroupami"
        else -> "—"
    }

    /**
     * Riziko bouřek z předpovědi: bouřka v kódu počasí, nebo vysoká CAPE
     * (energie pro vznik konvekce, J/kg).
     */
    fun stormLevel(code: Int, cape: Double): Int = when {
        code >= 95 -> 2
        !cape.isNaN() && cape >= 1000 -> 2
        !cape.isNaN() && cape >= 400 -> 1
        else -> 0
    }

    fun stormText(level: Int) = when (level) {
        2 -> "⚡ vysoké riziko bouřek"
        1 -> "⚡ možné bouřky"
        else -> ""
    }

    fun windDir(deg: Double): String = if (deg.isNaN()) "" else Weather.compass(deg)
}
