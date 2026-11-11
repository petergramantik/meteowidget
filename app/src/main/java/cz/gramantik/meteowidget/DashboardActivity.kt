package cz.gramantik.meteowidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import cz.gramantik.meteowidget.ui.AlertsScreen
import cz.gramantik.meteowidget.ui.ForecastScreen
import cz.gramantik.meteowidget.ui.RadarScreen
import cz.gramantik.meteowidget.ui.StationScreen
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast

/**
 * Celoobrazovkový panel pro televizi (O2 TV Box, Google TV).
 * OK / Menu = nastavení, šipky nahoru/dolů = jas, šipka doprava = obnovit.
 */
class DashboardActivity : Activity() {
    private lateinit var panel: TvPanel
    private var toast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // při spuštění budíkem rozsvítit obrazovku i z pohotovosti
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        TvSchedule.running = java.lang.ref.WeakReference(this)
        TvSchedule.reschedule(this)
        panel = TvPanel(this, interactive = true)
        panel.home.onTab = { openTab(it) }
        panel.contentHasFocus = { cardHost.visibility == View.VISIBLE && cardHost.hasFocus() }
        panel.onIdleReturn = { showCard(TvHome.TAB_TODAY) }
        // vnořené karty (Předpověď, Radar, Historie, Upozornění) pod horním menu
        cardHost = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.GONE
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val host = FrameLayout(this)
        host.addView(panel.root, FrameLayout.LayoutParams(-1, -1))
        host.addView(cardHost, FrameLayout.LayoutParams(-1, -1))
        setContentView(host)
        panel.showHint("← → karty  ·  ↓ do obsahu, ↑ zpět do menu  ·  OK obnovit")
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
        armAutoClose(intent)
        if (!Prefs(this).isConfigured) {
            show("Nejdřív zadej údaje z Tuya – otevírám nastavení")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        panel.start()
        cards[panel.home.activeTab]?.refresh()
    }

    override fun onPause() {
        panel.stop()
        super.onPause()
    }

    override fun onDestroy() {
        if (TvSchedule.running?.get() === this) TvSchedule.running = null
        countdownHandler.removeCallbacks(countdown)
        cards.values.forEach { it.destroy() }
        panel.release()
        super.onDestroy()
    }

