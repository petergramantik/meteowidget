package cz.gramantik.meteowidget

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/** Jedna výstraha ČHMÚ pro sledovanou oblast. */
data class WarnAlert(
    val id: String,
    val event: String,
    val severity: String,   // Minor / Moderate / Severe / Extreme
    val onset: Long,
    val expires: Long,
    val description: String,
    val instruction: String,
    val areas: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("event", event).put("severity", severity)
        .put("onset", onset).put("expires", expires)
        .put("description", description).put("instruction", instruction).put("areas", areas)

    /** Barva podle stupně nebezpečí (jako na webu ČHMÚ). */
    val color: Int
        get() = when (severity) {
            "Extreme" -> 0xFFB71C1C.toInt()
            "Severe" -> 0xFFE53935.toInt()
            "Moderate" -> 0xFFFB8C00.toInt()
            else -> 0xFFFDD835.toInt()
        }

    val level: String
        get() = when (severity) {
            "Extreme" -> "extrémní nebezpečí"
            "Severe" -> "vysoké nebezpečí"
            "Moderate" -> "výrazné nebezpečí"
            else -> "nízké nebezpečí"
        }

    companion object {
        fun fromJson(o: JSONObject) = WarnAlert(
            o.getString("id"), o.getString("event"), o.getString("severity"),
            o.getLong("onset"), o.getLong("expires"),
            o.optString("description"), o.optString("instruction"), o.optString("areas"),
        )
    }
}

/** Výstrahy ČHMÚ z veřejného CAP XML, filtrované podle názvu oblasti (ORP). */
object Alerts {
    private val URLS = listOf(
        "https://www.chmi.cz/files/portal/docs/meteo/om/bulletiny/XOCZ50_OKPR.xml",
        "https://portal.chmi.cz/files/portal/docs/meteo/om/bulletiny/XOCZ50_OKPR.xml",
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    fun region(ctx: Context): String = sp(ctx).getString("alertRegion", "Kyjov") ?: "Kyjov"
    fun setRegion(ctx: Context, v: String) = sp(ctx).edit().putString("alertRegion", v.trim()).apply()

    fun fetchedAt(ctx: Context): Long = sp(ctx).getLong("alertsTime", 0)

    fun load(ctx: Context): List<WarnAlert> {
        val s = sp(ctx).getString("alerts", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            val now = System.currentTimeMillis()
            (0 until a.length()).map { WarnAlert.fromJson(a.getJSONObject(it)) }.filter { it.expires > now }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun fetch(ctx: Context) {
        var lastErr: Exception? = null
        for (u in URLS) {
            try {
                val xml = download(u)
                val list = parse(xml, region(ctx))
                val a = JSONArray()
                list.forEach { a.put(it.toJson()) }
                sp(ctx).edit().putString("alerts", a.toString()).putLong("alertsTime", System.currentTimeMillis()).apply()
                Notify.checkAlerts(ctx, list)
                return
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: Exception("Výstrahy nedostupné")
    }

    private fun download(u: String): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "meteowidget-android")
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun time(s: String?): Long =
        s?.let { runCatching { OffsetDateTime.parse(it.trim()).toInstant().toEpochMilli() }.getOrNull() } ?: 0L

    /** Rozparsuje CAP, vrátí aktuální české výstrahy, jejichž oblast obsahuje [region]. */
    fun parse(xml: String, region: String): List<WarnAlert> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())

        var identifier = ""
        var msgType = ""
        val out = ArrayList<WarnAlert>()

        // stav aktuálního <info>
        var inInfo = false
        var lang = ""
        var event = ""; var severity = ""; var onset = ""; var expires = ""
        var desc = ""; var instr = ""
        val areas = ArrayList<String>()
        var text = StringBuilder()

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    if (name == "info") {
                        inInfo = true; lang = ""; event = ""; severity = ""; onset = ""; expires = ""
                        desc = ""; instr = ""; areas.clear()
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val v = text.toString().trim()
                    if (!inInfo) {
                        when (name) {
                            "identifier" -> identifier = v
                            "msgType" -> msgType = v
                        }
                    } else {
                        when (name) {
                            "language" -> lang = v
                            "event" -> event = v
                            "severity" -> severity = v
                            "onset" -> onset = v
                            "expires" -> expires = v
                            "description" -> desc = v
                            "instruction" -> instr = v
                            "areaDesc" -> areas.add(v)
                            "info" -> {
                                inInfo = false
                                val czech = lang.isEmpty() || lang.startsWith("cs")
                                val match = region.isBlank() || areas.any { it.contains(region, ignoreCase = true) }
                                val exp = time(expires)
                                if (czech && match && msgType != "Cancel" && exp > System.currentTimeMillis() &&
                                    !event.contains("Žádný", ignoreCase = true)
                                ) {
                                    out.add(
                                        WarnAlert(
                                            "$identifier|$event|$onset", event, severity, time(onset), exp,
                                            desc, instr, areas.filter { it.contains(region, true) }.joinToString(", "),
                                        )
                                    )
                                }
                            }
                        }
                    }
                    text = StringBuilder()
                }
            }
            ev = parser.next()
        }
        return out.distinctBy { it.event + it.onset }.sortedBy { it.onset }
    }
}
