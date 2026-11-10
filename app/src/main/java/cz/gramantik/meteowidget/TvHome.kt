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
        val TABS = listOf("Dnes", "Zítra", "Předpověď", "Radar", "Historie", "⚙ Nastavení")
        const val TAB_TODAY = 0
        const val TAB_TOMORROW = 1
    }

    private val cz = Locale("cs", "CZ")
    val tabViews = ArrayList<TextView>()
    var onTab: ((Int) -> Unit)? = null
    /** pohled na zítřek (záložka Zítra) */
    var tomorrow = false
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
        val tmr = LocalDate.now().plusDays(1)
        if (d == null) {
            left.addView(c.text("Předpověď zatím není k dispozici", 12f, C.DIM), c.lp(top = 8))
            return
        }
        val top = c.hbox()
        top.addView(c.icon(d.icon, 50))
        top.addView(c.vbox().apply {
            addView(c.hbox(Gravity.BOTTOM).apply {
                addView(c.text(deg(d.tMax), 34f, C.IN, condensed = true))
                addView(c.text("  ${deg(d.tMin)}", 18f, 0xFF8FB3FF.toInt(), condensed = true))
            })
            addView(c.text(ForecastFull.describe(d.code), 11f, C.WHITE, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        }, c.lp(0, WRAP, 1f, start = 8))
        left.addView(top)
        fun line(s: String, color: Int = C.LABEL) =
            left.addView(c.text(s, 10.5f, color, condensed = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, c.lp(top = 3))
        line("Pocitově ${deg(d.feelsMax)} / ${deg(d.feelsMin)}", C.FEELS)
        line("💨 vítr až ${d.windMax.roundToInt()} km/h · nárazy ${d.gustMax.roundToInt()} · ${ForecastFull.windDir(d.dir)}", C.WIND)
        line("☔ srážky ${f1(d.precip)} mm · ${d.prob} %" + (if (d.snow >= 0.1) " · sníh ${f1(d.snow)} cm" else ""), C.RAIN)
        line("UV max ${f1(d.uvMax)} · slunce ${f1(d.sunshineH)} h", C.UV)
        val tf = java.time.format.DateTimeFormatter.ofPattern("H:mm")
        line("🌅 ${d.sunrise?.let { tf.format(it) } ?: "--"}   🌇 ${d.sunset?.let { tf.format(it) } ?: "--"}   ·  svátek: ${NameDays.forDate(c, tmr)}", C.LIGHT)
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
        if (interactive) {
            TABS.forEachIndexed { i, name ->
                val tv = c.text(name, 11f, C.WHITE, bold = true).apply {
                    setPadding(c.dp(10), c.dp(4), c.dp(10), c.dp(4))
                    background = rounded(C.CARD2, c.dp(12).toFloat(), c.dp(1), C.LINE)
                    isFocusable = true
                    isClickable = true
                    setOnClickListener { onTab?.invoke(i) }
                    // vybraná záložka: jen žlutá výplň (bez rámečku a zvětšení)
                    setOnFocusChangeListener { v, has ->
                        (v as TextView).setTextColor(if (has) C.BG else C.WHITE)
                        v.background = rounded(if (has) C.ACCENT else C.CARD2, c.dp(12).toFloat(), c.dp(1), if (has) C.ACCENT else C.LINE)
                    }
                }
                tabViews.add(tv)
                top.addView(tv, c.lp(WRAP, WRAP, end = 6))
            }
        }
        top.addView(View(c), c.lp(0, 1, 1f))
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
            addView(c.text(t(temp), if (hasSensors) 34f else 40f, color, condensed = true), c.lp(WRAP, WRAP, top = 2))
            addView(c.text(Weather.h(hum), if (hasSensors) 15f else 17f, color, condensed = true), c.lp(WRAP, WRAP))
        }
        lr.addView(col(p.channelName(Channel.IN), w?.tempIn, w?.humIn, C.IN, false), c.lp(0, WRAP, 1f))
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
        // dnešní min/max venku
        val db = MeteoDb.get(c)
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val mn = db.extreme("tout", start, now, false)
        val mx = db.extreme("tout", start, now, true)
        if (mn != null && mx != null) {
            val tf = SimpleDateFormat("HH:mm", cz)
            left.addView(c.text("venku dnes ↓ ${f1(mn.second)}° ${tf.format(Date(mn.first))}   ↑ ${f1(mx.second)}° ${tf.format(Date(mx.first))}", 9f, C.DIM), c.lp(top = 3))
        }
        if (!morning) Alerts.load(c).firstOrNull()?.let { al ->
            left.addView(c.text("⚠ ${al.event} · ${SimpleDateFormat("EEE HH:mm", cz).format(Date(al.onset))}–${SimpleDateFormat("HH:mm", cz).format(Date(al.expires))}", 10f, al.color, bold = true), c.lp(top = 3))
        }
        if (morning) {
            // ranní režim: dlaždice nahradí jeden řádek a svátek
            val sum = "💨 ${f1(w?.windAvg)} km/h · " +
                (w?.pressure?.let { String.format(cz, "%.0f", it) } ?: "--") + " hPa$arrow · UV ${f1(w?.uv)}"
            left.addView(c.text(sum, 9.5f, C.WIND, condensed = true), c.lp(top = 2))
        }
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

        // uprostřed: dlaždice 2×3
        val tiles = listOf(
            Triple("VÍTR", "${f1(w?.windAvg)} km/h", C.WIND) to (w?.windGust?.let { "nárazy ${f1(it)}" } ?: ""),
            Triple("SRÁŽKY DNES", "${f1(w?.rainDay)} mm", C.RAIN) to (w?.rainRate?.takeIf { it > 0 }?.let { "prší ${f1(it)} mm/h" } ?: "neprší"),
            Triple("TLAK", (w?.pressure?.let { String.format(cz, "%.0f", it) } ?: "--") + " hPa$arrow", C.BARO) to (trend?.let { "za 3 h " + String.format(cz, "%+.1f", it) } ?: ""),
            Triple("POCITOVĚ", t(w?.feelsLike), C.FEELS) to "rosný bod ${t(w?.dewPoint)}",
            Triple("UV", f1(w?.uv), C.UV) to "",
            Triple("SVĚTLO", "${f1(w?.lightKlux)} klux", C.LIGHT) to "",
        )
        val grid = c.vbox()
        tiles.chunked(2).forEachIndexed { ri, pair ->
            val r = c.hbox(Gravity.TOP)
            pair.forEachIndexed { i, (head, sub) ->
                val tile = c.vbox().apply {
                    background = rounded(C.CARD, c.dp(9).toFloat(), c.dp(1), C.LINE)
                    setPadding(c.dp(7), c.dp(4), c.dp(6), c.dp(4))
                    addView(c.label(head.first).apply { textSize = 8f })
                    addView(c.text(head.second, 15f, head.third, condensed = true), c.lp(WRAP, WRAP, top = 2))
                    addView(c.text(sub, 8f, C.DIM))
                }
                r.addView(tile, c.lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f, end = if (i == 0) 5 else 0))
            }
            // tři řady přesně na výšku sousedních panelů (168 dp)
            grid.addView(r, c.lp(h = 0, weight = 1f, bottom = if (ri < 2) 5 else 0))
        }
        row.addView(grid, c.lp(c.dp(170), c.dp(168), end = 8))

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
                evs.take(4).forEach { (e, _) ->
                    val r = c.hbox(Gravity.TOP)
                    r.addView(c.text(e.time.ifEmpty { "celý den" }, 10f, C.ACCENT, condensed = true), c.lp(c.dp(38), WRAP))
                    r.addView(c.text(e.summary, 10f, C.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, c.lp(0, WRAP, 1f))
                    right.addView(r)
                }
                if (evs.size > 4) right.addView(c.text("+ další ${evs.size - 4}", 9f, C.DIM))
            }
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