    private fun show(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    // ---------- automatické zavření po spuštění budíkem ----------
    private var autoCloseAt = 0L
    private val countdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val countdown = object : Runnable {
        override fun run() {
            if (autoCloseAt == 0L) { panel.setCountdown(null); return }
            val left = autoCloseAt - System.currentTimeMillis()
            if (left <= 0) {
                panel.setCountdown(null)
                autoCloseAt = 0L
                // uspat box (zhasnutí obrazovky) – jinak by se ukázalo hlavní rozhraní boxu
                if (SleepAdmin.enabled(this@DashboardActivity) && !SleepAdmin.sleepNow(this@DashboardActivity)) {
                    // nejde uspat přímo -> černá obrazovka, box usne podle svého časovače
                    startActivity(android.content.Intent(this@DashboardActivity, BlackActivity::class.java))
                }
                finish() // jinak box usne podle svého nastavení (a přes HDMI-CEC zhasne TV)
                return
            }
            val s = (left / 1000).toInt()
            panel.setCountdown(if (left <= 5 * 60_000L) String.format("⏻ Vypnu za %d:%02d", s / 60, s % 60) else null)
            countdownHandler.postDelayed(this, 1000)
        }
    }

    private fun armAutoClose(i: Intent?) {
        val min = when {
            i == null -> 0
            i.hasExtra(EXTRA_AUTOCLOSE_MIN) -> i.getIntExtra(EXTRA_AUTOCLOSE_MIN, 0)
            i.getBooleanExtra(EXTRA_SCHEDULED, false) -> TvSchedule.autoCloseMin(this)
            else -> 0
        }
        if (min <= 0) return
        autoCloseAt = System.currentTimeMillis() + min * 60_000L
        countdownHandler.removeCallbacks(countdown)
        countdownHandler.post(countdown)
        panel.showHint("Panel se sám zavře za $min min · stiskem ovladače to zrušíš")
    }

    private fun cancelAutoClose() {
        if (autoCloseAt == 0L) return
        autoCloseAt = 0L
        countdownHandler.removeCallbacks(countdown)
        panel.setCountdown(null)
        show("Automatické vypnutí zrušeno")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        armAutoClose(intent)
    }

    companion object {
        const val EXTRA_SCHEDULED = "scheduled"
        const val EXTRA_AUTOCLOSE_MIN = "autoCloseMin"
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    // ---------- vnořené karty ----------
    private lateinit var cardHost: FrameLayout
    private val cards = HashMap<Int, MainActivity.Screen>()
    private val cardHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingCard: Runnable? = null
    private fun isCard(i: Int) = i == TvHome.TAB_FORECAST || i == TvHome.TAB_RADAR || i == TvHome.TAB_HISTORY || i == TvHome.TAB_ALERTS

    /** Zobrazí kartu podle záložky (Dnes/Zítra = TV panel, ostatní vnořené obrazovky). */
    fun showCard(i: Int) {
        pendingCard?.let { cardHandler.removeCallbacks(it) }
        if (i == TvHome.TAB_SETTINGS) return
        panel.home.activeTab = i
        if (!isCard(i)) {
            // skrýt kartu – focus nesmí zůstat v ní
            if (cardHost.hasFocus()) panel.home.tabViews.getOrNull(i)?.requestFocus()
            cardHost.visibility = View.GONE
            val wantTomorrow = i == TvHome.TAB_TOMORROW
            panel.home.tomorrow = wantTomorrow
            panel.render()
            return
        }
        // krátká prodleva, aby rychlé přejetí šipkou nic zbytečně nestavělo
        val r = Runnable {
            val s = cards[i] ?: when (i) {
                TvHome.TAB_FORECAST -> ForecastScreen(this)
                TvHome.TAB_RADAR -> RadarScreen(this)
                TvHome.TAB_HISTORY -> StationScreen(this)
                else -> AlertsScreen(this)
            }.also { cards[i] = it }
            if (i == TvHome.TAB_ALERTS) { s.refresh(); NotifyLog.markAllRead(this) }
            if (cardHost.getChildAt(0) !== s.view) {
                cardHost.removeAllViews()
                (s.view.parent as? android.view.ViewGroup)?.removeView(s.view)
                cardHost.addView(s.view, FrameLayout.LayoutParams(-1, -1))
            }
            placeCardHost()
            cardHost.visibility = View.VISIBLE
            panel.home.restyleTabs() // zvýraznění aktivní záložky (bez překreslení – focus zůstane v menu)
        }
        pendingCard = r
        cardHandler.postDelayed(r, 250)
    }

    /** Karta začíná pod řádkem horního menu. */
    private fun placeCardHost() {
        val tab = panel.home.tabViews.firstOrNull() ?: return
        val loc = IntArray(2).also { tab.getLocationOnScreen(it) }
        val hostLoc = IntArray(2).also { (cardHost.parent as View).getLocationOnScreen(it) }
        val d = resources.displayMetrics.density
        val top = (loc[1] - hostLoc[1] + tab.height * tab.scaleY * (tab.parent.parent as View).scaleY + 6 * d).toInt()
        val side = (resources.displayMetrics.widthPixels * 0.03f).toInt()
        val lp = cardHost.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top) {
            lp.topMargin = top.coerceAtLeast(0)
            cardHost.layoutParams = lp
        }
        cardHost.setPadding(side, 0, side, (resources.displayMetrics.heightPixels * 0.03f).toInt())
    }

    private fun focusTabs() {
        panel.home.tabViews.getOrNull(panel.focusedTab)?.requestFocus()
    }

    /** Volá radar (stránka) na okraji lišty: up = do menu, left/right = vedlejší karta. */
    fun fromContent(dir: String) {
        when (dir) {
            "up" -> focusTabs()
            "left", "right" -> moveTab(if (dir == "right") 1 else -1, keepContentFocus = false)
        }
    }

    private fun moveTab(delta: Int, keepContentFocus: Boolean = false) {
        val tabs = panel.home.tabViews
        if (tabs.isEmpty()) return
        val next = (panel.focusedTab + delta).coerceIn(0, tabs.size - 1)
        panel.focusedTab = next
        tabs[next].requestFocus()
        showCard(next)
    }

    /** OK na záložce. */
    private fun openTab(i: Int) {
        when (i) {
            TvHome.TAB_TODAY -> { showCard(i); panel.refreshNow() }
            TvHome.TAB_TOMORROW -> { showCard(i); panel.refreshNow() }
            TvHome.TAB_SETTINGS -> openSettings()
            else -> {
                // vstup do obsahu karty
                showCard(i)
                cardHandler.postDelayed({ cardHost.requestFocus(View.FOCUS_DOWN) }, 300)
            }
        }
    }

    private fun sameRow(a: View, b: View): Boolean {
        val ra = IntArray(2).also { a.getLocationOnScreen(it) }
        val rb = IntArray(2).also { b.getLocationOnScreen(it) }
        val top = maxOf(ra[1], rb[1])
        val bottom = minOf(ra[1] + a.height, rb[1] + b.height)
        return bottom - top > minOf(a.height, b.height) / 2
    }

    /** Rolovací obal karty, ve kterém je daný prvek (nebo hlavní obal karty). */
    private fun scrollerOf(v: View?): android.widget.ScrollView? {
        var p: Any? = v
        while (p is View && p !== cardHost) { if (p is android.widget.ScrollView) return p; p = p.parent }
        return cardHost.getChildAt(0) as? android.widget.ScrollView
    }

    private fun isVisibleOnScreen(v: View): Boolean {
        val r = android.graphics.Rect()
        return v.getGlobalVisibleRect(r) && r.height() >= v.height / 2
    }

    private fun isInCard(v: View?): Boolean {
        var p: Any? = v
        while (p is View) { if (p === cardHost) return true; p = p.parent }
        return false
    }

    /** Ovládání ovladačem zachycené na úrovni okna. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        panel.lastInput = System.currentTimeMillis()
        cancelAutoClose()
        val tabs = panel.home.tabViews
        val inContent = cardHost.visibility == View.VISIBLE && cardHost.hasFocus()
        val f = currentFocus
        // Zpět z jakékoli karty -> karta Dnes
        if (event.keyCode == KeyEvent.KEYCODE_BACK && panel.home.activeTab != TvHome.TAB_TODAY) {
            panel.focusedTab = TvHome.TAB_TODAY
            showCard(TvHome.TAB_TODAY)
            tabs.getOrNull(TvHome.TAB_TODAY)?.requestFocus()
            return true
        }
        if (inContent) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (f is android.webkit.WebView) return super.dispatchKeyEvent(event)
                    val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
                    val next = f?.focusSearch(if (up) View.FOCUS_UP else View.FOCUS_DOWN)
                    // další prvek je vidět ve stejné kartě -> normální přesun focusu
                    if (next != null && isInCard(next) && isVisibleOnScreen(next)) return super.dispatchKeyEvent(event)
                    // jinak rolovat kartou; do menu až z úplného vrcholu
                    val sv = scrollerOf(f)
                    if (sv != null && sv.canScrollVertically(if (up) -1 else 1)) {
                        sv.smoothScrollBy(0, (if (up) -1 else 1) * sv.height / 3)
                        return true
                    }
                    if (up) { focusTabs(); return true }
                    if (next != null && isInCard(next)) return super.dispatchKeyEvent(event)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (f is android.webkit.WebView || f is android.widget.SeekBar) return super.dispatchKeyEvent(event)
                    val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val next = f?.focusSearch(if (dir > 0) View.FOCUS_RIGHT else View.FOCUS_LEFT)
                    if (f == null || next == null || !isInCard(next) || !sameRow(f, next)) { moveTab(dir); return true }
                }
            }
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { openSettings(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (cardHost.visibility == View.VISIBLE) {
                    // do obsahu karty
                    if (!cardHost.requestFocus(View.FOCUS_DOWN)) cardHost.getChildAt(0)?.requestFocus()
                    return true
                }
                val b = panel.changeBrightness(-5)
                show((if (panel.isNight) "Noční jas" else "Jas") + ": $b %")
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // jas jen na kartách Dnes a Zítra
                if (cardHost.visibility == View.VISIBLE) return true
                val b = panel.changeBrightness(5)
                show((if (panel.isNight) "Noční jas" else "Jas") + ": $b %")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (tabs.isEmpty()) return true
                val cur = tabs.indexOfFirst { it.isFocused }.let { if (it < 0) panel.focusedTab else it }
                val right = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                // jako na mobilu: z Dnes šipkou vlevo na Upozornění, z Upozornění vpravo zpět na Dnes
                val target = when {
                    cur == TvHome.TAB_TODAY && !right -> TvHome.TAB_ALERTS
                    cur == TvHome.TAB_ALERTS && right -> TvHome.TAB_TODAY
                    else -> null
                }
                if (target != null) {
                    panel.focusedTab = target
                    tabs.getOrNull(target)?.requestFocus()
                    showCard(target)
                    return true
                }
                panel.focusedTab = cur
                moveTab(if (right) 1 else -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                openTab(tabs.indexOfFirst { it.isFocused }.let { if (it < 0) panel.focusedTab else it })
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
