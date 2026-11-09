package cz.gramantik.meteowidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
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
        setContentView(panel.root)
        panel.showHint("← → záložky (Dnes / Zítra hned)  ·  OK otevřít  ·  ↑ ↓ jas")
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
        if (!Prefs(this).isConfigured) {
            show("Nejdřív zadej údaje z Tuya – otevírám nastavení")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        panel.start()
    }

    override fun onPause() {
        panel.stop()
        super.onPause()
    }

    override fun onDestroy() {
        if (TvSchedule.running?.get() === this) TvSchedule.running = null
        panel.release()
        super.onDestroy()
    }

    private fun show(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    companion object {
        const val EXTRA_SCHEDULED = "scheduled"
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    /** Záložky: 0 Dnes, 1 Zítra, 2 Předpověď, 3 Radar, 4 Historie, 5 Nastavení. */
    private fun openTab(i: Int) {
        when (i) {
            TvHome.TAB_TODAY -> {
                // ranní režim: OK přepne ranní ↔ běžný pohled
                if (!panel.home.tomorrow && MorningPrefs(this).toggle()) panel.render() else panel.refreshNow()
            }
            TvHome.TAB_TOMORROW -> panel.refreshNow()
            2 -> startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1))
            3 -> startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 2))
            4 -> startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 0))
            5 -> openSettings()
        }
    }

    /** Ovládání ovladačem zachycené na úrovni okna. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        panel.lastInput = System.currentTimeMillis()
        val tabs = panel.home.tabViews
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { openSettings(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val b = panel.changeBrightness(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 5 else -5)
                show((if (panel.isNight) "Noční jas" else "Jas") + ": $b %")
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (tabs.isEmpty()) return true
                val cur = tabs.indexOfFirst { it.isFocused }.let { if (it < 0) panel.focusedTab else it }
                val next = (cur + if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, tabs.size - 1)
                tabs[next].requestFocus()
                panel.focusedTab = next
                // Dnes / Zítra se přepnou hned, bez OK
                val wantTomorrow = when (next) {
                    TvHome.TAB_TODAY -> false
                    TvHome.TAB_TOMORROW -> true
                    else -> panel.home.tomorrow
                }
                if (wantTomorrow != panel.home.tomorrow) {
                    panel.home.tomorrow = wantTomorrow
                    panel.render()
                }
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
