package cz.gramantik.meteowidget.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.gramantik.meteowidget.DayF
import cz.gramantik.meteowidget.ForecastFull
import cz.gramantik.meteowidget.FullForecast
import cz.gramantik.meteowidget.HourF
import cz.gramantik.meteowidget.MainActivity
import cz.gramantik.meteowidget.MeteoDb
import cz.gramantik.meteowidget.Prefs
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Obrazovka Předpověď: teď, hodinová (48 h), 3 dny po hodinách, 16 dní po dnech, přesnost. */
class ForecastScreen(private val a: Activity) : MainActivity.Screen {
    private val cz = Locale("cs", "CZ")
    private val zone = ZoneId.systemDefault()
    private val content = a.vbox().apply { setPadding(a.dp(12), a.dp(4), a.dp(12), a.dp(24)) }
    override val view: View = ScrollView(a).apply { addView(content); isFillViewport = true }

    private enum class Mode(val label: String) { H48("48 hodin"), D3("3 dny"), D16("16 dní") }
    private var mode = Mode.D16
    /** den, na který se má po přepnutí na 3 dny sjet */
    private var scrollTo: LocalDate? = null
    private val dayHeaders = HashMap<LocalDate, View>()
    private val expanded = HashSet<LocalDate>()

    init { refresh() }

