package cz.gramantik.meteowidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Jedna událost v daný den. time = "HH:mm" nebo "" u celodenních. */
data class CalEvent(val date: LocalDate, val time: String, val summary: String)

/**
 * Kalendář ze soukromé iCal adresy (např. Google „Tajná adresa ve formátu iCal“).
 * Zpracuje i opakované události (denně/týdně/měsíčně/ročně, výjimky, přesunuté výskyty).
 * Ukládá jen události na dnešek a zítřek.
 */
object CalendarIcs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    fun url(ctx: Context): String = sp(ctx).getString("icsUrl", "") ?: ""
    fun setUrl(ctx: Context, v: String) = sp(ctx).edit().putString("icsUrl", v.trim()).apply()
    fun fetchedAt(ctx: Context): Long = sp(ctx).getLong("icsTime", 0)
    fun error(ctx: Context): String? = sp(ctx).getString("icsError", null)

    fun events(ctx: Context, date: LocalDate): List<CalEvent> {
        val s = sp(ctx).getString("icsEvents", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { CalEvent(LocalDate.parse(it.getString("d")), it.getString("t"), it.getString("s")) }
                .filter { it.date == date }
                .sortedWith(compareBy({ it.time.isNotEmpty() }, { it.time }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Stáhne a zpracuje kalendář. Vrací null při úspěchu, jinak text chyby. */
    fun fetch(ctx: Context): String? {
        var u = url(ctx)
        if (u.isEmpty()) return "nevyplněno"
        if (u.startsWith("webcal://")) u = "https://" + u.removePrefix("webcal://")
        return try {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "meteowidget-android")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                throw Exception("HTTP $code")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()
            if (!text.contains("BEGIN:VCALENDAR")) throw Exception("adresa nevrací kalendář iCal")
            val today = LocalDate.now()
            val list = parse(text, listOf(today, today.plusDays(1)))
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("d", it.date.toString()).put("t", it.time).put("s", it.summary)) }
            sp(ctx).edit().putString("icsEvents", a.toString()).putLong("icsTime", System.currentTimeMillis())
                .remove("icsError").apply()
            null
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            sp(ctx).edit().putString("icsError", msg).putLong("icsTime", System.currentTimeMillis()).apply()
            msg
        }
    }

    // ---------- parser ----------
    private class Prop(val params: Map<String, String>, val value: String)

    private class Ev(val props: Map<String, List<Prop>>) {
        fun one(k: String) = props[k]?.firstOrNull()
        fun all(k: String) = props[k] ?: emptyList()
    }

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ")
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()

    /** Datum/čas z vlastnosti -> místní čas (a příznak celodenní). */
    private fun toLocal(p: Prop): Pair<LocalDateTime, Boolean>? {
        val v = p.value.trim()
        return try {
            if (p.params["VALUE"] == "DATE" || v.length == 8) {
                LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay() to true
            } else {
                val dt = LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                val zone: ZoneId = when {
                    v.endsWith("Z") -> ZoneOffset.UTC
                    p.params["TZID"] != null -> runCatching { ZoneId.of(p.params["TZID"]!!.trim('"')) }.getOrDefault(ZoneId.systemDefault())
                    else -> ZoneId.systemDefault()
                }
                dt.atZone(zone).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() to false
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parse(text: String, days: List<LocalDate>): List<CalEvent> {
        // rozbalení zalomených řádků
        val lines = ArrayList<String>()
        for (raw in text.split("\r\n", "\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) {
                lines[lines.size - 1] = lines.last() + raw.substring(1)
            } else lines.add(raw)
        }
        val events = ArrayList<Ev>()
        var cur: HashMap<String, MutableList<Prop>>? = null
        var depth = 0
        for (l in lines) {
            when {
                l == "BEGIN:VEVENT" -> { cur = HashMap(); depth = 0 }
                l.startsWith("BEGIN:") && cur != null -> depth++
                l.startsWith("END:") && cur != null && depth > 0 -> depth--
                l == "END:VEVENT" -> { cur?.let { events.add(Ev(it)) }; cur = null }
                cur != null && depth == 0 -> {
                    val colon = l.indexOf(':')
                    if (colon <= 0) continue
                    val head = l.substring(0, colon).split(';')
                    val params = head.drop(1).mapNotNull {
                        val eq = it.indexOf('='); if (eq > 0) it.substring(0, eq).uppercase() to it.substring(eq + 1) else null
                    }.toMap()
                    cur.getOrPut(head[0].uppercase()) { ArrayList() }.add(Prop(params, l.substring(colon + 1)))
                }
            }
        }

        // přesunuté výskyty opakovaných událostí (RECURRENCE-ID)
        val overridden = HashSet<String>()
        for (e in events) {
            val rid = e.one("RECURRENCE-ID") ?: continue
            val uid = e.one("UID")?.value ?: continue
            toLocal(rid)?.let { overridden.add(uid + "|" + it.first.toLocalDate()) }
        }

        val out = ArrayList<CalEvent>()
        for (e in events) {
            if (e.one("STATUS")?.value?.uppercase() == "CANCELLED") continue
            val start = e.one("DTSTART")?.let { toLocal(it) } ?: continue
            val allDay = start.second
            val s0 = start.first
            val end = e.one("DTEND")?.let { toLocal(it) }?.first
            val summary = unescape(e.one("SUMMARY")?.value ?: "(bez názvu)")
            val uid = e.one("UID")?.value ?: ""
            val durDays = if (end != null) ChronoUnit.DAYS.between(s0.toLocalDate(), end.toLocalDate()).coerceAtLeast(if (allDay) 1 else 0) else if (allDay) 1 else 0
            val rrule = e.one("RRULE")?.value
            val isOverride = e.one("RECURRENCE-ID") != null
            val exdates = e.all("EXDATE").flatMap { p ->
                p.value.split(',').mapNotNull { v -> toLocal(Prop(p.params, v))?.first?.toLocalDate() }
            }.toSet()

            for (day in days) {
                // výskyt, který „zasahuje“ do daného dne (i vícedenní události)
                var hit: LocalDate? = null
                val span = if (allDay) durDays - 1 else durDays
                for (back in 0..span.coerceIn(0, 30)) {
                    val occ = day.minusDays(back.toLong())
                    val ok = if (rrule == null || isOverride) occ == s0.toLocalDate()
                    else occursOn(rrule, s0.toLocalDate(), occ) && occ !in exdates && (uid + "|" + occ) !in overridden
                    if (ok) { hit = occ; break }
                }
                if (hit == null) continue
                val time = if (allDay || hit != day) "" else String.format("%02d:%02d", s0.hour, s0.minute)
                out.add(CalEvent(day, time, summary))
            }
        }
        return out
    }

    /** Nastává opakovaná událost (pravidlo RRULE, první výskyt [start]) v den [d]? */
    private fun occursOn(rrule: String, start: LocalDate, d: LocalDate): Boolean {
        if (d.isBefore(start)) return false
        val r = rrule.split(';').mapNotNull { val eq = it.indexOf('='); if (eq > 0) it.substring(0, eq).uppercase() to it.substring(eq + 1) else null }.toMap()
        val interval = r["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        r["UNTIL"]?.let { u ->
            val until = runCatching { LocalDate.parse(u.take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
            if (until != null && d.isAfter(until)) return false
        }
        val byDay = r["BYDAY"]?.split(',')?.map { it.trim() } ?: emptyList()
        val byMonthDay = r["BYMONTHDAY"]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        fun dow(code: String): DayOfWeek? = when (code.takeLast(2)) {
            "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY
            "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY; "SU" -> DayOfWeek.SUNDAY
            else -> null
        }
        fun nthMatch(code: String, date: LocalDate): Boolean {
            val wd = dow(code) ?: return false
            if (date.dayOfWeek != wd) return false
            val n = code.dropLast(2).toIntOrNull() ?: return true
            return if (n > 0) (date.dayOfMonth - 1) / 7 + 1 == n
            else (date.lengthOfMonth() - date.dayOfMonth) / 7 + 1 == -n
        }
        val base = when (r["FREQ"]) {
            "DAILY" -> ChronoUnit.DAYS.between(start, d) % interval == 0L
            "WEEKLY" -> {
                val w0 = start.minusDays((start.dayOfWeek.value - 1).toLong())
                val w1 = d.minusDays((d.dayOfWeek.value - 1).toLong())
                ChronoUnit.WEEKS.between(w0, w1) % interval == 0L &&
                    (if (byDay.isEmpty()) d.dayOfWeek == start.dayOfWeek else byDay.any { dow(it) == d.dayOfWeek })
            }
            "MONTHLY" -> {
                val months = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), d.withDayOfMonth(1))
                months % interval == 0L && when {
                    byMonthDay.isNotEmpty() -> byMonthDay.any { it == d.dayOfMonth || (it < 0 && d.lengthOfMonth() + it + 1 == d.dayOfMonth) }
                    byDay.isNotEmpty() -> byDay.any { nthMatch(it, d) }
                    else -> d.dayOfMonth == start.dayOfMonth
                }
            }
            "YEARLY" -> (d.year - start.year) % interval == 0 && d.monthValue == start.monthValue &&
                (if (byDay.isNotEmpty()) byDay.any { nthMatch(it, d) } else d.dayOfMonth == start.dayOfMonth)
            else -> false
        }
        if (!base) return false
        // COUNT: spočítat výskyty od začátku (jen rozumný rozsah)
        val count = r["COUNT"]?.toIntOrNull() ?: return true
        var n = 0
        var x = start
        var guard = 0
        val noCount = r.filterKeys { it != "COUNT" }.entries.joinToString(";") { it.key + "=" + it.value }
        while (!x.isAfter(d) && guard < 4000) {
            if (occursOn(noCount, start, x)) n++
            if (n > count) return false
            x = x.plusDays(1); guard++
        }
        return n <= count
    }
}

/** Český jmenný kalendář (přibalený v assets/namedays.json). */
object NameDays {
    private var map: Map<String, String>? = null

    private fun load(ctx: Context): Map<String, String> {
        map?.let { return it }
        val m = try {
            val o = JSONObject(ctx.assets.open("namedays.json").bufferedReader().use { it.readText() })
            o.keys().asSequence().associateWith { o.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
        map = m
        return m
    }

    fun forDate(ctx: Context, d: LocalDate): String =
        load(ctx)[String.format("%02d-%02d", d.monthValue, d.dayOfMonth)] ?: ""
}
