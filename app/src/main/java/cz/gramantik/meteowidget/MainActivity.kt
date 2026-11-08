package cz.gramantik.meteowidget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cz.gramantik.meteowidget.ui.C
import cz.gramantik.meteowidget.ui.ForecastScreen
import cz.gramantik.meteowidget.ui.RadarScreen
import cz.gramantik.meteowidget.ui.StationScreen
import cz.gramantik.meteowidget.ui.dp
import cz.gramantik.meteowidget.ui.hbox
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
    private val tabs = ArrayList<TextView>()
    private var screens = arrayOfNulls<Screen>(3)
    private var current = 0
    private lateinit var title: TextView
    private lateinit var refreshBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG

        val root = vbox().apply { setBackgroundColor(C.BG); fitsSystemWindows = true }

        // horní lišta
        val bar = hbox().apply { setPadding(dp(16), dp(10), dp(8), dp(6)) }
        title = text("Meteostanice", 20f, C.WHITE, bold = true)
        bar.addView(title, lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        refreshBtn = barButton("⟳") { refreshData() }
        bar.addView(refreshBtn)
        bar.addView(barButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        root.addView(bar)

        container = FrameLayout(this)
        root.addView(container, lp(h = 0, weight = 1f))

        // spodní záložky
        val nav = hbox(Gravity.CENTER).apply {
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(0xFF0B0D10.toInt())
        }
        listOf("📟  Stanice", "📅  Předpověď", "🛰  Radar").forEachIndexed { i, s ->
            val t = text(s, 14f, C.DIM, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener { show(i) }
            }
            tabs.add(t)
            nav.addView(t, lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f, start = 3, end = 3))
        }
        root.addView(nav)
        setContentView(root)

        FetchWorker.schedulePeriodic(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        show(intent?.getIntExtra(EXTRA_TAB, 0) ?: 0)
        if (!Prefs(this).isConfigured) startActivity(Intent(this, SettingsActivity::class.java))
        else if (System.currentTimeMillis() - Prefs(this).lastUpdate > 5 * 60_000) refreshData()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) show(intent.getIntExtra(EXTRA_TAB, 0))
    }

    private fun barButton(s: String, onClick: () -> Unit) = text(s, 22f, C.WHITE).apply {
        setPadding(dp(12), dp(4), dp(12), dp(4))
        setOnClickListener { onClick() }
    }

    private fun show(i: Int) {
        current = i
        tabs.forEachIndexed { j, t ->
            t.setTextColor(if (j == i) C.BG else C.DIM)
            t.background = if (j == i) rounded(C.ACCENT, dp(18).toFloat()) else null
        }
        val s = screens[i] ?: when (i) {
            0 -> StationScreen(this)
            1 -> ForecastScreen(this)
            else -> RadarScreen(this)
        }.also { screens[i] = it }
        container.removeAllViews()
        container.addView(s.view)
        title.text = listOf("Stanice", "Předpověď", "Radar")[i]
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
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        screens.forEach { it?.refresh() }
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
