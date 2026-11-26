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
    /** otevřená data ČHMÚ – adresář s CAP soubory (bere se nejnovější) */
    private const val OPENDATA_DIR = "https://opendata.chmi.cz/meteorology/weather/alerts/cap/"
    private val URLS = listOf(
        "https://www.chmi.cz/files/portal/docs/meteo/om/bulletiny/XOCZ50_OKPR.xml",
        "https://portal.chmi.cz/files/portal/docs/meteo/om/bulletiny/XOCZ50_OKPR.xml",
    )

    /** Kódy ORP (číselník CISORP) – Jihomoravský kraj; jiné ORP lze zadat ručně v nastavení. */
    private val ORP = mapOf(
        "blansko" to "6201", "boskovice" to "6202", "brno" to "6203", "břeclav" to "6204", "bučovice" to "6205",
        "hodonín" to "6206", "hustopeče" to "6207", "ivančice" to "6208", "kuřim" to "6209", "kyjov" to "6210",
        "mikulov" to "6211", "moravský krumlov" to "6212", "pohořelice" to "6213", "rosice" to "6214",
        "slavkov u brna" to "6215", "šlapanice" to "6216", "tišnov" to "6217", "veselí nad moravou" to "6218",
        "vyškov" to "6219", "znojmo" to "6220", "židlochovice" to "6221",
    )

    /** kód ORP pro výběr výstrah: ruční, jinak podle názvu oblasti */
    fun orpCode(ctx: Context): String =
        sp(ctx).getString("alertOrp", "")?.trim()?.takeIf { it.isNotEmpty() } ?: ORP[region(ctx).trim().lowercase()] ?: ""
    fun manualOrp(ctx: Context): String = sp(ctx).getString("alertOrp", "") ?: ""
    fun setManualOrp(ctx: Context, v: String) = sp(ctx).edit().putString("alertOrp", v.trim()).apply()

    /** stav posledního stažení pro nastavení */
    fun status(ctx: Context): String = sp(ctx).getString("alertsStatus", "") ?: ""

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
        // 1) otevřená data (nejnovější soubor), 2) starší adresy
        val sources = ArrayList<() -> String>()
        sources += { download(latestOpendata()) }
        URLS.forEach { u -> sources += { download(u) } }
        for (src in sources) {
            try {
                val xml = src()
                if (!xml.contains("<alert")) throw Exception("neplatná data")
                val list = parse(xml, region(ctx), orpCode(ctx))
                val a = JSONArray()
                list.forEach { a.put(it.toJson()) }
                val tf = java.text.SimpleDateFormat("HH:mm", java.util.Locale("cs", "CZ"))
                val n = list.size
                val st = "✅ staženo v ${tf.format(java.util.Date())} · " +
                    (if (n == 0) "žádná platná výstraha" else "$n platn${if (n == 1) "á výstraha" else if (n < 5) "é výstrahy" else "ých výstrah"}") +
                    " pro ${region(ctx)}" + (orpCode(ctx).takeIf { it.isNotEmpty() }?.let { " (ORP $it)" } ?: " – ⚠ neznámý kód ORP, hledá se jen podle názvu")
                sp(ctx).edit().putString("alerts", a.toString()).putLong("alertsTime", System.currentTimeMillis())
                    .putString("alertsStatus", st).apply()
                Notify.checkAlerts(ctx, list)
                return
            } catch (e: Exception) {
                lastErr = e
            }
        }
        sp(ctx).edit().putString("alertsStatus", "❌ výstrahy nelze stáhnout: ${lastErr?.message ?: "neznámá chyba"}").apply()
        throw lastErr ?: Exception("Výstrahy nedostupné")
    }

    /** Adresa nejnovějšího CAP souboru v adresáři otevřených dat. */
    private fun latestOpendata(): String {
        val html = download(OPENDATA_DIR)
        val re = Regex("(alert_cap_[0-9_]+\\.xml)</a>\\s+(\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2})")
        val fmt = java.text.SimpleDateFormat("dd-MMM-yyyy HH:mm", java.util.Locale.ENGLISH)
        val best = re.findAll(html).mapNotNull { m ->
            runCatching { m.groupValues[1] to fmt.parse(m.groupValues[2])!!.time }.getOrNull()
        }.maxByOrNull { it.second } ?: throw Exception("v otevřených datech chybí soubor výstrah")
        return OPENDATA_DIR + best.first
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
    fun parse(xml: String, region: String, orp: String = ""): List<WarnAlert> {
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
        val orps = HashSet<String>()
        var inGeocode = false
        var geoName = ""
        var text = StringBuilder()

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.substringAfter(':')
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    if (name == "info") {
                        inInfo = true; lang = ""; event = ""; severity = ""; onset = ""; expires = ""
                        desc = ""; instr = ""; areas.clear(); orps.clear()
                    }
                    if (name == "geocode") { inGeocode = true; geoName = "" }
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
                            "geocode" -> inGeocode = false
                            "valueName" -> if (inGeocode) geoName = v
                            "value" -> if (inGeocode && geoName.equals("CISORP", true)) orps.add(v)
                            "info" -> {
                                inInfo = false
                                val czech = lang.isEmpty() || lang.startsWith("cs")
                                // shoda podle kódu ORP (oblast ČHMÚ popisuje názvem kraje), záložně podle názvu
                                val match = (orp.isNotEmpty() && orp in orps) || region.isBlank() ||
                                    areas.any { a -> a.split(';', ',').any { it.trim().equals(region, true) } || a.contains(region, true) }
                                val exp = time(expires)
                                if (czech && match && msgType != "Cancel" && exp > System.currentTimeMillis() &&
                                    !event.contains("Žádný", ignoreCase = true)
                                ) {
                                    out.add(
                                        WarnAlert(
                                            "$identifier|$event|$onset", event, severity, time(onset), exp,
                                            desc, instr, region,
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
