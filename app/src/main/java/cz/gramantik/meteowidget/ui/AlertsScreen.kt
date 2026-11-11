package cz.gramantik.meteowidget.ui

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import cz.gramantik.meteowidget.Alerts
import cz.gramantik.meteowidget.MainActivity
import cz.gramantik.meteowidget.NotifyLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Upozornění: aktivní výstrahy ČHMÚ + historie upozornění aplikace za 7 dní.
 * Otevírá se zvonečkem v liště nebo přejetím doprava na prvním panelu Stanice (není v dolním menu).
 */
class AlertsScreen(private val a: Activity) : MainActivity.Screen {
    private val cz = Locale("cs", "CZ")
    private val content = a.vbox().apply { setPadding(a.dp(14), a.dp(4), a.dp(14), a.dp(24)) }
    private val swipe = SwipeFrame(a) { dir -> (a as? MainActivity)?.swipe(dir) }.apply { addView(content) }
    override val view: View = ScrollView(a).apply { addView(swipe); isFillViewport = true }

    init { refresh() }

    override fun refresh() {
        content.removeAllViews()
        val now = System.currentTimeMillis()

        // ---------- výstrahy ČHMÚ ----------
        val alerts = Alerts.load(a).filter { it.expires > now }
        val ac = a.card("Výstrahy ČHMÚ")
        if (alerts.isEmpty()) ac.addView(a.text("Žádné platné výstrahy 🙂", 14f, C.WHITE), a.lp(top = 4))
        val tf = SimpleDateFormat("EEE d. M. HH:mm", cz)
        alerts.forEach { w ->
            val box = a.vbox().apply {
                background = rounded(0xFF0C0F13.toInt(), a.dp(10).toFloat(), a.dp(1), w.color)
                setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8))
            }
            box.addView(a.text("⚠ ${w.event}", 15f, w.color, bold = true))
            box.addView(a.text("${w.level} · ${tf.format(Date(w.onset))} – ${tf.format(Date(w.expires))}", 12f, C.DIM), a.lp(top = 2))
            val detail = a.text((w.description + if (w.instruction.isNotBlank()) "\n\n" + w.instruction else "").trim(), 13f, C.LABEL).apply {
                visibility = View.GONE
            }
            box.addView(detail, a.lp(top = 6))
            box.addView(a.text("Klepni pro podrobnosti", 11f, C.DIM).also { hint ->
                box.setOnClickListener {
                    val open = detail.visibility != View.VISIBLE
                    detail.visibility = if (open) View.VISIBLE else View.GONE
                    hint.text = if (open) "Klepni pro skrytí" else "Klepni pro podrobnosti"
                }
            }, a.lp(top = 4))
            box.isFocusable = true
            box.tvFocus(restoreStroke = w.color, scale = 1.0f)
            ac.addView(box, a.lp(top = 8))
        }
        content.addView(ac, a.lp(bottom = 10))

        // ---------- historie upozornění ----------
        val items = NotifyLog.load(a)
        val hc = a.card("Upozornění za 7 dní")
        if (items.isEmpty()) hc.addView(a.text("Zatím žádná upozornění", 14f, C.WHITE), a.lp(top = 4))
        val hf = SimpleDateFormat("HH:mm", cz)
        var lastDay = ""
        items.forEach { it ->
            val day = dayLabel(it.t)
            if (day != lastDay) {
                hc.addView(a.label(day, C.BARO), a.lp(top = 10, bottom = 2))
                lastDay = day
            }
            val row = a.hbox(Gravity.TOP).apply {
                setPadding(a.dp(4), a.dp(6), a.dp(4), a.dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener { _ -> open(it.id) }
                tvFocus(scale = 1.0f)
            }
            row.addView(a.text(hf.format(Date(it.t)), 12f, C.DIM, condensed = true), a.lp(a.dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(a.vbox().apply {
                addView(a.text(it.title, 14f, if (it.read) C.WHITE else C.ACCENT, bold = true))
                addView(a.text(it.text, 12.5f, C.LABEL), a.lp(top = 1))
            }, a.lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            hc.addView(row)
        }
        content.addView(hc, a.lp(bottom = 10))

        if (items.isNotEmpty()) {
            content.addView(a.chip("🗑 Smazat historii", false) {
                NotifyLog.clear(a)
                refresh()
            }.apply { tvFocus() }, a.lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        content.addView(a.text(if (a.isTv()) "Zpět nebo šipkou → na Dnes" else "Přejetím doleva zpět na Stanici", 11f, C.DIM), a.lp(top = 12))
    }

    /** Přechod na související místo podle druhu upozornění. */
    private fun open(id: Int) {
        val m = a as? MainActivity ?: return
        when (id) {
            1, 2, 3, 5 -> m.openTab(1) // mráz, déšť, vítr, UV -> předpověď
            in 100..Int.MAX_VALUE -> (view as ScrollView).smoothScrollTo(0, 0) // ČHMÚ -> nahoru
            else -> m.openTab(0) // tlak, vlhkost, stanice -> stanice
        }
    }

    private fun dayLabel(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val diff = ((today.apply { set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis -
            Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 12) }.timeInMillis) / 86_400_000L).toInt()
        return when (diff) {
            0 -> "DNES"
            1 -> "VČERA"
            else -> SimpleDateFormat("EEEE d. M.", cz).format(c.time).uppercase(cz)
        }
    }
}
