package cz.gramantik.meteowidget

import android.content.Context

/**
 * Jednorázově doplní historii posledních dní z Tuya cloudu (report-logs),
 * po 15minutových úsecích. Tuya drží logy jen omezenou dobu, takže co se
 * nepodaří stáhnout, začne aplikace sbírat sama.
 */
object HistoryBackfill {
    private const val SLOT = 15 * 60_000L
    private val CODES = listOf(
        "temp_current", "humidity_value", "temp_current_external", "humidity_outdoor",
        "temp_current_external_1", "humidity_outdoor_1",
        "atmospheric_pressture", "windspeed_avg", "windspeed_gust", "rain_24h",
        "uv_index", "bright_value",
    )

    fun runIfNeeded(ctx: Context, p: Prefs, client: TuyaClient) {
        val sp = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (sp.getBoolean("histBackfillDone", false)) return
        if (now - sp.getLong("histBackfillTry", 0) < 6 * 3_600_000L) return
        sp.edit().putLong("histBackfillTry", now).apply()

        val from = now - 7 * 24 * 3_600_000L
        // slot -> (kód -> surová hodnota)
        val slots = HashMap<Long, HashMap<String, Any>>()
        var anyOk = false
        for (code in CODES) {
            try {
                val logs = client.fetchReportLogs(code, from, now, maxPages = 25)
                anyOk = true
                for ((t, v) in logs) {
                    slots.getOrPut(t / SLOT * SLOT) { HashMap() }[code] = v
                }
            } catch (e: Exception) {
                // kód nemusí být dostupný – pokračovat dalším
            }
        }
        if (!anyOk) return

        val db = MeteoDb.get(ctx)
        val spec = p.spec
        // hodnoty, které se nehlásí často (např. tlak), přenést do dalších slotů
        val carry = HashMap<String, Any>()
        for (slot in slots.keys.sorted()) {
            carry.putAll(slots[slot]!!)
            val t = slot + SLOT / 2
            if (db.hasNear(t, SLOT / 2)) continue
            db.insert(Sample.from(t, Weather(HashMap(carry), spec)), replace = false)
        }
        sp.edit().putBoolean("histBackfillDone", true).apply()
    }
}
