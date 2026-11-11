package cz.gramantik.meteowidget.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import cz.gramantik.meteowidget.CalEvent
import cz.gramantik.meteowidget.CalendarIcs
import cz.gramantik.meteowidget.DayF
import cz.gramantik.meteowidget.ForecastFull
import cz.gramantik.meteowidget.MainActivity
import cz.gramantik.meteowidget.NameDays
import cz.gramantik.meteowidget.TvSchedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.roundToInt

/** Obrazovka, která chce sama zpracovat některé klávesy (TV). */
interface KeyHandler {
    fun onKey(keyCode: Int): Boolean
}

/**
 * TV karta Kalendář: Rok (2 × 6 měsíců), 3 měsíce, Měsíc. Jen zobrazení.
 * Svátky a víkendy, jmeniny, počasí u nejbližších dnů, detail dne, vícedenní události,
 * nadcházející události s odpočtem, čísla týdnů, více kalendářů s barvami, hustota v ročním pohledu.
 */
class CalendarScreen(private val a: Activity) : MainActivity.Screen, KeyHandler {
    private enum class Mode(val label: String) { YEAR("Rok"), M3("3 měsíce"), M1("Měsíc"), WEEK("Týden") }

    private val cz = Locale("cs", "CZ")
    private var mode = Mode.M1
    private var anchor: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()
    private var data: Map<LocalDate, List<CalEvent>> = emptyMap()
    private var loadedKey = ""
    private var loading = false
    private val forecast = HashMap<LocalDate, DayF>()

