package cz.gramantik.meteowidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Datová řada grafu: časy (ms) a hodnoty (NaN = mezera). */
class Series(
    val name: String,
    val color: Int,
    val times: LongArray,
    val values: DoubleArray,
    val bars: Boolean = false,
    val unit: String = "",
    /** vlastní osa (např. srážky vedle teploty) */
    val ownAxis: Boolean = false,
)

/**
 * Jednoduchý časový graf: čáry a sloupce, mřížka, popisky os,
 * dotykem se ukáže svislá čára s hodnotami.
 */
class ChartView(ctx: Context) : View(ctx) {
    var series: List<Series> = emptyList()
        set(v) { field = v; cursorTime = null; invalidate() }

    /** Čas svislého kurzoru (sdílí se mezi grafy pod sebou). */
    var cursorTime: Long? = null
        set(v) { field = v; invalidate() }

    /** Volá se při posunu prstu – ostatní grafy nastaví stejný čas. */
    var onCursor: ((Long?) -> Unit)? = null

    /** Nadpis v levém horním rohu grafu. */
    var title: String? = null

    // geometrie z posledního vykreslení (pro převod dotyku na čas)
    private var gLeft = 0f
    private var gRight = 0f
    private var gT0 = 0L
    private var gT1 = 1L

    /** Volitelné vodorovné pásmo (např. doporučená vlhkost 40–60 %). */
    var band: Pair<Double, Double>? = null
        set(v) { field = v; invalidate() }
    private val bandPaint = Paint().apply { color = BAND_COLOR }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private val d = resources.displayMetrics.density
    private val grid = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); textSize = 10f * d
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursor = Paint().apply { color = 0x88FFFFFF.toInt(); strokeWidth = 1f * d }
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE1B2027.toInt() }
    private val tipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d; color = 0xFFFFFFFF.toInt() }

    init {
        minimumHeight = (200 * d).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize((210 * d).toInt(), heightMeasureSpec))
    }

    // směr gesta: 0 = zatím neurčen, 1 = vodorovně (kurzor), 2 = svisle (posun stránky)
    private var downX = 0f
    private var downY = 0f
    private var gesture = 0
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

    private fun cursorAt(x: Float) {
        if (gRight <= gLeft) return
        val t = gT0 + ((x.coerceIn(gLeft, gRight) - gLeft) / (gRight - gLeft) * (gT1 - gT0)).toLong()
        cursorTime = t
        onCursor?.invoke(t)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; gesture = 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture == 0) {
                    val dx = abs(e.x - downX)
                    val dy = abs(e.y - downY)
                    if (dx > slop || dy > slop) {
                        gesture = if (dx > dy) 1 else 2
                        if (gesture == 1) parent?.requestDisallowInterceptTouchEvent(true)
                        else return false // svislý pohyb nechat stránce
                    }
                }
                if (gesture == 1) cursorAt(e.x)
            }
            MotionEvent.ACTION_UP -> {
                if (gesture == 0) cursorAt(e.x) // krátké klepnutí
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun range(list: List<Series>): Pair<Double, Double>? {
        val v = list.flatMap { s -> s.values.filter { !it.isNaN() } }
        if (v.isEmpty()) return null
        var lo = v.min(); var hi = v.max()
        if (list.any { it.bars }) lo = min(0.0, lo)
        if (hi - lo < 1e-6) { hi += 1; lo -= if (lo == 0.0) 0.0 else 1.0 }
        val step = niceStep((hi - lo) / 4)
        return floor(lo / step) * step to ceil(hi / step) * step
    }

    private fun niceStep(raw: Double): Double {
        val exp = Math.pow(10.0, floor(Math.log10(raw)))
        val f = raw / exp
        return exp * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
    }

    override fun onDraw(c: Canvas) {
        val all = series.filter { it.times.isNotEmpty() }
        if (all.isEmpty()) {
            val msg = "Zatím žádná data"
            c.drawText(msg, (width - axisText.measureText(msg)) / 2, height / 2f, axisText)
            return
        }
        val main = all.filter { !it.ownAxis }
        val second = all.filter { it.ownAxis }
        var r1 = range(main.ifEmpty { all }) ?: return
        band?.let { b -> r1 = minOf(r1.first, b.first) to maxOf(r1.second, b.second) }
        val r2 = range(second)

        val left = 34f * d
        val right = width - if (r2 != null) 30f * d else 8f * d
        val top = 8f * d
        val bottom = height - 18f * d
        val t0 = all.minOf { it.times.first() }
        val t1 = all.maxOf { it.times.last() }.let { if (it == t0) t0 + 1 else it }
        fun x(t: Long) = left + (t - t0).toFloat() / (t1 - t0) * (right - left)
        gLeft = left; gRight = right; gT0 = t0; gT1 = t1
        fun y(v: Double, r: Pair<Double, Double>) = (bottom - (v - r.first) / (r.second - r.first) * (bottom - top)).toFloat()

        band?.let { b -> c.drawRect(left, y(b.second, r1), right, y(b.first, r1), bandPaint) }

        // mřížka a osa Y
        val steps = 4
        for (i in 0..steps) {
            val v = r1.first + (r1.second - r1.first) * i / steps
            val yy = y(v, r1)
            c.drawLine(left, yy, right, yy, grid)
            c.drawText(fmt(v), 2f * d, yy + 4f * d, axisText)
            if (r2 != null) {
                val v2 = r2.first + (r2.second - r2.first) * i / steps
                c.drawText(fmt(v2), right + 4f * d, yy + 4f * d, axisText)
            }
        }
        // osa X
        val span = t1 - t0
        val pattern = when {
            span <= 36 * 3_600_000L -> "HH:mm"
            span <= 4 * 86_400_000L -> "EEE HH"
            else -> "d.M."
        }
        val df = SimpleDateFormat(pattern, Locale("cs", "CZ"))
        val labels = 5
        for (i in 0..labels) {
            val t = t0 + span * i / labels
            val s = df.format(Date(t))
            val tw = axisText.measureText(s)
            c.drawText(s, (x(t) - tw / 2).coerceIn(0f, width - tw), height - 3f * d, axisText)
        }

        // data
        for (s in all) {
            val r = if (s.ownAxis && r2 != null) r2 else r1
            if (s.bars) {
                bar.color = s.color
                val bw = max(2f * d, (right - left) / s.times.size * 0.7f)
                for (i in s.times.indices) {
                    val v = s.values[i]
                    if (v.isNaN() || v <= 0) continue
                    val xx = x(s.times[i])
                    c.drawRect(xx - bw / 2, y(v, r), xx + bw / 2, y(max(0.0, r.first), r), bar)
                }
            } else {
                line.color = s.color
                val p = Path()
                var started = false
                var prevT = 0L
                val gap = max(3 * 3_600_000L, span / 40)
                dot.color = s.color
                val n = s.times.size
                fun connected(i: Int, j: Int) = j in 0 until n && !s.values[j].isNaN() && abs(s.times[j] - s.times[i]) <= gap
                for (i in s.times.indices) {
                    val v = s.values[i]
                    if (v.isNaN()) { started = false; continue }
                    val xx = x(s.times[i]); val yy = y(v, r)
                    if (!started || s.times[i] - prevT > gap) p.moveTo(xx, yy) else p.lineTo(xx, yy)
                    started = true; prevT = s.times[i]
                    // osamocený bod (bez sousedů) by čára nevykreslila -> tečka
                    if (!connected(i, i - 1) && !connected(i, i + 1)) c.drawCircle(xx, yy, 3f * d, dot)
                }
                c.drawPath(p, line)
            }
        }

        title?.let { c.drawText(it, left + 4f * d, top + 10f * d, axisText) }

        // kurzor – hodnoty v daném čase
        val ct = cursorTime
        val touchX = if (ct != null && ct in t0..t1) x(ct) else -1f
        if (ct != null && touchX >= left) {
            c.drawLine(touchX, top, touchX, bottom, cursor)
            val t = ct
            val rows = ArrayList<Pair<String, Int>>()
            rows.add(SimpleDateFormat("EEE d.M. HH:mm", Locale("cs", "CZ")).format(Date(t)) to 0xFFFFFFFF.toInt())
            val near = max(20 * 60_000L, span / 40)
            for (s in all) {
                var bi = -1; var bd = Long.MAX_VALUE
                for (i in s.times.indices) {
                    val dd = abs(s.times[i] - t)
                    if (dd < bd && !s.values[i].isNaN()) { bd = dd; bi = i }
                }
                if (bi >= 0 && bd <= near) rows.add("${s.name}: ${fmt(s.values[bi])} ${s.unit}".trim() to s.color)
            }
            val lh = 15f * d
            val tw = rows.maxOf { tipText.measureText(it.first) } + 12f * d
            val th = rows.size * lh + 8f * d
            val tx = if (touchX + tw + 8f * d < right) touchX + 6f * d else touchX - tw - 6f * d
            c.drawRoundRect(tx, top, tx + tw, top + th, 8f * d, 8f * d, tipBg)
            rows.forEachIndexed { i, (s, col) ->
                tipText.color = col
                c.drawText(s, tx + 6f * d, top + 4f * d + (i + 1) * lh - 3f * d, tipText)
            }
        }
    }

    private fun fmt(v: Double): String =
        if (abs(v) >= 100 || v == floor(v)) String.format(Locale("cs", "CZ"), "%.0f", v)
        else String.format(Locale("cs", "CZ"), "%.1f", v)
}

/** Barva pásma komfortní zóny (poloprůhledná zelená). */
const val BAND_COLOR = 0x333DDC97
/** Stejný odstín, jak vypadá pásmo na tmavé kartě (pro legendu). */
const val BAND_SWATCH = 0xFF1F5543.toInt()
/** Čitelná tmavší zelená pro text legendy. */
const val BAND_TEXT = 0xFF2FA57A.toInt()
