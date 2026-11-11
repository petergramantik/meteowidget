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

    private val tv = a.isTv()

    init {
        if (tv) {
            // na TV ovládá mapu lišta tlačítek ve stránce; ↑ ↓ vrátí focus aplikaci
            web.isFocusable = true
            web.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun leave(dir: String) {
                    web.post {
                        (a as? cz.gramantik.meteowidget.DashboardActivity)?.let { it.fromContent(dir); return@post }
                        if (dir == "left" || dir == "right") {
                            (a as? MainActivity)?.swipe(if (dir == "right") 1 else -1)
                            return@post
                        }
                        val next = web.focusSearch(if (dir == "up") View.FOCUS_UP else View.FOCUS_DOWN)
                        next?.requestFocus()
                    }
                }
            }, "TvBridge")
            web.setOnFocusChangeListener { _, has -> web.evaluateJavascript("if (window.tvFocus) tvFocus($has);", null) }
        }
    }

    private var loadedKey = ""
    private fun key(p: Prefs) = p.owmKey + "|" + p.mapyKey + "|" + p.lat + "|" + p.lon

    init { load() }

    private fun load() {
        val p = Prefs(a)
        // stránku vložíme s https základem, aby šly bez potíží volat API (CORS) a načítat mapy
        val html = a.assets.open("radar.html").bufferedReader().use { it.readText() }
            .replace("__LAT__", String.format(Locale.US, "%.4f", p.lat))
            .replace("__LON__", String.format(Locale.US, "%.4f", p.lon))
            .replace("__NAME__", p.locName.replace("'", ""))
            .replace("__OWM__", p.owmKey.replace("'", ""))
            .replace("__MAPY__", p.mapyKey.replace("'", ""))
            .replace("__TV__", if (tv) "1" else "0")
        loadedKey = key(p)
        web.loadDataWithBaseURL("https://meteowidget.local/", html, "text/html", "utf-8", null)
    }

    override fun refresh() {
        val p = Prefs(a)
        // změna klíče nebo místa v nastavení -> načíst stránku znovu
        if (key(p) != loadedKey) load()
        else web.evaluateJavascript("if (window.reloadFrames) reloadFrames();", null)
    }

    override fun destroy() {
        web.destroy()
    }
}
