package cz.gramantik.meteowidget.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import cz.gramantik.meteowidget.MainActivity
import cz.gramantik.meteowidget.Prefs
import java.util.Locale

/** Obrazovka Radar: mapa s animací srážek z RainViewer (stránka assets/radar.html). */
class RadarScreen(private val a: Activity) : MainActivity.Screen {

    @SuppressLint("SetJavaScriptEnabled")
    private val web = WebView(a).apply {
        setBackgroundColor(C.BG)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = settings.userAgentString + " meteowidget-android"
        webViewClient = object : WebViewClient() {
            // odkazy (atribuce) otevřít v prohlížeči
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                if (u.host == "meteowidget.local") return false
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.toString())))
                return true
            }
        }
    }

    override val view: View = web

    init { load() }

    private fun load() {
        val p = Prefs(a)
        // stránku vložíme s https základem, aby šly bez potíží volat API (CORS) a načítat mapy
        val html = a.assets.open("radar.html").bufferedReader().use { it.readText() }
            .replace("__LAT__", String.format(Locale.US, "%.4f", p.lat))
            .replace("__LON__", String.format(Locale.US, "%.4f", p.lon))
            .replace("__NAME__", p.locName.replace("'", ""))
        web.loadDataWithBaseURL("https://meteowidget.local/", html, "text/html", "utf-8", null)
    }

    override fun refresh() {
        web.evaluateJavascript("if (window.reloadFrames) reloadFrames();", null)
    }

    override fun destroy() {
        web.destroy()
    }
}
