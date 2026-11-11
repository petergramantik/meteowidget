package cz.gramantik.meteowidget

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import cz.gramantik.meteowidget.ui.C
import cz.gramantik.meteowidget.ui.WRAP
import cz.gramantik.meteowidget.ui.animIcon
import cz.gramantik.meteowidget.ui.dp
import cz.gramantik.meteowidget.ui.hbox
import cz.gramantik.meteowidget.ui.icon
import cz.gramantik.meteowidget.ui.label
import cz.gramantik.meteowidget.ui.lp
import cz.gramantik.meteowidget.ui.rounded
import cz.gramantik.meteowidget.ui.text
import cz.gramantik.meteowidget.ui.vbox
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Hlavní obrazovka pro televizi (na šířku), plátno 640×360 dp – TvPanel ji zvětší na obrazovku.
 * Nahoře záložky (ovladatelné), vlevo Doma/Venku, uprostřed dlaždice, vpravo Dnes
 * (svátek, kalendář, teď), dole předpověď 12 hodin a 10 dní.
 */
class TvHome(private val ctx: Context, private val interactive: Boolean) {

    companion object {
        val TABS = listOf("Dnes", "Zítra", "Předpověď", "Radar", "Doma", "⚙ Nastavení", "🔔")
        const val TAB_TODAY = 0
        const val TAB_TOMORROW = 1
        const val TAB_FORECAST = 2
        const val TAB_RADAR = 3
        const val TAB_HISTORY = 4
        const val TAB_SETTINGS = 5
        const val TAB_ALERTS = 6
    }

    private val cz = Locale("cs", "CZ")
    val tabViews = ArrayList<TextView>()
    var onTab: ((Int) -> Unit)? = null
    /** pohled na zítřek (záložka Zítra) */
    var tomorrow = false
    /** právě zobrazená karta (zvýrazní se i bez focusu) */
    var activeTab = 0
    private val tabStylers = ArrayList<(Boolean) -> Unit>()

    /** Přebarví záložky podle aktivní karty (bez překreslení celého panelu). */
    fun restyleTabs() = tabViews.forEachIndexed { i, t -> tabStylers.getOrNull(i)?.invoke(t.isFocused) }
    /** animovaná ikona „teď“ se drží mezi překresleními (aby se animace nerestartovala) */
    private var animKey: String? = null
    private var animView: View? = null

    private fun t(v: Double?) = if (v == null) "--°" else String.format(cz, "%.1f°", v)
    private fun f1(v: Double?) = if (v == null) "--" else String.format(cz, "%.1f", v)
    private fun deg(v: Double) = if (v.isNaN()) "--°" else "${v.roundToInt()}°"

    private fun panel(): LinearLayout = ctx.vbox().apply {
        background = rounded(C.CARD, ctx.dp(10).toFloat(), ctx.dp(1), C.LINE)
        setPadding(ctx.dp(10), ctx.dp(7), ctx.dp(10), ctx.dp(7))
    }

    /** tenká svislá dělicí linka mezi sloupci předpovědi */
    private fun sep() = View(ctx).apply { setBackgroundColor(0x1FFFFFFF) }

