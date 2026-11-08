package cz.gramantik.meteowidget

import android.content.Context
import org.json.JSONObject

/** Uložené nastavení a poslední stažená data. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = sp.getString(key, def) ?: def
    private fun put(key: String, value: String?) = sp.edit().putString(key, value).apply()

    // --- Tuya ---
    var accessId: String
        get() = str("accessId")
        set(v) = put("accessId", v.trim())
    var accessSecret: String
        get() = str("accessSecret")
        set(v) = put("accessSecret", v.trim())
    var deviceId: String
        get() = str("deviceId")
        set(v) = put("deviceId", v.trim())
    var host: String
        get() = str("host", DATA_CENTERS[0].second)
        set(v) = put("host", v)

    val isConfigured: Boolean
        get() = accessId.isNotEmpty() && accessSecret.isNotEmpty() && deviceId.isNotEmpty()

    // --- názvy kanálů ---
    fun channelName(ch: Channel): String = str("name_${ch.key}", ch.defaultName)
    fun setChannelName(ch: Channel, v: String) =
        put("name_${ch.key}", v.trim().ifEmpty { ch.defaultName })

    // --- mini widgety: kanál pro každý widget ---
    fun miniChannel(widgetId: Int): Channel =
        Channel.fromKey(sp.getString("mini_$widgetId", null)) ?: Channel.OUT
    fun setMiniChannel(widgetId: Int, ch: Channel) = put("mini_$widgetId", ch.key)
    fun removeMini(widgetId: Int) = sp.edit().remove("mini_$widgetId").apply()

    // --- předpověď ---
    var forecastSource: String
        get() = str("fcSource", SOURCE_OPEN_METEO)
        set(v) = put("fcSource", v)
    var googleKey: String
        get() = str("googleKey")
        set(v) = put("googleKey", v.trim())
    var mapyKey: String
        get() = str("mapyKey")
        set(v) = put("mapyKey", v.trim())
    var owmKey: String
        get() = str("owmKey")
        set(v) = put("owmKey", v.trim())
    var locName: String
        get() = str("locName", "Těmice")
        set(v) = put("locName", v.trim())
    var lat: Double
        get() = str("lat", "49.0017").toDoubleOrNull() ?: 49.0017
        set(v) = put("lat", v.toString())
    var lon: Double
        get() = str("lon", "17.265").toDoubleOrNull() ?: 17.265
        set(v) = put("lon", v.toString())

    var forecast: List<ForecastDay>
        get() = ForecastDay.fromJson(sp.getString("forecast", null))
        set(v) = sp.edit()
            .putString("forecast", ForecastDay.toJson(v))
            .putLong("forecastTime", System.currentTimeMillis())
            .putString("forecastFrom", forecastSource)
            .remove("forecastError")
            .apply()
    val forecastTime: Long get() = sp.getLong("forecastTime", 0)
    val forecastFrom: String get() = str("forecastFrom", "")
    var forecastError: String?
        get() = sp.getString("forecastError", null)
        set(v) = put("forecastError", v)

    // --- token ---
    val token: String? get() = sp.getString("token", null)
    val tokenExpiry: Long get() = sp.getLong("tokenExpiry", 0)
    fun saveToken(t: String, expiry: Long) =
        sp.edit().putString("token", t).putLong("tokenExpiry", expiry).apply()
    fun clearToken() = sp.edit().remove("token").remove("tokenExpiry").apply()

    // --- data ze stanice ---
    val lastData: Map<String, Any>?
        get() {
            val s = sp.getString("lastData", null) ?: return null
            return try {
                val o = JSONObject(s)
                o.keys().asSequence().associateWith { o.get(it) }
            } catch (e: Exception) {
                null
            }
        }
    val lastUpdate: Long get() = sp.getLong("lastUpdate", 0)
    fun saveData(data: Map<String, Any>, time: Long) =
        sp.edit()
            .putString("lastData", JSONObject(data).toString())
            .putLong("lastUpdate", time)
            .remove("lastError")
            .apply()

    var lastError: String?
        get() = sp.getString("lastError", null)
        set(v) = put("lastError", v)

    // --- specifikace (měřítka) ---
    var spec: Map<String, Spec>
        get() {
            val s = sp.getString("spec", null) ?: return emptyMap()
            return try {
                val o = JSONObject(s)
                o.keys().asSequence().associateWith {
                    val e = o.getJSONObject(it)
                    Spec(e.optInt("scale", 0), e.optString("unit", ""))
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }
        set(v) {
            val o = JSONObject()
            v.forEach { (code, s) -> o.put(code, JSONObject().put("scale", s.scale).put("unit", s.unit)) }
            put("spec", o.toString())
        }
    fun clearSpec() = sp.edit().remove("spec").apply()

    // --- aktualizace aplikace ---
    var updateCheckTime: Long
        get() = sp.getLong("updCheck", 0)
        set(v) = sp.edit().putLong("updCheck", v).apply()
    var availableVersion: Int
        get() = sp.getInt("updVersion", 0)
        set(v) = sp.edit().putInt("updVersion", v).apply()
    var availableUrl: String
        get() = str("updUrl")
        set(v) = put("updUrl", v)

    companion object {
        const val SOURCE_OPEN_METEO = "openmeteo"
        const val SOURCE_GOOGLE = "google"

        val DATA_CENTERS = listOf(
            "Central Europe" to "openapi.tuyaeu.com",
            "Western Europe" to "openapi-weaz.tuyaeu.com",
            "Western America" to "openapi.tuyaus.com",
            "Eastern America" to "openapi-ueaz.tuyaus.com",
            "China" to "openapi.tuyacn.com",
            "India" to "openapi.tuyain.com",
        )
    }
}

/** Měřicí kanály stanice. */
enum class Channel(val key: String, val defaultName: String, val tempCode: String, val humCode: String) {
    IN("in", "Doma", "temp_current", "humidity_value"),
    OUT("out", "Venku", "temp_current_external", "humidity_outdoor"),
    S1("s1", "Čidlo 1", "temp_current_external_1", "humidity_outdoor_1"),
    S2("s2", "Čidlo 2", "temp_current_external_2", "humidity_outdoor_2"),
    S3("s3", "Čidlo 3", "temp_current_external_3", "humidity_outdoor_3");

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k }
    }
}

/** Srozumitelný text chyby pro uživatele. */
fun friendlyError(raw: String?, lastUpdate: Long): String? {
    if (raw == null) return null
    val r = raw.lowercase()
    val time = if (lastUpdate > 0)
        java.text.SimpleDateFormat("d.M. HH:mm", java.util.Locale("cs", "CZ")).format(java.util.Date(lastUpdate)) else null
    val shown = time?.let { " – zobrazuji data z $it" } ?: ""
    return when {
        "unable to resolve host" in r || "no address associated" in r || "failed to connect" in r ||
            "network is unreachable" in r || "connection abort" in r || "connection reset" in r ->
            "📡 Bez připojení k internetu$shown"
        "timeout" in r || "timed out" in r -> "⏱ Server Tuya neodpovídá$shown"
        "token" in r || "sign" in r || "1004" in r || "1010" in r -> "🔑 Chyba přihlášení k Tuya – zkontroluj údaje v nastavení"
        "28841" in r || "expired" in r -> "⌛ Vypršela zkušební služba Tuya IoT Core – prodluž ji na iot.tuya.com"
        else -> "⚠ $raw"
    }
}
