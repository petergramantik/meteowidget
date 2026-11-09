package cz.gramantik.meteowidget

import android.service.dreams.DreamService

/** Spořič obrazovky pro televizi – stejný panel jako DashboardActivity. */
class MeteoDream : DreamService() {
    private var panel: TvPanel? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val p = TvPanel(this, interactive = false)
        isScreenBright = !p.isNight
        panel = p
        setContentView(p.root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        panel?.start()
    }

    override fun onDreamingStopped() {
        panel?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        panel?.release()
        panel = null
        super.onDetachedFromWindow()
    }
}