    /** Levý panel v pohledu Zítra: očekávané počasí. */
    private fun buildTomorrowLeft(left: LinearLayout, d: DayF?) {
        val c = ctx
        if (d == null) {
            left.addView(c.text("Předpověď zatím není k dispozici", 12f, C.DIM), c.lp(top = 8))
            return
        }
        // hlavička jako na Dnes: MAX vlevo, ikona + popis uprostřed, MIN vpravo
        val head = c.hbox(Gravity.TOP)
        head.addView(c.vbox(Gravity.START).apply {
            addView(c.label("MAX"), c.lp(WRAP, WRAP))
            addView(c.text(deg(d.tMax), 30f, C.IN, condensed = true), c.lp(WRAP, WRAP, top = 2))
        }, c.lp(0, WRAP, 1f))
        head.addView(c.vbox(Gravity.CENTER_HORIZONTAL).apply {
            addView(c.icon(d.icon, 38), c.lp(WRAP, WRAP, top = 2))
            addView(c.text(ForecastFull.describe(d.code), 9f, C.WHITE, bold = true).apply {
                gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }, c.lp(WRAP, WRAP))
        }, c.lp(c.dp(84), WRAP))
        head.addView(c.vbox(Gravity.END).apply {
            addView(c.label("MIN"), c.lp(WRAP, WRAP))
            addView(c.text(deg(d.tMin), 30f, 0xFF8FB3FF.toInt(), condensed = true), c.lp(WRAP, WRAP, top = 2))
        }, c.lp(0, WRAP, 1f))
        left.addView(head, c.lp(LinearLayout.LayoutParams.MATCH_PARENT, WRAP, bottom = 4))
        // zmenšené dlaždice 3 × 2
        val tf = java.time.format.DateTimeFormatter.ofPattern("H:mm")
        val tiles = listOf(
            Triple("POCITOVĚ", "${deg(d.feelsMax)} / ${deg(d.feelsMin)}", C.FEELS) to "",
            Triple("VÍTR", "${d.windMax.roundToInt()} km/h", C.WIND) to "nárazy ${d.gustMax.roundToInt()} · ${ForecastFull.windDir(d.dir)}",
            Triple("SRÁŽKY", "${f1(d.precip)} mm", C.RAIN) to ("${d.prob.coerceAtLeast(0)} %" + if (d.snow >= 0.1) " · sníh ${f1(d.snow)} cm" else ""),
            Triple("UV MAX", f1(d.uvMax), C.UV) to "",
            Triple("SLUNCE", "${f1(d.sunshineH)} h", C.ACCENT) to "svit",
            Triple("VÝCHOD / ZÁPAD", "${d.sunrise?.let { tf.format(it) } ?: "--"} – ${d.sunset?.let { tf.format(it) } ?: "--"}", C.LIGHT) to "",
        )
        val grid = c.vbox()
        tiles.chunked(3).forEachIndexed { ri, row ->
            val r = c.hbox(Gravity.TOP)
            row.forEachIndexed { k, (hd, sub) ->
                r.addView(c.vbox().apply {
                    background = rounded(C.CARD, c.dp(8).toFloat(), c.dp(1), C.LINE)
                    setPadding(c.dp(5), c.dp(3), c.dp(4), c.dp(3))
                    addView(c.label(hd.first).apply { textSize = 7f; maxLines = 1 })
                    addView(c.text(hd.second, 12f, hd.third, condensed = true).apply { maxLines = 1 }, c.lp(WRAP, WRAP, top = 1))
                    addView(c.text(sub.ifEmpty { " " }, 7.5f, C.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, c.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f, end = if (k < 2) 4 else 0))
            }
            grid.addView(r, c.lp(h = 0, weight = 1f, bottom = if (ri == 0) 4 else 0))
        }
        left.addView(grid, c.lp(h = 0, weight = 1f))
    }

    fun build(animated: Boolean): View {
        val c = ctx
        val p = Prefs(c)
        val w = p.lastData?.let { Weather(it, p.spec) }
        val root = c.vbox().apply {
            setBackgroundColor(Color.BLACK)
            setPadding(c.dp(16), c.dp(10), c.dp(16), c.dp(16)) // spodní okraj kvůli přesahu obrazu TV
            layoutParams = LinearLayout.LayoutParams(c.dp(640), c.dp(360))
        }

        // ---------- záložky + čas ----------
        val top = c.hbox()
        tabViews.clear()
        tabStylers.clear()
        if (interactive) {
            val unread = NotifyLog.unread(c)
            TABS.forEachIndexed { i, name0 ->
                val name = if (i == TAB_ALERTS && unread > 0) "🔔 $unread" else name0
                if (i == TAB_ALERTS) top.addView(View(c), c.lp(0, 1, 1f)) // zvoneček vpravo u hodin
                val tv = c.text(name, 11f, C.WHITE, bold = true).apply {
                    setPadding(c.dp(10), c.dp(4), c.dp(10), c.dp(4))
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { onTab?.invoke(i) }
                    // vybraná (focus): žlutá výplň; zobrazená karta bez focusu: žlutý text a rámeček
                    fun style(has: Boolean) {
                        val active = i == activeTab
                        setTextColor(if (has) C.BG else if (active) C.ACCENT else if (i == TAB_ALERTS && unread > 0) 0xFFFF8A80.toInt() else C.WHITE)
                        background = rounded(if (has) C.ACCENT else C.CARD2, c.dp(12).toFloat(), c.dp(1),
                            if (has || active) C.ACCENT else C.LINE)
                    }
                    style(false)
                    tabStylers.add { has -> style(has) }
                    setOnFocusChangeListener { _, has -> style(has) }
                }
                tabViews.add(tv)
                top.addView(tv, c.lp(WRAP, WRAP, end = 6))
            }
        } else top.addView(View(c), c.lp(0, 1, 1f))
        top.addView(c.text(SimpleDateFormat("EEE d.M.  HH:mm", cz).format(Date()), 12f, C.WIND))
        root.addView(top, c.lp(bottom = 6))

        // ---------- horní řada ----------
        val row = c.hbox(Gravity.TOP)

        // vlevo: Doma / Venku
        val trend = PressureHistory.trend3h(c)
        val arrow = when { trend == null -> ""; trend >= 1 -> " ↗"; trend <= -1 -> " ↘"; else -> " →" }
        val morning = MorningPrefs(c).active()
        val left = panel()
        val today = LocalDate.now()
        val f = ForecastFull.load(c)
        if (tomorrow) {
            buildTomorrowLeft(left, f?.days?.firstOrNull { it.date == today.plusDays(1) })
        } else {
        val lr = c.hbox(Gravity.TOP)
        val hasSensors = w != null && listOf(Channel.S1, Channel.S2, Channel.S3).any { w.temp(it.tempCode) != null }
        fun col(name: String, temp: Double?, hum: Int?, color: Int, end: Boolean) = c.vbox(if (end) Gravity.END else Gravity.START).apply {
            addView(c.label(name), c.lp(WRAP, WRAP))
            addView(c.text(t(temp), if (hasSensors) 30f else 34f, color, condensed = true), c.lp(WRAP, WRAP, top = 2))
            addView(c.text(Weather.h(hum), if (hasSensors) 14f else 16f, color, condensed = true), c.lp(WRAP, WRAP))
        }
        val todayF = f?.days?.firstOrNull { it.date == today }
        lr.addView(col(p.channelName(Channel.IN), w?.tempIn, w?.humIn, C.IN, false), c.lp(0, WRAP, 1f))
        // uprostřed dnešní předpověď (jako na kartě Zítra)
        if (todayF != null) lr.addView(c.vbox(Gravity.CENTER_HORIZONTAL).apply {
            addView(c.icon(todayF.icon, 30), c.lp(WRAP, WRAP, top = 2))
            addView(c.hbox(Gravity.BOTTOM).apply {
                addView(c.text(deg(todayF.tMax), 13f, C.IN, condensed = true))
                addView(c.text(" ${deg(todayF.tMin)}", 11f, 0xFF8FB3FF.toInt(), condensed = true))
            }, c.lp(WRAP, WRAP))
            addView(c.text(ForecastFull.describe(todayF.code), 8.5f, C.LABEL).apply {
                maxLines = 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END
            }, c.lp(WRAP, WRAP))
        }, c.lp(c.dp(70), WRAP))
        lr.addView(col(p.channelName(Channel.OUT), w?.tempOut, w?.humOut, C.OUT, true), c.lp(0, WRAP, 1f))
        left.addView(lr)
        if (w != null) {
            val sensors = listOf(Channel.S1, Channel.S2, Channel.S3).mapNotNull { ch ->
                w.temp(ch.tempCode)?.let { p.channelName(ch) to "${t(it)} · ${Weather.h(w.hum(ch.humCode))}" }
            }
            if (sensors.isNotEmpty()) {
                left.addView(View(c).apply { setBackgroundColor(C.LINE) }, c.lp(h = 1, top = 5, bottom = 3))
                // další čidla ve dvou sloupcích
                sensors.chunked(2).forEach { pair ->
                    val r = c.hbox(Gravity.TOP)
                    pair.forEachIndexed { i, (name, value) ->
                        r.addView(c.hbox().apply {
                            gravity = (if (i == 1) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                            addView(c.text(name, 9f, C.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                                c.lp(WRAP, WRAP, end = 4).apply { weight = 0f })
                            addView(c.text(value, 11f, C.SENSOR, condensed = true).apply { maxLines = 1 })
                        }, c.lp(0, WRAP, 1f))
                    }
                    if (pair.size == 1) r.addView(View(c), c.lp(0, 1, 1f))
                    left.addView(r, c.lp(bottom = 2))
                }
            }
        }
        // dlaždice 3 × 2 (jako na kartě Zítra)
        val db = MeteoDb.get(c)
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nowMs = System.currentTimeMillis()
        val mn = db.extreme("tout", start, nowMs, false)
        val mx = db.extreme("tout", start, nowMs, true)
        val hm = SimpleDateFormat("H:mm", cz)
        val tiles = listOf(
            Triple("POCITOVĚ", t(w?.feelsLike), C.FEELS) to (todayF?.let { "dnes ${deg(it.feelsMax)} / ${deg(it.feelsMin)}" } ?: "rosný bod ${t(w?.dewPoint)}"),
            Triple("VÍTR", "${f1(w?.windAvg)} km/h", C.WIND) to ("nárazy ${f1(w?.windGust)}" + (todayF?.let { " · až ${it.gustMax.roundToInt()}" } ?: "")),
            Triple("SRÁŽKY DNES", "${f1(w?.rainDay)} mm", C.RAIN) to (w?.rainRate?.takeIf { it > 0 }?.let { "prší ${f1(it)} mm/h" }
                ?: todayF?.let { "předpověď ${f1(it.precip)} mm · ${it.prob.coerceAtLeast(0)} %" } ?: "neprší"),
            Triple("TLAK", (w?.pressure?.let { String.format(cz, "%.0f", it) } ?: "--") + " hPa$arrow", C.BARO) to (trend?.let { "za 3 h " + String.format(cz, "%+.1f", it) } ?: ""),
            Triple("UV", f1(w?.uv), C.UV) to (todayF?.let { "max ${f1(it.uvMax)} · svit ${f1(it.sunshineH)} h" } ?: ""),
            Triple("VENKU DNES", if (mn != null && mx != null) "${f1(mn.second)}° / ${f1(mx.second)}°" else "--", C.OUT) to
                (if (mn != null && mx != null) "↓ ${hm.format(Date(mn.first))} · ↑ ${hm.format(Date(mx.first))}" else ""),
        )
        val lgrid = c.vbox()
        tiles.chunked(3).forEachIndexed { ri, rowT ->
            val r = c.hbox(Gravity.TOP)
            rowT.forEachIndexed { k, (hd, sub) ->
                r.addView(c.vbox().apply {
                    background = rounded(C.CARD, c.dp(8).toFloat(), c.dp(1), C.LINE)
                    setPadding(c.dp(5), c.dp(2), c.dp(4), c.dp(2))
                    addView(c.label(hd.first).apply { textSize = 7f; maxLines = 1 })
                    addView(c.text(hd.second, 11.5f, hd.third, condensed = true).apply { maxLines = 1 })
                    addView(c.text(sub.ifEmpty { " " }, 7f, C.DIM).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, c.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f, end = if (k < 2) 4 else 0))
            }
            lgrid.addView(r, c.lp(h = 0, weight = 1f, bottom = if (ri == 0) 3 else 0))
        }
        left.addView(lgrid, c.lp(h = 0, weight = 1f, top = 4))
        if (p.lastError != null) left.addView(c.text(friendlyError(p.lastError, p.lastUpdate) ?: "", 9f, C.ACCENT), c.lp(top = 3))
        else if (p.lastUpdate > 0 && System.currentTimeMillis() - p.lastUpdate > 15 * 60_000)
            left.addView(c.text("⚠ data z " + java.text.SimpleDateFormat("HH:mm", cz).format(Date(p.lastUpdate)), 9f, C.ACCENT), c.lp(top = 3))
        }
        row.addView(left, c.lp(c.dp(255), c.dp(168), end = 8))
        val nowHour = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
        val hours = f?.hours?.filter { !it.time.isBefore(nowHour) } ?: emptyList()
        if (tomorrow) {
            row.addView(TvMorning.build(c, 168, today.plusDays(1)))
        } else if (morning) {
            row.addView(TvMorning.build(c, 168))
        } else {


        // vpravo: Dnes – svátek, kalendář, teď
        val right = panel()
        right.addView(c.label("DNES", C.BARO).apply { textSize = 9f })
        right.addView(c.text("Svátek: ${NameDays.forDate(c, today)}", 12f, C.WHITE, bold = true), c.lp(top = 2))
        right.addView(c.text("zítra: ${NameDays.forDate(c, today.plusDays(1))}", 9f, C.DIM))
        val evToday = CalendarIcs.events(c, today)
        val evs = if (evToday.isEmpty()) CalendarIcs.events(c, today.plusDays(1)).map { it to true } else evToday.map { it to false }
        if (CalendarIcs.url(c).isNotEmpty()) {
            right.addView(View(c).apply { setBackgroundColor(C.LINE) }, c.lp(h = 1, top = 4, bottom = 3))
            if (evs.isEmpty()) right.addView(c.text("Dnes ani zítra nic v kalendáři", 10f, C.DIM))
            else {
                if (evs.first().second) right.addView(c.text("Zítra:", 9f, C.ACCENT, bold = true))
                evs.take(6).forEach { (e, _) ->
                    val r = c.hbox(Gravity.TOP)
                    r.addView(c.text(e.time.ifEmpty { "celý den" }, 10f, C.ACCENT, condensed = true), c.lp(c.dp(38), WRAP))
                    r.addView(c.text(e.summary, 10f, C.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, c.lp(0, WRAP, 1f))
                    right.addView(r)
                }
                if (evs.size > 6) right.addView(c.text("+ další ${evs.size - 6}", 9f, C.DIM))
            }
        }
        // výstraha ČHMÚ (dříve v levém panelu)
        Alerts.load(c).firstOrNull { it.expires > System.currentTimeMillis() }?.let { al ->
            right.addView(c.text("⚠ ${al.event} · ${SimpleDateFormat("EEE HH:mm", cz).format(Date(al.onset))}–${SimpleDateFormat("HH:mm", cz).format(Date(al.expires))}",
                10f, al.color, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, c.lp(top = 4))
        }
        // teď
        hours.firstOrNull()?.let { h ->
            right.addView(View(c), c.lp(h = 0, weight = 1f))
            val r = c.hbox()
            val iv = if (animated) {
                if (animKey != h.icon || animView == null) { animView = c.animIcon(h.icon, 34); animKey = h.icon }
                (animView!!.parent as? android.view.ViewGroup)?.removeView(animView)
                animView!!
            } else c.icon(h.icon, 34)
            r.addView(iv)
            r.addView(c.vbox().apply {
                val day = f?.days?.firstOrNull { it.date == today }
                addView(c.text("${deg(h.temp)}  ${ForecastFull.describe(h.code)}", 12f, C.WHITE, bold = true))
                day?.let { addView(c.text("max ${deg(it.tMax)} / min ${deg(it.tMin)}", 9f, C.DIM)) }
            }, c.lp(0, WRAP, 1f, start = 6))
            right.addView(r)
        }
        row.addView(right, c.lp(0, c.dp(168), 1f))
        }
        root.addView(row)

        // ---------- dole: předpověď 12 h + 10 dní ----------
        val bottom = panel()
        val br = c.hbox(Gravity.TOP)
        val hcol = c.vbox()
        val tmr = today.plusDays(1)
        val shownHours = if (tomorrow) f?.hours?.filter { it.time.toLocalDate() == tmr && it.time.hour in 6..21 } ?: emptyList()
            else hours.take(12)
        hcol.addView(c.label(if (tomorrow) "ZÍTRA 6–21 H" else "PŘÍŠTÍCH 12 HODIN", C.BARO).apply { textSize = 9f })
        val hr = c.hbox(Gravity.TOP)
        shownHours.forEachIndexed { i, h ->
            if (i > 0) hr.addView(sep(), c.lp(1, LinearLayout.LayoutParams.MATCH_PARENT))
            hr.addView(c.vbox(Gravity.CENTER_HORIZONTAL).apply {
                // zítra 16 hodin – užší buňky
                addView(c.text(if (tomorrow) "${h.time.hour}" else "${h.time.hour}:00", 9f, C.LABEL), c.lp(WRAP, WRAP))
                addView(c.icon(h.icon, if (tomorrow) 17 else 24), c.lp(WRAP, WRAP, top = 1))
                addView(c.text(deg(h.temp), if (tomorrow) 10f else 12f, C.WHITE, condensed = true), c.lp(WRAP, WRAP))
                addView(c.text(if (h.precip >= 0.1) f1(h.precip) else " ", if (tomorrow) 7f else 8f, C.RAIN), c.lp(WRAP, WRAP))
            }, c.lp(0, WRAP, 1f))
        }
        hcol.addView(hr, c.lp(top = 4))
        br.addView(hcol, c.lp(0, WRAP, 1f))
        br.addView(View(c).apply { setBackgroundColor(C.LINE) }, c.lp(1, c.dp(80), start = 6, end = 6))
        val dcol = c.vbox()
        dcol.addView(c.label("10 DNÍ", C.BARO).apply { textSize = 9f })
        val dr = c.hbox(Gravity.TOP)
        val days = f?.days?.filter { !it.date.isBefore(today) }?.take(10) ?: emptyList()
        days.forEachIndexed { i, d ->
            if (i > 0) dr.addView(sep(), c.lp(1, LinearLayout.LayoutParams.MATCH_PARENT))
            val name = when (d.date) {
                today -> "Dnes"
                else -> d.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, cz).take(2).replaceFirstChar { it.uppercase() }
            }
            dr.addView(c.vbox(Gravity.CENTER_HORIZONTAL).apply {
                addView(c.text(name, 9f, C.LABEL), c.lp(WRAP, WRAP))
                addView(c.icon(d.icon, 24), c.lp(WRAP, WRAP, top = 1))
                addView(c.text(deg(d.tMax), 11f, C.IN, condensed = true), c.lp(WRAP, WRAP))
                addView(c.text(deg(d.tMin), 10f, 0xFF8FB3FF.toInt(), condensed = true), c.lp(WRAP, WRAP))
            }, c.lp(0, WRAP, 1f))
        }
        dcol.addView(dr, c.lp(top = 4))
        br.addView(dcol, c.lp(0, WRAP, 1f))
        bottom.addView(br)
        if (interactive) {
            bottom.addView(c.text((if (MorningPrefs(c).scheduled()) "← → záložky (Dnes / Zítra se přepnou hned) · OK otevřít, na Dnes ranní / běžný pohled · ↑ ↓ jas" else "← → záložky (Dnes / Zítra se přepnou hned) · OK otevřít · ↑ ↓ jas · Menu = nastavení"), 8f, 0x66FFFFFF), c.lp(top = 2))
        }
        root.addView(bottom, c.lp(h = 0, weight = 1f, top = 8))
        return root
    }
}
