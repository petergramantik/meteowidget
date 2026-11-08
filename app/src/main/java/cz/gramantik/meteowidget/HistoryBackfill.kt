package cz.gramantik.meteowidget

import android.content.Context
import org.json.JSONObject

/**
 * Doplní historii posledních dní z Tuya cloudu po 15minutových úsecích.
 * Zkouší nové API (report-logs) a když nic nevrátí, starší API (device logs).
 * Výsledek (počet záznamů pro každou hodnotu) uloží pro diagnostiku v nastavení.
 */
object HistoryBackfill {
    private const val SLOT = 15 * 60_000L
    val CODES = listOf(
        "temp_current", "humidity_value", "temp_current_external", "humidity_outdoor",
        "temp_current_external_1", "humidity_outdoor_1",
        "atmospheric_pressture", "windspeed_avg", "windspeed_gust", "rain_24h",
        "uv_index", "bright_value",
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    /** Výsledek posledního doplnění: kód -> text (počet záznamů nebo chyba). */
    fun stats(ctx: Context): Map<String, String> {
        val s = sp(ctx).getString("histBackfillStats", null) ?: return emptyMap()
        return try {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun lastRun(ctx: Context): Long = sp(ctx).getLong("histBackfillTry", 0)

    fun runIfNeeded(ctx: Context, p: Prefs, client: TuyaClient) {
        val now = System.currentTimeMillis()
        if (sp(ctx).getBoolean("histBackfillDone", false)) return
        if (now - sp(ctx).getLong("histBackfillTry", 0) < 6 * 3_600_000L) return
        run(ctx, p, client)
    }

    /** Spustí doplnění hned (i opakovaně). Vrací počet nově vložených záznamů. */
    fun run(ctx: Context, p: Prefs, client: TuyaClient): Int {
        val now = System.currentTimeMillis()
        sp(ctx).edit().putLong("histBackfillTry", now).apply()

        val from = now - 7 * 24 * 3_600_000L
        val slots = HashMap<Long, HashMap<String, Any>>()
        val stats = JSONObject()
        var anyOk = false
        for (code in CODES) {
            var logs: List<Pair<Long, Double>> = emptyList()
            var note = ""
            try {
                logs = client.fetchReportLogs(code, from, now, maxPages = 25)
                note = "v2"
            } catch (e: Exception) {
                note = "v2 chyba: ${e.message}"
            }
            if (logs.isEmpty()) {
                try {
                    logs = client.fetchLogsV1(code, from, now, maxPages = 25)
                    note += ", v1"
                } catch (e: Exception) {
                    note += ", v1 chyba: ${e.message}"
                }
            }
            if (logs.isNotEmpty()) anyOk = true
            stats.put(code, "${logs.size} záznamů ($note)")
            for ((t, v) in logs) slots.getOrPut(t / SLOT * SLOT) { HashMap() }[code] = v
        }
        sp(ctx).edit().putString("histBackfillStats", stats.toString()).apply()
        if (!anyOk) return 0

        val db = MeteoDb.get(ctx)
        val spec = p.spec
        // hodnoty hlášené jen při změně přenést do dalších úseků
        val carry = HashMap<String, Any>()
        var inserted = 0
        for (slot in slots.keys.sorted()) {
            carry.putAll(slots[slot]!!)
            val t = slot + SLOT / 2
            if (db.hasNear(t, SLOT / 2)) continue
            db.insert(Sample.from(t, Weather(HashMap(carry), spec)), replace = false)
            inserted++
        }
        sp(ctx).edit().putBoolean("histBackfillDone", true).apply()
        return inserted
    }
}
