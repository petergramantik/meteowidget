package cz.gramantik.meteowidget

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.Calendar
import kotlin.random.Random

/** Nastavení televizního panelu (jas, noční režim). */
class TvPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("meteo", Context.MODE_PRIVATE)

    /** jas ve dne 0–100 % */
    var dayBrightness: Int
        get() = sp.getInt("tvDay", 100)
        set(v) = sp.edit().putInt("tvDay", v.coerceIn(0, 100)).apply()

    /** jas v noci 0–100 % (0 = černá obrazovka) */
    var nightBrightness: Int
        get() = sp.getInt("tvNight", 8)
        set(v) = sp.edit().putInt("tvNight", v.coerceIn(0, 100)).apply()

    var nightFrom: Int
        get() = sp.getInt("tvNightFrom", 22)
        set(v) = sp.edit().putInt("tvNightFrom", v.coerceIn(0, 23)).apply()

    var nightTo: Int
        get() = sp.getInt("tvNightTo", 6)
        set(v) = sp.edit().putInt("tvNightTo", v.coerceIn(0, 23)).apply()

    /** animované ikony předpovědi (v noci se vždy vypnou) */
    var animatedIcons: Boolean
        get() = sp.getBoolean("tvAnimIcons", true)
        set(v) = sp.edit().putBoolean("tvAnimIcons", v).apply()

    var nightEnabled: Boolean
        get() = sp.getBoolean("tvNightOn", true)
        set(v) = sp.edit().putBoolean("tvNightOn", v).apply()

    fun isNight(): Boolean {
        if (!nightEnabled) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (nightFrom == nightTo) false
        else if (nightFrom < nightTo) h in nightFrom until nightTo
        else h >= nightFrom || h < nightTo
    }

    var current: Int
        get() = if (isNight()) nightBrightness else dayBrightness
        set(v) = if (isNight()) nightBrightness = v else dayBrightness = v
}

/**
 * Celoobrazovkový panel: vykreslí TV rozložení, zvětší ho na obrazovku,
 * pravidelně obnovuje data, ztlumuje jas a posouvá obsah proti vypálení.
 */
class TvPanel(private val ctx: Context, interactive: Boolean = false) {
    val home = TvHome(ctx, interactive)
    /** index záložky s focusem (obnoví se po překreslení) */
    var focusedTab = 0

    private val tv = TvPrefs(ctx)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var syncing = false
    private var shiftX = 0f
    private var shiftY = 0f
    private var lastShift = 0L

    private val content = FrameLayout(ctx)
    private val dim = View(ctx).apply { setBackgroundColor(Color.BLACK) }

    val root: FrameLayout = FrameLayout(ctx).apply {
        setBackgroundColor(Color.BLACK)
        addView(content, FrameLayout.LayoutParams(-1, -1))
        addView(dim, FrameLayout.LayoutParams(-1, -1))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
    }

    /** Krátká nápověda ovládání po spuštění. */
    fun showHint(text: String) {
        val d = ctx.resources.displayMetrics.density
        val hint = android.widget.TextView(ctx).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC171B21.toInt()); cornerRadius = 18 * d }
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (24 * d).toInt()
        })
        handler.postDelayed({ root.removeView(hint) }, 6_000)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            maybeSync()
            render()
            handler.postDelayed(this, 60_000)
        }
    }

    fun start() {
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    /** Uvolní obrazovku (volat při zavření). */
    fun release() {
        stop()
        content.removeAllViews()
    }

    fun render() {
        val animated = tv.animatedIcons && !tv.isNight()
        val view = home.build(animated)
        content.removeAllViews()
        content.addView(view)
        // focus zpět na stejnou záložku
        home.tabViews.forEachIndexed { i, t ->
            t.setOnFocusChangeListener(t.onFocusChangeListener.let { old ->
                android.view.View.OnFocusChangeListener { v, has -> old?.onFocusChange(v, has); if (has) focusedTab = i }
            })
        }
        home.tabViews.getOrNull(focusedTab)?.requestFocus()
        // posun obsahu o pár dp každých 10 minut
        val now = System.currentTimeMillis()
        if (now - lastShift > 10 * 60_000) {
            val d = ctx.resources.displayMetrics.density
            shiftX = Random.nextInt(-6, 7) * d
            shiftY = Random.nextInt(-4, 5) * d
            lastShift = now
        }
        applyBrightness()
        fit()
    }

    fun applyBrightness() {
        dim.alpha = 1f - tv.current / 100f
    }

    /** Změní jas aktuálního režimu (den / noc) o krok. Vrací nový jas. */
    fun changeBrightness(delta: Int): Int {
        tv.current = (tv.current + delta).coerceIn(0, 100)
        applyBrightness()
        return tv.current
    }

    val isNight get() = tv.isNight()

    /** Zvětší panel 640×360 dp na celou obrazovku se zachováním poměru stran. */
    private fun fit() {
        val child = content.getChildAt(0) ?: return
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0 || h <= 0) return
        val d = ctx.resources.displayMetrics.density
        val cw = 640 * d
        val ch = 360 * d
        val scale = minOf(w / cw, h / ch)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        child.translationX = (w - cw * scale) / 2 + shiftX
        child.translationY = (h - ch * scale) / 2 + shiftY
    }
}
