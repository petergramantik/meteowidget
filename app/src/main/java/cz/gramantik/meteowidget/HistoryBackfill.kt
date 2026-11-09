package cz.gramantik.meteowidget

import android.content.Context
import org.json.JSONObject

/**
 * Doplní historii z Tuya cloudu po 15minutových úsecích.
 *
 * Šetrně k limitům Tuya: všechny hodnoty jedním dotazem, po dnech, s pauzami
 * a opakováním. Když Tuya dotazy omezí, pokračuje se při další obnově tam,
 * kde se skončilo. Do existujících záznamů se jen doplní chybějící hodnoty.
 */
object HistoryBackfill {
    private const val SLOT = 15 * 60_000L
    private const val DAY = 24 * 3_600_000L
    private const val DAYS = 7

    val CODES = listOf(
        "temp_current", "humidity_value", "temp_current_external", "humidity_outdoor",
        "temp_current_external_1", "humidity_outdoor_1",
        "temp_current_external_2", "humidity_outdoor_2",
        "temp_current_external_3", "humidity_outdoor_3",
        "atmospheric_pressture", "windspeed_avg", "windspeed_gust", "rain_24h",
        "uv_index", "bright_value", "feellike_temp", "dew_point_temp",
    )

    private fun sp(ctx: Context) = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    /** Stav pro výpis v nastavení: kód -> počet záznamů, plus řádek "_stav". */
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

    /** Začátky dnů (ms), které jsou už stažené. */
    private fun doneChunks(ctx: Context): MutableSet<String> =
        sp(ctx).getStringSet("histChunksDone", emptySet())!!.toMutableSet()

    /** Při běžné obnově: pokračuje v nedokončeném doplňování (nejdřív po 15 min). */
    fun runIfNeeded(ctx: Context, p: Prefs, client: TuyaClient) {
        if (sp(ctx).getBoolean("histBackfillDone", false)) return
        if (System.currentTimeMillis() - lastRun(ctx) < 15 * 60_000L) return
        run(ctx, p, client, restart = false)
    }

    /**
     * Doplní posledních 7 dní. [restart] = začít znovu (tlačítko v nastavení),
     * jinak pokračuje u dnů, které ještě nejsou hotové.
     * Vrací počet doplněných/rozšířených záznamů.
     */
    fun run(ctx: Context, p: Prefs, client: TuyaClient, restart: Boolean = true): Int {
        val now = System.currentTimeMillis()
        sp(ctx).edit().putLong("histBackfillTry", now).apply()
        if (restart) {
            sp(ctx).edit().remove("histChunksDone").remove("histCounts").putBoolean("histBackfillDone", false).apply()
        }
        val done = doneChunks(ctx)
        val counts = loadCounts(ctx)
        val today = now / DAY * DAY
        var changed = 0
        var limited = false
        var lastError: String? = null

        // od nejnovějšího dne (nejdůležitější) po nejstarší
        for (k in 0 until DAYS) {
            val from = today - k * DAY
            val to = minOf(from + DAY, now)
            if (from.toString() in done) continue
            try {
                var logs = client.fetchReportLogsMulti(CODES, from, to)
                if (logs.isEmpty()) logs = client.fetchLogsV1Multi(CODES, from, to)
                logs.groupingBy { it.code }.eachCount().forEach { (c, n) -> counts[c] = (counts[c] ?: 0) + n }
                changed += store(ctx, p, logs)
                // dnešek se nikdy neoznačí jako hotový – ještě přibývá
                if (k > 0) done.add(from.toString())
            } catch (e: TuyaClient.RateLimitException) {
                limited = true
                break
            } catch (e: Exception) {
                lastError = e.message
                break
            }
        }

        val remaining = (1 until DAYS).count { (today - it * DAY).toString() !in done }
        val status = when {
            limited -> "⏳ Tuya omezila počet dotazů – dokončí se automaticky později (zbývá $remaining dní)"
            lastError != null -> "⚠ Chyba: $lastError"
            remaining == 0 -> "✅ Hotovo – posledních $DAYS dní doplněno"
            else -> "Zbývá $remaining dní"
        }
        val stats = JSONObject().put("_stav", status)
        CODES.forEach { stats.put(it, "${counts[it] ?: 0} záznamů") }
        sp(ctx).edit()
            .putStringSet("histChunksDone", done)
            .putString("histBackfillStats", stats.toString())
            .putString("histCounts", JSONObject(counts as Map<*, *>).toString())
            .putBoolean("histBackfillDone", remaining == 0)
            .apply()
        return changed
    }

    /**
     * Doplní mezeru v historii (např. když telefon v noci uspal aplikaci).
     * Každou mezeru zkusí jen jednou.
     */
    fun fillGap(ctx: Context, p: Prefs, client: TuyaClient, from: Long, to: Long): Int {
        if (sp(ctx).getLong("gapTried", 0) == from) return 0
        sp(ctx).edit().putLong("gapTried", from).apply()
        return try {
            var logs = client.fetchReportLogsMulti(CODES, from, to, maxPages = 15)
            if (logs.isEmpty()) logs = client.fetchLogsV1Multi(CODES, from, to, maxPages = 15)
            store(ctx, p, logs)
        } catch (e: Exception) {
            0
        }
    }

    private fun loadCounts(ctx: Context): MutableMap<String, Int> {
        val s = sp(ctx).getString("histCounts", null) ?: return HashMap()
        return try {
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { o.getInt(it) }.toMutableMap()
        } catch (e: Exception) {
            HashMap()
        }
    }

    /** Rozdělí logy do 15min úseků a sloučí je s databází. */
    private fun store(ctx: Context, p: Prefs, logs: List<TuyaClient.LogEntry>): Int {
        if (logs.isEmpty()) return 0
        val slots = HashMap<Long, HashMap<String, Any>>()
        for (e in logs.sortedBy { it.t }) slots.getOrPut(e.t / SLOT * SLOT) { HashMap() }[e.code] = e.v
        val db = MeteoDb.get(ctx)
        val spec = p.spec
        // hodnoty hlášené jen při změně přenést do dalších úseků
        val carry = HashMap<String, Any>()
        var n = 0
        for (slot in slots.keys.sorted()) {
            carry.putAll(slots[slot]!!)
            val t = slot + SLOT / 2
            if (db.mergeNear(Sample.from(t, Weather(HashMap(carry), spec)), SLOT / 2)) n++
        }
        return n
    }
}
