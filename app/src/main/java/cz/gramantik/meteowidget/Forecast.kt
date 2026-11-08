package cz.gramantik.meteowidget

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Jeden den předpovědi. date = yyyy-MM-dd */
data class ForecastDay(
    val date: String,
    val icon: String,
    val tMax: Double,
    val tMin: Double,
    val precipMm: Double,
    val precipProb: Int,
) {
    companion object {
        fun toJson(list: List<ForecastDay>): String {
            val a = JSONArray()
            list.forEach {
                a.put(
                    JSONObject()
                        .put("d", it.date).put("i", it.icon)
                        .put("x", it.tMax).put("n", it.tMin)
                        .put("p", it.precipMm).put("pp", it.precipProb)
                )
            }
            return a.toString()
        }

        fun fromJson(s: String?): List<ForecastDay> {
            if (s.isNullOrEmpty()) return emptyList()
            return try {
                val a = JSONArray(s)
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    ForecastDay(
                        o.getString("d"), o.getString("i"),
                        o.getDouble("x"), o.getDouble("n"),
                        o.optDouble("p", 0.0), o.optInt("pp", -1)
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

object ForecastClient {

    fun fetch(p: Prefs): List<ForecastDay> =
        if (p.forecastSource == Prefs.SOURCE_GOOGLE) google(p) else openMeteo(p)

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "meteowidget-android")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(body).let { o ->
                        o.optJSONObject("error")?.optString("message") ?: o.optString("reason")
                    }
                } catch (e: Exception) {
                    ""
                }
                throw Exception("HTTP $code ${msg.take(120)}".trim())
            }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

    // ---------- Open-Meteo ----------
    private fun openMeteo(p: Prefs): List<ForecastDay> {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${coord(p.lat)}&longitude=${coord(p.lon)}" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max" +
            "&timezone=Europe%2FPrague&forecast_days=10"
        val d = get(url).getJSONObject("daily")
        val time = d.getJSONArray("time")
        val code = d.getJSONArray("weather_code")
        val max = d.getJSONArray("temperature_2m_max")
        val min = d.getJSONArray("temperature_2m_min")
        val pr = d.getJSONArray("precipitation_sum")
        val pp = d.optJSONArray("precipitation_probability_max")
        return (0 until time.length()).map { i ->
            ForecastDay(
                date = time.getString(i),
                icon = wmoIcon(code.optInt(i, -1)),
                tMax = max.optDouble(i, Double.NaN),
                tMin = min.optDouble(i, Double.NaN),
                precipMm = pr.optDouble(i, 0.0).let { if (it.isNaN()) 0.0 else it },
                precipProb = pp?.optInt(i, -1) ?: -1,
            )
        }
    }

    /** WMO kód počasí -> název ikony Meteocons */
    private fun wmoIcon(c: Int) = when (c) {
        0 -> "clear-day"
        1, 2 -> "partly-cloudy-day"
        3 -> "overcast"
        45, 48 -> "fog-day"
        51, 53, 55 -> "drizzle"
        56, 57, 66, 67 -> "sleet"
        61, 63, 65 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "partly-cloudy-day-rain"
        85, 86 -> "partly-cloudy-day-snow"
        95 -> "thunderstorms-day-rain"
        96, 99 -> "thunderstorms-rain"
        else -> "not-available"
    }

    // ---------- Google Weather API ----------
    private fun google(p: Prefs): List<ForecastDay> {
        if (p.googleKey.isEmpty()) throw Exception("Chybí Google API klíč")
        val result = ArrayList<ForecastDay>()
        var pageToken: String? = null
        do {
            var url = "https://weather.googleapis.com/v1/forecast/days:lookup" +
                "?key=${URLEncoder.encode(p.googleKey, "UTF-8")}" +
                "&location.latitude=${coord(p.lat)}&location.longitude=${coord(p.lon)}" +
                "&days=10&pageSize=10&languageCode=cs&unitsSystem=METRIC"
            if (pageToken != null) url += "&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}"
            val json = get(url)
            val days = json.optJSONArray("forecastDays") ?: JSONArray()
            for (i in 0 until days.length()) {
                val d = days.getJSONObject(i)
                val dd = d.getJSONObject("displayDate")
                val date = String.format(
                    Locale.US, "%04d-%02d-%02d",
                    dd.getInt("year"), dd.getInt("month"), dd.getInt("day")
                )
                val dayF = d.optJSONObject("daytimeForecast")
                val nightF = d.optJSONObject("nighttimeForecast")
                val type = dayF?.optJSONObject("weatherCondition")?.optString("type") ?: ""
                fun qpf(f: JSONObject?) =
                    f?.optJSONObject("precipitation")?.optJSONObject("qpf")?.optDouble("quantity", 0.0) ?: 0.0
                fun prob(f: JSONObject?) =
                    f?.optJSONObject("precipitation")?.optJSONObject("probability")?.optInt("percent", -1) ?: -1
                result.add(
                    ForecastDay(
                        date = date,
                        icon = googleIcon(type),
                        tMax = d.optJSONObject("maxTemperature")?.optDouble("degrees", Double.NaN) ?: Double.NaN,
                        tMin = d.optJSONObject("minTemperature")?.optDouble("degrees", Double.NaN) ?: Double.NaN,
                        precipMm = qpf(dayF) + qpf(nightF),
                        precipProb = maxOf(prob(dayF), prob(nightF)),
                    )
                )
            }
            pageToken = json.optString("nextPageToken", "").ifEmpty { null }
        } while (pageToken != null && result.size < 10)
        return result.take(10)
    }

    /** Typ počasí z Google -> název ikony Meteocons */
    private fun googleIcon(t: String): String = when {
        t.contains("THUNDER") -> if (t.startsWith("SCATTERED") || t.startsWith("LIGHT")) "thunderstorms-day-rain" else "thunderstorms-rain"
        t.contains("HAIL") -> "hail"
        t == "RAIN_AND_SNOW" -> "sleet"
        t.contains("SNOW") && (t.contains("SHOWERS")) -> "partly-cloudy-day-snow"
        t.contains("SNOW") -> "snow"
        t == "WIND_AND_RAIN" -> "rain"
        t.contains("SHOWERS") -> "partly-cloudy-day-rain"
        t == "LIGHT_RAIN" -> "drizzle"
        t.contains("RAIN") -> "rain"
        t == "PARTLY_CLOUDY" || t == "MOSTLY_CLEAR" -> "partly-cloudy-day"
        t == "MOSTLY_CLOUDY" -> "overcast-day"
        t == "CLOUDY" -> "overcast"
        t == "CLEAR" -> "clear-day"
        t.contains("WIND") -> "wind"
        t.contains("FOG") || t.contains("HAZE") -> "fog-day"
        else -> "not-available"
    }
}
