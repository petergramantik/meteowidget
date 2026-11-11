package cz.gramantik.meteowidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Srážkový pásek dne: 12 stejně širokých dílků po 2 hodinách (0–24 h).
 * V dílku 1–3 kapky (déšť) nebo vločky (sníh) podle intenzity, pod páskem 0 · 6 · 12 · 18.
 */
class RainStripView(ctx: Context) : View(ctx) {
    /** pro každý dílek: srážky celkem (mm) a z toho sníh (mm vody); NaN = bez dat */
    var rain = DoubleArray(12) { Double.NaN }
    var snow = DoubleArray(12)
    /** dílky před tímto indexem jsou už uplynulé (ztlumit) */
    var pastBefore = 0

    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF; strokeWidth = d }
    private val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4FA3FF.toInt(); style = Paint.Style.FILL }
    private val flake = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE3F2FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.3f * d; strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); textSize = 8f * d; textAlign = Paint.Align.CENTER }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val labelH = 10 * d
        val w = width.toFloat()
        val h = height - labelH
        val cw = w / 12f
        // dělicí linky
        for (i in 0..12) c.drawLine(i * cw, 0f, i * cw, h, line)
        for (i in 0 until 12) {
            val total = rain[i]
            if (total.isNaN() || total < 0.1) continue
            val alpha = if (i < pastBefore) 90 else 255
            val sn = snow[i].coerceAtMost(total)
            val n = when { total < 1.0 -> 1; total < 4.0 -> 2; else -> 3 }
            val isSnow = sn >= total * 0.6
            val mixed = !isSnow && sn >= 0.1
            val s = minOf(cw / (n * 2.4f), h * 0.32f, 5.5f * d) // velikost ikonky – vejde se i 3×
            val cy = h / 2
            for (k in 0 until n) {
                val cx = i * cw + cw / 2 + (k - (n - 1) / 2f) * s * 2.3f
                val asFlake = isSnow || (mixed && k == n - 1)
                if (asFlake) drawFlake(c, cx, cy, s, alpha) else drawDrop(c, cx, cy, s, alpha)
            }
        }
        for (hh in listOf(0, 6, 12, 18)) c.drawText(hh.toString(), (hh / 2) * cw + (if (hh == 0) 4 * d else 0f), height - 1 * d, label)
    }

    private fun drawDrop(c: Canvas, cx: Float, cy: Float, s: Float, a: Int) {
        drop.alpha = a
        path.reset()
        path.moveTo(cx, cy - s * 1.25f)
        path.cubicTo(cx + s * 0.9f, cy - s * 0.1f, cx + s, cy + s * 0.9f, cx, cy + s)
        path.cubicTo(cx - s, cy + s * 0.9f, cx - s * 0.9f, cy - s * 0.1f, cx, cy - s * 1.25f)
        c.drawPath(path, drop)
    }

    private fun drawFlake(c: Canvas, cx: Float, cy: Float, s: Float, a: Int) {
        flake.alpha = a
        for (k in 0 until 3) {
            val ang = Math.PI / 3 * k + Math.PI / 2
            val dx = (cos(ang) * s * 1.1).toFloat()
            val dy = (sin(ang) * s * 1.1).toFloat()
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, flake)
        }
    }
}
