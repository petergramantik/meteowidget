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
        panel = TvPanel(this)
        setContentView(panel.root)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val b = panel.changeBrightness(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) 5 else -5)
                show((if (panel.isNight) "Noční jas" else "Jas") + ": $b %")
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                show("Obnovuji…")
                panel.refreshNow()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
