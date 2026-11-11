package cz.gramantik.meteowidget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cz.gramantik.meteowidget.ui.AlertsScreen
import cz.gramantik.meteowidget.ui.C
import cz.gramantik.meteowidget.ui.ForecastScreen
import cz.gramantik.meteowidget.ui.RadarScreen
import cz.gramantik.meteowidget.ui.StationScreen
import cz.gramantik.meteowidget.ui.dp
import cz.gramantik.meteowidget.ui.hbox
import cz.gramantik.meteowidget.ui.isTv
import cz.gramantik.meteowidget.ui.tvFocus
import cz.gramantik.meteowidget.ui.lp
import cz.gramantik.meteowidget.ui.rounded
import cz.gramantik.meteowidget.ui.text
import cz.gramantik.meteowidget.ui.vbox

/** Hlavní aplikace: Stanice / Předpověď / Radar. */
class MainActivity : Activity() {

    interface Screen {
        val view: View
        fun refresh()
        fun destroy() {}
    }

    private lateinit var container: FrameLayout
    private val tabs = ArrayList<ImageView>()
    private var screens = arrayOfNulls<Screen>(4) // 3 = Upozornění (mimo dolní menu)
    private lateinit var badge: TextView
    private var current = 0
    private lateinit var title: TextView
    private lateinit var refreshBtn: ImageView

    /** Na televizi se bez konkrétní záložky otevře televizní panel. */
    private fun openTvPanelIfNeeded(i: Intent?): Boolean {
        if (!isTv() || i?.hasExtra(EXTRA_TAB) == true) return false
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && openTvPanelIfNeeded(intent)) return
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG

        val root = vbox().apply { setBackgroundColor(C.BG); fitsSystemWindows = true }

