package cz.gramantik.meteowidget.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cz.gramantik.meteowidget.Astro
import cz.gramantik.meteowidget.Alerts
import cz.gramantik.meteowidget.Channel
import cz.gramantik.meteowidget.ForecastFull
import cz.gramantik.meteowidget.MainActivity
import cz.gramantik.meteowidget.MeteoDb
import cz.gramantik.meteowidget.Prefs
import cz.gramantik.meteowidget.PressureHistory
import cz.gramantik.meteowidget.Sample
import cz.gramantik.meteowidget.Weather
import cz.gramantik.meteowidget.NotifyPrefs
import cz.gramantik.meteowidget.friendlyError
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Obrazovka Stanice: aktuální hodnoty, souhrny, historie s grafy, rekordy, Slunce a Měsíc. */
class StationScreen(private val a: Activity) : MainActivity.Screen {
    private val cz = Locale("cs", "CZ")
    private val zone = ZoneId.systemDefault()
    private val content = a.vbox().apply { setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(24)) }
    override val view: View = ScrollView(a).apply { addView(content); isFillViewport = true }

    // stav historie
    private enum class Period(val label: String) { H24("24 h"), D7("7 dní"), D30("30 dní"), DAY("Den…"), RANGE("Rozsah…") }
    private enum class Metric(val label: String) { TEMP("Teplota"), HUM("Vlhkost"), PRES("Tlak"), WIND("Vítr"), RAIN("Srážky"), UV("UV"), LIGHT("Světlo") }
    private var period = Period.H24
    /** vybrané veličiny (vícenásobný výběr, pamatuje se) */
    private val metrics: MutableSet<Metric> = run {
        val saved = a.getSharedPreferences("meteo", android.content.Context.MODE_PRIVATE).getString("histMetrics", null)
        val set = saved?.split(',')?.mapNotNull { n -> Metric.entries.firstOrNull { it.name == n } }?.toMutableSet()
        if (set.isNullOrEmpty()) mutableSetOf(Metric.TEMP) else set
    }

    private fun saveMetrics() {
        a.getSharedPreferences("meteo", android.content.Context.MODE_PRIVATE).edit()
            .putString("histMetrics", metrics.joinToString(",") { it.name }).apply()
    }
    private var dayFrom: LocalDate = LocalDate.now()
    private var dayTo: LocalDate = LocalDate.now()
    private var historyBox: LinearLayout? = null

    init { refresh() }

    private fun f1(v: Double?) = if (v == null) "--" else String.format(cz, "%.1f", v)
    private fun t(v: Double?) = if (v == null) "--°" else String.format(cz, "%.1f°", v)
    private fun time(ms: Long) = SimpleDateFormat("HH:mm", cz).format(Date(ms))
    private fun dateTime(ms: Long) = SimpleDateFormat("d.M.yyyy HH:mm", cz).format(Date(ms))

    /** TV karta Doma: výstraha na vlhkost vnitřních čidel mimo komfortní zónu. */
    private fun humidityWarnings(p: Prefs, w: Weather) {
        val n = NotifyPrefs(a)
        val list = ArrayList<Pair<String, Int>>()
        val chans = listOf(Channel.IN to true, Channel.S1 to n.indoor(1), Channel.S2 to n.indoor(2), Channel.S3 to n.indoor(3))
        for ((ch, indoor) in chans) {
            if (!indoor) continue
            val h = w.hum(ch.humCode) ?: continue
            val name = p.channelName(ch)
            when {
                h < n.humMin -> list += "💧 Nízká vlhkost – $name: $h % (komfort ${n.humMin.toInt()}–${n.humMax.toInt()} %) – zvlhči vzduch" to 0xFFFFB74D.toInt()
                h > n.humMax -> list += "💦 Vysoká vlhkost – $name: $h % (komfort ${n.humMin.toInt()}–${n.humMax.toInt()} %) – vyvětrej" to C.RAIN
            }
        }
        if (list.isEmpty()) return
        val c = a.card("Vlhkost mimo komfortní zónu", 0xFFFFB74D.toInt())
        list.forEach { (t, col) -> c.addView(a.text(t, 14f, col, bold = true), a.lp(top = 4)) }
        content.addView(c, a.lp(bottom = 10))
    }

    override fun refresh() {
        content.removeAllViews()
        val p = Prefs(a)
        val data = p.lastData
        val w = data?.let { Weather(it, p.spec) }

        // stav
        val age = System.currentTimeMillis() - p.lastUpdate
        val (status, statusColor) = when {
            !p.isConfigured -> "Nejdřív zadej údaje z Tuya v nastavení (⚙)." to C.ACCENT
            p.lastError != null -> (friendlyError(p.lastError, p.lastUpdate) ?: "") to C.ACCENT
            p.lastUpdate > 0 && age > 45 * 60_000L -> "⚠ Data jsou stará ${age / 60_000} min (poslední ${dateTime(p.lastUpdate)})" to C.ACCENT
            p.lastUpdate > 0 -> "Aktualizováno ${dateTime(p.lastUpdate)}" to C.DIM
            else -> "Načítám…" to C.DIM
        }
        content.addView(a.text(status, 12f, statusColor), a.lp(bottom = 8))

        if (w != null && a.isTv()) humidityWarnings(p, w)
        alerts()
        if (w != null) {
            current(p, w)
            todayCard()
            ventilation(w)
        }
        rainCard()
        history()
        records()
        sunMoon(p)
        val db = MeteoDb.get(a)
        val first = db.first()
        content.addView(
            a.text(
                "Záznamů v historii: ${db.count()}" + (first?.let { " · od ${dateTime(it)}" } ?: ""),
                11f, C.DIM
            ), a.lp(top = 6)
        )
    }

    // ---------- výstrahy ----------
    private fun alerts() {
        for (al in Alerts.load(a)) {
            val c = a.vbox().apply {
                background = rounded(0x22000000 or (al.color and 0x00FFFFFF), a.dp(14).toFloat(), a.dp(2), al.color)
                setPadding(a.dp(14), a.dp(10), a.dp(14), a.dp(12))
            }
            c.addView(a.text("⚠ ${al.event}", 16f, al.color, bold = true))
            val fmt = SimpleDateFormat("EEE d.M. HH:mm", cz)
            c.addView(a.text("${al.level} · ${fmt.format(Date(al.onset))} – ${fmt.format(Date(al.expires))}", 12f, C.LABEL), a.lp(top = 4))
            if (al.description.isNotBlank()) c.addView(a.text(al.description, 13f, C.WHITE), a.lp(top = 6))
            if (al.instruction.isNotBlank()) c.addView(a.text(al.instruction, 12f, C.DIM), a.lp(top = 4))
            c.addView(a.text("Zdroj: ČHMÚ", 10f, C.DIM), a.lp(top = 6))
            content.addView(c, a.lp(bottom = 10))
        }
    }

    // ---------- aktuální hodnoty ----------
    // rozbalený graf čidla z prvního panelu
    private var openChannel: Channel? = null
    private var openDays = 1

    private fun toggleChannel(ch: Channel) {
        openChannel = if (openChannel == ch) null else ch
        refresh()
    }

    /** TV: Doma, Venku a čidla v jednom řádku. */
    private fun currentTv(p: Prefs, w: Weather) {
        val big = a.card()
        val row = a.hbox(Gravity.CENTER_VERTICAL)
        val chans = listOf(Triple(Channel.IN, w.tempIn, w.humIn) to C.IN, Triple(Channel.OUT, w.tempOut, w.humOut) to C.OUT) +
            listOf(Channel.S1, Channel.S2, Channel.S3).mapNotNull { ch -> w.temp(ch.tempCode)?.let { Triple(ch, it, w.hum(ch.humCode)) to C.SENSOR } }
        chans.forEachIndexed { i, (tr, color) ->
            val (ch, temp, hum) = tr
            if (i > 0) row.addView(a.text("·", 18f, C.DIM), a.lp(WRAP, WRAP, start = 4, end = 4))
            row.addView(a.hbox(Gravity.BOTTOM).apply {
                setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4))
                addView(a.text(p.channelName(ch) + if (openChannel == ch) " ▾" else "", 13f, C.LABEL, bold = true), a.lp(WRAP, WRAP, end = 6))
                addView(a.text(t(temp), 24f, color, condensed = true))
                addView(a.text(" " + Weather.h(hum), 14f, color, condensed = true))
                if (openChannel == ch) background = rounded(C.CARD2, a.dp(10).toFloat(), a.dp(1), 0x88FFD54F.toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleChannel(ch) }
                tvFocus(if (openChannel == ch) 0x88FFD54F.toInt() else C.LINE, 1.0f)
            }, a.lp(WRAP, WRAP))
        }
        big.addView(HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        content.addView(big, a.lp(bottom = 10))
    }

    private fun current(p: Prefs, w: Weather) {
        if (a.isTv()) {
            currentTv(p, w)
            openChannel?.let { channelCharts(p, it) }
            humidityChart(p)
            currentTiles(w)
            return
        }
        val big = a.card()
        val row = a.hbox(Gravity.TOP)
        val selBg = { rounded(C.CARD2, a.dp(12).toFloat(), a.dp(1), 0x88FFD54F.toInt()) }
        fun col(ch: Channel, temp: Double?, hum: Int?, color: Int, gravity: Int): LinearLayout = a.vbox(gravity).apply {
            addView(a.label(p.channelName(ch) + if (openChannel == ch) "  ▾" else ""), a.lp(WRAP, WRAP))
            addView(a.text(t(temp), 46f, color, condensed = true), a.lp(WRAP, WRAP, top = 4))
            addView(a.text(Weather.h(hum), 22f, color, condensed = true), a.lp(WRAP, WRAP, top = 2))
            setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(6))
            if (openChannel == ch) background = selBg()
            setOnClickListener { toggleChannel(ch) }
            tvFocus(if (openChannel == ch) 0x88FFD54F.toInt() else C.LINE, 1.03f)
        }
        row.addView(col(Channel.IN, w.tempIn, w.humIn, C.IN, Gravity.START), a.lp(0, WRAP, 1f, end = 4))
        row.addView(col(Channel.OUT, w.tempOut, w.humOut, C.OUT, Gravity.END), a.lp(0, WRAP, 1f, start = 4))
        big.addView(row)
        val sensors = listOf(Channel.S1, Channel.S2, Channel.S3).mapNotNull { ch ->
            w.temp(ch.tempCode)?.let { ch to "${p.channelName(ch)}  ${t(it)} · ${Weather.h(w.hum(ch.humCode))}" }
        }
        if (sensors.isNotEmpty()) {
            big.addView(a.divider(), a.lp(h = a.dp(1), top = 10, bottom = 6))
            sensors.forEach { (ch, txt) ->
                val r = a.text(txt + if (openChannel == ch) "  ▾" else "", 16f, C.SENSOR, condensed = true).apply {
                    setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6))
                    if (openChannel == ch) background = selBg()
                    setOnClickListener { toggleChannel(ch) }
                    tvFocus(if (openChannel == ch) 0x88FFD54F.toInt() else C.LINE, 1.03f)
                }
                big.addView(r, a.lp(bottom = 2))
            }
        }
        big.addView(a.text("Klepni na čidlo pro graf · přejetím do strany další karta", 11f, C.DIM), a.lp(top = 6))
        content.addView(SwipeFrame(a) { (a as? MainActivity)?.swipe(it) }.apply { addView(big) }, a.lp(bottom = 10))
        openChannel?.let { channelCharts(p, it) }
        humidityChart(p)
        currentTiles(w)
    }

    private fun currentTiles(w: Weather) {
        val trend = PressureHistory.trend3h(a)
        val arrow = when { trend == null -> ""; trend >= 1 -> " ↗"; trend <= -1 -> " ↘"; else -> " →" }
        val tiles = listOf(
            a.tile("Vítr", "${f1(w.windAvg)} km/h", C.WIND,
                listOfNotNull(w.windGust?.let { "nárazy ${f1(it)}" }, w.windDir).joinToString(" · ").ifEmpty { null }),
            a.tile("Srážky dnes", "${f1(w.rainDay)} mm", C.RAIN,
                w.rainRate?.takeIf { it > 0 }?.let { "prší ${f1(it)} mm/h" } ?: "neprší"),
            a.tile("Tlak", (w.pressure?.let { String.format(cz, "%.0f", it) } ?: "--") + " hPa$arrow", C.BARO,
                trend?.let { "za 3 h " + String.format(cz, "%+.1f", it) }),
            a.tile("Pocitově", t(w.feelsLike), C.FEELS, w.windChill?.let { "wind chill ${t(it)}" }),
            a.tile("Rosný bod", t(w.dewPoint), C.FEELS),
            a.tile("UV index", f1(w.uv), C.UV, uvText(w.uv)),
            a.tile("Světlo", "${f1(w.lightKlux)} klux", C.LIGHT),
            humTile(w),
        )
        content.addView(a.grid(tiles), a.lp(bottom = 2))
    }

    /** Graf teploty a vlhkosti vybraného čidla (rozbalený pod prvním panelem). */
    private fun channelCharts(p: Prefs, ch: Channel) {
        val (tc, hc) = when (ch) {
            Channel.IN -> "tin" to "hin"
            Channel.OUT -> "tout" to "hout"
            Channel.S1 -> "t1" to "h1"
            Channel.S2 -> "t2" to "h2"
            Channel.S3 -> "t3" to "h3"
        }
        val color = when (ch) { Channel.IN -> C.IN; Channel.OUT -> C.OUT; else -> C.SENSOR }
        val now = System.currentTimeMillis()
        val samples = MeteoDb.get(a).range(now - openDays * 24 * 3_600_000L, now)
        fun ser(col: String, name: String, c: Int, unit: String): List<Series> {
            val list = samples.filter { it.get(col) != null }
            if (list.isEmpty()) return emptyList()
            return listOf(Series(name, c, list.map { it.t }.toLongArray(), list.map { it.get(col)!! }.toDoubleArray(), unit = unit))
        }
        val card = a.card(p.channelName(ch) + " – " + if (openDays == 1) "24 h" else "7 dní", color)
        val charts = ArrayList<ChartView>()
        val n = NotifyPrefs(a)
        for ((series, title) in listOf(ser(tc, "Teplota", color, "°C") to "Teplota", ser(hc, "Vlhkost", 0xFF8FD3FF.toInt(), "%") to "Vlhkost")) {
            val chart = ChartView(a).apply {
                this.series = series
                this.title = title
                val indoor = when (ch) {
                    Channel.IN -> true
                    Channel.OUT -> false
                    Channel.S1 -> n.indoor(1)
                    Channel.S2 -> n.indoor(2)
                    Channel.S3 -> n.indoor(3)
                }
                if (indoor) band = if (title == "Vlhkost") n.humMin.toDouble() to n.humMax.toDouble()
                    else n.tempMin.toDouble() to n.tempMax.toDouble()
                onCursor = { tm -> charts.forEach { o -> if (o !== this) o.cursorTime = tm } }
            }
            charts.add(chart)
            card.addView(chart, a.lp(h = a.dp(160), bottom = 4))
            series.firstOrNull()?.let { sr ->
                val v = sr.values.filter { !it.isNaN() }
                if (v.isNotEmpty()) card.addView(
                    a.text("min ${f1(v.min())}  ø ${f1(v.average())}  max ${f1(v.max())} ${sr.unit}", 12f, C.LABEL, condensed = true),
                    a.lp(bottom = 6)
                )
            }
        }
        if (samples.isEmpty()) card.addView(a.text("Zatím žádná data.", 13f, C.DIM))
        val chips = a.chipRow(
            a.chip("24 h", openDays == 1) { openDays = 1; refresh() },
            a.chip("7 dní", openDays == 7) { openDays = 7; refresh() },
            a.chip("✕ Zavřít", false) { openChannel = null; refresh() },
        )
        card.addView(chips, a.lp(top = 4))
        content.addView(card, a.lp(bottom = 10))
    }

    /** Panel 24 h: přepínatelná vlhkost / teplota, Doma + Venku + volitelná čidla, komfortní zóna. */
    private fun humidityChart(p: Prefs) {
        val n = NotifyPrefs(a)
        val sp = a.getSharedPreferences("meteo", android.content.Context.MODE_PRIVATE)
        val temp = sp.getString("panel24Metric", "hum") == "temp"
        val now = System.currentTimeMillis()
        val samples = MeteoDb.get(a).range(now - 24 * 3_600_000L, now)
        val unit = if (temp) "°C" else "%"
        fun s(col: String, name: String, color: Int): Series? {
            val list = samples.filter { it.get(col) != null }
            if (list.isEmpty()) return null
            return Series(name, color, list.map { it.t }.toLongArray(), list.map { it.get(col)!! }.toDoubleArray(), unit = unit)
        }
        // všechna čidla zapínatelná; výběr zvlášť pro teplotu a vlhkost
        data class Ch(val ch: Channel, val col: String, val color: Int)
        val pre = if (temp) "t" else "h"
        val all = listOf(
            Ch(Channel.IN, pre + "in", C.IN), Ch(Channel.OUT, pre + "out", C.OUT),
            Ch(Channel.S1, pre + "1", C.SENSOR), Ch(Channel.S2, pre + "2", 0xFF9FA8DA.toInt()), Ch(Channel.S3, pre + "3", 0xFFCE93D8.toInt()),
        ).filter { c -> samples.any { it.get(c.col) != null } || c.ch == Channel.IN || c.ch == Channel.OUT }
        val metricKey = if (temp) "temp" else "hum"
        fun isOn(c: Ch) = sp.getBoolean("p24_${metricKey}_${c.ch.key}", c.ch == Channel.IN || c.ch == Channel.OUT)
        val series = all.filter { isOn(it) }.mapNotNull { s(it.col, p.channelName(it.ch), it.color) }

        val c = a.card((if (temp) "Teplota" else "Vlhkost") + " – 24 h")
        val chips = mutableListOf(
            a.chip("Vlhkost", !temp) { sp.edit().putString("panel24Metric", "hum").apply(); refresh() },
            a.chip("Teplota", temp) { sp.edit().putString("panel24Metric", "temp").apply(); refresh() },
        )
        all.forEach { c ->
            val on = isOn(c)
            chips.add(a.chip((if (on) "✓ " else "+ ") + p.channelName(c.ch), on) {
                // aspoň jedno čidlo musí zůstat zapnuté
                if (on && all.count { isOn(it) } <= 1) return@chip
                sp.edit().putBoolean("p24_${metricKey}_${c.ch.key}", !on).apply()
                refresh()
            })
        }
        c.addView(a.chipRow(*chips.toTypedArray()), a.lp(bottom = 8))
        val zone = if (temp) n.tempMin.toDouble() to n.tempMax.toDouble()
            else if (n.hum) n.humMin.toDouble() to n.humMax.toDouble() else null
        c.addView(ChartView(a).apply {
            this.series = series
            band = zone
        }, a.lp(h = a.dp(190)))
        val legend = a.hbox()
        series.forEach { legend.addView(a.text("● ${it.name}  ", 12f, it.color)) }
        c.addView(legend, a.lp(top = 4))
        if (zone != null) {
            val z = a.hbox()
            z.addView(a.text("▮ ", 14f, BAND_SWATCH, bold = true))
            z.addView(a.text("Komfortní zóna: ${zone.first.toInt()}–${zone.second.toInt()} $unit", 12f, BAND_TEXT))
            c.addView(z, a.lp(top = 2))
        }
        content.addView(c, a.lp(bottom = 10))
    }

    /** Dlaždice vlhkosti venku – klepnutím přepíná % a g/m³. */
    private fun humTile(w: Weather): View {
        val sp = a.getSharedPreferences("meteo", android.content.Context.MODE_PRIVATE)
        val abs = sp.getBoolean("outHumAbs", true)
        val t = w.tempOut
        val h = w.humOut
        val tile = if (abs) {
            a.tile("Abs. vlhkost venku",
                if (t != null && h != null) "${f1(Astro.absHumidity(t, h.toDouble()))} g/m³" else "--",
                C.OUT, "klepni pro %")
        } else {
            a.tile("Vlhkost venku", Weather.h(h), C.OUT, "klepni pro g/m³")
        }
        tile.setOnClickListener {
            sp.edit().putBoolean("outHumAbs", !abs).apply()
            refresh()
        }
        tile.tvFocus(scale = 1.04f)
        return tile
    }

    private fun uvText(uv: Double?): String? = uv?.let {
        when { it < 3 -> "nízký"; it < 6 -> "střední"; it < 8 -> "vysoký"; it < 11 -> "velmi vysoký"; else -> "extrémní" }
    }

    // ---------- dnešní minima a maxima ----------
    private fun todayCard() {
        val db = MeteoDb.get(a)
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = System.currentTimeMillis()
        val c = a.card("Dnes")
        fun line(name: String, col: String, color: Int, unit: String = "°") {
            val mn = db.extreme(col, start, end, false)
            val mx = db.extreme(col, start, end, true)
            if (mn == null || mx == null) return
            val r = a.hbox()
            r.addView(a.text(name, 14f, C.LABEL), a.lp(0, WRAP, 1f))
            r.addView(a.text("↓ ${f1(mn.second)}$unit ${time(mn.first)}", 15f, 0xFF8FB3FF.toInt(), condensed = true), a.lp(WRAP, WRAP, end = 14))
            r.addView(a.text("↑ ${f1(mx.second)}$unit ${time(mx.first)}", 15f, color, condensed = true))
            c.addView(r, a.lp(bottom = 6))
        }
        line(Prefs(a).channelName(Channel.OUT), "tout", C.OUT)
        line(Prefs(a).channelName(Channel.IN), "tin", C.IN)
        line("Vlhkost venku", "hout", C.OUT, " %")
        line("Tlak", "pres", C.BARO, " hPa")
        db.extreme("gust", start, end, true)?.let {
            val r = a.hbox()
            r.addView(a.text("Nejsilnější náraz", 14f, C.LABEL), a.lp(0, WRAP, 1f))
            r.addView(a.text("${f1(it.second)} km/h ${time(it.first)}", 15f, C.WIND, condensed = true))
            c.addView(r)
        }
        if (c.childCount > 1) content.addView(c, a.lp(top = 6, bottom = 10))
    }

    // ---------- větrání ----------
    private fun ventilation(w: Weather) {
        val ti = w.tempIn ?: return; val hi = w.humIn ?: return
        val to = w.tempOut ?: return; val ho = w.humOut ?: return
        val ain = Astro.absHumidity(ti, hi.toDouble())
        val aout = Astro.absHumidity(to, ho.toDouble())
        // relativní vlhkost doma, kdyby se venkovní vzduch ohřál na vnitřní teplotu
        val rhAfter = (aout / Astro.absHumidity(ti, 100.0) * 100).coerceIn(0.0, 100.0)
        val (head, color, msg) = when {
            aout < ain - 1.0 && hi > 55 ->
                Triple("🪟 Vyvětrej", C.OUT, "Venkovní vzduch je sušší. Po vyvětrání klesne vlhkost doma přibližně na ${rhAfter.toInt()} %.")
            aout > ain + 0.5 ->
                Triple("🚫 Nevětrej kvůli vlhkosti", C.DIR, "Venku je vlhčí vzduch (${f1(aout)} g/m³ proti ${f1(ain)} g/m³ doma) – vlhkost doma by stoupla.")
            hi < NotifyPrefs(a).humMin ->
                Triple("💧 Doma je sucho", C.RAIN, "Vlhkost $hi %. Větráním se nezvýší – venkovní vzduch má po ohřátí jen asi ${rhAfter.toInt()} %.")
            else ->
                Triple("👍 Vlhkost v pořádku", C.OUT, "Doma $hi % (${f1(ain)} g/m³), venku ${f1(aout)} g/m³. Větrej podle potřeby.")
        }
        val c = a.card("Větrání")
        val n = NotifyPrefs(a)
        if (n.hum && hi < n.humMin) {
            c.addView(a.text("⚠ Vlhkost doma $hi % – pod ${n.humMin.toInt()} %, zvlhčuj", 16f, C.ACCENT, bold = true), a.lp(bottom = 6))
        } else if (n.hum && hi > n.humMax) {
            c.addView(a.text("⚠ Vlhkost doma $hi % – nad ${n.humMax.toInt()} %, hrozí plíseň", 16f, C.ACCENT, bold = true), a.lp(bottom = 6))
        }
        c.addView(a.text(head, 17f, color, bold = true))
        c.addView(a.text(msg + if (to < 5) " Při chladu větrej krátce a intenzivně." else "", 13f, C.LABEL), a.lp(top = 4))
        content.addView(c, a.lp(bottom = 10))
    }

    // ---------- souhrny srážek ----------
    private fun rainCard() {
        val db = MeteoDb.get(a)
        val today = LocalDate.now()
        val days = db.days(today.withDayOfYear(1), today)
        if (days.isEmpty()) return
        fun sum(from: LocalDate) = days.filter { !it.date.isBefore(from) }.sumOf { it.rain ?: 0.0 }
        val c = a.card("Srážky – úhrn", C.RAIN)
        val tiles = listOf(
            a.tile("Dnes", "${f1(days.lastOrNull { it.date == today }?.rain ?: 0.0)} mm", C.RAIN),
            a.tile("7 dní", "${f1(sum(today.minusDays(6)))} mm", C.RAIN),
            a.tile("Tento měsíc", "${f1(sum(today.withDayOfMonth(1)))} mm", C.RAIN),
            a.tile("Tento rok", "${f1(sum(today.withDayOfYear(1)))} mm", C.RAIN),
        )
        c.addView(a.grid(tiles))
        c.addView(a.text("Počítáno z naměřených dat od začátku sběru historie.", 11f, C.DIM))
        content.addView(c, a.lp(bottom = 10))
    }

    // ---------- historie ----------
    private fun rangeMs(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (period) {
            Period.H24 -> now - 24 * 3_600_000L to now
            Period.D7 -> now - 7 * 86_400_000L to now
            Period.D30 -> now - 30 * 86_400_000L to now
            Period.DAY, Period.RANGE ->
                dayFrom.atStartOfDay(zone).toInstant().toEpochMilli() to
                    dayTo.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        }
    }

    private fun history() {
        val c = a.card("Historie")
        historyBox = c
        content.addView(c, a.lp(bottom = 10))
        fillHistory()
    }

    private fun pickDate(initial: LocalDate, title: String, then: (LocalDate) -> Unit) {
        DatePickerDialog(a, { _, y, m, d -> then(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
            .apply { setTitle(title) }.show()
    }

    private fun fillHistory() {
        val c = historyBox ?: return
        while (c.childCount > 1) c.removeViewAt(1)

        val periodChips = Period.entries.map { pr ->
            a.chip(pr.label, pr == period) {
                when (pr) {
                    Period.DAY -> pickDate(dayFrom, "Vyber den") { d -> period = pr; dayFrom = d; dayTo = d; fillHistory() }
                    Period.RANGE -> pickDate(dayFrom, "Od") { d1 ->
                        pickDate(if (dayTo.isAfter(d1)) dayTo else d1, "Do") { d2 ->
                            period = pr
                            dayFrom = if (d1.isBefore(d2)) d1 else d2
                            dayTo = if (d1.isBefore(d2)) d2 else d1
                            fillHistory()
                        }
                    }
                    else -> { period = pr; fillHistory() }
                }
            }
        }
        c.addView(a.chipRow(*periodChips.toTypedArray()), a.lp(bottom = 6))
        val metricChips = Metric.entries.map { m ->
            a.chip(m.label, m in metrics) {
                // vícenásobný výběr; aspoň jedna veličina musí zůstat
                if (m in metrics) { if (metrics.size > 1) metrics.remove(m) } else metrics.add(m)
                saveMetrics()
                fillHistory()
            }
        }
        c.addView(a.chipRow(*metricChips.toTypedArray()), a.lp(bottom = 8))

        val (from, to) = rangeMs()
        val df = DateTimeFormatter.ofPattern("d.M.yyyy")
        if (period == Period.DAY || period == Period.RANGE) {
            c.addView(
                a.text(if (dayFrom == dayTo) dayFrom.format(df) else "${dayFrom.format(df)} – ${dayTo.format(df)}", 13f, C.ACCENT, bold = true),
                a.lp(bottom = 6)
            )
        }
        val samples = MeteoDb.get(a).range(from, to)
        val p = Prefs(a)
        fun s(col: String, name: String, color: Int, unit: String, bars: Boolean = false, own: Boolean = false): Series? {
            val list = samples.filter { it.get(col) != null }
            if (list.isEmpty()) return null
            return Series(name, color, list.map { it.t }.toLongArray(), list.map { it.get(col)!! }.toDoubleArray(), bars, unit, own)
        }
        fun seriesFor(m: Metric): List<Series> = when (m) {
            Metric.TEMP -> listOfNotNull(
                s("tout", p.channelName(Channel.OUT), C.OUT, "°C"), s("tin", p.channelName(Channel.IN), C.IN, "°C"),
                s("t1", p.channelName(Channel.S1), C.SENSOR, "°C"), s("t2", p.channelName(Channel.S2), 0xFF9FA8DA.toInt(), "°C"),
                s("t3", p.channelName(Channel.S3), 0xFFCE93D8.toInt(), "°C"), s("feels", "Pocitově", C.FEELS, "°C"),
            )
            Metric.HUM -> listOfNotNull(
                s("hout", p.channelName(Channel.OUT), C.OUT, "%"), s("hin", p.channelName(Channel.IN), C.IN, "%"),
                s("h1", p.channelName(Channel.S1), C.SENSOR, "%"),
            )
            Metric.PRES -> listOfNotNull(s("pres", "Tlak", C.BARO, "hPa"))
            Metric.WIND -> listOfNotNull(s("wind", "Vítr", C.WIND, "km/h"), s("gust", "Nárazy", C.DIR, "km/h"))
            Metric.RAIN -> listOfNotNull(s("rain", "Úhrn dne", C.RAIN, "mm"), s("rrate", "Intenzita", 0xFF8FD3FF.toInt(), "mm/h", bars = true, own = true))
            Metric.UV -> listOfNotNull(s("uv", "UV", C.UV, ""))
            Metric.LIGHT -> listOfNotNull(s("light", "Světlo", C.LIGHT, "klux"))
        }

        // pro každou vybranou veličinu samostatný graf, společný kurzor
        val charts = ArrayList<ChartView>()
        val selected = Metric.entries.filter { it in metrics }
        val single = selected.size == 1
        var any = false
        for (m in selected) {
            val series = seriesFor(m)
            val chart = ChartView(a).apply {
                this.series = series
                title = if (single) null else m.label
                if (m == Metric.HUM && NotifyPrefs(a).hum) band = NotifyPrefs(a).humMin.toDouble() to NotifyPrefs(a).humMax.toDouble()
                onCursor = { t -> charts.forEach { other -> if (other !== this) other.cursorTime = t } }
            }
            charts.add(chart)
            c.addView(chart, a.lp(h = a.dp(if (single) 220 else 170), top = if (single) 0 else 8))
            // legenda + statistika
            for (sr in series) {
                val v = sr.values.filter { !it.isNaN() }
                if (v.isEmpty()) continue
                any = true
                val r = a.hbox()
                r.addView(a.text("● ${sr.name}", 13f, sr.color), a.lp(0, WRAP, 1f))
                r.addView(a.text("min ${f1(v.min())}  ø ${f1(v.average())}  max ${f1(v.max())} ${sr.unit}", 12f, C.LABEL, condensed = true))
                c.addView(r, a.lp(top = 4))
            }
            if (series.isEmpty()) c.addView(a.text("${m.label}: pro toto období nejsou data.", 12f, C.DIM), a.lp(top = 4))
        }
        if (!any && selected.size > 1) c.addView(a.text("Pro toto období nejsou data.", 13f, C.DIM), a.lp(top = 6))

        c.addView(a.text("⬇ Exportovat období do CSV", 14f, C.ACCENT, bold = true).apply {
            setPadding(a.dp(4), a.dp(12), a.dp(4), a.dp(2))
            setOnClickListener { export(from, to) }
            tvFocus()
        })
    }

    private fun export(from: Long, to: Long) {
        val csv = MeteoDb.get(a).csv(from, to)
        val name = "meteostanice_" + SimpleDateFormat("yyyyMMdd_HHmm", cz).format(Date()) + ".csv"
        if (android.os.Build.VERSION.SDK_INT < 29) {
            // starší Android: do složky aplikace (Android/data/…/files/Download)
            try {
                val dir = a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: a.filesDir
                val f = java.io.File(dir, name)
                f.outputStream().use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(csv.toByteArray())
                }
                Toast.makeText(a, "Uloženo: ${f.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(a, "Export selhal: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("nelze vytvořit soubor")
            a.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) // BOM pro Excel
                out.write(csv.toByteArray())
            }
            Toast.makeText(a, "Uloženo do Stažené soubory: $name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(a, "Export selhal: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- rekordy ----------
    private fun records() {
        val db = MeteoDb.get(a)
        val now = System.currentTimeMillis()
        val yearStart = LocalDate.now().withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val c = a.card("Rekordy", C.ACCENT)
        val fmt = SimpleDateFormat("d.M.yyyy", cz)
        fun row(name: String, col: String, max: Boolean, unit: String, color: Int) {
            val y = db.extreme(col, yearStart, now, max)
            val all = db.extreme(col, 0, now, max)
            if (all == null) return
            val r = a.vbox()
            r.addView(a.text(name, 13f, C.LABEL))
            val line = a.hbox()
            line.addView(
                a.text("Letos " + (y?.let { "${f1(it.second)} $unit (${fmt.format(Date(it.first))})" } ?: "--"), 14f, color, condensed = true),
                a.lp(0, WRAP, 1f)
            )
            line.addView(a.text("Celkem ${f1(all.second)} $unit (${fmt.format(Date(all.first))})", 14f, color, condensed = true))
            r.addView(line)
            c.addView(r, a.lp(bottom = 8))
        }
        row("Nejvyšší teplota venku", "tout", true, "°C", C.IN)
        row("Nejnižší teplota venku", "tout", false, "°C", 0xFF8FB3FF.toInt())
        row("Nejsilnější náraz větru", "gust", true, "km/h", C.WIND)
        row("Nejvíc srážek za den", "rain", true, "mm", C.RAIN)
        row("Nejvyšší tlak", "pres", true, "hPa", C.BARO)
        row("Nejnižší tlak", "pres", false, "hPa", C.BARO)
        if (c.childCount > 1) content.addView(c, a.lp(bottom = 10))
    }

    // ---------- Slunce a Měsíc ----------
    private fun sunMoon(p: Prefs) {
        val c = a.card("Slunce a Měsíc")
        val today = LocalDate.now()
        val tf = DateTimeFormatter.ofPattern("HH:mm")
        ForecastFull.load(a)?.days?.firstOrNull { it.date == today }?.let { d ->
            val r = a.hbox()
            r.addView(a.icon("clear-day", 40))
            r.addView(a.vbox().apply {
                addView(a.text("Východ ${d.sunrise?.format(tf) ?: "--"} · západ ${d.sunset?.format(tf) ?: "--"}", 15f, C.ACCENT))
                addView(a.text("Délka dne ${hm(d.daylightH)} · slunečný svit ${hm(d.sunshineH)}", 12f, C.LABEL), a.lp(top = 2))
            }, a.lp(0, WRAP, 1f, start = 10))
            c.addView(r, a.lp(bottom = 10))
        }
        val now = System.currentTimeMillis()
        val (rise, set) = Astro.moonTimes(today, p.lat, p.lon)
        val r = a.hbox()
        r.addView(a.text(Astro.phaseEmoji(now), 34f), a.lp(WRAP, WRAP))
        r.addView(a.vbox().apply {
            addView(a.text("${Astro.phaseName(now)} · osvětleno ${(Astro.illumination(now) * 100).toInt()} %", 15f, C.WIND))
            addView(
                a.text("Východ ${rise?.let { time(it) } ?: "--"} · západ ${set?.let { time(it) } ?: "--"} · stáří ${f1(Astro.moonAge(now))} dne", 12f, C.LABEL),
                a.lp(top = 2)
            )
        }, a.lp(0, WRAP, 1f, start = 10))
        c.addView(r)
        content.addView(c, a.lp(bottom = 10))
    }

    private fun hm(h: Double): String {
        val m = (h * 60).toInt()
        return "${m / 60} h ${m % 60} min"
    }

    @Suppress("unused")
    private fun TextView.noop() = Unit

    @Suppress("unused")
    private fun Sample.noop() = Unit
}