    private fun f1(v: Double) = if (v.isNaN()) "--" else String.format(cz, "%.1f", v)
    private fun deg(v: Double) = if (v.isNaN()) "--°" else "${v.roundToInt()}°"
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    private fun dayName(d: LocalDate): String = when (d) {
        LocalDate.now() -> "Dnes"
        LocalDate.now().plusDays(1) -> "Zítra"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, cz).replaceFirstChar { it.uppercase() }
    }

    override fun refresh() {
        content.removeAllViews()
        dayHeaders.clear()
        val f = ForecastFull.load(a)
        if (f == null) {
            content.addView(a.text("Předpověď se načítá… Klepni na ⟳ nahoře.", 14f, C.DIM), a.lp(top = 20))
            return
        }
        now(f)
        val chips = Mode.entries.map { m -> a.chip(m.label, m == mode) { mode = m; refresh() } }
        content.addView(a.chipRow(*chips.toTypedArray()), a.lp(top = 4, bottom = 10))
        when (mode) {
            Mode.H48 -> hours(f, 48, list = false)
            Mode.D3 -> hours(f, 72, list = true)
            Mode.D16 -> days(f)
        }
        accuracy()
        scrollTo?.let { target ->
            scrollTo = null
            val header = dayHeaders[target]
            view.post {
                if (header == null) { (view as ScrollView).smoothScrollTo(0, 0); return@post }
                var y = 0
                var v: View? = header
                while (v != null && v !== content) {
                    y += v.top
                    v = v.parent as? View
                }
                (view as ScrollView).smoothScrollTo(0, (y - a.dp(8)).coerceAtLeast(0))
            }
        }
        content.addView(
            a.text("Předpověď: Open-Meteo.com (CC BY 4.0) · ${Prefs(a).locName}", 11f, C.DIM),
            a.lp(top = 8)
        )
    }

    private fun upcoming(f: FullForecast): List<HourF> {
        val now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
        return f.hours.filter { !it.time.isBefore(now) }
    }

    // ---------- teď (kompaktní) ----------
    private fun now(f: FullForecast) {
        val h = upcoming(f).firstOrNull() ?: return
        val today = f.days.firstOrNull { it.date == LocalDate.now() }
        val c = a.card("Teď · ${h.time.hour}:00")
        val top = a.hbox()
        top.addView(a.animIcon(h.icon, 72))
        val col = a.vbox()
        val tempRow = a.hbox(Gravity.BOTTOM)
        tempRow.addView(a.text(deg(h.temp), 42f, C.WHITE, condensed = true))
        today?.let {
            tempRow.addView(a.vbox().apply {
                addView(a.text("dnes max ${deg(it.tMax)}", 12f, C.IN, condensed = true))
                addView(a.text("dnes min ${deg(it.tMin)}", 12f, 0xFF8FB3FF.toInt(), condensed = true))
            }, a.lp(WRAP, WRAP, start = 10))
        }
        col.addView(tempRow)
        col.addView(a.text(ForecastFull.describe(h.code), 15f, C.LABEL), a.lp(top = 2))
        top.addView(col, a.lp(0, WRAP, 1f, start = 10))
        c.addView(top)

        // drobné řádky po třech hodnotách
        fun mini(label: String, value: String, color: Int) = a.vbox().apply {
            addView(a.text(label, 10f, C.DIM, bold = true).apply { letterSpacing = 0.06f })
            addView(a.text(value, 14f, color, condensed = true), a.lp(WRAP, WRAP, top = 1))
        }
        val items = listOf(
            mini("POCITOVĚ", deg(h.feels), C.FEELS),
            mini("SRÁŽKY", "${f1(h.precip)} mm" + if (h.prob >= 0) " · ${h.prob} %" else "", C.RAIN),
            mini("VÍTR", "${h.wind.roundToInt()} (${h.gust.roundToInt()}) km/h ${ForecastFull.windDir(h.dir)}", C.WIND),
            mini("VLHKOST", "${h.hum.roundToInt()} % · rb ${deg(h.dew)}", C.OUT),
            mini("TLAK", "${h.pressure.roundToInt()} hPa", C.BARO),
            mini("OBLAČNOST", "${h.cloud.roundToInt()} % · ${(h.visibility / 1000).roundToInt()} km", C.LABEL),
        )
        items.chunked(3).forEach { rowItems ->
            val r = a.hbox(Gravity.TOP)
            rowItems.forEach { r.addView(it, a.lp(0, WRAP, 1f)) }
            c.addView(r, a.lp(top = 8))
        }
        val storm = upcoming(f).take(12).maxOfOrNull { it.storm } ?: 0
        if (storm > 0) c.addView(a.text(ForecastFull.stormText(storm) + " v příštích 12 h", 13f, C.ACCENT, bold = true), a.lp(top = 8))
        content.addView(SwipeFrame(a) { (a as? MainActivity)?.swipe(it) }.apply { addView(c) }, a.lp(bottom = 4))
    }

    // ---------- hodinová ----------
    private fun hours(f: FullForecast, count: Int, list: Boolean) {
        val hs = upcoming(f).take(count)
        if (hs.isEmpty()) return
        val c = a.card(if (count <= 48) "Příštích 48 hodin" else "3 dny po hodinách")

        if (!list) {
            // vodorovný pás hodin
            val row = a.hbox(Gravity.TOP)
            for (h in hs) {
                val cell = a.vbox(Gravity.CENTER_HORIZONTAL).apply {
                    background = rounded(C.CARD2, a.dp(12).toFloat())
                    setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
                }
                val hourLabel = if (h.time.hour == 0) h.time.format(DateTimeFormatter.ofPattern("d.M.")) else "${h.time.hour}:00"
                cell.addView(a.text(hourLabel, 12f, if (h.time.hour == 0) C.ACCENT else C.LABEL, bold = h.time.hour == 0))
                cell.addView(a.icon(h.icon, 40), a.lp(WRAP, WRAP, top = 4))
                cell.addView(a.text(deg(h.temp), 17f, C.WHITE, condensed = true), a.lp(WRAP, WRAP, top = 2))
                cell.addView(a.text(if (h.precip >= 0.1) "${f1(h.precip)} mm" else " ", 11f, C.RAIN), a.lp(WRAP, WRAP, top = 2))
                cell.addView(a.text(if (h.prob >= 0) "${h.prob} %" else " ", 11f, C.RAIN))
                cell.addView(a.text("${h.wind.roundToInt()} km/h", 11f, C.WIND), a.lp(WRAP, WRAP, top = 2))
                cell.addView(a.text(if (h.storm > 0) "⚡" else " ", 12f, C.ACCENT))
                row.addView(cell, a.lp(a.dp(70), WRAP, end = 6))
            }
            val hsv = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(row) }
            fun arrow(t: String, dir: Int) = a.text(t, 26f, C.ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(a.dp(4), a.dp(20), a.dp(4), a.dp(20))
                setOnClickListener { hsv.smoothScrollBy(dir * a.dp(76) * 4, 0) }
                tvFocus(scale = 1.2f)
            }
            val left = arrow("‹", -1)
            val right = arrow("›", 1)
            fun updateArrows() {
                val max = (row.width - hsv.width).coerceAtLeast(0)
                left.alpha = if (hsv.scrollX <= 0) 0.25f else 1f
                right.alpha = if (hsv.scrollX >= max) 0.25f else 1f
            }
            hsv.setOnScrollChangeListener { _, _, _, _, _ -> updateArrows() }
            hsv.post { updateArrows() }
            val wrap = a.hbox()
            wrap.addView(left, a.lp(WRAP, WRAP))
            wrap.addView(hsv, a.lp(0, WRAP, 1f))
            wrap.addView(right, a.lp(WRAP, WRAP))
            c.addView(wrap)
        } else {
            var lastDay: LocalDate? = null
            for (h in hs) {
                val d = h.time.toLocalDate()
                if (d != lastDay) {
                    val header = a.text(dayName(d) + " " + d.format(DateTimeFormatter.ofPattern("d.M.")), 15f, C.ACCENT, bold = true)
                    dayHeaders[d] = header
                    c.addView(header, a.lp(top = 10, bottom = 4))
                    lastDay = d
                }
                val r = a.hbox()
                r.addView(a.text(String.format("%02d:00", h.time.hour), 13f, C.LABEL, condensed = true), a.lp(a.dp(44), WRAP))
                r.addView(a.icon(h.icon, 30))
                r.addView(a.text(deg(h.temp), 16f, C.WHITE, condensed = true), a.lp(a.dp(46), WRAP, start = 6))
                r.addView(a.text(if (h.precip >= 0.1) "${f1(h.precip)} mm" else "", 12f, C.RAIN), a.lp(a.dp(52), WRAP))
                r.addView(a.text(if (h.prob > 0) "${h.prob} %" else "", 12f, C.RAIN), a.lp(a.dp(38), WRAP))
                r.addView(a.text("${h.wind.roundToInt()}/${h.gust.roundToInt()} ${ForecastFull.windDir(h.dir)}", 12f, C.WIND, condensed = true), a.lp(0, WRAP, 1f))
                r.addView(a.text((if (h.storm > 0) "⚡ " else "") + "☁${h.cloud.roundToInt()}%", 11f, if (h.storm > 0) C.ACCENT else C.DIM))
                c.addView(r, a.lp(bottom = 2))
            }
            c.addView(a.text("vítr: průměr/nárazy km/h", 11f, C.DIM), a.lp(top = 6))
        }
        // graf až pod výpisem
        val times = hs.map { ms(it.time) }.toLongArray()
        c.addView(ChartView(a).apply {
            series = listOf(
                Series("Teplota", C.IN, times, hs.map { it.temp }.toDoubleArray(), unit = "°C"),
                Series("Pocitově", C.FEELS, times, hs.map { it.feels }.toDoubleArray(), unit = "°C"),
                Series("Srážky", C.RAIN, times, hs.map { it.precip }.toDoubleArray(), bars = true, unit = "mm", ownAxis = true),
            )
        }, a.lp(h = a.dp(210), top = 12))
        c.addView(a.text("● teplota  ● pocitově  ▮ srážky (mm, pravá osa)", 11f, C.DIM), a.lp(top = 4))
        content.addView(c, a.lp(bottom = 10))
    }

    // ---------- 16 dní ----------
    private fun days(f: FullForecast) {
        val ds = f.days.filter { !it.date.isBefore(LocalDate.now()) }
        if (ds.isEmpty()) return
        val c = a.card("${ds.size} dní")
        c.addView(a.text("Klepni na den pro podrobnosti", 11f, C.DIM), a.lp(bottom = 6))
        val tf = DateTimeFormatter.ofPattern("HH:mm")
        val hourDays = f.hours.map { it.time.toLocalDate() }.toSet()
        for ((di, d) in ds.withIndex()) {
            val jump = di < 3 && d.date in hourDays
            val box = a.vbox().apply {
                background = rounded(C.CARD2, a.dp(12).toFloat())
                setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8))
            }
            val r = a.hbox()
            r.addView(a.vbox().apply {
                addView(a.text(dayName(d.date), 15f, C.WHITE, bold = true))
                addView(a.text(d.date.format(DateTimeFormatter.ofPattern("d.M.")), 12f, C.DIM))
            }, a.lp(a.dp(78), WRAP))
            r.addView(a.icon(d.icon, 44))
            r.addView(a.vbox().apply {
                addView(a.text(ForecastFull.describe(d.code), 13f, C.LABEL))
                if (d.precip >= 0.1 || d.prob > 0) {
                    addView(a.text("${f1(d.precip)} mm · ${d.prob.coerceAtLeast(0)} %", 12f, C.RAIN), a.lp(top = 2))
                }
                val storm = f.hours.filter { it.time.toLocalDate() == d.date }.maxOfOrNull { it.storm }
                    ?: ForecastFull.stormLevel(d.code, Double.NaN)
                if (storm > 0) addView(a.text(ForecastFull.stormText(storm), 12f, C.ACCENT), a.lp(top = 2))
            }, a.lp(0, WRAP, 1f, start = 8))
            r.addView(a.text(deg(d.tMax), 18f, C.IN, condensed = true), a.lp(WRAP, WRAP, end = 8))
            r.addView(a.text(deg(d.tMin), 16f, 0xFF8FB3FF.toInt(), condensed = true))
            if (jump) r.addView(a.text("›", 24f, C.ACCENT, bold = true), a.lp(WRAP, WRAP, start = 8))
            box.addView(r)
            if (jump) box.background = rounded(0xFF1D2630.toInt(), a.dp(12).toFloat(), a.dp(1), 0x66FFD54F)
            if (d.date in expanded) {
                val tiles = listOf(
                    a.tile("Pocitově", "${deg(d.feelsMax)} / ${deg(d.feelsMin)}", C.FEELS),
                    a.tile("Vítr max", "${d.windMax.roundToInt()} km/h", C.WIND, "nárazy ${d.gustMax.roundToInt()} · ${ForecastFull.windDir(d.dir)}"),
                    a.tile("Srážky", "${f1(d.precip)} mm", C.RAIN, "${f1(d.precipHours)} h" + if (d.snow > 0) " · sníh ${f1(d.snow)} cm" else ""),
                    a.tile("UV max", f1(d.uvMax), C.UV),
                    a.tile("Slunce", "${d.sunrise?.format(tf) ?: "--"} – ${d.sunset?.format(tf) ?: "--"}", C.ACCENT, "svit ${f1(d.sunshineH)} h"),
                )
                box.addView(a.grid(tiles), a.lp(top = 8))
                // hodiny daného dne
                val hs = f.hours.filter { it.time.toLocalDate() == d.date && it.time.hour % 3 == 0 }
                if (hs.isNotEmpty()) {
                    val row = a.hbox(Gravity.TOP)
                    for (h in hs) {
                        row.addView(a.vbox(Gravity.CENTER_HORIZONTAL).apply {
                            addView(a.text("${h.time.hour}:00", 11f, C.LABEL))
                            addView(a.icon(h.icon, 30))
                            addView(a.text(deg(h.temp), 13f, C.WHITE, condensed = true))
                        }, a.lp(0, WRAP, 1f))
                    }
                    box.addView(row, a.lp(top = 4))
                }
            }
            box.setOnClickListener {
                if (jump) {
                    // přepnout na 3 dny po hodinách a sjet na vybraný den
                    mode = Mode.D3
                    scrollTo = d.date
                    refresh()
                } else {
                    if (!expanded.add(d.date)) expanded.remove(d.date)
                    refresh()
                }
            }
            box.tvFocus(if (jump) 0x66FFD54F else 0, 1.02f)
            c.addView(box, a.lp(bottom = 6))
        }
        val times = ds.map { ms(it.date.atTime(12, 0)) }.toLongArray()
        c.addView(ChartView(a).apply {
            series = listOf(
                Series("Max", C.IN, times, ds.map { it.tMax }.toDoubleArray(), unit = "°C"),
                Series("Min", 0xFF8FB3FF.toInt(), times, ds.map { it.tMin }.toDoubleArray(), unit = "°C"),
                Series("Srážky", C.RAIN, times, ds.map { it.precip }.toDoubleArray(), bars = true, unit = "mm", ownAxis = true),
            )
        }, a.lp(h = a.dp(200), top = 8))
        c.addView(a.text("● max  ● min  ▮ srážky (mm, pravá osa)", 11f, C.DIM), a.lp(top = 4))
        content.addView(c, a.lp(bottom = 10))
    }

    // ---------- přesnost předpovědí ----------
    private fun accuracy() {
        val db = MeteoDb.get(a)
        val to = LocalDate.now().minusDays(1)
        val from = to.minusDays(13)
        val actual = db.days(from, to).associateBy { it.date }
        val logs = db.forecastLog(from, to).filter { actual[it.date]?.toutMax != null }
        val c = a.card("Předpověď vs. realita (14 dní)", C.ACCENT)
        if (logs.isEmpty()) {
            c.addView(a.text("Porovnání se začne plnit po prvních dnech sběru – aplikace si každý den uloží předpověď na zítřek a porovná ji s naměřenými hodnotami.", 13f, C.DIM))
            content.addView(c, a.lp(bottom = 10))
            return
        }
        for ((src, rows) in logs.groupBy { it.source }) {
            val eMax = rows.map { abs(it.tMax - actual[it.date]!!.toutMax!!) }.average()
            val eMin = rows.mapNotNull { r -> actual[r.date]?.toutMin?.let { abs(r.tMin - it) } }.average()
            val eRain = rows.mapNotNull { r -> actual[r.date]?.rain?.let { abs(r.precip - it) } }.ifEmpty { listOf(Double.NaN) }.average()
            val name = if (src == Prefs.SOURCE_GOOGLE) "Google" else "Open-Meteo"
            c.addView(a.text(name, 15f, C.WHITE, bold = true), a.lp(top = 4))
            c.addView(
                a.text("Průměrná odchylka: max ${f1(eMax)} °C · min ${f1(eMin)} °C · srážky ${f1(eRain)} mm (${rows.size} dní)", 13f, C.LABEL),
                a.lp(bottom = 6)
            )
        }
        val head = a.hbox()
        listOf("Den" to 1.2f, "Předpověď" to 1.6f, "Naměřeno" to 1.6f, "Srážky" to 1.4f).forEach { (s, w) ->
            head.addView(a.text(s, 11f, C.DIM, bold = true), a.lp(0, WRAP, w))
        }
        c.addView(head, a.lp(top = 4, bottom = 2))
        val df = DateTimeFormatter.ofPattern("d.M.")
        for (r in logs.filter { it.source == Prefs.SOURCE_OPEN_METEO || logs.none { l -> l.source == Prefs.SOURCE_OPEN_METEO } }.sortedByDescending { it.date }) {
            val act = actual[r.date] ?: continue
            val row = a.hbox()
            row.addView(a.text(r.date.format(df), 13f, C.LABEL), a.lp(0, WRAP, 1.2f))
            row.addView(a.text("${deg(r.tMax)} / ${deg(r.tMin)}", 13f, C.FEELS, condensed = true), a.lp(0, WRAP, 1.6f))
            row.addView(a.text("${deg(act.toutMax ?: Double.NaN)} / ${deg(act.toutMin ?: Double.NaN)}", 13f, C.OUT, condensed = true), a.lp(0, WRAP, 1.6f))
            row.addView(a.text("${f1(r.precip)} / ${f1(act.rain ?: Double.NaN)}", 13f, C.RAIN, condensed = true), a.lp(0, WRAP, 1.4f))
            c.addView(row, a.lp(bottom = 2))
        }
        content.addView(c, a.lp(bottom = 10))
    }

    @Suppress("unused")
    private fun DayF.noop(): LinearLayout? = null
}