        // horní lišta
        val bar = hbox().apply { setPadding(dp(16), dp(10), dp(8), dp(6)) }
        title = text("Meteostanice", 20f, C.WHITE, bold = true)
        bar.addView(title, lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // zvoneček s počtem nepřečtených upozornění
        val bell = FrameLayout(this).apply {
            addView(barButton(R.drawable.ic_bell) { openTab(if (current == 3) 0 else 3) })
            badge = text("", 10f, C.BG, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(0xFFFF5252.toInt(), dp(8).toFloat())
                setPadding(dp(4), 0, dp(4), 0)
                minWidth = dp(16)
                visibility = View.GONE
            }
            addView(badge, FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); marginEnd = dp(4) })
        }
        bar.addView(bell)
        refreshBtn = barButton(R.drawable.ic_refresh) { refreshData() }
        bar.addView(refreshBtn)
        if (isTv()) bar.addView(barButton(R.drawable.ic_tv) { backToTvPanel() })
        bar.addView(barButton(R.drawable.ic_settings) { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(bar)

        container = FrameLayout(this)
        root.addView(container, lp(h = 0, weight = 1f))

        // spodní záložky
        val nav = hbox(Gravity.CENTER).apply {
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(0xFF0B0D10.toInt())
        }
        // záložky jen s piktogramy (název obrazovky je nahoře v liště)
        listOf(R.drawable.ic_tab_station, R.drawable.ic_tab_forecast, R.drawable.ic_tab_radar).forEachIndexed { i, icon ->
            val t = ImageView(this).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(9), dp(8), dp(9))
                contentDescription = listOf("Stanice", "Předpověď", "Radar")[i]
                setOnClickListener { show(i) }
                tvFocus(scale = 1.12f)
            }
            tabs.add(t)
            nav.addView(t, lp(0, dp(46), 1f, start = 10, end = 10))
        }
        root.addView(nav)
        setContentView(root)

        FetchWorker.schedulePeriodic(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        show(intent?.getIntExtra(EXTRA_TAB, 0) ?: 0)
        if (isTv()) tabs[current].post { tabs[current].requestFocus() } else askBatteryExemption()
        if (!Prefs(this).isConfigured) startActivity(Intent(this, SettingsActivity::class.java))
        else if (System.currentTimeMillis() - Prefs(this).lastUpdate > 5 * 60_000) refreshData()
    }

    /**
     * Samsung uspává aplikace mimo Google Play – bez výjimky z optimalizace baterie
     * v noci neběží sběr dat. Nabídne výjimku (nejvýš jednou za 3 dny).
     */
    private fun askBatteryExemption() {
        if (Battery.isUnrestricted(this)) return
        val sp = getSharedPreferences("meteo", MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("batteryAsk", 0) < 3 * 86_400_000L) return
        sp.edit().putLong("batteryAsk", System.currentTimeMillis()).apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("Sběr dat na pozadí")
            .setMessage(
                "Telefon aplikaci v noci uspává, a proto v historii chybí data. " +
                    "Povol prosím aplikaci běžet bez omezení baterie."
            )
            .setPositiveButton("Povolit") { _, _ -> Battery.requestExemption(this) }
            .setNegativeButton("Teď ne", null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (openTvPanelIfNeeded(intent)) return
        if (intent.hasExtra(EXTRA_TAB)) {
            show(intent.getIntExtra(EXTRA_TAB, 0))
            if (isTv()) tabs[current].requestFocus()
        }
    }

    private fun barButton(icon: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        setColorFilter(C.WHITE)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(42))
        setOnClickListener { onClick() }
        tvFocus(scale = 1.15f)
    }

    private fun show(i: Int) {
        current = i
        val tabIdx = if (i == 3) 0 else i // Upozornění patří ke Stanici
        tabs.forEachIndexed { j, t ->
            t.setColorFilter(if (j == tabIdx) C.BG else C.DIM)
            t.background = if (j == tabIdx) rounded(C.ACCENT, dp(18).toFloat()) else null
        }
        val s = screens[i] ?: when (i) {
            0 -> StationScreen(this)
            1 -> ForecastScreen(this)
            2 -> RadarScreen(this)
            else -> AlertsScreen(this)
        }.also { screens[i] = it }
        container.removeAllViews()
        container.addView(s.view)
        title.text = listOf("Stanice", "Předpověď", "Radar", "Upozornění")[i]
        if (i == 3) {
            s.refresh()
            NotifyLog.markAllRead(this)
        }
        updateBadge()
    }

    /** Otevře kartu (0–2) nebo stránku Upozornění (3). */
    fun openTab(i: Int) {
        show(i)
        container.alpha = 0.4f
        container.animate().alpha(1f).setDuration(150).start()
    }

    private fun updateBadge() {
        if (!::badge.isInitialized) return
        val n = NotifyLog.unread(this)
        badge.text = if (n > 99) "99+" else n.toString()
        badge.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    /**
     * TV: šipky ← → přepínají karty odkudkoli. Výjimka: posuvník a řada prvků vedle sebe –
     * tam se nejdřív posouvá výběr a karta se přepne až na okraji řady. Na Radaru řeší okraj stránka.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (isTv() && event.action == android.view.KeyEvent.ACTION_DOWN &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val dir = if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            val f = currentFocus
            when {
                f is android.webkit.WebView -> Unit // radar: lišta tlačítek ve stránce
                f is android.widget.SeekBar -> Unit
                f != null && f in tabs -> {
                    val t = (if (current == 3) 0 else current) + dir
                    if (t in 0..2) { show(t); tabs[t].requestFocus() }
                    return true
                }
                else -> {
                    val next = f?.focusSearch(if (dir > 0) View.FOCUS_RIGHT else View.FOCUS_LEFT)
                    if (f == null || next == null || !sameRow(f, next)) {
                        swipe(dir)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Leží dva prvky vedle sebe ve stejném řádku (svislý překryv)? */
    private fun sameRow(a: View, b: View): Boolean {
        val ra = IntArray(2).also { a.getLocationOnScreen(it) }
        val rb = IntArray(2).also { b.getLocationOnScreen(it) }
        val top = maxOf(ra[1], rb[1])
        val bottom = minOf(ra[1] + a.height, rb[1] + b.height)
        return bottom - top > minOf(a.height, b.height) / 2
    }

    /** Přepnutí na vedlejší kartu přejetím prstem (dir +1 = další, −1 = předchozí). */
    fun swipe(dir: Int) {
        // Upozornění leží „před“ Stanicí: Stanice + přejetí doprava (dir −1) -> Upozornění
        val t = when {
            current == 3 -> if (dir > 0) 0 else return
            current == 0 && dir < 0 -> 3
            else -> current + dir
        }
        if (t !in 0..3) return
        show(t)
        if (isTv()) container.post { if (!container.requestFocus(View.FOCUS_DOWN)) tabs[if (t == 3) 0 else t].requestFocus() }
        container.translationX = dir * container.width * 0.3f
        container.alpha = 0.4f
        container.animate().translationX(0f).alpha(1f).setDuration(180).start()
    }

    /** Stáhne nová data a překreslí obrazovky. */
    fun refreshData() {
        refreshBtn.isEnabled = false
        refreshBtn.alpha = 0.4f
        Thread {
            Sync.run(this, forceForecast = false)
            runOnUiThread {
                refreshBtn.isEnabled = true
                refreshBtn.alpha = 1f
                screens.forEach { it?.refresh() }
                if (current == 3) NotifyLog.markAllRead(this)
                updateBadge()
            }
        }.start()
    }

    // automatický nový pokus, jakmile je zase internet
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val p = Prefs(this@MainActivity)
            if (p.lastError != null || System.currentTimeMillis() - p.lastUpdate > 15 * 60_000L) {
                runOnUiThread { if (refreshBtn.isEnabled) refreshData() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        screens.forEach { it?.refresh() }
        if (current == 3) NotifyLog.markAllRead(this)
        updateBadge()
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
        } catch (e: Exception) {
            // nevadí
        }
    }

    override fun onPause() {
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
        } catch (e: Exception) {
            // nebyl registrován
        }
        super.onPause()
    }

    /** Zpět na televizní panel (i když ho systém mezitím zavřel). */
    private fun backToTvPanel() {
        startActivity(Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current == 3) openTab(0)
        else if (isTv()) backToTvPanel()
        else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        screens.forEach { it?.destroy() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}

@Suppress("unused")
private fun LinearLayout.noop() = Unit