    private val root = a.vbox().apply { setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(2)) }
    override val view: View = root

    private var detailBox: LinearLayout? = null
    /** tag prvku, na který se má po (asynchronním) překreslení vrátit výběr */
    private var pendingFocus: Any? = null
    private val cells = HashMap<LocalDate, View>()
    private val monthBoxes = HashMap<YearMonth, View>()

    init { refresh() }

    /** volné dny (víkend, státní svátek) – lehce odlišené pozadí */
    private val FREE_BG = 0xFF2B2430.toInt()

    override fun refresh() {
        // při posunu nechat staré zobrazení, dokud se nenačtou nová data (výběr pak neuteče do menu)
        if (data.isNotEmpty() && root.childCount > 1 && CalendarIcs.sources(a).isNotEmpty()) {
            val (f0, t0) = range()
            if ("$f0|$t0|${CalendarIcs.fetchedAt(a)}" != loadedKey) {
                root.findViewWithTag<View>("loading")?.visibility = View.VISIBLE
                if (pendingFocus == null) root.findFocus()?.tag?.let { pendingFocus = it }
                startLoad(f0, t0)
                return
            }
        }
        // focus po překreslení vrátit na vybraný den / tlačítko
        if (pendingFocus == null) root.findFocus()?.tag?.let { pendingFocus = it }
        root.removeAllViews()
        cells.clear()
        monthBoxes.clear()
        detailBox = null
        forecast.clear()
        ForecastFull.load(a)?.days?.forEach { forecast[it.date] = it }

        root.addView(topBar())
        if (CalendarIcs.sources(a).isEmpty()) {
            root.addView(a.text("Kalendář není nastavený – vlož iCal adresu v Nastavení → 🔑 Klíče a přístupy.", 14f, C.DIM), a.lp(top = 20))
            return
        }
        // tlačítka v liště se při načítání přestaví – výběr na nich musí zůstat
        val (from, to) = range()
        val key = "$from|$to|${CalendarIcs.fetchedAt(a)}"
        if (key != loadedKey) {
            startLoad(from, to)
            root.addView(a.text("Načítám kalendář…", 14f, C.DIM), a.lp(top = 20))
            pendingFocus?.let { t -> root.findViewWithTag<View>(t)?.let { v -> v.post { v.requestFocus() } } }
            return
        }
        val body = when (mode) {
            Mode.YEAR -> yearView()
            Mode.M3 -> threeMonths()
            Mode.M1 -> monthView()
            Mode.WEEK -> weekView()
        }
        root.addView(body, a.lp(h = 0, weight = 1f, top = 4))
        val ft = pendingFocus
        pendingFocus = null
        val target = ft?.let { root.findViewWithTag<View>(it) }
            ?: if (ft != null && (ft.toString().startsWith("d_") || ft.toString().startsWith("m_"))) (cells[selected] ?: monthBoxes[YearMonth.from(selected)]) else null
        target?.let { v -> v.post { v.requestFocus() } }
    }

    private fun startLoad(from: LocalDate, to: LocalDate) {
        if (loading) return
        loading = true
        Thread {
            if (!CalendarIcs.hasCache(a)) CalendarIcs.fetch(a)
            val upTo = maxOf(to, LocalDate.now().plusDays(90))
            val fromR = minOf(from, LocalDate.now())
            val m = CalendarIcs.range(a, fromR, upTo)
            root.post {
                data = m
                loadedKey = "$from|$to|${CalendarIcs.fetchedAt(a)}"
                loading = false
                // mezitím se mohl rozsah znovu změnit – refresh() případně načte znovu
                refresh()
            }
        }.start()
    }

    // ---------- rozsah dat ----------
    private fun range(): Pair<LocalDate, LocalDate> = when (mode) {
        Mode.YEAR -> LocalDate.of(anchor.year, 1, 1) to LocalDate.of(anchor.year, 12, 31)
        Mode.M3 -> gridStart(anchor) to anchor.plusMonths(2).atEndOfMonth().plusDays(7)
        Mode.M1 -> gridStart(anchor) to anchor.atEndOfMonth().plusDays(7)
        Mode.WEEK -> weekStart() to weekStart().plusDays(6)
    }

    private fun weekStart(): LocalDate = selected.minusDays((selected.dayOfWeek.value - 1).toLong())

    private fun gridStart(m: YearMonth): LocalDate {
        val first = m.atDay(1)
        return first.minusDays((first.dayOfWeek.value - 1).toLong())
    }

    private fun visibleEvents(d: LocalDate) = (data[d] ?: emptyList()).filter { CalendarIcs.shown(a, it.src) }

    private fun srcColor(i: Int) = CalendarIcs.COLORS.getOrElse(i) { C.ACCENT }

    private fun isFree(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || TvSchedule.isCzHoliday(d)

    // ---------- horní lišta ----------
    private fun topBar(): View {
        val bar = a.hbox(Gravity.CENTER_VERTICAL)
        Mode.entries.forEach { m ->
            bar.addView(a.chip(m.label, m == mode) {
                mode = m
                if (m != Mode.YEAR) anchor = YearMonth.from(selected)
                refresh()
            }.apply { tag = "mode_${m.name}"; tvFocus(scale = 1.0f) }, a.lp(WRAP, WRAP, end = 6))
        }
        fun arrow(t: String, dir: Int) = a.chip(t, false) { shift(dir) }.apply { tag = "nav_$dir"; tvFocus(scale = 1.0f) }
        bar.addView(arrow("‹", -1), a.lp(WRAP, WRAP, start = 10))
        val title = when (mode) {
            Mode.YEAR -> anchor.year.toString()
            Mode.M3 -> monthName(anchor) + " – " + monthName(anchor.plusMonths(2))
            Mode.M1 -> monthName(anchor)
            Mode.WEEK -> {
                val ws = weekStart()
                "${ws.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}. týden · ${ws.dayOfMonth}. ${ws.monthValue}. – " +
                    "${ws.plusDays(6).dayOfMonth}. ${ws.plusDays(6).monthValue}. ${ws.plusDays(6).year}"
            }
        }
        bar.addView(a.text(title, 18f, C.ACCENT, bold = true).apply { gravity = Gravity.CENTER }, a.lp(a.dp(250), WRAP))
        bar.addView(arrow("›", 1), a.lp(WRAP, WRAP))
        bar.addView(a.text("načítám…", 11f, C.DIM).apply { tag = "loading"; visibility = if (loading) View.VISIBLE else View.GONE },
            a.lp(WRAP, WRAP, start = 8))
        bar.addView(View(a), a.lp(0, 1, 1f))
        // kalendáře – barvy, klepnutím skrýt / zobrazit
        CalendarIcs.sources(a).forEach { s ->
            val on = CalendarIcs.shown(a, s.idx)
            bar.addView(a.text((if (on) "● " else "○ ") + s.name, 12f, if (on) s.color else C.DIM, bold = on).apply {
                setPadding(a.dp(8), a.dp(4), a.dp(8), a.dp(4))
                background = rounded(C.CARD2, a.dp(12).toFloat(), a.dp(1), if (on) s.color else C.LINE)
                isClickable = true
                isFocusable = true
                tag = "src_${s.idx}"
                setOnClickListener { CalendarIcs.setShown(a, s.idx, !on); refresh() }
                tvFocus(if (on) s.color else C.LINE, 1.0f)
            }, a.lp(WRAP, WRAP, start = 6))
        }
        return bar
    }

    private fun monthName(m: YearMonth) =
        m.month.getDisplayName(TextStyle.FULL_STANDALONE, cz).replaceFirstChar { it.uppercase() } + " " + m.year

    private fun shift(dir: Int) {
        if (mode == Mode.WEEK) {
            selected = selected.plusDays(7L * dir)
            anchor = YearMonth.from(selected)
            refresh()
            return
        }
        anchor = if (mode == Mode.YEAR) anchor.plusYears(dir.toLong()) else anchor.plusMonths(dir.toLong())
        selected = if (mode == Mode.YEAR) selected.plusYears(dir.toLong()) else anchor.atDay(selected.dayOfMonth.coerceAtMost(anchor.lengthOfMonth()))
        refresh()
    }

    // ---------- měsíční mřížka ----------
    private fun weekHeader(small: Boolean): View {
        val r = a.hbox()
        r.addView(a.text("týd", if (small) 7f else 8f, C.DIM).apply { gravity = Gravity.CENTER }, a.lp(a.dp(if (small) 18 else 24), WRAP))
        listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne").forEachIndexed { i, n ->
            r.addView(a.text(n, if (small) 8f else 10f, if (i >= 5) 0xFFFF8A80.toInt() else C.LABEL, bold = true).apply { gravity = Gravity.CENTER },
                a.lp(0, WRAP, 1f))
        }
        return r
    }

    private fun monthGrid(m: YearMonth, small: Boolean): View {
        val box = a.vbox()
        box.addView(weekHeader(small), a.lp(bottom = 2))
        var d = gridStart(m)
        val end = m.atEndOfMonth()
        while (!d.isAfter(end)) {
            val row = a.hbox()
            row.addView(a.text(d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString(), if (small) 7f else 9f, C.DIM).apply {
                gravity = Gravity.CENTER
            }, a.lp(a.dp(if (small) 18 else 24), LinearLayout.LayoutParams.MATCH_PARENT))
            repeat(7) {
                row.addView(dayCell(d, YearMonth.from(d) == m, small), a.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f, end = 2))
                d = d.plusDays(1)
            }
            box.addView(row, a.lp(h = 0, weight = 1f, bottom = 2))
        }
        return box
    }

    private fun dayCell(d: LocalDate, inMonth: Boolean, small: Boolean): View {
        val today = d == LocalDate.now()
        val evs = visibleEvents(d)
        val cell = a.vbox().apply {
            background = rounded(if (!inMonth) C.CARD else if (isFree(d)) FREE_BG else C.CARD2, a.dp(6).toFloat(), a.dp(1), if (today) C.ACCENT else C.LINE)
            setPadding(a.dp(3), a.dp(1), a.dp(3), a.dp(1))
            isFocusable = inMonth
            isClickable = inMonth
            alpha = if (inMonth) 1f else 0.45f
            tag = "d_$d"
        }
        // hlavička: číslo dne, jmeniny, počasí
        val head = a.hbox(Gravity.CENTER_VERTICAL)
        head.addView(a.text(d.dayOfMonth.toString(), if (small) 10f else 13f,
            when { today -> C.ACCENT; isFree(d) -> 0xFFFF8A80.toInt(); else -> C.WHITE }, bold = true))
        if (!small) {
            head.addView(a.text(" " + NameDays.forDate(a, d), 8f, C.DIM).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, a.lp(0, WRAP, 1f))
        } else head.addView(View(a), a.lp(0, 1, 1f))
        forecast[d]?.let { f ->
            head.addView(a.icon(f.icon, if (small) 12 else 16), a.lp(a.dp(if (small) 12 else 16), a.dp(if (small) 12 else 16)))
            if (!small) head.addView(a.text("${f.tMax.roundToInt()}°/${f.tMin.roundToInt()}°", 8f, C.LABEL, condensed = true))
        }
        cell.addView(head)
        val max = if (small) 2 else 4
        evs.take(max).forEach { e -> cell.addView(eventLine(e, small)) }
        if (evs.size > max) cell.addView(a.text("+${evs.size - max} další", 7.5f, C.DIM))
        if (inMonth) {
            cell.setOnFocusChangeListener { _, has -> if (has) { selected = d; showDetail(d) } }
            cell.setOnClickListener {
                selected = d
                if (mode != Mode.M1) { mode = Mode.M1; anchor = YearMonth.from(d); refresh() } else showDetail(d)
            }
            cell.tvFocus(if (today) C.ACCENT else C.LINE, 1.0f)
            cells[d] = cell
        }
        return cell
    }

    /** Řádek události: vícedenní / celodenní jako barevný pruh, jinak čas a název. */
    private fun eventLine(e: CalEvent, small: Boolean): View {
        val col = srcColor(e.src)
        return if (e.time.isEmpty()) {
            a.text(e.summary, if (small) 7.5f else 9f, C.BG, bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                background = rounded((col and 0x00FFFFFF) or 0xDD000000.toInt(), a.dp(3).toFloat())
                setPadding(a.dp(3), 0, a.dp(3), 0)
            }.also { it.layoutParams = a.lp(LinearLayout.LayoutParams.MATCH_PARENT, WRAP, top = 1) }
        } else {
            a.text(if (small) e.summary else "${e.time} ${e.summary}", if (small) 7.5f else 9f, col).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }
    }

    // ---------- pohledy ----------
    private fun monthView(): View {
        val r = a.hbox(Gravity.TOP)
        r.addView(monthGrid(anchor, small = false), a.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f))
        val side = a.vbox()
        val det = a.card()
        detailBox = det
        side.addView(det, a.lp(bottom = 6))
        side.addView(upcoming(), a.lp(h = 0, weight = 1f))
        r.addView(side, a.lp(a.dp(250), LinearLayout.LayoutParams.MATCH_PARENT, start = 8))
        showDetail(selected)
        return r
    }

    private fun threeMonths(): View {
        val v = a.vbox()
        val r = a.hbox(Gravity.TOP)
        for (k in 0..2) {
            val m = anchor.plusMonths(k.toLong())
            val col = a.vbox()
            col.addView(a.text(monthName(m), 13f, if (m == YearMonth.now()) C.ACCENT else C.WHITE, bold = true).apply { gravity = Gravity.CENTER },
                a.lp(LinearLayout.LayoutParams.MATCH_PARENT, WRAP, bottom = 2))
            col.addView(monthGrid(m, small = true), a.lp(h = 0, weight = 1f))
            r.addView(col, a.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f, end = if (k < 2) 8 else 0))
        }
        v.addView(r, a.lp(h = 0, weight = 1f))
        val det = a.vbox().apply {
            background = rounded(C.CARD, a.dp(10).toFloat(), a.dp(1), C.LINE)
            setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4))
        }
        detailBox = det
        v.addView(det, a.lp(h = a.dp(64), top = 6))
        showDetail(selected)
        return v
    }

    /** Týden: 7 sloupců s detailem každého dne. */
    private fun weekView(): View {
        val r = a.hbox(Gravity.TOP)
        val ws = weekStart()
        for (k in 0..6) {
            val d = ws.plusDays(k.toLong())
            val today = d == LocalDate.now()
            val col = a.vbox().apply {
                background = rounded(if (isFree(d)) FREE_BG else C.CARD2, a.dp(8).toFloat(), a.dp(1), if (today) C.ACCENT else C.LINE)
                setPadding(a.dp(5), a.dp(3), a.dp(5), a.dp(3))
                isFocusable = true
                isClickable = true
                tag = "d_$d"
            }
            val name = d.dayOfWeek.getDisplayName(TextStyle.FULL, cz).replaceFirstChar { it.uppercase() }
            col.addView(a.text(name, 12f, when { today -> C.ACCENT; isFree(d) -> 0xFFFF8A80.toInt(); else -> C.WHITE }, bold = true))
            col.addView(a.text("${d.dayOfMonth}. ${d.monthValue}." + if (TvSchedule.isCzHoliday(d)) " · svátek" else "", 10f, C.LABEL))
            col.addView(a.text(NameDays.forDate(a, d), 9f, C.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            forecast[d]?.let { f ->
                col.addView(a.hbox(Gravity.CENTER_VERTICAL).apply {
                    addView(a.icon(f.icon, 20), a.lp(a.dp(20), a.dp(20)))
                    addView(a.text(" ${f.tMax.roundToInt()}° / ${f.tMin.roundToInt()}°", 10f, C.WHITE, condensed = true))
                    if (f.precip >= 0.1) addView(a.text(" · " + String.format(cz, "%.1f", f.precip) + " mm", 9f, C.RAIN))
                }, a.lp(top = 2))
            }
            col.addView(View(a).apply { setBackgroundColor(C.LINE) }, a.lp(h = 1, top = 3, bottom = 3))
            val evs = visibleEvents(d)
            if (evs.isEmpty()) col.addView(a.text("—", 10f, C.DIM))
            evs.forEach { e ->
                if (e.time.isEmpty()) col.addView(eventLine(e, small = false))
                else {
                    col.addView(a.text(e.time, 9f, srcColor(e.src), bold = true), a.lp(top = 2))
                    col.addView(a.text(e.summary, 10f, C.WHITE).apply { maxLines = 3 })
                }
            }
            col.setOnFocusChangeListener { _, has -> if (has) selected = d }
            col.tvFocus(if (today) C.ACCENT else C.LINE, 1.0f)
            cells[d] = col
            r.addView(col, a.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f, end = if (k < 6) 4 else 0))
        }
        return r
    }

    private fun yearView(): View {
        val v = a.vbox()
        // nejvytíženější den roku – pro sytost barvy (hustota)
        val maxCount = (1..12).flatMap { mo -> val ym = YearMonth.of(anchor.year, mo); (1..ym.lengthOfMonth()).map { visibleEvents(ym.atDay(it)).size } }
            .maxOrNull()?.coerceAtLeast(1) ?: 1
        for (rowI in 0..1) {
            val r = a.hbox(Gravity.TOP)
            for (k in 0..5) {
                val m = YearMonth.of(anchor.year, rowI * 6 + k + 1)
                r.addView(miniMonth(m, maxCount), a.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f, end = if (k < 5) 5 else 0))
            }
            v.addView(r, a.lp(h = 0, weight = 1f, bottom = 5))
        }
        val det = a.vbox().apply {
            background = rounded(C.CARD, a.dp(10).toFloat(), a.dp(1), C.LINE)
            setPadding(a.dp(10), a.dp(4), a.dp(10), a.dp(4))
        }
        detailBox = det
        v.addView(det, a.lp(h = a.dp(54)))
        showMonthSummary(YearMonth.from(selected).takeIf { it.year == anchor.year } ?: YearMonth.of(anchor.year, 1))
        return v
    }

    private fun miniMonth(m: YearMonth, maxCount: Int): View {
        val box = a.vbox().apply {
            background = rounded(C.CARD2, a.dp(8).toFloat(), a.dp(1), if (m == YearMonth.now()) C.ACCENT else C.LINE)
            setPadding(a.dp(4), a.dp(2), a.dp(4), a.dp(2))
            isFocusable = true
            isClickable = true
            tag = "m_$m"
        }
        box.addView(a.text(m.month.getDisplayName(TextStyle.FULL_STANDALONE, cz).replaceFirstChar { it.uppercase() }, 11f,
            if (m == YearMonth.now()) C.ACCENT else C.WHITE, bold = true).apply { gravity = Gravity.CENTER },
            a.lp(LinearLayout.LayoutParams.MATCH_PARENT, WRAP))
        val hdr = a.hbox()
        listOf("P", "Ú", "S", "Č", "P", "S", "N").forEachIndexed { i, n ->
            hdr.addView(a.text(n, 7f, if (i >= 5) 0xFFFF8A80.toInt() else C.DIM).apply { gravity = Gravity.CENTER }, a.lp(0, WRAP, 1f))
        }
        box.addView(hdr)
        var d = gridStart(m)
        val end = m.atEndOfMonth()
        while (!d.isAfter(end)) {
            val row = a.hbox()
            repeat(7) {
                val inM = YearMonth.from(d) == m
                val n = if (inM) visibleEvents(d).size else 0
                val t = a.text(if (inM) d.dayOfMonth.toString() else "", 8f,
                    when { d == LocalDate.now() -> C.BG; isFree(d) -> 0xFFFF8A80.toInt(); else -> C.WHITE }, bold = d == LocalDate.now()).apply {
                    gravity = Gravity.CENTER
                    if (inM && d == LocalDate.now()) background = rounded(C.ACCENT, a.dp(3).toFloat())
                    else if (n == 0 && inM && isFree(d)) background = rounded(0x22FF8A80, a.dp(3).toFloat())
                    else if (n > 0) {
                        // sytost podle počtu událostí
                        val alphaV = (60 + 150 * n / maxCount).coerceAtMost(210)
                        val col = srcColor(visibleEvents(d).first().src)
                        background = rounded((col and 0x00FFFFFF) or (alphaV shl 24), a.dp(3).toFloat())
                    }
                }
                row.addView(t, a.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                d = d.plusDays(1)
            }
            box.addView(row, a.lp(h = 0, weight = 1f))
        }
        box.setOnFocusChangeListener { _, has -> if (has) { selected = m.atDay(1); showMonthSummary(m) } }
        box.setOnClickListener { mode = Mode.M1; anchor = m; selected = if (m == YearMonth.now()) LocalDate.now() else m.atDay(1); refresh() }
        box.tvFocus(if (m == YearMonth.now()) C.ACCENT else C.LINE, 1.0f)
        monthBoxes[m] = box
        return box
    }

    // ---------- detail dne / měsíce ----------
    private fun showDetail(d: LocalDate) {
        val box = detailBox ?: return
        box.removeAllViews()
        val evs = visibleEvents(d)
        val title = d.dayOfWeek.getDisplayName(TextStyle.FULL, cz).replaceFirstChar { it.uppercase() } + " " +
            d.format(java.time.format.DateTimeFormatter.ofPattern("d. M. yyyy"))
        val compact = mode != Mode.M1
        val head = a.hbox(Gravity.CENTER_VERTICAL)
        head.addView(a.text(title, if (compact) 12f else 14f, C.ACCENT, bold = true))
        if (TvSchedule.isCzHoliday(d)) head.addView(a.text("  · státní svátek", 10f, 0xFFFF8A80.toInt()))
        box.addView(head)
        val info = ArrayList<String>()
        info += "svátek: " + NameDays.forDate(a, d)
        forecast[d]?.let { info += ForecastFull.describe(it.code).lowercase(cz) + " ${it.tMax.roundToInt()}° / ${it.tMin.roundToInt()}°" +
            if (it.precip >= 0.1) " · ${String.format(cz, "%.1f", it.precip)} mm" else "" }
        val days = ChronoUnit.DAYS.between(LocalDate.now(), d)
        info += when { days == 0L -> "dnes"; days == 1L -> "zítra"; days > 1 -> "za $days dní"; days == -1L -> "včera"; else -> "před ${-days} dny" }
        box.addView(a.text(info.joinToString(" · "), 10f, C.LABEL).apply { maxLines = if (compact) 1 else 3 }, a.lp(top = 1))
        if (evs.isEmpty()) box.addView(a.text("Žádné události", 11f, C.DIM), a.lp(top = 3))
        if (compact) {
            box.addView(a.text(evs.joinToString("   ") { (if (it.time.isEmpty()) "celý den" else it.time) + " " + it.summary }, 11f, C.WHITE).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, a.lp(top = 2))
        } else evs.forEach { e ->
            val r = a.hbox(Gravity.TOP)
            r.addView(View(a).apply { background = rounded(srcColor(e.src), a.dp(2).toFloat()) }, a.lp(a.dp(4), a.dp(14), top = 2, end = 5))
            r.addView(a.text(e.time.ifEmpty { "celý den" }, 11f, srcColor(e.src), bold = true, condensed = true), a.lp(a.dp(52), WRAP))
            r.addView(a.text(e.summary, 11f, C.WHITE).apply { maxLines = 2 }, a.lp(0, WRAP, 1f))
            box.addView(r, a.lp(top = 3))
        }
    }

    private fun showMonthSummary(m: YearMonth) {
        val box = detailBox ?: return
        box.removeAllViews()
        val all = (1..m.lengthOfMonth()).flatMap { visibleEvents(m.atDay(it)) }
        box.addView(a.text(monthName(m) + " · ${all.size} událostí · OK otevře měsíc", 12f, C.ACCENT, bold = true))
        val next = all.filter { !it.date.isBefore(LocalDate.now()) }.take(5)
            .joinToString("   ") { it.date.format(java.time.format.DateTimeFormatter.ofPattern("d.M.")) + " " + it.summary }
        box.addView(a.text(next.ifEmpty { "Žádné nadcházející události" }, 11f, C.WHITE).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, a.lp(top = 2))
    }

    /** Nejbližší události s odpočtem. */
    private fun upcoming(): View {
        val c = a.card("Nadcházející")
        val today = LocalDate.now()
        val seen = HashSet<String>()
        val list = data.keys.filter { !it.isBefore(today) }.sorted().flatMap { visibleEvents(it) }
            .filter { seen.add(it.summary + "|" + it.src + "|" + (if (it.time.isEmpty()) "" else it.date)) } // vícedenní jen jednou
            .take(12)
        if (list.isEmpty()) c.addView(a.text("Nic v nejbližších 3 měsících", 11f, C.DIM))
        list.forEach { e ->
            val days = ChronoUnit.DAYS.between(today, e.date)
            val r = a.hbox(Gravity.TOP)
            r.addView(a.text(when (days) { 0L -> "dnes"; 1L -> "zítra"; else -> "za $days d" }, 10f, if (days <= 1) C.ACCENT else C.LABEL, bold = true),
                a.lp(a.dp(48), WRAP))
            r.addView(a.text(e.summary, 10.5f, srcColor(e.src)).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                a.lp(0, WRAP, 1f))
            c.addView(r, a.lp(top = 2))
        }
        return c
    }

    // ---------- ovladač ----------
    /** Sousední prvek v daném směru ze stejné skupiny (dny / měsíce), nebo null na okraji. */
    private fun neighbor(from: View, pool: Collection<View>, dx: Int, dy: Int): View? {
        val f = IntArray(2).also { from.getLocationOnScreen(it) }
        val fcx = f[0] + from.width / 2f
        val fcy = f[1] + from.height / 2f
        var best: View? = null
        var bestD = Float.MAX_VALUE
        for (v in pool) {
            if (v === from || !v.isShown) continue
            val l = IntArray(2).also { v.getLocationOnScreen(it) }
            val cx = l[0] + v.width / 2f
            val cy = l[1] + v.height / 2f
            val ok = when {
                dx > 0 -> cx > fcx + from.width / 2f && kotlin.math.abs(cy - fcy) < from.height / 2f
                dx < 0 -> cx < fcx - from.width / 2f && kotlin.math.abs(cy - fcy) < from.height / 2f
                dy > 0 -> cy > fcy + from.height / 2f && kotlin.math.abs(cx - fcx) < from.width
                else -> cy < fcy - from.height / 2f && kotlin.math.abs(cx - fcx) < from.width
            }
            if (!ok) continue
            val d = kotlin.math.abs(cx - fcx) + kotlin.math.abs(cy - fcy)
            if (d < bestD) { bestD = d; best = v }
        }
        return best
    }

    override fun onKey(keyCode: Int): Boolean {
        if (loading && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) return true
        val f = root.findFocus() ?: return false
        val tag = f.tag?.toString() ?: return false
        val dx = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        val dy = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> 0
        }
        if (dx == 0 && dy == 0) return false
        val isDay = tag.startsWith("d_")
        val isMonth = tag.startsWith("m_")
        if (!isDay && !isMonth) return false
        val pool: Collection<View> = if (isDay) cells.values else monthBoxes.values
        neighbor(f, pool, dx, dy)?.let { it.requestFocus(); return true }
        // okraj
        if (dy < 0) return false // nahoru -> tlačítka v liště
        if (isDay && mode == Mode.WEEK) {
            if (dx == 0) return true
            val t = LocalDate.parse(tag.removePrefix("d_")).plusDays(dx.toLong())
            selected = t
            anchor = YearMonth.from(t)
            pendingFocus = "d_$t"
            refresh()
            return true
        }
        val step = if (dx != 0) dx else dy
        if (isMonth) {
            // rok: vlevo / vpravo (i dolů z posledního řádku) -> další / předchozí rok
            val m = YearMonth.parse(tag.removePrefix("m_"))
            val t = YearMonth.of(m.year + step, m.monthValue)
            anchor = YearMonth.of(t.year, 1)
            selected = t.atDay(1)
            pendingFocus = "m_$t"
        } else {
            // měsíc / 3 měsíce: posun o měsíc, výběr ve stejném řádku a dni v týdnu
            val d = LocalDate.parse(tag.removePrefix("d_"))
            anchor = anchor.plusMonths(step.toLong())
            var t = d.plusMonths(step.toLong())
            if (dx != 0) t = t.with(java.time.temporal.TemporalAdjusters.nextOrSame(if (dx > 0) DayOfWeek.MONDAY else DayOfWeek.SUNDAY))
                .let { x -> if (dx < 0) x.minusDays(0) else x }
            // zůstat uvnitř zobrazených měsíců
            val first = anchor.atDay(1)
            val last = (if (mode == Mode.M3) anchor.plusMonths(2) else anchor).atEndOfMonth()
            if (t.isBefore(first)) t = first
            if (t.isAfter(last)) t = last
            selected = t
            pendingFocus = "d_$t"
        }
        refresh()
        return true
    }
}
