package cz.gramantik.meteowidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max

/** Historie tlaku (hPa) za posledních ~26 h, uložená v telefonu. */
object PressureHistory {
    private const val KEEP = 26 * 3_600_000L
    private const val HOUR = 3_600_000L

    /** Historie tlaku z hlavní databáze (stejná data jako v aplikaci, včetně doplnění z Tuya). */
    fun load(ctx: Context): List<Pair<Long, Double>> {
        val now = System.currentTimeMillis()
        return MeteoDb.get(ctx).range(now - KEEP, now, maxPoints = 400)
            .mapNotNull { s -> s.pres?.let { s.t to it } }
    }

    /** Dřív samostatná historie – teď se tlak ukládá jen do databáze. */
    @Suppress("UNUSED_PARAMETER")
    fun add(ctx: Context, points: List<Pair<Long, Double>>) = Unit

    /** Je historie krátká? (pak má smysl dotáhnout ji z Tuya) */
    fun needsBackfill(ctx: Context): Boolean {
        val h = load(ctx)
        return h.isEmpty() || System.currentTimeMillis() - h.first().first < 20 * HOUR
    }

    /** Změna tlaku za poslední 3 h (hPa), nebo null. */
    fun trend3h(ctx: Context): Double? {
        val h = load(ctx)
        if (h.size < 2) return null
        val now = h.last()
        val past = valueAt(h, now.first - 3 * HOUR) ?: return null
        return now.second - past
    }

    private fun valueAt(h: List<Pair<Long, Double>>, t: Long): Double? {
        val best = h.minByOrNull { abs(it.first - t) } ?: return null
        return if (abs(best.first - t) <= 45 * 60_000L) best.second else null
    }

    /**
     * Vykreslí graf ve stylu stanice: 25 sloupců (-24 h … teď), čtverečky ukazují
     * rozdíl tlaku oproti současnosti, tečkovaná mřížka.
     */
    fun render(ctx: Context, widthDp: Int = 340, heightDp: Int = 64): Bitmap {
        val d = 2f // pevné rozlišení (2 px/dp) – stačí a drží malou velikost bitmapy
        val w = (widthDp * d).toInt()
        val hgt = (heightDp * d).toInt()
        val bmp = Bitmap.createBitmap(w, hgt, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val baro = ctx.getColor(R.color.lcd_baro)
        val dim = 0x55FFFFFF.toInt()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            textSize = 9f * d
            typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
        }
        val dot = Paint().apply { color = dim }
        val sq = Paint().apply { color = baro }
        val zero = Paint().apply { color = 0x88FFFFFF.toInt(); strokeWidth = 1f * d }

        val h = load(ctx)
        val now = h.lastOrNull()
        val left = 22f * d
        val bottom = hgt - 11f * d
        val top = 3f * d
        val right = w - 4f * d
        val cols = 25
        val step = (right - left) / (cols - 1)

        val diffs = (0 until cols).map { k ->
            if (now == null) null
            else valueAt(h, now.first - (24 - k) * HOUR)?.let { it - now.second }
        }
        val maxAbs = diffs.filterNotNull().maxOfOrNull { abs(it) } ?: 0.0
        val range = listOf(2.0, 4.0, 6.0, 10.0, 20.0).firstOrNull { maxAbs <= it } ?: max(20.0, maxAbs)
        val midY = (top + bottom) / 2
        fun y(v: Double) = (midY - (v / range) * (bottom - top) / 2).toFloat()

        // tečkovaná mřížka
        for (k in 0 until cols) {
            val x = left + k * step
            var yy = top
            while (yy <= bottom) {
                c.drawRect(x - 0.6f * d, yy, x + 0.6f * d, yy + 0.8f * d, dot)
                yy += 4f * d
            }
        }
        c.drawLine(left - 2f * d, midY, right + 2f * d, midY, zero)

        // osa Y
        val r = if (range % 1.0 == 0.0) range.toInt().toString() else range.toString()
        c.drawText("+$r", 0f, top + 8f * d, text)
        c.drawText("0", 6f * d, midY + 3f * d, text)
        c.drawText("-$r", 0f, bottom, text)

        // osa X
        for ((k, label) in listOf(0 to "-24", 6 to "-18", 12 to "-12", 18 to "-6", 24 to "0 h")) {
            val x = left + k * step
            val tw = text.measureText(label)
            c.drawText(label, (x - tw / 2).coerceIn(0f, w - tw), hgt - 1f * d, text)
        }

        // čtverečky
        val half = minOf(step * 0.38f, 4f * d)
        diffs.forEachIndexed { k, v ->
            if (v == null) return@forEachIndexed
            val x = left + k * step
            val yy = y(v.coerceIn(-range, range))
            c.drawRect(x - half, yy - half, x + half, yy + half, sq)
        }

        if (h.size < 2) {
            val msg = "Historie se sbírá…"
            c.drawText(msg, (w - text.measureText(msg)) / 2, top + 10f * d, text)
        }
        return bmp
    }
}
