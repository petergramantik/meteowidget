package cz.gramantik.meteowidget.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cz.gramantik.meteowidget.WidgetRenderer

/** Barvy aplikace – stejné jako LCD widget. */
object C {
    const val BG = 0xFF060709.toInt()
    const val CARD = 0xFF101317.toInt()
    const val CARD2 = 0xFF171B21.toInt()
    const val LINE = 0x33FFFFFF
    const val WHITE = 0xFFFFFFFF.toInt()
    const val DIM = 0x99FFFFFF.toInt()
    const val LABEL = 0xB3FFFFFF.toInt()
    const val OUT = 0xFF3DDC97.toInt()
    const val IN = 0xFFF5B88A.toInt()
    const val WIND = 0xFFCFE3FF.toInt()
    const val DIR = 0xFFFF6B6B.toInt()
    const val FEELS = 0xFFB8C8FF.toInt()
    const val UV = 0xFFDCCBF2.toInt()
    const val LIGHT = 0xFFB8F0C8.toInt()
    const val BARO = 0xFFD4F05A.toInt()
    const val RAIN = 0xFF2E9BFF.toInt()
    const val SENSOR = 0xFF5FD3C8.toInt()
    const val ACCENT = 0xFFFFD54F.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.text(
    s: CharSequence, size: Float = 14f, color: Int = C.WHITE,
    bold: Boolean = false, condensed: Boolean = false,
): TextView = TextView(this).apply {
    text = s
    textSize = size
    setTextColor(color)
    typeface = Typeface.create(if (condensed) "sans-serif-condensed" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    includeFontPadding = false
}

/** Popisek ve stylu LCD (velká písmena, mezery). */
fun Context.label(s: String, color: Int = C.LABEL): TextView =
    text(s.uppercase(), 11f, color, bold = true).apply { letterSpacing = 0.08f }

fun Context.vbox(gravity: Int = Gravity.START): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    this.gravity = gravity
}

fun Context.hbox(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

/** Karta s nadpisem. */
fun Context.card(title: String? = null, titleColor: Int = C.LABEL): LinearLayout = vbox().apply {
    background = rounded(C.CARD, dp(16).toFloat(), dp(1), C.LINE)
    setPadding(dp(14), dp(12), dp(14), dp(14))
    if (title != null) addView(label(title, titleColor), lp(bottom = 8))
}

fun Context.lp(
    w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight).apply {
    topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end)
}

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** Přepínací „čip“. */
fun Context.chip(s: String, selected: Boolean, onClick: () -> Unit): TextView =
    text(s, 13f, if (selected) C.BG else C.WHITE, bold = selected).apply {
        background = rounded(if (selected) C.ACCENT else C.CARD2, dp(14).toFloat(), dp(1), if (selected) C.ACCENT else C.LINE)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
        tvFocus(if (selected) C.ACCENT else C.LINE, 1.08f)
    }

fun Context.chipRow(vararg chips: View): HorizontalScrollView = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    val row = hbox()
    chips.forEach { row.addView(it, lp(WRAP, WRAP, end = 6)) }
    addView(row)
}

fun Context.divider(): View = View(this).apply { setBackgroundColor(C.LINE) }

/** Statická ikona Meteocons. */
fun Context.icon(key: String, sizeDp: Int): ImageView = ImageView(this).apply {
    val res = WidgetRenderer.iconRes(this@icon, key)
    if (res != 0) setImageResource(res)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Animovaná ikona Meteocons (SVG ve WebView). */
@SuppressLint("SetJavaScriptEnabled")
fun Context.animIcon(key: String, sizeDp: Int): View {
    val res = WidgetRenderer.iconRes(this, key)
    if (res == 0) return icon(key, sizeDp)
    val wv = WebView(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        settings.javaScriptEnabled = false
        val html = "<html><body style='margin:0;background:transparent;overflow:hidden'>" +
            "<img src='file:///android_asset/meteocons/$key.svg' style='width:100%;height:100%'></body></html>"
        loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    }
    return FrameLayout(this).apply {
        addView(wv, FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}

/** Dlaždice s popiskem a hodnotou. */
fun Context.tile(title: String, value: String, color: Int, sub: String? = null): LinearLayout = vbox().apply {
    background = rounded(C.CARD, dp(14).toFloat(), dp(1), C.LINE)
    setPadding(dp(12), dp(10), dp(12), dp(10))
    addView(label(title))
    addView(text(value, 24f, color, condensed = true), lp(top = 4))
    if (sub != null) addView(text(sub, 12f, C.DIM), lp(top = 2))
}

/** Mřížka dlaždic po dvou. */
fun Context.grid(tiles: List<View>, cols: Int = 2): LinearLayout = vbox().apply {
    tiles.chunked(cols).forEach { rowTiles ->
        val row = hbox(Gravity.TOP)
        rowTiles.forEachIndexed { i, t ->
            row.addView(t, lp(0, MATCH, 1f, end = if (i < cols - 1) 8 else 0))
        }
        repeat(cols - rowTiles.size) { row.addView(View(this@grid), lp(0, 1, 1f)) }
        addView(row, lp(bottom = 8))
    }
}


/**
 * Výrazné zvýraznění prvku vybraného ovladačem (TV): bílý rámeček / žlutý podklad a mírné zvětšení.
 * Zachová případný dřívější posluchač focusu.
 */
fun View.tvFocus(restoreStroke: Int = C.LINE, scale: Float = 1.05f, light: Boolean = false): View {
    isFocusable = true
    val d = resources.displayMetrics.density
    val origBg = background
    val old = onFocusChangeListener
    setOnFocusChangeListener { v, has ->
        old?.onFocusChange(v, has)
        v.scaleX = if (has) scale else 1f
        v.scaleY = if (has) scale else 1f
        val bg = v.background
        when {
            // světlé pozadí (nastavení): tenká tmavě šedá linka
            bg is GradientDrawable && light -> bg.setStroke(((if (has) 2 else 1) * d).toInt(), if (has) 0xFF505050.toInt() else restoreStroke)
            bg is GradientDrawable -> bg.setStroke(((if (has) 3 else 1) * d).toInt(), if (has) Color.WHITE else restoreStroke)
            has -> v.background = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    origBg ?: android.graphics.drawable.ColorDrawable(0),
                    GradientDrawable().apply {
                        cornerRadius = 6 * d
                        if (light) {
                            setColor(0x14000000)
                            setStroke((2 * d).toInt(), 0xFF505050.toInt())
                        } else {
                            setColor(0x40FFC107)
                            setStroke((3 * d).toInt(), 0xFFFFC107.toInt())
                        }
                    }
                )
            )
            else -> v.background = origBg
        }
    }
    return this
}

/**
 * Běží aplikace na televizi? Systémový TV režim / Android TV, nebo zařízení bez dotykové
 * obrazovky s displejem na šířku (TV boxy, které se jako televize nehlásí – např. O2 TV Box).
 */
fun Context.isTv(): Boolean {
    val ui = (getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager).currentModeType
    if (ui == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = packageManager
    if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return true
    @Suppress("DEPRECATION")
    if (pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION)) return true
    val noTouch = !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) ||
        resources.configuration.touchscreen == android.content.res.Configuration.TOUCHSCREEN_NOTOUCH
    val dm = resources.displayMetrics
    return noTouch && dm.widthPixels >= dm.heightPixels
}
