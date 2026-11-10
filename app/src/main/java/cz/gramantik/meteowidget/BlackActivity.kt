package cz.gramantik.meteowidget

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Černá obrazovka po automatickém zavření panelu, když box nejde uspat přímo.
 * Nic nepřehrává a nedrží displej zapnutý, takže box usne podle svého časovače
 * (a přes HDMI-CEC vypne TV). Jakýkoli stisk ovladače ji zavře.
 */
class BlackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        // úmyslně BEZ FLAG_KEEP_SCREEN_ON
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) finish()
        return true
    }
}
